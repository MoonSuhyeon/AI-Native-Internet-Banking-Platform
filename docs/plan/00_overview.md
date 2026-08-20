# loan-service 보완·개선·추가 plan — 개요

<!-- plan-status -->
> **상태: 🔨 진행 중** — 개요 문서. 아래 개별 계획을 묶는다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


## 목적

`docs/plan/loan_domain_plan.md` 의 후속. 인터넷 뱅킹 여신계(loan-service)의 현재 코드베이스를 1차 분석해 **보완(완성도 부족) / 개선(설계 약점) / 추가(누락된 영역)** 으로 묶고, 각각을 별도 plan 파일로 분리한다. 단위는 "독립적으로 한 단계씩 끝낼 수 있는 작업"이며, 각 plan 안에서도 커밋 단위까지 세분화한다.

## 제외 (사용자 지시)

다음은 본 plan군의 **모든 파일에서 제외**한다.

- **상계(set-off)** — 예금/적금 등 다른 계정과의 상계 처리
- **대환(refinancing, 대환대출)** — 기존 대출을 새 대출로 갈음
- **연체 도메인의 모든 후속 작업** — 단, **연체 → 신용정보등록(creditreport)** 흐름만 유지·강화 대상
  - 즉, `DelinquencyRolloverService` 자체 보완·`autodebit` 실패 시 OVERDUE 전이 강화·연체이자 분배 정밀화·연체 stage 정책 확장 등은 **본 plan군에서 다루지 않는다**
  - 다만 OVERDUE/RESOLVED 전이 시점에 `CreditInfoReportService` 호출이 자동으로 발화되도록 만드는 부분은 살린다 (단방향 호출만)
- 위 영역에 대한 신규 테스트도 동일하게 제외 (`creditreport` 테스트 보강은 허용)

→ 본 plan군 작업이 끝나도 연체 일배치 본체와 자동이체 재시도/실패 라이프사이클은 손대지 않은 상태가 된다. 사용자가 명시적으로 허락한 "연체에서의 신용정보등록 자동화" 만 살린다.

## 진척도 (스냅샷: 2026-05-23 갱신)

상태 표기: ☑ 완료 / ◑ 진행중 / ☐ 미착수 / ⏸ 보류

| # | 파일 | 상태 | 진척 | 최근 관련 커밋 | 잔여 |
|---|---|---|---|---|---|
| 1 | `01_creditreport_auto_emit.md` | ◑ | 7/8 | 479f4a9, da5fa6d, 665e509, 9e9a7b4, c3add92, 9532aee | 종결 listener(08 의존), 종결 자동신고 테스트 |
| 2 | `02_creditreport_lifecycle.md` | ☑ | 9/9 | 7d44253, fb73b6a, 04d52cd, d9b775e, 8e8150c, 6b87f72, a923848, 55be710, b7e60ac | 완료 (라이프사이클·재전송·디스패치·ACK 풀세트) |
| 3 | `03_notification_outbox.md` | ☑ | 7/7 | 9a38441, fbd4459, 916dd66, 3792b8e, c984f2d, 3b419b1, dfd5cd3 | 완료 (outbox·어댑터·디스패치·운영자 API·라이프사이클 회귀) |
| 4 | `04_payment_allocation.md` | ☑ | 완료 | 34bbdfc, 2058381, bd68bc6 | (12 의 회귀 테스트로 검증 완료) |
| 5 | `05_schedule_calendar.md` | ☑ | 8/8 | 9842126, 68ddacc, d6342d8, c422e6e, 8597cda, 7fa0732, 27b8326, dcd1ade | 완료 (스케줄·autodebit·만기 휴일 보정 + 공휴일 시드 2026-2035) |
| 6 | `06_guarantor_integration.md` | ☑ | 6/6 | 43c447d, d94c3c4, b7593ba, 5d173b9, e508340, 651f216 | 완료 (minGuarantorCount·Validator·3서비스 통합·취소이벤트·회귀) |
| 7 | `07_creditscore_link.md` | ☐ | 0 | — | preview 통합 또는 deprecate 판단부터 |
| 8 | `08_closure_completeness.md` | ☐ | 0 | — | WRITE_OFF/SUBROGATION 실로직 전체 |
| 9 | `09_application_expiry_per_product.md` | ☑ | 3/3 | 7ac2a26, ca000de, 1ac04fb | 완료 (validityDays 필드·배치 차등·회귀) |
| 10 | `10_observability.md` | ☐ | 0 | — | MDC/메트릭/상태이력 보강 전체 |
| 11 | `11_security_pii_idempotency.md` | ☐ | 0 | — | PII 암호화·인가·멱등 전체 |
| 12 | `12_test_gaps.md` | ◑ | reversal/partial/prepay ☑, closure ☐ | a52c339, 17f158f, fa1c199 | closure 통합 테스트 (08 의존) |
| 13 | `13_rag_operationalization.md` | ☐ | Phase 1.7 완료, 운영 전환 대기 | 091ecb3 외 RAG 커밋 | Stage 1~7 (임베딩 모델·시드·백필·인덱스·통합·메트릭·정리) |

**다음 단계**: plan 08 `종결 completeness` → WRITE_OFF/SUBROGATION 실로직. 완료 시 plan 01·12 의 종결 관련 잔여도 동시 해소. plan 07(orphan API 정리)은 독립이라 언제든 착수 가능.

## plan 파일 색인 (구현 순서 권장)

| # | 파일 | 영역 | 비고 |
|---|---|---|---|
| 1 | `01_creditreport_auto_emit.md` | 연체→신용정보등록 자동화 | 본 plan군에서 *유일하게 살아남는* 연체 연동 |
| 2 | `02_creditreport_lifecycle.md` | 신용정보 신고 라이프사이클 완성 | ACKED/FAILED/재전송/실패큐 |
| 3 | `03_notification_outbox.md` | 알림 outbox 패턴 | 5개 listener 의 stub → outbox 적재로 교체 |
| 4 | `04_payment_allocation.md` | 회차상환·중도상환 분배 정밀화 | RepaymentService/PrepaymentService 의 `0L` 하드코딩 제거 |
| 5 | `05_schedule_calendar.md` | 휴일 보정 (dueDate/maturity) | 신규 약정부터 적용 |
| 6 | `06_guarantor_integration.md` | 연대보증인 SIGNED 검증 통합 | 본심사·약정 사전조건 |
| 7 | `07_creditscore_link.md` | creditscore preview 통합 또는 deprecate | orphan API 정착 |
| 8 | `08_closure_completeness.md` | 종결 WRITE_OFF/SUBROGATION 실로직 | 잔액 회계·회차 일괄 전이 |
| 9 | `09_application_expiry_per_product.md` | 신청 만료 상품별 차등 | 14일 상수 → 상품 필드 |
| 10 | `10_observability.md` | 관측성·구조화 로그·트레이싱 | MDC·메트릭·상태이력 보강 |
| 11 | `11_security_pii_idempotency.md` | PII 암호화·인가·멱등 | CryptoService 적용 자리 |
| 12 | `12_test_gaps.md` | 통합 테스트 갭 | reversal/partialrepayment/prepayment/closure 등 |
| 13 | `13_rag_operationalization.md` | RAG 운영 전환 (모델·시드·백필·통합) | Phase 1.7 후속 |
| 14 | `14_common_db_layer.md` | 공통 계층(common_db) 확장 + loan 연결 | common_db Phase 1 후속, deposit Phase 2 핸드오프 |

## 비-목표 (본 plan군 전체 공통)

- 위 "제외" 섹션 항목 전부
- 외부 SMS/KAKAO/EMAIL SDK 실 연동 — 03 은 outbox 와 어댑터 인터페이스만 도입
- 외부 결제 모듈(`LoanExecutionService.transactionId`) 실 송금 연동
- Kafka/MQ 도입 — outbox 다음 단계 작업
- 본심사 LLM 도입 — `docs/ai/banking-review-llm.md` 별도 plan
- 다국가/다통화/외화 — 통화 코드만 유지, 환산·외화금리 없음
- 권한·역할 매트릭스 전면 재설계 — `support` 영역에 부분 보강만 (11번)

## 검증 공통 규칙

- 각 plan 마지막 단계에서 `:services:loan-service:test` 풀런
- 메모리 룰: 배치성 테스트는 테스트별로 다른 연도(2033/2034/2035/...) 사용 — 시기 중복 X
- 메모리 룰: 한 커밋에 `feat` 와 `test` 섞지 말 것 — 분리 커밋
- 메모리 룰: 커밋 메시지에 AI 흔적·Co-Authored-By 금지
- 메모리 룰: 커밋 메시지는 `<type>(<scope>): <한글 subject>` 한 줄 — body/불릿 금지 (AI_GUIDELINES)
- 메모리 룰: 한 단계 끝나면 보고 후 멈춤 (자동 연속 진행 금지)

## AI_GUIDELINES 준수 체크리스트 (모든 plan 공통)

`AI_GUIDELINES.md` 의 룰을 plan 작업·구현 전반에 강제한다. 본 plan군의 어느 항목도 아래를 위반하지 않는다.

### 모델링
- **금액 컬럼**: `BIGINT` (Java `Long`) — 원 단위 정수
- **금리 컬럼**: `INT` (Java `Integer`), 단위 bps
- **날짜 컬럼**: `CHAR(8)` (Java `String`, `YYYYMMDD`)
- **시각 컬럼**: `TIMESTAMPTZ(3)` (Java `OffsetDateTime`)
- **하드 삭제 금지** — soft-delete 컬럼 `deleted_at` 사용
- **상태 전이마다 `status_history` append** — 누락 금지
- **domain 은 POJO** — Lombok `@Getter @Builder @RequiredArgsConstructor @Slf4j` 만 사용. `@Data @AllArgsConstructor @Setter(entity)` 금지

### 아키텍처
- `controller → service → repository → domain → common` 단방향
- 서비스 간 직접 import 금지 — 이벤트 또는 outbox 경유
- `common` 변경 시 모든 의존 서비스 회귀

### 엔지니어링
- 트랜잭션 내 외부 API 호출 금지 — 외부 어댑터(KCB/NICE/SMS 등) 호출은 항상 **dispatch 배치** 에서 처리, 도메인 트랜잭션은 outbox row 적재까지만
- `SELECT *` 금지, JPA 쿼리에서 필요한 컬럼만
- N+1 금지 — 일괄 전이는 `bulk update` 또는 `saveAll` 후 flush 1회
- `findAll(...)` 무페이지 금지 — 운영자 조회 엔드포인트는 모두 `Pageable` 필수
- 캐시 도입 시 TTL 필수 — 본 plan군은 캐시 추가 없음
- 예외는 `BusinessException + LoanErrorCode` 만 — 광범위 catch 금지, `throw new RuntimeException(...)` 금지

### 보안·로그
- PII 컬럼은 11 plan 의 `@PiiEncrypted` 적용 대상
- 로그에 PII 평문 금지 — 마스킹 + `traceId(MDC)` (10 plan)
- AI 흔적 (Co-Authored-By, 모델명, "Generated by ...") 금지
- `.env` 커밋 금지, 운영 DB 접근 금지

### 워크플로
- 5 파일 초과 변경 시 사용자 승인 후 진행 — 본 plan군의 커밋 단계는 대부분 이를 의식해 분리되어 있음
- WHY 코멘트만 — WHAT 은 코드/식별자로 표현
- 큰 변경 전 영향 파일 목록 보고
