# 본심사 LLM 도입 Plan

> 현재 구현(룰 기반 본심사) 위에 LLM 보조 기능을 얹는 단계별 계획서.
> 본심사(loan_review) 결정권은 여전히 룰·사람에게 있고, LLM 은 *보조* 역할만 한다.

---

## 1. 현재 본심사 도메인 — 출발점

코드 기준 현황(LoanReviewService / ReviewCheckLogService / Controller).

### 1.1 엔드포인트

| Method | Path | 용도 |
|---|---|---|
| POST   | `/api/loan-applications/{applId}/review`              | 본심사 수동 실행 (`run`) |
| GET    | `/api/loan-applications/{applId}/review`              | 본심사 조회 |
| PATCH  | `/api/loan-applications/{applId}/review`              | 결정 정정 (`revise`) |
| POST   | `/api/loan-applications/{applId}/review/auto-decide`  | 자동 결정 권고 (`autoDecide` → PENDING_APPROVAL) |
| POST   | `/api/loan-applications/{applId}/review/confirm`      | 권고 확정 (`confirm` → COMPLETED) |
| GET    | `/api/loan-reviews/pending`                           | 권고 대기 목록 |
| GET    | `/api/loan-reviews/stats`                             | 결정 통계 |
| GET    | `/api/loan-reviews/{revId}/checks`                    | 체크로그 조회 |
| POST   | `/api/loan-reviews/{revId}/checks`                    | 체크로그 수동 적재 |
| POST   | `/api/internal/loan-reviews/expire-pending`           | 권고 만료 배치 |

### 1.2 사전조건(LOAN_038)

- 신청 상태 = `PRESCREENED`
- `credit_evaluation` 존재 + `cevalDecisionCd != REJECT`
- `dsr_calculation` 존재 + `dsrStatusCd = PASS`
- `loan_identity_verification` 1건 이상 `PASS`
- 상품 `collateralRequiredYn = Y` 면 활성 담보별 LTV PASS

### 1.3 결정 룰 (`autoDecide`)

우선순위: `CB.REJECT` → `DSR.FAIL` → `LTV.FAIL` → `APPROVED`.
`CB.REVIEW` 는 `LOAN_048` 로 자동 결정 거부 → **수동 본심사 권유**. 이 지점이 LLM 첫 진입점.

### 1.4 체크로그 항목

`ReviewCheckLog.ITEM_*`: `PRESCREEN_PASS`, `CB_DECISION`, `DSR_CHECK`, `LTV_CHECK`, `FINAL_DECISION`.
RESULT: `PASS / FAIL / REVIEW / N_A`. append-only.

---

## 2. LLM 도입 목표

룰만으로 판단이 흐릿한 케이스를 사람이 잘 결정하도록 **요약·근거·추천**을 제공한다. 결정 자체는 LLM 이 내리지 않는다.

| 기능 | 효과 | 결정권 |
|---|---|---|
| F1. CB.REVIEW 사유 요약 + 인사이트 | 수동 본심사 도입부에서 reviewer 가 빠르게 컨텍스트 파악 | reviewer |
| F2. 거절 사유 자연어 생성 | `rejectReasonCd` 코드 → 고객 통지 문구 자동 초안 | reviewer 검토 |
| F3. 정정 사유 추천 | `revise` 호출 시 입력 변경분 기반으로 `revisitReasonCd` 후보 제시 | reviewer |
| F4. 권고 vs 실제 결정 갭 탐지 | `autoDecide` 권고와 `confirm`/`revise` 결정이 자주 어긋나는 패턴 보고 | 운영 |
| F5. 첨부 문서 발췌 (소득증빙·재직증명) | 서류에서 핵심 수치 추출해 reviewer 화면에 노출 | reviewer |

---

## 3. 아키텍처

```
[loan-service]
   ├─ LoanReviewService.autoDecide()
   │     └─ (룰 결정 완료 후) ReviewAiAdvisor.summarize(applId, revId)  ← 비동기, 실패 무시
   ├─ LoanReviewService.revise()
   │     └─ ReviewAiAdvisor.suggestRevisitReason(beforeRev, afterReq)
   ├─ LoanReviewService.confirm()
   │     └─ ReviewAiAdvisor.draftRejectionLetter(rev)  (거절일 때만)
   └─ ReviewAiAdvisorClient  ──HTTP──▶  [review-ai-gateway]
                                              ├─ prompt builder
                                              ├─ LLM provider (OpenAI/Bedrock/내부)
                                              └─ 감사 로그 (ai_review_advice)
```

### 3.1 설계 원칙

- **결정권 분리**: LLM 출력은 `review_check_log` 옆 `ai_review_advice` 테이블에만 저장. `loan_review.rev_decision_cd` 에는 절대 영향 주지 않는다.
- **사후 호출**: 본심사 트랜잭션 커밋 *이후* `@TransactionalEventListener(AFTER_COMMIT)` 로 호출. LLM 실패가 본심사 결과를 막지 않는다.
- **결정성 유지**: 같은 input 에 대해서는 `temperature=0`, `seed` 고정. 운영 가시성을 위해 `prompt_hash`, `model`, `model_version` 을 같이 저장.
- **PII 마스킹**: 주민번호·계좌번호는 호출 직전 `Masking.*` 로 마스킹. 마스킹 전 raw 는 LLM gateway 외부로 안 나간다.
- **타임아웃·서킷브레이커**: 2 s + Resilience4j circuit breaker. 떨어지면 advice 미생성, 본 흐름은 통과.

---

## 4. 새 테이블 — `ai_review_advice`

| 컬럼 | 타입 | 비고 |
|---|---|---|
| advice_id | BIGSERIAL PK | |
| rev_id | BIGINT FK → loan_review | |
| advice_type_cd | VARCHAR(40) | `SUMMARY` / `REJECTION_LETTER` / `REVISIT_REASON` / `GAP_REPORT` / `DOC_EXTRACTION` |
| advice_body | TEXT | LLM 출력 (자연어 또는 JSON) |
| model | VARCHAR(80) | e.g. `claude-3-5-sonnet-20241022` |
| model_version | VARCHAR(40) | provider 가 주는 버전 또는 hash |
| prompt_hash | CHAR(64) | sha256(prompt) — 재현성 |
| input_token | INT | |
| output_token | INT | |
| latency_ms | INT | |
| created_at | TIMESTAMPTZ | |
| created_by | BIGINT | actor (시스템이면 NULL) |

인덱스: `(rev_id, advice_type_cd, created_at DESC)`.

soft delete 불필요 — append-only.

---

## 5. API 추가

| Method | Path | 용도 |
|---|---|---|
| GET  | `/api/loan-reviews/{revId}/advices` | 해당 본심사의 advice 목록 |
| POST | `/api/loan-reviews/{revId}/advices/{adviceTypeCd}/regenerate` | 운영자가 강제 재생성 |
| GET  | `/api/internal/ai-review/gap-report?from=&to=` | F4 갭 리포트 |

기존 `review`/`confirm`/`revise` 응답 envelope 은 변경하지 않는다 — advice 는 별도 조회.

---

## 6. 단계별 실행 plan

각 단계는 **별도 PR + 별도 커밋**. 기능/테스트 분리(`feat(...)` + `test(...)`).

### Step 1. 인프라 — 게이트웨이 스켈레톤
- `agents/review-ai-gateway` 신규 모듈 (Spring Boot).
- `/v1/advice` POST 단일 엔드포인트, 더미 응답 (`"stub"`) 반환.
- 헬스체크, 메트릭(timer), 구조화 로그.
- 빌드/도커 등록.

### Step 2. 스키마 + Advisor 클라이언트
- `ai_review_advice` 테이블 + JPA 엔티티 + repository.
- `ReviewAiAdvisorClient` (RestClient + Resilience4j) — gateway 호출.
- `ReviewAiAdvisor` (loan-service 측 facade) — 비동기 발행 + 저장.
- 단위 테스트: gateway down → advice 미생성, 본 흐름은 성공.

### Step 3. F1 — `autoDecide` 요약 advice
- `autoDecide` 완료 이벤트 `LoanReviewAutoDecidedEvent` 발행.
- 리스너에서 CB/DSR/LTV/IDV/Collateral 입력값을 prompt 로 빌드 → gateway 호출.
- 응답을 `ai_review_advice (SUMMARY)` 로 저장.
- 통합 테스트: 권고 생성 후 advice 1건이 SUMMARY 로 저장됨.

### Step 4. F2 — 거절 통지 초안
- `confirm` 시 결정이 REJECTED 면 `REJECTION_LETTER` advice 생성.
- 한국어 톤 가이드라인을 system prompt 에 고정.
- 통합 테스트: 거절 confirm → REJECTION_LETTER advice 존재, 승인 confirm → 없음.

### Step 5. F3 — 정정 사유 추천
- `revise` 입력(before vs after) 을 prompt 로 → `REVISIT_REASON` advice.
- 운영자는 이를 reviewerId 메모로 복사. `revisitReasonCd` 자동 채움 ❌ (사람이 선택).

### Step 6. F4 — 갭 리포트 배치
- 일 1회 batch — 직전 N일 권고 vs 확정 결정 비교.
- 어긋남 비율·자주 어긋나는 `rejectReasonCd` 를 prompt 에 넣고 `GAP_REPORT` 생성.
- 운영 대시보드(미정)에 GET 으로 노출.

### Step 7. F5 — 문서 발췌 (옵션, 후순위)
- `loan_document` 업로드 시 PDF 텍스트 추출 → 핵심 필드(연소득·재직월수) 후보 추출.
- 추출 결과는 advisor 가 본심사 시점에 SUMMARY 컨텍스트에 포함.
- OCR/PDF 파서 외부 의존성 평가 필요 — 별도 spike.

---

## 7. 테스트 전략

기존 통합 테스트 컨벤션(`AbstractLoanIntegrationTest`) 그대로 따른다.

| 클래스 | 목표 |
|---|---|
| `ReviewAiAdvisorFlowTest` | autoDecide → SUMMARY advice 1건 생성, gateway down 시 본 흐름 통과 |
| `RejectionLetterAdviceTest` | confirm(REJECTED) → REJECTION_LETTER 생성 / confirm(APPROVED) → 미생성 |
| `RevisitReasonAdviceTest` | revise 호출 시 REVISIT_REASON advice 생성 |
| `AiReviewAdviceApiTest` | GET 목록·POST regenerate 동작, 미존재 revId 404 |
| `AiReviewGatewayContractTest` (gateway 모듈) | request/response 스키마 계약, 타임아웃, 4xx/5xx 핸들링 |

advisor 호출은 stub WireMock 으로 격리 — 실제 LLM provider 비용 발생 금지.

날짜 격리: 배치 테스트(F4) 는 메모리 룰대로 연도 분리(`2031/2041/...`).

---

## 8. 운영·관측

- 메트릭: `ai_review_advice_seconds`(timer), `ai_review_advice_total{type, status}`.
- 로그 키: `revId`, `adviceType`, `model`, `promptHash`, `latencyMs`, `outcome`.
- 알람: gateway 5xx > 5% / 5min, p95 latency > 3 s.
- 비용 가드: 일별 토큰 사용량 cap, 초과 시 advice 생성 중단(본 흐름은 통과).

---

## 9. 리스크 / 미결

- **데이터 거버넌스**: 외부 LLM 사용 시 개인정보 전송 사전 검토 필요. 내부 모델 전환 옵션 항상 열어둔다 (gateway 추상화).
- **모델 변경 비용**: 모델 업그레이드 시 advice 톤이 바뀜 → 운영자 혼란. 모델 ID/버전을 advice 행에 박아두는 게 회귀 추적의 최소 안전망.
- **사람의 자동화 편향**: SUMMARY 가 너무 단정적이면 reviewer 가 그대로 따른다. prompt 에 "결정을 내리지 말 것" 명시 + 항상 근거 코드(CB/DSR/LTV) 인용을 강제.
- **CB.REVIEW 처리**: 현재 `LOAN_048` 로 자동 결정 거부 후 사람으로 흘림. LLM 도입 후에도 이 흐름은 유지 — 단지 SUMMARY 가 첨부될 뿐.
- **F5 의존성**: 문서 파서/OCR 도입 비용이 크면 F5 는 별 plan 으로 분리.

---

## 10. 완료 정의 (DoD)

- [ ] `review-ai-gateway` 모듈 + 헬스체크 + 메트릭
- [ ] `ai_review_advice` 테이블 마이그레이션
- [ ] `ReviewAiAdvisor` 비동기 호출 (AFTER_COMMIT)
- [ ] F1 SUMMARY 통합 테스트 통과
- [ ] F2 REJECTION_LETTER 통합 테스트 통과
- [ ] F3 REVISIT_REASON 통합 테스트 통과
- [ ] gateway down 시 본심사 흐름이 정상 통과함을 검증
- [ ] 비용 cap 환경변수화 + 초과 시 fail-open 확인
- [ ] 운영 대시보드/알람 룰 등록 (Grafana JSON 커밋)
