# Advisory 시스템 연동 가이드 (loan-service 참고용)

> 대상 독자: loan-service 심사 프로세스 개발자  
> 최종 수정: 2026-05-28

---

## 1. 시스템 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                         loan-service (:8083)                    │
│                                                                 │
│  LoanReviewService.run()                                        │
│       │                                                         │
│       ├─ 심사 결과 저장 (loan_review)                            │
│       └─ LoanReviewCompletedEvent 발행 (Kafka)                  │
└────────────────────────┬────────────────────────────────────────┘
                         │ Kafka: loan-review-completed (Avro/JSON)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                    advisory-service (:8080)                     │
│                                                                 │
│  AdvisoryReviewListener                                         │
│       │                                                         │
│       ├─ AdvisoryEvaluator (규칙 엔진 동기 실행)                  │
│       │       ├─ BiasApprovalRateDeviationRule                  │
│       │       ├─ BiasRejectRateDeviationRule                    │
│       │       ├─ DsrThresholdOverrideRule                       │
│       │       ├─ LtvThresholdOverrideRule                       │
│       │       └─ PeerDecisionDivergenceRule                     │
│       │                                                         │
│       ├─ ReviewAdvisoryReport 저장 (severity: INFO/WARN/CRITICAL)│
│       │                                                         │
│       └─ AuditFairnessAgent → review-ai-gateway 호출            │
│                                                                 │
│  RAG 검색 (pgvector)                                            │
│       ├─ SimilarCaseRetriever   — 유사 심사 사례 top-5           │
│       └─ PolicyCitationRetriever — CRITICAL 규칙 정책 근거       │
└────────────────────────┬────────────────────────────────────────┘
                         │ HTTP (내부망)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                  review-ai-gateway (:8088)                      │
│                                                                 │
│  AgenticLoop (Claude claude-opus-4-7)                           │
│       ├─ Tool: CohortStatsToolExecutor                          │
│       ├─ Tool: PolicyCitationToolExecutor                       │
│       ├─ Tool: ReviewerHistoryToolExecutor                      │
│       └─ Tool: SimilarCasesToolExecutor                         │
│                                                                 │
│  → AuditAnalysisResponse (conclusion + reasoning + citations)   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 심사 프로세스에서 Advisory가 개입하는 시점

loan-service는 심사 완료 이벤트를 발행하기만 하면 됩니다. Advisory 평가는 비동기로 실행되며 loan-service를 직접 블로킹하지 않습니다.

### 2.1 현재 이벤트 발행 위치

`LoanReviewService.run()` 내부에서 심사가 완료되면 두 가지 이벤트를 발행합니다.

```java
// services/loan-service/.../review/LoanReviewService.java

eventPublisher.publishEvent(new LoanApprovedEvent(review));
eventPublisher.publishEvent(new LoanReviewCompletedEvent(review));
//                                  ↑
//  advisory-service의 AdvisoryReviewListener가 이 이벤트를 소비
```

### 2.2 Advisory 평가 결과를 심사 화면에 노출하는 흐름

```
심사관(UI)
  │
  ├─ POST /api/loan-applications/{applId}/review   → loan-service 심사 실행
  │
  └─ (비동기, 수 초 후)
       GET /api/advisory/reports?revId={revId}    → advisory-service 조회
       GET /api/advisory/reports/{advrId}/similar-cases
       GET /api/advisory/reports/{advrId}/citations
```

loan-service 내부에서 Advisory 결과를 직접 pull 해야 하는 경우(예: 심사 확정 전 경고 표시)라면 아래 섹션의 REST API를 참조하세요.

---

## 3. Advisory-Service REST API

> Base URL: `http://advisory-service:8080` (loan-service `application.yml` → `advisory.ai-gateway.base-url` 참조)

### 3.1 Advisory 리포트 목록 조회

```
GET /api/advisory/reports?revId={revId}
```

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `revId` | Long | 심사 ID (loan_review.rev_id) |

**응답 예시**

```json
[
  {
    "advrId": 1024,
    "revId": 507,
    "advisoryTypeCd": "BIAS_DETECTION",
    "severityCd": "WARN",
    "advrStatusCd": "OPEN",
    "advrTitle": "동일 코호트 대비 승인율 편차 감지",
    "advrSummary": "동일 신용등급 대출 목적 코호트 기준 승인율이 평균 대비 18%p 낮습니다.",
    "targetReviewerId": "rev-2041"
  }
]
```

**severityCd 의미**

| 값 | 의미 | 권고 조치 |
|----|------|----------|
| `INFO` | 참고 정보 | 열람 후 진행 가능 |
| `WARN` | 주의 필요 | 심사관 확인 후 진행 권장 |
| `CRITICAL` | 위반 가능성 | 반드시 리포트 확인 및 ACK 필요 |

---

### 3.2 유사 심사 사례 조회 (RAG)

```
GET /api/advisory/reports/{advrId}/similar-cases
```

pgvector 코사인 유사도 검색으로 top-5 유사 사례를 반환합니다. 번복(overturn) 사례는 동점 시 우선 노출됩니다.

**응답 예시**

```json
[
  {
    "caseId": "CASE-20240312-081",
    "decisionCd": "APPROVED",
    "overturned": false,
    "creditScore": 724,
    "dsrRatioBps": 3850,
    "ltvRatioBps": 6200,
    "employmentTypeCd": "EMPLOYED",
    "loanPurposeCd": "HOUSING",
    "similarityScore": 0.94,
    "summaryText": "신용점수 720대, DSR 38.5%, 주택담보 승인 사례"
  }
]
```

**활용 방법**: 심사관이 현재 심사건과 유사한 과거 사례의 결정 근거를 참조하도록 UI에 노출합니다.

---

### 3.3 정책 인용(Policy Citation) 조회 (RAG)

```
GET /api/advisory/reports/{advrId}/citations
```

CRITICAL 규칙이 트리거된 리포트에 한해, 해당 규칙 위반과 관련된 내부 정책 문서 인용 목록을 반환합니다.

**응답 예시**

```json
{
  "citations": [
    {
      "documentId": "POL-FAIR-LENDING-2024-003",
      "documentTitle": "공정대출 심사 기준",
      "chunkText": "동일 조건 차주에 대한 대출 결정은 신용도 외 개인 속성에 의해 차별되어서는 안 됩니다. ...",
      "relevanceScore": 0.91
    }
  ]
}
```

---

### 3.4 리포트 ACK (확인 처리)

```
POST /api/advisory/reports/{advrId}/ack
```

심사관이 Advisory 내용을 확인했음을 기록합니다. CRITICAL 리포트는 ACK 없이 최종 심사 확정 처리가 불가하도록 loan-service에서 사전 검증하는 것을 권장합니다.

---

### 3.5 감사(Audit) 의견 조회

```
GET /api/advisory/audit/opinions/by-report/{advrId}
GET /api/advisory/audit/opinions/by-reviewer/{reviewerId}
GET /api/advisory/audit/risk-scores/{reviewerId}
```

AI Gateway(Claude)가 생성한 편향·컴플라이언스 분석 결과입니다. 감사팀 대시보드 또는 리뷰어 리스크 점수 표시에 활용합니다.

---

## 4. CRITICAL 리포트 미확인 시 심사 확정 차단 (권장 구현)

심사 확정(`POST /review/confirm` 또는 `PATCH /review`) 시점에 아래 체크를 추가하면 CRITICAL Advisory를 심사관이 반드시 확인하도록 강제할 수 있습니다.

```java
// loan-service 내 LoanReviewService 또는 LoanReviewReviseService에 추가

private void assertNoCriticalUnackedAdvisory(Long revId) {
    List<AdvisoryReportSummary> reports = advisoryClient.getReports(revId);
    boolean hasUnackedCritical = reports.stream()
        .filter(r -> "CRITICAL".equals(r.getSeverityCd()))
        .anyMatch(r -> !"ACKED".equals(r.getAdvrStatusCd())
                    && !"RESOLVED".equals(r.getAdvrStatusCd()));

    if (hasUnackedCritical) {
        throw new LoanReviewBlockedException(
            "CRITICAL Advisory 리포트를 먼저 확인(ACK)해야 합니다."
        );
    }
}
```

Advisory-service 호출을 위한 HTTP 클라이언트는 `advisory.ai-gateway.base-url` 설정값을 사용합니다.

---

## 5. Kafka 이벤트 흐름 상세

### 5.1 loan-service → advisory-service

| 이벤트 | 토픽 | 트리거 |
|--------|------|--------|
| `LoanReviewCompletedEvent` | (loan-service 내부 Spring ApplicationEvent) | `LoanReviewService.run()` 완료 |

> 현재는 Spring ApplicationEvent로 loan-service JVM 내에서 직접 전달됩니다. advisory-service가 같은 JVM에 모듈로 포함된 경우에만 동작합니다. 마이크로서비스 분리 시 Kafka 외부 토픽으로 전환이 필요합니다.

### 5.2 advisory-service 발행 토픽

| 토픽 | 보존 기간 | 내용 |
|------|----------|------|
| `advisory.report.published.v1` | 7일 | Advisory 리포트 생성 알림 |
| `advisory.quarantine.triggered.v1` | 30일 | CRITICAL 심사 격리 이벤트 |

loan-service에서 Advisory 리포트 생성을 실시간으로 감지하려면 `advisory.report.published.v1` 토픽을 구독하세요.

---

## 6. 설정값 참조

### loan-service `application.yml`

```yaml
advisory:
  ai-gateway:
    base-url: ${AIGATEWAY_BASE_URL:http://localhost:8088}
    timeout-ms: ${AIGATEWAY_TIMEOUT_MS:35000}
    connect-timeout-ms: ${AIGATEWAY_CONNECT_TIMEOUT_MS:3000}

advisory:
  rag:
    embed:
      provider: ${ADVISORY_RAG_EMBED_PROVIDER:stub}   # 로컬: stub / 운영: openai
      model: ${ADVISORY_RAG_EMBED_MODEL:text-embedding-3-small}
      dimension: ${ADVISORY_RAG_EMBED_DIMENSION:1536}
      openai:
        api-key: ${OPENAI_API_KEY:}
```

### 환경 변수 요약

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `AIGATEWAY_BASE_URL` | `http://localhost:8088` | AI Gateway 주소 |
| `AIGATEWAY_TIMEOUT_MS` | `35000` | 응답 타임아웃 (ms) |
| `ADVISORY_RAG_EMBED_PROVIDER` | `stub` | 임베딩 제공자 (`stub`/`openai`) |
| `OPENAI_API_KEY` | (없음) | OpenAI API 키 (운영 필수) |

---

## 7. 로컬 개발 환경에서 Advisory 동작 확인

### 7.1 stub 모드 (외부 의존 없음)

`ADVISORY_RAG_EMBED_PROVIDER=stub`으로 실행하면 임베딩 없이 고정 벡터를 사용합니다. 유사 사례 검색 결과는 더미 데이터입니다.

### 7.2 Advisory 규칙 강제 트리거

심사 완료 후 Advisory가 자동으로 평가됩니다. 특정 규칙만 확인하려면 DB에서 해당 규칙의 `active_yn`을 `'Y'`로, 나머지를 `'N'`으로 설정한 뒤 심사를 재실행하세요.

```sql
-- advisory_rule 테이블 (loan_db 안에 있음)
UPDATE review_advisory_rule SET active_yn = 'N';
UPDATE review_advisory_rule SET active_yn = 'Y' WHERE rule_cd = 'BIAS_APPROVAL_RATE';
```

### 7.3 CRITICAL 리포트 수동 생성 (통합 테스트)

```sql
INSERT INTO review_advisory_report
  (rev_id, advisory_type_cd, severity_cd, advr_status_cd, advr_title, advr_summary)
VALUES
  ({revId}, 'COMPLIANCE_VERIFICATION', 'CRITICAL', 'OPEN',
   '[테스트] 컴플라이언스 위반 감지', '테스트용 CRITICAL 리포트입니다.');
```

---

## 8. 주요 도메인 코드 위치

| 컴포넌트 | 경로 |
|---------|------|
| 심사 서비스 | `services/loan-service/.../review/LoanReviewService.java` |
| 심사 완료 이벤트 | `services/loan-service/.../review/event/LoanReviewCompletedEvent.java` |
| Advisory 이벤트 리스너 | `agents/advisory-service/.../listener/AdvisoryReviewListener.java` |
| 규칙 엔진 | `agents/advisory-service/.../engine/AdvisoryEvaluator.java` |
| 유사 사례 RAG | `agents/advisory-service/.../rag/SimilarCaseRetriever.java` |
| 정책 인용 RAG | `agents/advisory-service/.../rag/PolicyCitationRetriever.java` |
| AI Gateway 에이전틱 루프 | `agents/review-ai-gateway/.../agent/AgenticLoop.java` |
| Tool 목록 | `agents/review-ai-gateway/.../tool/executor/` |

---

## 9. 데이터 흐름 요약 (시퀀스)

```
심사관            loan-service          advisory-service        review-ai-gateway
   │                    │                      │                        │
   │  POST /review      │                      │                        │
   │──────────────────►│                      │                        │
   │                    │ LoanReviewCompletedEvent                      │
   │                    │─────────────────────►│                        │
   │                    │                      │ 규칙 평가 (동기)         │
   │                    │                      │──────────┐              │
   │                    │                      │◄─────────┘              │
   │                    │                      │ POST /internal/audit/analyze
   │                    │                      │────────────────────────►│
   │                    │                      │                        │ Claude 에이전틱 루프
   │                    │                      │                        │──────┐
   │                    │                      │                        │◄─────┘
   │                    │                      │◄───────────────────────│
   │                    │                      │ ReviewAdvisoryReport 저장
   │  200 OK (심사 결과) │                      │                        │
   │◄──────────────────│                      │                        │
   │                    │                      │                        │
   │  (UI에서 Advisory 조회)                    │                        │
   │  GET /api/advisory/reports?revId=…        │                        │
   │──────────────────────────────────────────►│                        │
   │◄──────────────────────────────────────────│                        │
   │  GET /api/advisory/reports/{id}/similar-cases                      │
   │──────────────────────────────────────────►│                        │
   │◄──────────────────────────────────────────│                        │
```

---

## 10. FAQ

**Q. Advisory 평가가 끝나기 전에 심사 응답이 먼저 오면?**  
Advisory 평가는 비동기입니다. 심사 완료 응답은 즉시 반환되고, Advisory 리포트는 수 초 내에 생성됩니다. UI에서 폴링하거나 `advisory.report.published.v1` 토픽을 구독해 실시간 알림을 받는 것을 권장합니다.

**Q. Advisory 서비스가 다운되면 심사에 영향이 있나?**  
현재 구조상 `LoanReviewService`는 Advisory 평가 결과를 기다리지 않습니다. Advisory 서비스 장애 시 심사 자체는 정상 완료되고, Advisory 리포트만 누락됩니다.

**Q. CRITICAL 리포트를 무시하고 심사를 확정할 수 있나?**  
기본 구현에는 차단 로직이 없습니다. [섹션 4](#4-critical-리포트-미확인-시-심사-확정-차단-권장-구현)의 구현을 추가하면 강제 확인을 요구할 수 있습니다.

**Q. 유사 사례 검색이 느린 경우?**  
`advisory_case_index` 테이블의 `embedding` 컬럼에 `ivfflat` 또는 `hnsw` 인덱스가 있는지 확인하세요. 운영 환경에서는 `ADVISORY_RAG_EMBED_PROVIDER=openai`로 설정해야 실제 벡터 검색이 동작합니다.
