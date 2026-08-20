# 14. 공통 계층(common_db) 확장 + loan-service 연결

<!-- plan-status -->
> **상태: ✅ 구현됨** — `com.bank.commonaccount.*` 패키지
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> 선행: `project_common_db_initiative` (common_db 신설 Phase 1 완료 — common_account + 시스템계좌 seed)
> 본 문서: common_db 에 **공통 부모 테이블**을 추가하고(Part 1), loan-service 를 거래원장까지 연결한다(Part 2, 깊이 D).
> 작성 기준: loan 브랜치. **deposit-service / payment-service 코드·스키마는 손대지 않는다** (deposit 는 Phase 2 에서 자기 테이블을 common_db 참조로 전환 — 핸드오프).

---

## 0. 현재 사실 / 제약

### common_db 현황 (`db/common-migration`)
- 전용 Flyway 이력 `common_flyway_schema_history`, `baselineOnMigrate=true`
- V1 `common_account`, V2 시스템계좌 seed(LOAN_COLLECTION / LOAN_DISBURSEMENT) — 이게 전부

### 배선 패턴 (`CommonDataSourceConfig`)
- common_db 엔티티 = `com.bank.commonaccount.domain`, 리포지토리 = `com.bank.commonaccount.repository`
- 전용 EMF `commonEntityManagerFactory` + tx매니저 `commonTransactionManager`
- common Flyway 는 Flyway 타입 빈으로 노출하지 않고 마커 빈에서 직접 `migrate()` (노출 시 loan_db Flyway 자동설정이 backoff 됨)
- ⚠️ **Primary EMF 가 `com.bank.loan` + `com.bank.common` 을 스캔** → common_db 엔티티는 절대 그 패키지에 두면 안 됨

### 이미 깔려 있는 브리지 컬럼 (전부 nullable, 현재 NULL)

| loan_db 테이블.컬럼 | → common_db 대상 | JPA 매핑 |
|---|---|---|
| `loan_product.product_id` | `common_product.product_id` | LoanProduct.productId ✓ |
| `loan_contract.contract_id` | `common_contract.contract_id` | LoanContract.contractId ✓ |
| `repayment_account.account_id` | `common_account.account_id` | 고객 상환계좌 미연결 |
| `loan_execution.transaction_id` | `common_transaction.transaction_id` | 플레이스홀더 |
| `repayment_transaction.transaction_id` | `common_transaction.transaction_id` | 플레이스홀더 |

### 출처 스키마
deposit `services/deposit-service/.../V5__full_erd_schema.sql` 에 공통 부모 테이블의 정식 정의가 있다(현재 deposit_db 에 존재). 이를 common_db 로 가져온다.

---

## Part 1 — common_db 공통 테이블 추가 (스키마)

deposit V5 의 **공통 부모 테이블만** 가져온다. 수신/여신 서브타입(`deposit_*`, `loan_*`)은 제외 — loan 은 이미 loan_db 에 자기 도메인 테이블을 갖고 브리지 컬럼으로 연결하므로 common_db 엔 부모만 있으면 된다.

### 정책
- **FK**: common_db 내부 FK 는 유지, 타 서비스 소유(`customer`/`party`) FK 는 제거하고 값 참조만 (common_account 선례)
- **타입 정규화** (AI_GUIDELINES): 금액 BIGINT / 금리 bps INT / 날짜 CHAR(8) / 시각 TIMESTAMPTZ(3). deposit V5 의 ERD 아티팩트 교정 — `common_contract.base_rate_bps VARCHAR(10)`→`INT`, 정체불명 `contract INT` 컬럼 드롭 등. deposit V5 와의 차이는 마이그레이션 주석에 명시
- **seed 없음**: 시스템계좌 외엔 운영/이관 데이터라 loan 이 임의 seed 하지 않음 (DDL only)

### 마이그레이션 순서 (내부 FK 의존성 = 커밋 단위)

| 단계 | 파일 | 테이블 | 내부 FK |
|---|---|---|---|
| 1 | `V3__common_product.sql` | `common_product` | 없음 |
| 2 | `V4__common_terms_template.sql` | `common_terms_template` | 없음 |
| 3 | `V5__common_contract.sql` | `common_contract` | → common_product |
| 4 | `V6__common_terms_consent.sql` | `common_terms_consent` | → common_terms_template |
| 5 | `V7__common_transaction.sql` | `common_transaction` | → common_account, common_contract |

각 단계 DDL only. 통합테스트는 공유 DB 라 테이블 추가만으로 부팅 검증 가능.

---

## Part 2 — loan-service ↔ 공통 계층 연결 (깊이 D: 거래원장까지)

### 핵심 아키텍처 주의 — 이중 datasource 쓰기
loan_db ↔ common_db 별도 datasource, **XA(분산 트랜잭션) 미구성**. 한 흐름에서 양쪽을 쓰면 정합성이 깨질 수 있음.
→ **loan_db outbox 경유 비동기 upsert** 로 해소. 기존 `notification_outbox` / `credit_info_report_outbox` 디스패치 패턴 재사용.
→ 브리지 컬럼은 common_db 생성 PK 가 필요하므로: 디스패처가 common 쓰기 → 생성 PK 를 loan 브리지 컬럼에 백필. 재시도 안전을 위해 common 측에 **출처 자연키**(loan cntr_no / exec idempotency_key) 로 멱등 dedupe.

### P2-A: 바인딩 + 엔티티

| # | 작업 | 커밋 |
|---|---|---|
| A-1 | 패키지 확장 `com.bank.commonaccount` → `com.bank.commondb.{account,product,contract,transaction,terms}`. common EMF·repo 스캔을 `com.bank.commondb` 로 확장 (Primary 스캔과 겹치지 않음 재확인) | refactor(loan) |
| A-2 | `CommonProduct`·`CommonContract`·`CommonTransaction` 엔티티 + 리포지토리. 각 테이블에 출처 자연키 컬럼(`source_ref`) | feat(loan) |

### P2-B: 상품·계약 write-through (브리지 `product_id`·`contract_id`)

| # | 작업 | 커밋 |
|---|---|---|
| B-1 | `common_sync_outbox` 마이그레이션 (loan_db, V32~) — target_type(PRODUCT/CONTRACT/TRANSACTION), source_id, status, 백오프 | feat(loan) |
| B-2 | `CommonSyncDispatchService` — outbox 픽업 → common_db upsert(자연키 멱등) → 생성 PK 를 loan 브리지 컬럼 백필. common 쓰기와 loan 백필 트랜잭션 분리, 재시도 안전 | feat(loan) |
| B-3 | 상품 등록 훅 + 기존 `loan_product` 백필 배치 → `common_product` 적재, `loan_product.product_id` 채움 | feat(loan) |
| B-4 | 계약 활성(`LoanContract` ACTIVE 전이) 시 `common_contract` 부모 생성 outbox 적재 → `loan_contract.contract_id` 백필 | feat(loan) |
| B-5 | 통합 테스트 (상품/계약 동기화·멱등·백필) | test(loan) |

### P2-C: 거래원장 분개 (브리지 `transaction_id`)

`loan_execution` / `repayment_transaction` 의 `transaction_id=null` 플레이스홀더 해소. payment-service 이체와 별개로, 은행 공통 원장(`common_transaction`)에 분개 row 적재.

> ⚠️ 분개 규칙(차변/대변 방향, 통화, 잔액 스냅샷)은 설계 합의 대상. `loan-payment-integration-spec` 에서 "회계 반대분개(common_transaction)는 본 단계 외" 로 미뤄둔 영역. B 완료 후 **C 진입 전 분개 명세 먼저 확정**.

| # | 작업 | 커밋 |
|---|---|---|
| C-1 | 대출실행 COMPLETED 시 `common_transaction` 분개 outbox 적재 → `loan_execution.transaction_id` 백필 | feat(loan) |
| C-2 | 상환거래 성공 시 동일 → `repayment_transaction.transaction_id` 백필 | feat(loan) |
| C-3 | 역분개(ReversalService) 반대분개 row | feat(loan) |
| C-4 | 통합 테스트 (분개·역분개·멱등) | test(loan) |

---

## 진행 순서

```
Part 1   V3 product → V4 terms_template → V5 contract → V6 terms_consent → V7 transaction
Part 2-A 패키지 확장 + 엔티티/리포지토리
Part 2-B outbox → 디스패처 → 상품 백필 → 계약 write-through → 테스트
Part 2-C 분개 명세 합의 → 실행/상환 분개 → 역분개 → 테스트
```

## 검증·워크플로 규칙 (00_overview 공통)
- 한 단계(커밋) 끝낼 때마다 보고 후 멈춤 — 자동 연속 진행 금지
- `feat` 와 `test` 분리 커밋
- 커밋 메시지 `<type>(<scope>): <한글 subject>` 한 줄, AI 흔적 금지
- 5파일 초과 변경 단계는 사전 승인
- 마지막 단계에서 `:services:loan-service:test` 풀런, 배치성 테스트는 연도 격리

## 비-목표
- deposit-service / payment-service 코드·스키마 변경 (Phase 2 핸드오프)
- common_db 운영/이관 데이터 seed (시스템계좌 외)
- `repayment_account.account_id` 고객 상환계좌 연결 (별도 검토)
