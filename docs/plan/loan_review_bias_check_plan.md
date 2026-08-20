# 본심사 편향 검증 + 승인자 단계 도입 Plan

<!-- plan-status -->
> **상태: 🔨 진행 중** — `AuditFairnessAgent` 있음. 승인자 단계 미완 체크 10개
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> 심사원이 본심사 결정을 내린 직후, 외부 **편향 검증 에이전트(서비스)** 가 결정의 편향성·규정 준수 여부를 분석해 리포트를 만들고, 심사원이 그 리포트를 확인(acknowledge)한 후에야 승인자(approver)에게 넘어가도록 본심사 흐름을 확장한다.

---

## 1. 배경 / 목표

### 1.1 현재 본심사 종료 흐름
```
[심사원] run() / autoDecide()+confirm()
   ├─ loan_review.rev_status_cd        : COMPLETED
   ├─ loan_review.rev_decision_cd      : APPROVED / REJECTED
   └─ loan_application.appl_status_cd  : PRESCREENED → APPROVED / REJECTED
```
한 콜로 본심사가 종결되고 신청 상태가 곧장 전이된다. 4-eye 절차 없음.

### 1.2 To-be 흐름
```
[심사원] run() / confirm()
   ├─ loan_review.rev_status_cd        : BIAS_REVIEWING        ← 新
   ├─ loan_review.rev_decision_cd      : APPROVED / REJECTED   (그대로)
   └─ loan_application.appl_status_cd  : PRESCREENED           (전이 안함)
         │
         ▼ outbox → Kafka(loan-domain-events)
   LoanBiasCheckRequestedEvent
         │
   [bias-review-service]               ← 별도 서비스 (사용자 구현)
         ├─ 편향/규정 분석
         └─ POST /api/internal/loan-reviews/{revId}/bias-report (loan-service 내부 API)
                  └─ ai_review_advice (advice_type_cd=BIAS_CHECK, severity_cd=...)

[심사원] GET .../advices 로 리포트 확인 → 동의
   POST .../review/acknowledge-bias
   ├─ BIAS_CHECK advice 존재 + severity != BLOCKED 검증
   └─ loan_review.rev_status_cd : BIAS_REVIEWING → PENDING_APPROVER

[승인자] POST .../review/approver-approve
   ├─ decision = APPROVE_AS_IS | OVERRIDE_APPROVED | OVERRIDE_REJECTED
   ├─ override 이면 override_reason 필수
   ├─ loan_review.rev_decision_cd     : (변경되면) override 결정으로 갱신
   ├─ loan_review.approver_id         : 승인자 ID 기록 (新)
   ├─ loan_review.approved_at         : 승인 시각
   ├─ loan_review.rev_status_cd       : PENDING_APPROVER → COMPLETED
   └─ loan_application.appl_status_cd : PRESCREENED → APPROVED / REJECTED
```

### 1.3 설계 원칙

- **4-eye**: 심사원과 승인자가 반드시 다른 사용자여야 한다. (검증 단계)
- **결정권은 사람**: 에이전트는 리포트만 생성한다. 코드는 advice 만 받고 결정에 직접 반영하지 않는다.
- **하드 차단**: severity = `BLOCKED` (명백한 규정 위반) 인 경우 `acknowledgeBias` 를 막는다. 심사원은 ① `revise()` 로 결정을 정정한 뒤 재진입하거나, ② 상급자로부터 BLOCKED 우회 승인(`bias-override`)을 받은 뒤 진행한다.
- **사후 호출 (AFTER_COMMIT)**: 본심사 트랜잭션이 커밋된 뒤 이벤트 발행. 에이전트 장애가 본 결정을 막지 않는다. 단, advice 가 생성되지 않으면 `acknowledgeBias` 진행 불가 — 이건 의도된 동작.
- **에이전트 장애 시 운영 처리**: 운영자는 `bias-ops-note` 로 NONE severity 의 운영 진단 advice 를 강제 적재할 수 있다. 이후 심사원 ack + 승인자 approve 는 그대로 사람이 함 — **4-eye 보존**. 운영자는 결정에 직접 관여하지 않는다.

---

## 2. 상태 머신

### 2.1 `loan_review.rev_status_cd` 전이

| 현재 | 전이 후 | 트리거 | 비고 |
|---|---|---|---|
| (신규) | `PENDING_APPROVAL` | `autoDecide()` | 기존, 변경 없음 |
| `PENDING_APPROVAL` | `BIAS_REVIEWING` | `confirm()` | **변경됨** (이전: COMPLETED) |
| (신규) | `BIAS_REVIEWING` | `run()` (수동) | **변경됨** (이전: COMPLETED) |
| `BIAS_REVIEWING` | `PENDING_APPROVER` | `acknowledgeBias()` | **新** |
| `BIAS_REVIEWING` | `BIAS_REVIEWING` | `biasOpsNote()` (운영, advice 강제 적재) | **新**, ack 가드 해소만. 상태 전이 없음 |
| `PENDING_APPROVER` | `COMPLETED` | `approverApprove()` | **新** |
| `PENDING_APPROVAL` | `EXPIRED` | 배치 (`expirePending`) | 기존, 변경 없음 |
| `BIAS_REVIEWING` | `EXPIRED` | 배치 (`expireBiasReviewing`) | **新**, N일 미응답시 |

### 2.2 `loan_application.appl_status_cd` 전이 시점 이동

- **이전**: `run()` / `confirm()` 시점에 `PRESCREENED → APPROVED/REJECTED` 전이
- **이후**: `approverApprove()` 시점에만 전이

이 변경의 의미: 본심사 결정이 내려진 직후 약정·실행이 시작될 수 없게 된다. 영향받는 도메인:
- `LoanApprovedEvent` 발행 시점: `approverApprove()` 로 이동
- `contract` / `execution` 진입 가드: 변화 없음 (이미 `appl_status_cd = APPROVED` 를 보고 동작)
- `revise()`: 현재 COMPLETED 상태에서만 동작 → **PENDING_APPROVER / BIAS_REVIEWING 에서도 가능하게 확장 필요?** → ❌ Phase 1 범위 외. 정정은 여전히 COMPLETED 후에만.

### 2.3 ITEM_FINAL_DECISION 로그
- `confirm()` / `run()` 시점에 기존처럼 FINAL_DECISION 한 줄 append (심사원 결정)
- `approverApprove()` 시점에 새 `APPROVER_DECISION` 한 줄 append (승인자 확정)

---

## 3. 데이터 모델 변경

### 3.1 `loan_review` 컬럼 추가 (V18)

```sql
ALTER TABLE loan_review
  ADD COLUMN approver_id           BIGINT,
  ADD COLUMN approved_decision_cd  VARCHAR(50),   -- 승인자가 확정한 최종 결정 (override 가능)
  ADD COLUMN override_reason_cd    VARCHAR(50),   -- override 시 사유 코드 (CODE_MASTER)
  ADD COLUMN override_remark       VARCHAR(500),  -- override 자유 메모
  ADD COLUMN bias_severity_cd      VARCHAR(20),   -- 마지막 BIAS_CHECK 결과 캐시 (BLOCKED 게이트용)
  ADD COLUMN bias_override_by      BIGINT,        -- BLOCKED 우회 승인자 (상급자) ID
  ADD COLUMN bias_override_reason  VARCHAR(500),  -- 우회 사유
  ADD COLUMN bias_overridden_at    TIMESTAMPTZ;   -- 우회 승인 시각

CREATE INDEX ix_loan_review_status_bias
  ON loan_review (rev_status_cd)
  WHERE rev_status_cd IN ('BIAS_REVIEWING','PENDING_APPROVER');
```

**주의**: 기존 `approved_at` 의미가 흔들리지 않도록 한다.
- `approved_at` = 신청이 최종 APPROVED 로 전이된 시각 (승인자 시점)
- 신청 상태가 REJECTED 면 `approved_at` 은 null

### 3.2 `ai_review_advice` 신설 (V19)

`docs/ai/banking-review-llm.md §4` 의 스키마를 그대로 적용하고, 편향 검증을 위해 컬럼 2개 보강.

```sql
CREATE TABLE ai_review_advice (
  advice_id       BIGSERIAL PRIMARY KEY,
  rev_id          BIGINT       NOT NULL REFERENCES loan_review(rev_id),
  advice_type_cd  VARCHAR(40)  NOT NULL,  -- BIAS_CHECK / SUMMARY / REJECTION_LETTER / ...
  severity_cd     VARCHAR(20),            -- BLOCKED / HIGH / MEDIUM / LOW / NONE
  advice_body     TEXT         NOT NULL,  -- 자연어 또는 JSON
  model           VARCHAR(80),
  model_version   VARCHAR(40),
  prompt_hash     CHAR(64),
  input_token     INT,
  output_token    INT,
  latency_ms      INT,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by      BIGINT
);

CREATE INDEX ix_ai_review_advice_rev_type_created
  ON ai_review_advice (rev_id, advice_type_cd, created_at DESC);
```

소프트 삭제 없음. append-only.

### 3.3 코드 마스터 추가 (CODE_MASTER)

| group_cd | code | 의미 |
|---|---|---|
| `LOAN_REV_STATUS` | `BIAS_REVIEWING` | 편향 검증 진행/대기 |
| `LOAN_REV_STATUS` | `PENDING_APPROVER` | 승인자 대기 |
| `LOAN_REV_REASON` | `BIAS_ACKNOWLEDGED` | 심사원 리포트 확인 |
| `LOAN_REV_REASON` | `APPROVER_APPROVED` | 승인자 확정 (그대로) |
| `LOAN_REV_REASON` | `APPROVER_OVERRIDDEN` | 승인자가 결정 변경 |
| `LOAN_REV_REASON` | `BIAS_FORCED_BY_OPS` | 운영 강제 진행 |
| `LOAN_REV_REASON` | `BIAS_REVIEWING_EXPIRED` | 무응답 만료 |
| `AI_ADVICE_TYPE` | `BIAS_CHECK` | 편향/규정 검증 |
| `AI_ADVICE_SEVERITY` | `BLOCKED` / `HIGH` / `MEDIUM` / `LOW` / `NONE` | 위험도 |
| `LOAN_REV_OVERRIDE` | `RISK_TOLERANCE` / `BIAS_FIX` / `POLICY_EXCEPTION` / `OTHER` | override 사유 |

---

## 4. 이벤트 계약

### 4.1 발행: `LoanBiasCheckRequestedEvent`

NotificationOutbox 패턴으로 발행. 토픽 `loan-domain-events`, 키 `BIAS_CHECK_REQUESTED:{revId}`.

```json
{
  "eventTypeCd": "BIAS_CHECK_REQUESTED",
  "occurredAt": "2026-05-27T14:30:00+09:00",
  "revId": 1001,
  "applId": 12345,
  "revTypeCd": "AUTO",
  "reviewerDecision": {
    "decisionCd": "APPROVED",
    "rejectReasonCd": null,
    "approvedAmount": 50000000,
    "approvedRateBps": 500,
    "approvedPeriodMo": 60,
    "reviewerId": 201,
    "reviewedAt": "2026-05-27T14:29:50+09:00"
  },
  "context": {
    "productCd": "HL01",
    "cbDecisionCd": "APPROVE",
    "cbScore": 720,
    "dsrPct": 38,
    "ltvPct": 65,
    "requestedAmount": 60000000,
    "requestedPeriodMo": 60
  },
  "checkLogs": [
    { "itemCd": "CB_DECISION", "resultCd": "PASS" },
    { "itemCd": "DSR_CHECK",   "resultCd": "PASS" },
    { "itemCd": "LTV_CHECK",   "resultCd": "PASS" }
  ]
}
```

PII (주민번호·계좌번호) 는 페이로드에 포함하지 않는다. 에이전트가 추가 정보가 필요하면 별도 read-only API 호출.

### 4.2 결과 수신: 내부 API

에이전트 서비스 → loan-service 호출. Kafka 회신이 아닌 동기 REST 로 채택 (단순화).

```
POST /api/internal/loan-reviews/{revId}/bias-report
Authorization: Bearer <service-token>
Content-Type: application/json

{
  "severityCd": "MEDIUM",
  "summary": "심사원 결정과 모델 예측이 일치하나, 동일 코호트 거절률 대비 12%p 높음",
  "findings": [
    { "code": "REGULATION_DSR",    "result": "PASS", "detail": "..." },
    { "code": "BIAS_COHORT_AGE",   "result": "WARN", "detail": "30대 여성 거절률 편향 가능성" }
  ],
  "model": "bias-detector-v1",
  "modelVersion": "2026-05-01",
  "promptHash": "sha256:...",
  "inputToken": 1234,
  "outputToken": 567,
  "latencyMs": 1800
}
```

서버:
1. `loan_review` 존재 + `rev_status_cd = BIAS_REVIEWING` 검증 (그 외 상태에는 207 / 적재 후 noop)
2. `ai_review_advice` 에 advice_type_cd=`BIAS_CHECK` 로 append
3. `loan_review.bias_severity_cd = severityCd` 캐시 갱신

### 4.3 운영용 회신 API

| Method | Path | 용도 |
|---|---|---|
| `GET` | `/api/internal/loan-reviews/bias-pending?olderThanMin=30` | 편향 검증이 지연 중인 본심사 목록 |
| `POST` | `/api/internal/loan-reviews/{revId}/bias-force-complete` | 운영자 강제 진행 — BIAS_REVIEWING → COMPLETED, 신청 상태 전이 포함, advice 없이도 진행 |
| `POST` | `/api/internal/loan-reviews/expire-bias-reviewing?cutoffDate=YYYYMMDD` | 만료 배치 — N일 미응답 BIAS_REVIEWING → EXPIRED |

---

## 5. API 변경

### 5.1 신규 엔드포인트

| Method | Path | 권한 | 용도 |
|---|---|---|---|
| `POST` | `/api/loan-applications/{applId}/review/acknowledge-bias` | 심사원 | 리포트 확인 → PENDING_APPROVER 전이 |
| `POST` | `/api/loan-applications/{applId}/review/approver-approve` | 승인자 | 최종 확정 → COMPLETED + 신청 상태 전이 |
| `POST` | `/api/loan-reviews/{revId}/bias-override` | 심사원 상급자 | BLOCKED severity 우회 승인 (상급자 전용) |
| `GET`  | `/api/loan-reviews/pending-approver` | 승인자 | 승인 대기 목록 |
| `GET`  | `/api/loan-reviews/{revId}/advices` | 심사원/승인자 | advice 목록 (편향 리포트 포함) |
| `POST` | `/api/internal/loan-reviews/{revId}/bias-report` | service-to-service | 에이전트 결과 수신 |
| `POST` | `/api/internal/loan-reviews/{revId}/bias-ops-note` | 운영자 | 에이전트 장애 시 운영 진단 advice 강제 적재 (severity=NONE, 상태 전이 없음) |

### 5.2 응답 envelope 변경 없음
기존 `LoanReviewResponse` 에 `approverId`, `approvedDecisionCd`, `biasSeverityCd` 만 추가.

### 5.3 요청 DTO

```java
public record AcknowledgeBiasRequest(
    @NotNull Long reviewerId,
    String ackRemark
) {}

public record ApproverApproveRequest(
    @NotNull Long approverId,
    @NotNull String approverDecisionCd,   // APPROVE_AS_IS | OVERRIDE_APPROVED | OVERRIDE_REJECTED
    String overrideReasonCd,              // override 일 때 필수
    String overrideRemark,
    Long approvedAmount,                  // OVERRIDE_APPROVED 일 때 필수
    Integer approvedRateBps,
    Integer approvedPeriodMo,
    String rejectReasonCd                 // OVERRIDE_REJECTED 일 때 필수
) {}
```

---

## 6. 에러 코드 (LoanErrorCode)

심사 구간(030-049) 은 포화. 확장 구간(192+) 사용.

| 코드 | HTTP | 의미 |
|---|---|---|
| `LOAN_192` | 422 | 본심사가 편향 검증 단계가 아님 (acknowledge-bias 호출 시) |
| `LOAN_193` | 422 | 편향 검증 리포트가 아직 생성되지 않음 (acknowledge 차단) |
| `LOAN_194` | 422 | 편향 검증 결과가 BLOCKED 상태 — 정정 필요 |
| `LOAN_195` | 422 | 본심사가 승인자 대기 단계가 아님 (approver-approve 호출 시) |
| `LOAN_196` | 422 | 승인자와 심사원이 동일 — 4-eye 위반 |
| `LOAN_197` | 422 | override 시 사유(overrideReasonCd) 누락 |
| `LOAN_198` | 422 | override_approved 시 금액/금리/기간 누락 |
| `LOAN_199` | 404 | 편향 검증 결과 조회 실패 (internal API) |

---

## 7. 영향 분석

### 7.1 기존 코드 변경

| 파일 | 변경 |
|---|---|
| `LoanReview.java` | 상태 상수 + `markBiasReviewing()` / `acknowledgeBias()` / `approverApprove()` / `forceComplete()` / `expireBiasReviewing()` |
| `LoanReviewService.run()` | 최종 상태를 COMPLETED → BIAS_REVIEWING, 신청 상태 전이 제거, 이벤트 outbox 적재 |
| `LoanReviewAutoDecideService.confirm()` | 동일. `LoanApprovedEvent` 발행 제거 |
| `LoanReviewReviseService.revise()` | 동작 유지 (COMPLETED 후 정정). 단 신청 상태 전이는 그대로 — 이미 승인자 단계까지 통과한 후이므로 |
| `LoanReviewController` | 엔드포인트 3개 + DTO 추가 |
| `InternalReviewBatchController` | bias-force-complete, expire-bias-reviewing 엔드포인트 추가 |
| `LoanReviewResponse` | `approverId`, `approvedDecisionCd`, `biasSeverityCd` 추가 |
| `LoanErrorCode` | LOAN_192~199 추가 |
| `LoanReviewRepository` | `findByRevStatusCd(...)` 페이징 쿼리 추가 |

### 7.2 신규 파일

- `LoanReviewBiasService.java` — acknowledge / approverApprove / forceComplete / expireBiasReviewing
- `LoanReviewBiasReportService.java` — internal API 핸들러 (advice append)
- `LoanBiasCheckRequestedEvent.java` — 페이로드 record
- `LoanBiasCheckRequestedPayloadBuilder.java` — 위 record 구성 헬퍼
- `AiReviewAdvice.java` — JPA 엔티티
- `AiReviewAdviceRepository.java`
- `AcknowledgeBiasRequest`, `ApproverApproveRequest`, `BiasReportRequest`, `BiasReportFinding` DTO
- `AiReviewAdviceResponse` DTO
- `PendingApproverController` (또는 기존 컨트롤러에 메서드 추가)

### 7.3 신청 상태 전이 시점 이동의 파급

`appl_status_cd = APPROVED` 를 체크하는 코드 모두 영향 받음:
- `ContractService` — 약정 생성. 본심사 직후 → 승인자 통과 후로 시작 시점 이동
- `NotificationOutbox` — `LOAN_APPROVED` SMS 발송. 발송 시점도 이동
- `LoanApprovedEvent` 리스너 — 발행 시점 이동

이 영향이 비즈니스적으로 의도한 것임을 명확히 한다. 기존 통합 테스트(`LoanReviewFlowTest`, `LoanApprovedNotificationFlowTest` 등) 의 시나리오가 깨질 것 — 테스트 보정 필요.

---

## 8. 단계별 실행 plan

**메모리 규칙**: 한 단계씩 진행, 단계마다 `feat(...)` + `test(...)` 분리 커밋. 단계 끝나면 멈추고 보고.

### Step 1. `ai_review_advice` 테이블 + 엔티티
- V18 마이그레이션 (`ai_review_advice` 테이블, 인덱스, 코드 마스터)
- `AiReviewAdvice` 엔티티 + repository
- 단위 테스트: 엔티티 영속화 / 인덱스로 조회
- 커밋 2개: `feat(loan/review): ai_review_advice 테이블·엔티티 추가` + `test(loan/review): ai_review_advice 영속화 테스트`

### Step 2. `loan_review` 컬럼 확장
- V19 마이그레이션 (approver_id 등 5개 컬럼 + 인덱스 + 코드 마스터)
- `LoanReview` 엔티티 필드 추가 (전이 메서드는 추가 안함, 다음 step)
- 응답 DTO 필드 추가
- 커밋: `feat(loan/review): 승인자/편향 검증 컬럼 추가`

### Step 3. 상태 전이 메서드 + 에러 코드
- `LoanReview.markBiasReviewing()`, `acknowledgeBias()`, `approverApprove()`, `forceComplete()`, `expireBiasReviewing()`
- `LoanErrorCode.LOAN_192~199`
- 엔티티 단위 테스트: 각 전이의 전/후 상태 + 예외 조건
- 커밋 2개: `feat(loan/review): 편향 검증·승인자 상태 전이 추가` + `test(loan/review): 본심사 상태 전이 단위 테스트`

### Step 4. `run()` / `confirm()` 흐름 변경
- COMPLETED → BIAS_REVIEWING 으로 종료 상태 변경
- 신청 상태 전이 제거
- `LoanApprovedEvent` 발행 제거
- `LoanBiasCheckRequestedEvent` 페이로드 빌더 + outbox 적재 (eventTypeCd=`BIAS_CHECK_REQUESTED`)
- 기존 통합 테스트(`LoanReviewFlowTest` 등) 보정: COMPLETED 까지의 단계로 expectation 조정 — 일단 BIAS_REVIEWING 으로 끝나는 것까지만 검증
- 커밋 2개: `feat(loan/review): 본심사 종료를 편향 검증 단계로 전환` + `test(loan/review): 본심사 후 BIAS_REVIEWING 전이 검증`

### Step 5. 내부 API — bias-report 수신
- `POST /api/internal/loan-reviews/{revId}/bias-report`
- `LoanReviewBiasReportService.append(revId, request)` — advice 적재 + `bias_severity_cd` 캐시
- 멱등성: 같은 model+promptHash 인 advice 중복 시 신규 row 만들지 않음 (선택)
- 통합 테스트: BIAS_REVIEWING 상태에서 호출 → advice 1건 + severity 캐시 갱신
- 커밋 2개

### Step 6. acknowledge-bias 엔드포인트
- `POST .../review/acknowledge-bias`
- 검증: 상태 = BIAS_REVIEWING, BIAS_CHECK advice 존재, severity != BLOCKED
- 전이: BIAS_REVIEWING → PENDING_APPROVER + ReviewCheckLog(BIAS_ACKNOWLEDGED) append + status_history
- 4-eye 준비: 심사원 ID 를 acknowledgeBias 호출자로 고정 (이후 approver 와 비교)
- 통합 테스트: advice 없음 → LOAN_193, BLOCKED → LOAN_194, 정상 → PENDING_APPROVER
- 커밋 2개

### Step 7. approver-approve 엔드포인트
- `POST .../review/approver-approve`
- 검증: 상태 = PENDING_APPROVER, approverId != reviewerId(LOAN_196), override 시 사유 필수(LOAN_197), override_approved 시 금액 등 필수(LOAN_198)
- 전이:
  - PENDING_APPROVER → COMPLETED
  - `loan_review.approver_id`, `approved_decision_cd`, `override_reason_cd`, `override_remark` 채움
  - override 라면 `rev_decision_cd` 갱신 + `approvedAmount/rate/period` 또는 `rejectReasonCd` 갱신
  - 신청 상태 전이: PRESCREENED → APPROVED / REJECTED
  - `LoanApprovedEvent` 발행 (승인일 때만, 여기서!)
  - ReviewCheckLog(APPROVER_DECISION) append
- `GET /api/loan-reviews/pending-approver` 추가
- 통합 테스트: 정상 승인, override_approved, override_rejected, 4-eye 위반, 사유 누락
- 커밋 2개

### Step 8. 운영 도구
- `GET /api/internal/loan-reviews/bias-pending`
- `POST /api/internal/loan-reviews/{revId}/bias-force-complete` — 4-eye 우회, 사유 필수
- `POST /api/internal/loan-reviews/expire-bias-reviewing?cutoffDate=YYYYMMDD` — 배치
- 메트릭 + Grafana 패널 (옵션)
- 커밋 2개

### Step 9. (옵션) EOD 통합
- `LoanEodJob` 에 `biasReviewingExpiryStep` 추가 (Step 8 의 expire-bias-reviewing 호출)
- 커밋 1+1

---

## 9. 테스트 전략

### 9.1 통합 테스트 (`AbstractLoanIntegrationTest` 상속)

| 클래스 | 시나리오 |
|---|---|
| `LoanReviewBiasFlowTest` | autoDecide → confirm → BIAS_REVIEWING + outbox row 1건, bias-report 수신 → advice 1건 + severity 캐시, acknowledge → PENDING_APPROVER, approverApprove(AS_IS) → COMPLETED + 신청 APPROVED + LoanApprovedEvent 발행 |
| `LoanReviewBiasGuardTest` | advice 없는 상태에서 ack → LOAN_193, BLOCKED severity 에서 ack → LOAN_194, 잘못된 상태에서 ack → LOAN_192 |
| `LoanReviewApproverOverrideTest` | OVERRIDE_APPROVED → 결정 변경 + 금액 적용, OVERRIDE_REJECTED → 결정 변경 + 신청 REJECTED |
| `LoanReviewApproverGuardTest` | 4-eye 위반(LOAN_196), 사유 누락(LOAN_197), override_approved 필수값 누락(LOAN_198) |
| `LoanReviewBiasForceCompleteTest` | 운영 강제 진행 → advice 없이도 COMPLETED + 신청 전이 |
| `LoanReviewBiasExpiryBatchTest` | N일 미응답 BIAS_REVIEWING → EXPIRED, EOD 스텝 연결 확인 |
| (기존) `LoanReviewFlowTest` | confirm 후 COMPLETED 가정 → **수정 필요**: BIAS_REVIEWING 가정으로 갱신 |
| (기존) `LoanApprovedNotificationFlowTest` | 알림 발송 시점 이동 → **수정 필요** |

**날짜 격리** (메모리 규칙): 각 테스트 다른 연도 사용
- BiasFlow: 2032
- BiasGuard: 2033
- ApproverOverride: 2034
- ApproverGuard: 2035
- BiasForceComplete: 2036
- BiasExpiryBatch: 2037

### 9.2 단위 테스트
- `LoanReview` 상태 전이 메서드 각각
- `LoanBiasCheckRequestedPayloadBuilder` 출력 스키마
- `LoanReviewBiasReportService` 멱등성

---

## 10. 운영·관측

- 메트릭:
  - `loan_review_bias_reviewing_total` (counter, by reasonCd)
  - `loan_review_bias_reviewing_age_seconds` (gauge, p50/p95)
  - `loan_review_bias_blocked_total` (counter)
  - `loan_review_approver_override_total{decision}` (counter)
- 로그 키: `revId`, `applId`, `reviewerId`, `approverId`, `biasSeverity`, `eventOutcome`
- 알람:
  - BIAS_REVIEWING age p95 > 30 분
  - bias-report 5xx > 5% / 5min
  - override 비율 > 20% / 일 (편향 의심 신호)

---

## 11. 리스크 / 미결정

| 항목 | 메모 |
|---|---|
| 편향 에이전트 SLA | TAT 가정치 = 1~3분. 30분 cutoff 가 합리적인지 운영과 합의 필요 |
| BLOCKED 처리 | Phase 1 은 ack 만 차단 (LOAN_194). Phase 2 에서 자동 revise 권유 흐름 추가 가능 |
| 4-eye 우회 | force-complete 사용 권한을 ROLE_RISK_OPS 등으로 제한 필요 (별도 권한 정책) |
| 신청 상태 전이 이동 영향 | 약정·실행·알림 시점 이동을 비즈니스가 수용한 결정인지 확인 필요 |
| `revise()` 동작 | 현재처럼 COMPLETED 후에만 동작 유지. PENDING_APPROVER 에서 정정하려면 별도 plan |
| 페이로드 PII | 주민번호·계좌번호 제외. 에이전트가 추가 정보 필요시 별도 read-only API + 마스킹 |
| 에이전트 인증 | 내부 API 호출에 service-to-service JWT 또는 API 키. 정책은 별도 결정 |
| `ai_review_advice` 와 `banking-review-llm.md` 의 SUMMARY 등 다른 advice_type 의 관계 | 동일 테이블 공유. Phase 1 은 BIAS_CHECK 만 적재 |

---

## 12. 완료 정의 (DoD)

- [ ] V18, V19 마이그레이션 적용
- [ ] `loan_review` 상태 전이 4종 (BIAS_REVIEWING/PENDING_APPROVER 진입·이탈) 단위 테스트 통과
- [ ] `run()` / `confirm()` 후 BIAS_REVIEWING + outbox row 1건 통합 테스트 통과
- [ ] bias-report 내부 API → advice 1건 + severity 캐시 통합 테스트 통과
- [ ] acknowledge-bias 차단 케이스 3종 (없음/BLOCKED/상태) 통합 테스트 통과
- [ ] approver-approve AS_IS / OVERRIDE_APPROVED / OVERRIDE_REJECTED 3종 통합 테스트 통과
- [ ] 4-eye·필수값 가드 4종 통합 테스트 통과
- [ ] force-complete + expire-bias-reviewing 운영 도구 통합 테스트 통과
- [ ] 기존 `LoanReviewFlowTest`, `LoanApprovedNotificationFlowTest` 보정 통과
- [ ] 메트릭 등록 + 알람 룰 (Grafana JSON 커밋, 옵션)
