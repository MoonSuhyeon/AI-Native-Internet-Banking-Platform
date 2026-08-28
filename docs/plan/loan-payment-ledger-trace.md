# 여신 자금이동 경로 추적 — Loan → Payment → Deposit → Ledger

<!-- plan-status -->
> **상태: 🔨 진행 중** — 추적은 끝났다(2026-08-28). 발견된 차단 4겹은 아직 하나도 고치지 않았다.
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).

> 이 문서는 계획이 아니라 **결과 리포트**다. C1(Loan ↔ Ledger 회계 경계)에 착수하기 전
> "현재 경로가 실제로 도는가" 를 확인한 기록이고, C1 ②의 근거가 전부 여기에 있다.

---

## 요약

**현재 `Loan → Payment → Deposit → Ledger` 경로는 작동하지 않는다.** 한 군데가 아니라
네 겹으로 막혀 있고, 그중 둘은 설정 누락이 아니라 설계 충돌이다.

그리고 **여신이 결제를 태운 적이 한 번도 없다.** 코드는 있고 테스트도 초록이지만,
그 테스트는 전부 WireMock 스텁을 상대로 한 것이었다.

```sql
SELECT count(*) FROM payment.payment_instruction
 WHERE idempotency_key LIKE 'EXEC-%' OR LIKE 'AUTO-%'
    OR idempotency_key LIKE 'ONL-%'  OR LIKE 'REV-%';
-- 0

SELECT count(*) FROM payment.ledger WHERE (대출 관련);
-- 0   원장 28행은 전부 2026-08-09 이체 시연분이다
```

`loan_execution` 에 DONE 1건이 있지만 시드다(2025-03-10, `payment_instruction_id` NULL,
`journal_entry_no` NULL). 실제로 결제를 거친 흔적이 아니다.

---

## 왜 이 추적을 먼저 했나

C1 초안은 전제가 이랬다.

> `loan-service  core-banking / deposit 참조 0개 파일`
> `Feign·RestTemplate·WebClient 0건 → 동기 호출 자체가 불가`

**둘 다 사실이 아니었다.** `grep "core-banking"` 이 0건이었던 것은 설정 키 이름이
`PAYMENT_SERVICE_URL` 이기 때문이고, 호출은 `RestClient` 로 하고 있었다.

```java
// services/loan-service/src/main/java/com/bank/loan/payment/client/PaymentServiceClient.java
private static final String PAYMENTS_PATH = "/api/v1/payments";
private final RestClient restClient;
```

자금이동 네 종류가 전부 이 경로를 쓴다.

| 흐름 | 멱등키 접두 | 호출부 |
|---|---|---|
| 대출 실행 | `EXEC-` | `LoanExecutionService:148` |
| 자동이체 상환 | `AUTO-` | `AutoDebitBatchService` |
| 온라인 상환 | `ONL-` | `OnlineRepaymentService:40` |
| 역분개 환급 | `REV-` | `ReversalService:297` |

즉 C1 은 "새 원장 입구를 만드는" 작업이 아니라 **이미 있는 경로를 복구하고 회계적으로
완결시키는** 작업이다. 이 정정이 없었으면 이미 있는 것을 다시 만들 뻔했다.

---

## 추적 절차와 결과

최소 스택만 올렸다(`core-banking-a`, `loan-service`, `core-banking-db-a`, `loan-db`,
`common-db`, `redis`, `kafka`). 코드는 수정하지 않았다.

```
[1] POST /api/loan-contracts/9001/executions
    → 404 LOAN_080 "상환계좌를 찾을 수 없습니다"
    repayment_account 는 cntr_id=9201 하나뿐이고, 9201은 한도 전액 소진
    (12,000,000 / 12,000,000). 9001에 테스트 상환계좌 1행을 만들어 재시도.

[2] 재호출
    → HTTP 500
    ResourceAccessException: I/O error on POST
      "http://localhost:8080/api/v1/payments": Connection refused
    ★ 1차 차단
    loan_execution 은 롤백됐다. 고아 행은 남지 않았다.

[3] payment 를 직접 호출해 그 다음 홉을 확인 (loan 이 보내는 페이로드 그대로)

    A. X-Auth-Token-Id = SHA-256 파생값 (loan 방식)
       → 401 TRANSFER_APPROVAL_INVALID
       "이체 승인 검증 실패 fromAccountNo=0040000000002
        reason=feign.RetryableException: customer-service ...
        POST /api/internal/transaction-approvals/verify"
       ★ 2차 차단

    B. X-Auth-Token-Id 빈 값 · 1,000,000원
       → 401 TRANSFER_APPROVAL_REQUIRED
       100만원은 SMALL 구간(10만원 미만)이 아니라 토큰이 필수다.

    C. X-Auth-Token-Id 빈 값 · 50,000원 (SMALL — 게이트 통과)
       → 500 DuplicateKeyException "uq_payment_instruction_auth_token_id"
       빈 토큰은 DB에서 딱 한 번만 쓸 수 있다.
       이미 P-20260808-000001 이 점유하고 있다.
       ★ 3차 차단

[4] 계좌 확인
    deposit_accounts 37건 중 '0040000000002'(LOAN_DISBURSEMENT) → 0건
    ★ 4차 차단 — 게이트를 다 통과해도 송신계좌 조회에서 멈춘다
```

프로브가 남긴 부작용은 없다 — `payment_instruction` 0행, `ledger` 0행,
`loan_execution` 0행. 유일한 잔여물은 [1]에서 만든 테스트 상환계좌 1행이다.

---

## 차단 1 — 배선이 없다 (설정)

`PAYMENT_SERVICE_URL` 은 loan 의 `application.yml` 기본값 선언에만 존재하고,
`docker-compose.yml` 어디에도 주입되지 않는다.

```yaml
# services/loan-service/src/main/resources/application.yml:111
payment:
  url: ${PAYMENT_SERVICE_URL:http://localhost:8080}
```

컨테이너 안에서 `localhost:8080` 은 아무것도 아니다. **도커 스택에서 이 경로가 한 번도
돈 적 없는 직접적인 이유가 이것이다.**

같은 파일의 `KAFKA_BOOTSTRAP_SERVERS` 는 compose 에 주입돼 있는데 payment URL 만
빠졌다 — 누락이지 의도가 아니다.

---

## 차단 2 — `X-Auth-Token-Id` 가 두 역할을 겸한다 (설계 충돌)

| 보는 쪽 | 이 헤더를 무엇으로 읽는가 |
|---|---|
| `PaymentServiceClient` (loan) | `payment_instruction.auth_token_id` — VARCHAR(20) **UNIQUE 기술 식별자**. 호출마다 유일해야 해서 멱등키를 SHA-256 해시해 만든다 |
| `TransferApprovalGate` (core) | **고객 이체 승인 토큰**. 비어 있지 않으면 customer-service 로 검증하러 간다 |

loan 은 (1)을 만족시키려 값을 만들어내고, core 는 그 값을 (2)로 검증한다.
**customer-service 가 떠 있어도 이 값은 실제 승인 토큰이 아니므로 항상 거부된다.**

한쪽을 고쳐서 될 문제가 아니다. loan 이 토큰을 안 보내면 `auth_token_id` UNIQUE 에
걸리고(차단 3), 보내면 승인 검증에 걸린다. **두 관심사를 다른 필드로 분리해야 한다.**

---

## 차단 3 — 승인 게이트를 켤 때 여신 경로가 목록에서 빠졌다 (설계 충돌)

`services/core-banking/src/main/resources/application.yml` 의 주석이다.

> 켜기 전에 확인한 것: 이 게이트를 지나는 경로는 **셋이고 전부 토큰을 보낸다** —
> 웹 내부이체, 웹 타행이체(결제계), 챗봇 이체. **자동이체 스케줄러는 컨트롤러를 거치지
> 않아 해당 없고**, 예약이체는 등록 시점에 확인한다.

**"자동이체 스케줄러는 컨트롤러를 거치지 않는다" 가 사실이 아니다.**
`AutoDebitBatchService` 는 `PaymentServiceClient` 를 통해 `POST /api/v1/payments`
(= 컨트롤러)를 탄다. 대출 실행·온라인 상환·역분개도 같다.

경로는 셋이 아니라 일곱이었고 넷이 누락됐다. `TRANSFER_APPROVAL_REQUIRED` 기본값이
`true` 이므로, **이 게이트가 켜진 시점부터 여신 자금이동 전체가 401 로 막혀 있다.**

게이트 자체는 잘 만들어졌다 — 금액 구간별 강도, fail-closed, 초거액 비대면 차단까지
있다. 문제는 통제가 나쁜 게 아니라 **적용 대상 목록이 실제 호출부와 어긋난 것**이다.

> 별도 결함 티켓 감이다. C1 과 별개로, "게이트를 지나는 경로 목록" 을 코드에서
> 도출해 검증하는 테스트가 없으면 같은 종류의 누락이 또 난다.
>
> 게이트의 설계 의도는 [`transfer-step-up-auth.md`](transfer-step-up-auth.md) 에 있다.
> 그 문서를 고칠 때 경로 목록도 함께 고쳐야 한다 — 지금은 셋으로 적혀 있다.

---

## 차단 4 — 집행·수납 계좌가 deposit 에 없다 (데이터)

```sql
-- loan-service/src/main/resources/db/common-migration/V2__seed_system_accounts.sql
('0040000000001', ..., 'LOAN_COLLECTION',   ...),
('0040000000002', ..., 'LOAN_DISBURSEMENT', ...);
-- common_db 에만 있다

-- core_banking.deposit.deposit_accounts 37건 중
SELECT ... WHERE account_number IN ('0040000000001','0040000000002');  -- 0건
```

`PaymentOrchestratorImpl:728` 이 `depositAccountClient.getAccountByNo(senderAccountId)`
로 송신계좌를 조회하므로, 이 상태에서는 송신계좌 미존재로 실패한다.

**은행코드는 문제가 아니다.** deposit 데모 계좌의 `bank_code` 도 `004` 가 섞여 있어
loan 시스템계좌와 어긋나지 않는다. `BANK_CODE=A/B` 는 자행·타행 라우팅용 별개 코드다.

---

## 원장이 실제로 남기는 모양 — C1 ④의 설계 템플릿

이건 성공적으로 확보했다. 기존 타행이체 1건이 원장에 남긴 구조다.

```
journal_no          account_id        DR/CR   journal_type       amount
──────────────────────────────────────────────────────────────────────
JN-20260809-000001  001-2000-0000017  DEBIT   TRANSFER_OUT       10,000
JN-20260809-000001  KB-CLR-088        CREDIT  CLEARING_PENDING   10,000   균형
JN-20260809-000002  001-2000-0000017  DEBIT   FEE                   500
JN-20260809-000002  KB-FEE-001        CREDIT  FEE_INCOME            500   균형
```

여기서 확인된 것 셋.

1. **내부계정은 실계좌가 아니어도 된다.** `KB-CLR-088`, `KB-FEE-001` 이 `account_id`
   컬럼에 문자열로 그대로 들어간다. deposit 에 계좌를 만들 필요가 없다.
2. **한 결제가 여러 분개 묶음을 만든다.** 수수료가 별도 `journal_no` 로 분리돼 있다.
   상환의 원금·이자 분리도 같은 방식으로 표현할 수 있다.
3. **수익 계정 선례가 있다.** `KB-FEE-001`(수수료수익)이 있으므로 이자수익
   `KB-INT-INC` 도 같은 방식으로 만들면 된다.

**이것이 C1 ⓪의 답을 바꾼다.** 집행계좌를 deposit 실계좌로 만드는 것이 아니라
`KB-LOAN-CR`(대출채권) 같은 내부계정으로 설계하면, ⓪(계정 전제)과 ④(회계 완결)가
한 작업으로 합쳐진다. 실계좌로 때우면 ④에서 다시 뜯게 된다.

다만 **송신계좌 조회 경로가 내부계정을 받아들이는지는 아직 확인하지 않았다.**
현재 `getAccountByNo` 는 deposit 실계좌를 전제한다. 이 부분은 C1 ② 착수 시 확인한다.

---

## C1 에 미치는 영향

**②의 성격이 바뀐다.**

```
기존:  ② 기존 Payment 경로를 검증한다
수정:  ② 기존 Payment 경로를 복구한다     ← 검증할 대상이 아직 동작한 적 없다
```

그리고 ②가 ④보다 **먼저**여야 한다. 대출 분개를 설계해도 경로가 안 열리면 검증할
방법이 없다.

### ②의 실제 작업 항목

| # | 항목 | 크기 | 성격 |
|---|---|---|---|
| 1 | compose 에 `PAYMENT_SERVICE_URL` 주입 | 1줄 | 설정 |
| 2 | `X-Auth-Token-Id` 의 두 역할 분리 | 중 | 설계 |
| 3 | 시스템 개시 거래의 승인 정책 결정 | — | **정책 결정** |
| 4 | 집행·수납 계좌를 내부계정 방식으로 전환 | 중 | 설계 |

**3번이 나머지를 푸는 열쇠다.** 시스템이 개시하는 거래에 고객 승인 토큰을 요구하는 것은
의미가 없지만, 면제 경로를 뚫으면 게이트에 구멍이 생긴다. 은행 표준으로는 **채널 구분**
(`channel=SYSTEM`)으로 다른 통제를 적용하는 쪽이다 — 승인 대신 배치 승인·이중 확인·
사후 대사.

---

## 재현 방법

```bash
docker compose up -d core-banking-a loan-service

# 계좌 확인
docker exec ib-core-banking-db-a psql -U core -d core_banking \
  -c "SELECT account_number FROM deposit.deposit_accounts WHERE account_number LIKE '004%';"

# 여신이 태운 결제 확인 (0이면 이 문서의 전제가 유효하다)
docker exec ib-core-banking-db-a psql -U core -d core_banking \
  -c "SELECT count(*) FROM payment.payment_instruction WHERE idempotency_key LIKE 'EXEC-%';"

# 실행 호출
curl -X POST http://localhost:8083/api/loan-contracts/9001/executions \
  -H 'Content-Type: application/json' -H 'X-User-Id: 9001' -H 'X-User-Role: OPS' \
  -H 'Idempotency-Key: PROBE-001' \
  -d '{"executedAmount":1000000,"currencyCd":"KRW","disbursementBankCd":"001",
       "disbursementAccountNo":"001-2000-0000012","valueDate":"20260828","feeAmount":0}'
```

추적에 쓴 테스트 데이터(되돌리려면):

```sql
DELETE FROM repayment_account WHERE racct_id = 1 AND cntr_id = 9001;
```

②를 복구한 뒤 같은 프로브를 다시 돌릴 것이라면 남겨 두는 편이 편하다.

---

## 관련 문서

- `docs/OPEN_ITEMS.md` — 정본. 이 문서와 어긋나면 정본을 고친다
- `docs/plan/consultation-db-direct-access-removal.md` — A2. 같은 종류의
  "경계가 코드와 어긋난" 사례
