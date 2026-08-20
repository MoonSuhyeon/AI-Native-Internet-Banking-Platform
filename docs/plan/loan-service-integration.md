# loan-service ↔ AI 서비스 연결 가이드

<!-- plan-status -->
> **상태: ✅ 구현됨** — 연동 가이드. AI 서비스 호출 경로가 코드에 있다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> 작성일: 2026-05-28
> 대상 브랜치: `auto-loan-review`

---

## 개요

현재 loan-service는 세 가지 AI 처리 경로가 단절되어 있다.

| 단계 | 현재 상태 | 연결 목표 |
|---|---|---|
| **가심사 (Prescreening)** | MockCreditScoreEngine/HttpCreditScoreEngine 자체 처리 | → auto-loan-review `POST /api/ai/auto-review/evaluate` 호출 |
| **자동심사 (autoDecide)** | CB/DSR/LTV 룰만 | → ML PD 스코어 + Track 분기 결과 반영 |
| **편향 검증 (BiasCheck)** | Kafka 이벤트 발행 후 소비자 없음 | → review-ai-gateway Kafka Consumer 추가 |

---

## 연결 포인트 A — 가심사 → auto-loan-review evaluate

### 현재 흐름

```
POST /api/loans/{applId}/pre-screening/evaluate
  └─ LoanPrescreeningService.run()
       └─ CreditScoreEngine.evaluate()   ← MockCreditScoreEngine or HttpCreditScoreEngine
            결과: score / grade / decision / estimatedLimitAmt
       └─ LoanPrescreening 저장 → 신청 상태 PRESCREENED / REJECTED
```

PreReviewAgentService(LLM 정책 분석)와의 연결 없음.

### 목표 흐름

```
LoanPrescreeningService.run()
  1) CreditScoreEngine.evaluate()           ← 변경 없음
  2) PASS 판정 후 → AutoReviewEvaluateClient.evaluate()
        → POST /api/ai/auto-review/evaluate
        → AutoReviewEvaluateResponse (Track 1/2/3, PD, rationale)
  3) prescreening 결과에 ai_track_cd 컬럼 저장 (선택)
     (PreReviewAgentService는 auto-loan-review 내부에서 비동기 실행됨)
```

### 변경 파일 목록

| 파일 | 작업 |
|---|---|
| `loan-service/.../rag/AutoReviewAiClient.java` | evaluate 메서드 추가 (현재는 임베딩 배치 전용) |
| `loan-service/.../prescreening/service/LoanPrescreeningService.java` | PASS 후 evaluate 호출 추가 |
| `loan-service/.../prescreening/domain/LoanPrescreening.java` | `aiTrackCd` 컬럼 추가 (선택) |
| `loan-service/src/main/resources/application.yml` | `auto-review.url` 설정 추가 |

### 구현 — AutoReviewEvaluateClient 추가

`AutoReviewAiClient`에 evaluate 메서드를 추가하거나, 별도 컴포넌트로 분리한다.

```java
// loan-service/.../prescreening/client/AutoReviewEvaluateClient.java

@Component
public class AutoReviewEvaluateClient {

    private final RestClient restClient;

    public AutoReviewEvaluateClient(@Value("${auto-review.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public AutoReviewEvaluateResult evaluate(AutoReviewEvaluateRequest req) {
        return restClient.post()
                .uri("/api/ai/auto-review/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(AutoReviewEvaluateResult.class);
    }
}
```

### AutoReviewRequest 필드 매핑

auto-loan-review의 `AutoReviewRequest`는 59개 필드를 받는다. loan-service는 신청 시점에 일부만 보유한다.

#### loan-service가 보유하는 필드

| AutoReviewRequest 필드 | loan-service 출처 |
|---|---|
| `revId` | 아직 미확정 (prescreening 단계) — `null` 허용 |
| `annualIncomeKw` | `LoanApplication.estimatedIncomeAmt` |
| `requestedAmountKw` | `LoanApplication.requestedAmount` |
| `requestedPeriodMo` | `LoanApplication.requestedPeriodMo` |
| `purposeCd` | `LoanApplication.loanPurposeCd` |
| `occupation` | `LoanApplication.employmentTypeCd` |
| `creditScoreProxy` | `CreditScoreResult.score` (엔진 결과) |
| `dsr` | `DsrCalculation.dsrRatioBps / 10000.0` |
| `ltv` | `LtvCalculation.ltvRatioBps / 10000.0` (담보상품만) |
| `productCode` | `LoanProduct.prodCd` |

#### 부족한 필드 처리 방법

Layer 1(개인정보: age, sex, maritalStatus 등)과 Layer 4(PD 전용 필드)는 loan-service에 없다.

**옵션 1 (권장)**: RunPrescreeningRequest에 Layer 1 필드 추가
```java
// RunPrescreeningRequest 확장
public record RunPrescreeningRequest(
    String prescResultCd,
    Long estimatedLimitAmt,
    // ... 기존 필드 ...
    // ---- AI 피처 보강 ----
    Integer age,
    String sex,
    String maritalStatus,
    String educationLevel,
    String housingType
) {}
```
클라이언트(프론트엔드 또는 BFF)가 신청 폼 데이터를 함께 전달한다.

**옵션 2**: customer-service에서 고객 정보 조회
```java
// LoanPrescreeningService 주입 후
CustomerProfile profile = customerServiceClient.getProfile(application.getCustomerId());
// → age, sex, maritalStatus 등 보강
```

**옵션 3**: null 허용 (현재 모델이 NaN imputation 처리)
- Layer 1/4 필드를 null로 전달하면 ML 모델이 missing 분기로 처리함 (AutoReviewRequest Javadoc 참조)
- 빠른 MVP에 적합, 모델 정확도 다소 저하

### LoanPrescreeningService 변경 포인트

```java
// LoanPrescreeningService.run() 내부
// 기존 저장 직후 (line ~130 이후)

LoanPrescreening saved = repository.save(...);

// ↓ 추가: PASS인 경우 AI 평가 비동기 호출
if (pass) {
    try {
        var aiReq = AutoReviewEvaluateRequest.from(application, engineResult, product);
        var aiResp = autoReviewEvaluateClient.evaluate(aiReq);
        // 결과 저장 (LoanPrescreening.aiTrackCd 컬럼이 있으면)
        saved.recordAiTrack(aiResp.track());
    } catch (Exception e) {
        // AI 장애는 가심사 결과에 영향 주지 않음 — warn 로그만
        log.warn("auto-review evaluate 실패 applId={}", applId, e);
    }
}
```

### 설정 추가

```yaml
# loan-service/src/main/resources/application.yml
auto-review:
  base-url: http://auto-loan-review:8080
  internal-token: ${AUTO_REVIEW_INTERNAL_TOKEN}
```

---

## 연결 포인트 B — autoDecide → auto-loan-review LLM agent

### 현재 흐름

```
POST /api/loans/{applId}/reviews  (autoDecide)
  └─ LoanReviewAutoDecideService.autoDecide()
       1) CreditEvaluation CB 결과 로드
       2) DsrCalculation DSR 결과 로드
       3) LtvCalculation LTV 결과 로드 (담보 상품만)
       4) 결정 룰: CB.REJECT → REJECTED / DSR.FAIL → REJECTED / LTV.FAIL → REJECTED / 그 외 → APPROVED
       5) LoanReview(PENDING_APPROVAL) 저장
```

auto-loan-review ML 스코어 / Track 분기 결과가 LoanReview에 포함되지 않음.

### 목표 흐름

```
LoanReviewAutoDecideService.autoDecide()
  1) 기존 CB/DSR/LTV 룰 체크 (변경 없음, Hard Constraint)
  2) AutoReviewEvaluateClient.evaluate() 호출
       → Track 결정, PD 스코어, rationale 수신
  3) Track 결과를 LoanReview에 저장
       revAiTrackCd    — TRACK_1 / TRACK_2 / TRACK_3
       revAiPd         — PD 스코어 (decimal)
       revAiRationale  — 한국어 결정 근거
  4) LoanReview(PENDING_APPROVAL) 저장
```

#### 하드 룰 vs AI 결과 충돌 처리 원칙

- **하드 룰(CB/DSR/LTV)이 최우선**: 하드 룰 REJECT는 AI 결과와 무관하게 REJECTED.
- Track 2(AI 자동반려) + 하드 룰 PASS: 심사원 확인 필요 (Track 3에 준해 처리 권장).
- Track 1(AI 자동승인) + 하드 룰 PASS: 기존 APPROVED 유지.

### 변경 파일 목록

| 파일 | 작업 |
|---|---|
| `LoanReviewAutoDecideService.java` | evaluate 호출 + Track 결과 저장 |
| `LoanReview.java` (domain) | `revAiTrackCd`, `revAiPd`, `revAiRationale` 컬럼 추가 |
| DB migration | `loan_review` 테이블 컬럼 추가 |
| `LoanReviewResponse.java` | 신규 컬럼 응답에 포함 |

### LoanReviewAutoDecideService 변경 포인트

```java
// autoDecide() 내부 — 기존 룰 체크 이후
// line ~125 결정 룰 직후

// ↓ 추가: AI Track 분기 조회
AutoReviewEvaluateResult aiResult = null;
try {
    var aiReq = buildAutoReviewRequest(application, ceval, dsr, chosenLtv, product);
    aiResult = autoReviewEvaluateClient.evaluate(aiReq);
} catch (Exception e) {
    log.warn("auto-review evaluate 실패 applId={}, 룰 결정 계속 진행", applId, e);
}

// ↓ 수정: LoanReview.save() 시 AI 결과 포함
LoanReview saved = repository.save(LoanReview.builder()
        ...
        .revAiTrackCd(aiResult != null ? aiResult.track() : null)
        .revAiPd(aiResult != null ? aiResult.pd() : null)
        .revAiRationale(aiResult != null ? aiResult.rationale() : null)
        .build());
```

### AutoReviewRequest 조립 — autoDecide 시점

autoDecide 시점에는 CreditEvaluation, DsrCalculation 결과가 있으므로 가심사보다 더 많은 필드를 채울 수 있다.

| AutoReviewRequest 필드 | autoDecide 출처 |
|---|---|
| `creditScoreProxy` | `CreditEvaluation.cevalScore` |
| `dsr` | `DsrCalculation.dsrRatioBps / 10000.0` |
| `ltv` | `LtvCalculation.ltvRatioBps / 10000.0` |
| `requestedAmountKw` | `LoanApplication.requestedAmount` |
| `requestedPeriodMo` | `LoanApplication.requestedPeriodMo` |
| `purposeCd` | `LoanApplication.loanPurposeCd` |
| `annualIncomeKw` | `LoanApplication.estimatedIncomeAmt` |
| `productCode` | `LoanProduct.prodCd` |

Layer 1 개인정보는 가심사 때와 동일하게 옵션 1~3 중 선택.

### DB Migration

```sql
-- V4__add_ai_track_to_loan_review.sql
ALTER TABLE loan_review
    ADD COLUMN rev_ai_track_cd    VARCHAR(20)    NULL,
    ADD COLUMN rev_ai_pd          DECIMAL(10,6)  NULL,
    ADD COLUMN rev_ai_rationale   TEXT           NULL;

COMMENT ON COLUMN loan_review.rev_ai_track_cd   IS 'AI 트랙 분기 결과 (TRACK_1/2/3)';
COMMENT ON COLUMN loan_review.rev_ai_pd         IS 'AI PD 스코어 (0~1)';
COMMENT ON COLUMN loan_review.rev_ai_rationale  IS 'AI 결정 근거 한 줄 요약';
```

---

## 연결 포인트 C — review-ai-gateway 편향 검증 연결

### 현재 흐름

```
LoanReviewAutoDecideService.confirm()
  └─ enqueueBiasCheck()
       └─ NotificationOutboxAppender.enqueueInCurrentTx()
            → Kafka 토픽 "BIAS_CHECK_REQUESTED" 발행
               (페이로드: LoanBiasCheckRequestedPayload)
```

review-ai-gateway에 Kafka Consumer가 없어서 이벤트가 소비되지 않음.

### 목표 흐름

```
Kafka "loan.bias-check-requested" 토픽
  └─ review-ai-gateway: BiasCheckKafkaConsumer
       1) LoanBiasCheckRequestedPayload 역직렬화
       2) AuditAnalysisRequest 변환
       3) AgenticAuditAnalysisService.analyze() 호출
            → AgenticLoop (Claude tool-use, 최대 5턴)
       4) 결과 → loan-service REST callback
            POST /api/loans/reviews/{revId}/bias-result
            (또는 별도 Kafka reply 토픽)
```

### 변경 파일 목록

| 서비스 | 파일 | 작업 |
|---|---|---|
| review-ai-gateway | `BiasCheckKafkaConsumer.java` (신규) | Kafka consumer |
| review-ai-gateway | `BiasCheckPayload.java` (신규) | 이벤트 페이로드 DTO |
| review-ai-gateway | `build.gradle` | `spring-kafka` 의존성 추가 |
| review-ai-gateway | `application.yml` | Kafka 브로커·토픽 설정 |
| loan-service | `BiasResultCallbackController.java` (신규) | 편향 검증 결과 수신 |
| loan-service | `LoanReview.java` | `biasCheckStatusCd` 컬럼 업데이트 |

### Kafka Consumer 구현 (review-ai-gateway)

```java
// review-ai-gateway/.../consumer/BiasCheckKafkaConsumer.java

@Component
@RequiredArgsConstructor
public class BiasCheckKafkaConsumer {

    private static final String TOPIC = "loan.bias-check-requested";

    private final AgenticAuditAnalysisService auditService;
    private final LoanServiceCallbackClient callbackClient;

    @KafkaListener(topics = TOPIC, groupId = "review-ai-gateway")
    public void consume(LoanBiasCheckRequestedPayload payload) {
        var request = AuditAnalysisRequest.from(payload);
        try {
            AuditAnalysisResponse result = auditService.analyze(request);
            callbackClient.reportResult(payload.revId(), result);
        } catch (Exception e) {
            log.error("bias-check 실패 revId={}", payload.revId(), e);
            callbackClient.reportFailure(payload.revId(), e.getMessage());
        }
    }
}
```

### LoanBiasCheckRequestedPayload → AuditAnalysisRequest 매핑

```java
// AuditAnalysisRequest에 정적 팩토리 추가
public static AuditAnalysisRequest from(LoanBiasCheckRequestedPayload p) {
    var signals = List.of(
        new SignalSummary("DECISION",       p.reviewerDecision().decisionCd()),
        new SignalSummary("REJECT_REASON",  p.reviewerDecision().rejectReasonCd()),
        new SignalSummary("CB_DECISION",    p.context().cbDecisionCd()),
        new SignalSummary("CB_SCORE",       String.valueOf(p.context().cbScore())),
        new SignalSummary("DSR_RATIO_BPS",  String.valueOf(p.context().dsrRatioBps())),
        new SignalSummary("DSR_LIMIT_BPS",  String.valueOf(p.context().dsrLimitBps())),
        new SignalSummary("PRODUCT_CD",     p.context().productCd())
    );
    return new AuditAnalysisRequest(
        "BIAS_DETECTION",
        p.revId(),
        p.reviewerDecision().reviewerId(),
        null,         // reviewOpinionText — 선택
        signals,
        List.of()     // ragChunks — consumer 내부에서 별도 조회 가능
    );
}
```

### review-ai-gateway build.gradle 추가

```groovy
dependencies {
    implementation 'org.springframework.kafka:spring-kafka'
    // 기존 의존성 유지
}
```

### review-ai-gateway application.yml 추가

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: review-ai-gateway
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.bank.loan.review.event"

loan-service:
  callback-url: ${LOAN_SERVICE_URL:http://loan-service:8080}
```

### loan-service — 편향 검증 결과 수신 콜백

```java
// loan-service/.../review/controller/BiasResultCallbackController.java

@RestController
@RequestMapping("/api/loans/reviews")
@RequiredArgsConstructor
public class BiasResultCallbackController {

    private final LoanReviewBiasResultService biasResultService;

    @PostMapping("/{revId}/bias-result")
    public ResponseEntity<Void> receive(
            @PathVariable Long revId,
            @RequestBody BiasResultCallbackRequest req) {
        biasResultService.apply(revId, req);
        return ResponseEntity.ok().build();
    }
}
```

```java
// BiasResultCallbackRequest
public record BiasResultCallbackRequest(
    String status,          // PASS / FLAGGED / FAILED
    String analysisType,
    String findingSummary,
    boolean biasDetected
) {}
```

`biasResultService.apply()`는 `LoanReview.biasCheckStatusCd`를 `BIAS_REVIEWING → BIAS_DONE / BIAS_FLAGGED`로 전이한다.

---

## 구현 순서 (권장)

```
Phase 1 — HTTP 클라이언트 + 설정 (의존성 없음)
  ├─ AutoReviewEvaluateClient 구현
  ├─ loan-service application.yml에 auto-review.base-url 추가
  └─ WireMock 통합 테스트 작성

Phase 2 — autoDecide 연결 (B포인트, 가장 임팩트 큼)
  ├─ DB migration V4 적용
  ├─ LoanReview 도메인 컬럼 추가
  ├─ LoanReviewAutoDecideService 수정
  └─ 통합 테스트 업데이트

Phase 3 — 가심사 연결 (A포인트)
  ├─ RunPrescreeningRequest Layer 1 필드 추가 여부 결정
  ├─ LoanPrescreeningService 수정
  └─ LoanPrescreening 도메인 aiTrackCd 추가 (선택)

Phase 4 — 편향 검증 연결 (C포인트, Kafka 인프라 필요)
  ├─ review-ai-gateway Kafka Consumer 구현
  ├─ LoanService 콜백 컨트롤러 구현
  └─ Testcontainers Kafka 통합 테스트
```

---

## 공통 고려 사항

### 장애 격리

AI 서비스 장애가 핵심 대출 흐름을 막으면 안 된다. 모든 AI 호출은 try-catch로 감싸고 실패 시 warn 로그만 남긴다. `CircuitBreaker` 적용을 권장한다.

```java
// Resilience4j CircuitBreaker 적용 예시
@CircuitBreaker(name = "autoReviewEvaluate", fallbackMethod = "evaluateFallback")
public AutoReviewEvaluateResult evaluate(AutoReviewEvaluateRequest req) { ... }

private AutoReviewEvaluateResult evaluateFallback(AutoReviewEvaluateRequest req, Exception e) {
    log.warn("auto-review fallback 활성화", e);
    return null;  // null이면 AI 결과 없이 진행
}
```

### 내부 호출 인증

auto-loan-review의 `POST /api/ai/auto-review/evaluate`는 내부 전용 엔드포인트이므로 `X-Internal-Token` 헤더를 사용한다 (AutoReviewAiClient 패턴 동일).

### 로컬 개발 환경

auto-loan-review 서버가 없는 경우를 위해 loan-service에 `auto-review.enabled: false` 토글을 추가하면 클라이언트 호출 자체를 건너뛸 수 있다.

```yaml
# local 프로파일
auto-review:
  enabled: false   # true면 실제 호출, false면 스킵
```
