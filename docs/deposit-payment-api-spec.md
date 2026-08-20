# core-banking 내부 v1 API 명세 (이체계 ↔ 수신계)

> **이 명세는 살아 있다. 다만 두 서비스 사이가 아니다.**
>
> 원래 `payment-service`(이체계)가 `deposit-service`(수신계)를 HTTP 로 부르기 위한
> 계약이었고, 문서가 "**deposit-service 담당자가 이 명세를 기준으로 구현한다**" 로
> 시작했다 — 경계가 트랜잭션 요구가 아니라 **담당자 분담**을 따라 그어졌다는 흔적이다
> ([결정 문서](decisions/core-banking-merge.md)가 이 문장을 근거로 인용한다).
>
> 둘은 `core-banking` 으로 합쳐졌다. 여기 적힌 `/api/v1/**` 엔드포인트는 그대로
> 존재하며(`AccountV1Controller`·`BalanceV1Controller`), 이제 **같은 프로세스 안의
> 내부 API** 다. 자행이체 잔액 반영은 이 HTTP 경로가 아니라 `LocalLedgerAdapter` 를
> 탄다 — 그래서 출금과 입금이 한 트랜잭션에서 끝난다.
>
> `X-Caller-Service: payment-service` 헤더 요구와 A-1·B-3 같은 협상 번호는 그 시절의
> 흔적이다. 코드가 아직 그 헤더를 요구하므로 명세에서 지우지 않는다.

payment 도메인이 deposit 도메인에 요구하는 API 전체 목록.

---

## 공통 사항

### 요청 헤더 (모든 엔드포인트)

| 헤더 | 값 | 필수 |
|---|---|---|
| `X-Caller-Service` | `payment-service` | O |
| `X-Bank-Code` | 은행코드 (예: `004`) | O |
| `X-Request-Id` | UUID (매 호출마다 신규 생성) | O |
| `X-Idempotency-Key` | UUID (B-3/B-4/B-5만) | 조건부 |

### 응답 래퍼

모든 응답은 아래 구조로 감싼다.

```json
{
  "code": "DEP-0000",
  "message": "success",
  "timestamp": "2026-05-28T12:00:00Z",
  "data": { ... }
}
```

- 성공 코드: `DEP-0000`
- 실패 시 `data`는 `null`, `code`에 오류 코드

---

## 엔드포인트 목록

### A-1. 계좌 조회

```
GET /api/v1/accounts/{accountNo}
```

**용도**: 이체 전 계좌 상태·사기 플래그 확인 (송신/수신 양쪽)

**응답 `data` 필드**

| 필드 | 타입 | 설명 |
|---|---|---|
| `accountNo` | String | 계좌번호 |
| `accountType` | String | `SAVINGS` / `DEMAND` / `TIME` / `SUBSCRIPTION` |
| `accountStatus` | String | `ACTIVE` / `FROZEN` / `CLOSED` / `DORMANT` |
| `productCode` | String | 상품코드 |
| `openedAt` | String | 개설일 |
| `closedAt` | String | 해지일 (nullable) |
| `branchCode` | String | 지점코드 |
| `fraudFlag` | Boolean | 사기 계좌 여부 |
| `version` | Integer | 낙관적 잠금용 버전 |

**payment가 실제 사용하는 필드**: `accountStatus`, `fraudFlag`

**검증 로직 (payment 내부)**
- `accountStatus != ACTIVE` → `ACCOUNT_CLOSED` / `ACCOUNT_FROZEN` 오류
- `fraudFlag == true` → `ACCOUNT_RESTRICTED` 오류

---

### A-2. 예금주 조회

```
GET /api/v1/accounts/{accountNo}/holder
```

**용도**: 예금주명 일치 확인, 수신자 사망 여부 확인

**응답 `data` 필드**

| 필드 | 타입 | 설명 |
|---|---|---|
| `accountNo` | String | 계좌번호 |
| `holderName` | String | 예금주명 |
| `holderType` | String | `INDIVIDUAL` / `CORPORATE` / `JOINT` |
| `customerId` | String | 고객 ID |
| `deceasedFlag` | Boolean | 사망 여부 |
| `version` | Integer | 낙관적 잠금용 버전 |

**payment가 실제 사용하는 필드**: `holderName`, `deceasedFlag`

**검증 로직 (payment 내부)**
- 수신자 `deceasedFlag == true` → `OWNER_INQUIRY_FAILED` 오류
- 수신자 `holderName` ≠ 요청의 `receiverHolderName` → `OWNER_INQUIRY_FAILED` 오류

---

### B-1. 잔액 조회

```
GET /api/v1/balances/{accountNo}
```

**용도**: 이체 가능 잔액 확인

**응답 `data` 필드**

| 필드 | 타입 | 설명 |
|---|---|---|
| `accountNo` | String | 계좌번호 |
| `balance` | Long | 장부 잔액 (원) |
| `availableBalance` | Long | 출금 가능 잔액 (원, hold 차감 후) |
| `holdAmount` | Long | 지급 보류 금액 |
| `currency` | String | 통화 (`KRW`) |
| `lastTxAt` | String | 최종 거래일시 |
| `version` | Integer | 낙관적 잠금용 버전 |

**payment가 실제 사용하는 필드**: `availableBalance`

**검증 로직 (payment 내부)**
- `availableBalance < 이체금액` → `INSUFFICIENT_BALANCE` 오류

---

### B-2. 한도 조회

```
GET /api/v1/limits/{accountNo}?date={yyyyMMdd}
```

**파라미터**

| 파라미터 | 위치 | 필수 | 설명 |
|---|---|---|---|
| `accountNo` | Path | O | 계좌번호 |
| `date` | Query | X | 조회 기준일 (생략 시 오늘) |

**용도**: 1회·일·월 이체 한도 확인

**응답 `data` 필드**

| 필드 | 타입 | 설명 |
|---|---|---|
| `accountNo` | String | 계좌번호 |
| `date` | String | 기준일 |
| `dailyLimit` | Long | 일 한도 (원) |
| `dailyUsed` | Long | 당일 사용액 |
| `dailyRemaining` | Long | 당일 잔여 한도 |
| `monthlyLimit` | Long | 월 한도 (원) |
| `monthlyUsed` | Long | 당월 사용액 |
| `monthlyRemaining` | Long | 당월 잔여 한도 |
| `perTxLimit` | Long | 건당 한도 (원) |
| `limitTier` | String | 한도 등급 |

**payment가 실제 사용하는 필드**: `perTxLimit`, `dailyRemaining`, `monthlyRemaining`

**검증 로직 (payment 내부)**
- `이체금액 > perTxLimit` → `LIMIT_EXCEEDED`
- `이체금액 > dailyRemaining` → `LIMIT_EXCEEDED`
- `이체금액 > monthlyRemaining` → `LIMIT_EXCEEDED`

---

### B-3. 출금

```
POST /api/v1/balances/withdraw
X-Idempotency-Key: {UUID}
```

**용도**: 송신 계좌 출금 (트랜잭션 외부 호출)

**요청 Body**

```json
{
  "accountNo": "12345678901234",
  "amount": 100000,
  "currency": "KRW",
  "transactionType": "TRANSFER_OUT",
  "referenceNo": "PI-20260528-001",
  "counterparty": {
    "bankCode": "088",
    "accountNo": "98765432109876",
    "holderName": "성춘향"
  },
  "memo": "용돈"
}
```

**응답 `data` 필드**

| 필드 | 타입 | 설명 |
|---|---|---|
| `depositTransactionNo` | String | **원거래 식별자 (B-5 취소 시 참조 필수)** |
| `accountNo` | String | 계좌번호 |
| `amount` | Long | 출금액 |
| `balanceBefore` | Long | 출금 전 잔액 |
| `balanceAfter` | Long | 출금 후 잔액 |
| `transactionAt` | String | 거래일시 |
| `transactionType` | String | `TRANSFER_OUT` |

---

### B-4. 입금

```
POST /api/v1/balances/deposit
X-Idempotency-Key: {UUID}
```

**용도**: 수신 계좌 입금 (자행 이체 시만 호출, 트랜잭션 외부)

**요청 Body**

```json
{
  "accountNo": "98765432109876",
  "amount": 100000,
  "currency": "KRW",
  "transactionType": "TRANSFER_IN",
  "referenceNo": "PI-20260528-001",
  "counterparty": {
    "bankCode": "004",
    "accountNo": "12345678901234",
    "holderName": "이몽룡",
    "passbookDisplay": "이몽룡"
  },
  "memo": "용돈"
}
```

**응답 `data` 필드**: B-3과 동일 (`transactionType`: `TRANSFER_IN`)

---

### B-5. 출금 취소 (Saga 보상)

```
POST /api/v1/balances/withdraw/cancel
X-Idempotency-Key: {UUID}
```

**용도**: B-4 입금 실패 또는 이후 단계 실패 시 B-3 출금 보상

**요청 Body**

```json
{
  "originalDepositTransactionNo": "DEP-TX-001",
  "accountNo": "12345678901234",
  "amount": 100000,
  "reason": "PAYMENT_FAILED",
  "referenceNo": "PI-20260528-001"
}
```

- `originalDepositTransactionNo`: B-3 응답의 `depositTransactionNo`
- `reason`: `PAYMENT_FAILED` / `OPERATOR_CANCEL` / `FRAUD_REPORT`

**응답 `data` 필드**

| 필드 | 타입 | 설명 |
|---|---|---|
| `cancelTransactionNo` | String | 취소 거래 식별자 |
| `originalDepositTransactionNo` | String | 원거래 식별자 |
| `accountNo` | String | 계좌번호 |
| `amount` | Long | 취소액 |
| `balanceBefore` | Long | 취소 전 잔액 |
| `balanceAfter` | Long | 취소 후 잔액 |
| `canceledAt` | String | 취소일시 |

---

## 오류 코드 (payment가 처리하는 케이스)

| 상황 | payment 오류코드 | deposit 응답 예시 |
|---|---|---|
| 계좌 해지 | `ACCOUNT_CLOSED` | `accountStatus: CLOSED` |
| 계좌 동결 | `ACCOUNT_FROZEN` | `accountStatus: FROZEN` |
| 사기 계좌 | `ACCOUNT_RESTRICTED` | `fraudFlag: true` |
| 잔액 부족 | `INSUFFICIENT_BALANCE` | `availableBalance < amount` |
| 한도 초과 | `LIMIT_EXCEEDED` | `perTxLimit` / `dailyRemaining` / `monthlyRemaining` 부족 |
| 예금주 불일치 | `OWNER_INQUIRY_FAILED` | `holderName` 불일치 또는 `deceasedFlag: true` |
| 입금 실패 | Saga 보상 트리거 | deposit `code != DEP-0000` (B-4) |

---

## Feign 클라이언트 설정 (payment-service 기준)

```yaml
# payment-service application.yml
deposit:
  account:
    url: ${DEPOSIT_ACCOUNT_URL:http://localhost:8082}
  balance:
    url: ${DEPOSIT_BALANCE_URL:http://localhost:8082}
```
