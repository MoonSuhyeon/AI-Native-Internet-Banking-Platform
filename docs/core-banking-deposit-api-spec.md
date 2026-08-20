# core-banking — 수신 도메인 API 명세서

> **`core-banking` 의 수신 도메인**(`com.bank.deposit` 패키지) API 명세다.
>
> 예전에는 `deposit-service` 라는 별도 서비스였다. `payment-service` 와 함께
> `core-banking` 으로 합쳐졌다([결정 문서](decisions/core-banking-merge.md)) —
> 자행이체를 로컬 트랜잭션으로 끝내기 위한 선택이고, 타행이체 Saga 는 그대로 남아 있다.
>
> | | 예전 | 지금 |
> |---|---|---|
> | 서비스 | `deposit-service` | `core-banking` |
> | 포트 | 8082 | 8082 (그대로) |
> | 모듈 | `services/deposit-service` | `services/core-banking` |
> | 이체계 호출 | HTTP | 같은 프로세스 (`LocalLedgerAdapter`) |
>
> **엔드포인트 경로와 요청·응답 규약은 바뀌지 않았다.** 달라진 것은 어디에 붙느냐다.
> 경로 전체 목록은 [api-spec.md](api-spec.md) 가 소스에서 뽑아 유지한다.



수신(예금·적금·계좌) 서비스의 전체 REST 엔드포인트 상세 명세. 컨트롤러·DTO·에러코드 소스에서 추출해 정리한다.

> payment-service ↔ deposit-service 내부 연동 규약은 별도 문서 [deposit-payment-api-spec.md](deposit-payment-api-spec.md) 참조.
> 엔드포인트 전체 목록은 [api-spec.md](api-spec.md) 참조.

> ⚠️ **주의**: deposit-service 는 다른 서비스와 응답 규약이 다르다.
> - 공통 `ApiResponse` envelope 를 **쓰지 않고** 도메인 객체/DTO 를 직접 반환한다.
> - 에러는 자체 `ErrorResponse` 포맷(아래)을 쓴다.

---

## 공통 사항

### 인증·인가

deposit-service 는 **Spring Security 를 두지 않는다**. API Gateway 1차 검증 + 내부망 신뢰를 전제로 하고,
고객 본인 데이터 접근만 `X-Customer-Id` 헤더로 검증한다(`AuthenticatedCustomerValidator`).

| 헤더 | 설명 | 필수 |
|---|---|---|
| `X-Customer-Id` | 게이트웨이가 주입한 인증 고객 ID. 요청 `customerId` 와 불일치 시 `FORBIDDEN` | 조건부 |

- 요청에 대상 `customerId` 가 있고 `X-Customer-Id` 가 없으면 `403 FORBIDDEN`("인증된 고객 ID가 필요합니다.")
- `X-Customer-Id` ≠ 요청 `customerId` 이면 `403 FORBIDDEN`("다른 고객의 데이터에는 접근할 수 없습니다.")

### 성공 응답

엔드포인트별 도메인 객체 또는 응답 DTO 를 **그대로** 반환한다(envelope 없음). 생성 계열은 `201 Created`.

### 에러 응답 (ErrorResponse)

```json
{
  "code": "ACCOUNT_NOT_FOUND",
  "message": "계좌를 찾을 수 없습니다.",
  "errors": ["필드 검증 메시지", "..."],
  "timestamp": "2026-06-11T12:00:00+09:00"
}
```

- `code`: `ErrorCode` enum 이름(아래 표) / `errors`: 검증 실패 시 메시지 목록(없으면 생략)

### 에러코드 (ErrorCode)

| 코드 | HTTP | 설명 |
|---|---|---|
| `NOT_FOUND` | 404 | 리소스를 찾을 수 없습니다. |
| `INVALID_STATUS` | 400 | 유효하지 않은 상태입니다. |
| `INSUFFICIENT_BALANCE` | 400 | 잔액이 부족합니다. |
| `ACCOUNT_NOT_ACTIVE` | 400 | 계좌가 활성 상태가 아닙니다. |
| `ACCOUNT_NOT_FOUND` | 404 | 계좌를 찾을 수 없습니다. |
| `CONTRACT_NOT_FOUND` | 404 | 계약을 찾을 수 없습니다. |
| `PRODUCT_NOT_FOUND` | 404 | 상품을 찾을 수 없습니다. |
| `PRODUCT_NOT_SELLING` | 400 | 판매 중인 상품이 아닙니다. |
| `TRANSACTION_NOT_FOUND` | 404 | 거래를 찾을 수 없습니다. |
| `ALREADY_CANCELED` | 400 | 이미 취소된 거래입니다. |
| `DAILY_TRANSFER_AMOUNT_EXCEEDED` | 400 | 하루 이체 금액 한도를 초과했습니다. |
| `DAILY_TRANSFER_COUNT_EXCEEDED` | 400 | 하루 이체 횟수 한도를 초과했습니다. |
| `DUPLICATE` | 409 | 이미 존재하는 데이터입니다. |
| `FORBIDDEN` | 403 | 접근 권한이 없습니다. |
| `INTERNAL_SERVER_ERROR` | 500 | 서버 오류가 발생했습니다. |

---

## 엔드포인트

### 계좌

#### AccountController

##### `GET` `/accounts`

**헤더**

| 이름 | 필수 |
|---|---|
| `X-Customer-Id` | - |

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `customerId` | `String` | O |

**응답**: `List<Account>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` |  |
| `version` | `Long` |  |
| `accountNumber` | `String` |  |
| `customerId` | `String` |  |
| `contractId` | `Long` |  |
| `accountType` | `ProductType` |  |
| `savingType` | `SavingType` |  |
| `bankCode` | `String` |  |
| `accountAlias` | `String` |  |
| `balance` | `BigDecimal` |  |
| `totalPaidAmount` | `BigDecimal` |  |
| `totalInterestAmount` | `BigDecimal` |  |
| `lastTransactionAt` | `OffsetDateTime` |  |
| `lastInterestPaidAt` | `OffsetDateTime` |  |
| `currency` | `String` |  |
| `accountPassword` | `String` |  |
| `dailyWithdrawLimit` | `BigDecimal` |  |
| `dailyWithdrawCountLimit` | `Integer` |  |
| `atmWithdrawLimit` | `BigDecimal` |  |
| `isWithdrawable` | `Boolean` |  |
| `isOnlineBankingEnabled` | `Boolean` |  |
| `isMobileBankingEnabled` | `Boolean` |  |
| `isPhoneBankingEnabled` | `Boolean` |  |
| `accountStatus` | `AccountStatus` |  |
| `openedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `dormantAt` | `LocalDate` |  |
| `dormantReleasedAt` | `LocalDate` |  |
| `closedAt` | `LocalDate` |  |
| `statusChangedAt` | `LocalDate` |  |

##### `POST` `/accounts`

**헤더**

| 이름 | 필수 |
|---|---|
| `X-Customer-Id` | - |

**요청 본문**: `AccountCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `customerId` | `String` | 필수(공백불가) |
| `contractId` | `Long` | 필수 |
| `accountType` | `ProductType` | 필수 |
| `savingType` | `SavingType` |  |
| `accountAlias` | `String` |  |
| `accountPassword` | `String` | 필수(공백불가) |

**응답**: `Account`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` |  |
| `version` | `Long` |  |
| `accountNumber` | `String` |  |
| `customerId` | `String` |  |
| `contractId` | `Long` |  |
| `accountType` | `ProductType` |  |
| `savingType` | `SavingType` |  |
| `bankCode` | `String` |  |
| `accountAlias` | `String` |  |
| `balance` | `BigDecimal` |  |
| `totalPaidAmount` | `BigDecimal` |  |
| `totalInterestAmount` | `BigDecimal` |  |
| `lastTransactionAt` | `OffsetDateTime` |  |
| `lastInterestPaidAt` | `OffsetDateTime` |  |
| `currency` | `String` |  |
| `accountPassword` | `String` |  |
| `dailyWithdrawLimit` | `BigDecimal` |  |
| `dailyWithdrawCountLimit` | `Integer` |  |
| `atmWithdrawLimit` | `BigDecimal` |  |
| `isWithdrawable` | `Boolean` |  |
| `isOnlineBankingEnabled` | `Boolean` |  |
| `isMobileBankingEnabled` | `Boolean` |  |
| `isPhoneBankingEnabled` | `Boolean` |  |
| `accountStatus` | `AccountStatus` |  |
| `openedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `dormantAt` | `LocalDate` |  |
| `dormantReleasedAt` | `LocalDate` |  |
| `closedAt` | `LocalDate` |  |
| `statusChangedAt` | `LocalDate` |  |

##### `GET` `/accounts/by-number/{accountNo}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `accountNo` | `String` |

**응답**: `Account`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` |  |
| `version` | `Long` |  |
| `accountNumber` | `String` |  |
| `customerId` | `String` |  |
| `contractId` | `Long` |  |
| `accountType` | `ProductType` |  |
| `savingType` | `SavingType` |  |
| `bankCode` | `String` |  |
| `accountAlias` | `String` |  |
| `balance` | `BigDecimal` |  |
| `totalPaidAmount` | `BigDecimal` |  |
| `totalInterestAmount` | `BigDecimal` |  |
| `lastTransactionAt` | `OffsetDateTime` |  |
| `lastInterestPaidAt` | `OffsetDateTime` |  |
| `currency` | `String` |  |
| `accountPassword` | `String` |  |
| `dailyWithdrawLimit` | `BigDecimal` |  |
| `dailyWithdrawCountLimit` | `Integer` |  |
| `atmWithdrawLimit` | `BigDecimal` |  |
| `isWithdrawable` | `Boolean` |  |
| `isOnlineBankingEnabled` | `Boolean` |  |
| `isMobileBankingEnabled` | `Boolean` |  |
| `isPhoneBankingEnabled` | `Boolean` |  |
| `accountStatus` | `AccountStatus` |  |
| `openedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `dormantAt` | `LocalDate` |  |
| `dormantReleasedAt` | `LocalDate` |  |
| `closedAt` | `LocalDate` |  |
| `statusChangedAt` | `LocalDate` |  |

##### `GET` `/accounts/{accountId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `accountId` | `Long` |

**응답**: `Account`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` |  |
| `version` | `Long` |  |
| `accountNumber` | `String` |  |
| `customerId` | `String` |  |
| `contractId` | `Long` |  |
| `accountType` | `ProductType` |  |
| `savingType` | `SavingType` |  |
| `bankCode` | `String` |  |
| `accountAlias` | `String` |  |
| `balance` | `BigDecimal` |  |
| `totalPaidAmount` | `BigDecimal` |  |
| `totalInterestAmount` | `BigDecimal` |  |
| `lastTransactionAt` | `OffsetDateTime` |  |
| `lastInterestPaidAt` | `OffsetDateTime` |  |
| `currency` | `String` |  |
| `accountPassword` | `String` |  |
| `dailyWithdrawLimit` | `BigDecimal` |  |
| `dailyWithdrawCountLimit` | `Integer` |  |
| `atmWithdrawLimit` | `BigDecimal` |  |
| `isWithdrawable` | `Boolean` |  |
| `isOnlineBankingEnabled` | `Boolean` |  |
| `isMobileBankingEnabled` | `Boolean` |  |
| `isPhoneBankingEnabled` | `Boolean` |  |
| `accountStatus` | `AccountStatus` |  |
| `openedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `dormantAt` | `LocalDate` |  |
| `dormantReleasedAt` | `LocalDate` |  |
| `closedAt` | `LocalDate` |  |
| `statusChangedAt` | `LocalDate` |  |

##### `PATCH` `/accounts/{accountId}/alias`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `accountId` | `Long` |

**요청 본문**: `AccountAliasUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountAlias` | `String` | 필수(공백불가), 길이제한 |

**응답**: `Account`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` |  |
| `version` | `Long` |  |
| `accountNumber` | `String` |  |
| `customerId` | `String` |  |
| `contractId` | `Long` |  |
| `accountType` | `ProductType` |  |
| `savingType` | `SavingType` |  |
| `bankCode` | `String` |  |
| `accountAlias` | `String` |  |
| `balance` | `BigDecimal` |  |
| `totalPaidAmount` | `BigDecimal` |  |
| `totalInterestAmount` | `BigDecimal` |  |
| `lastTransactionAt` | `OffsetDateTime` |  |
| `lastInterestPaidAt` | `OffsetDateTime` |  |
| `currency` | `String` |  |
| `accountPassword` | `String` |  |
| `dailyWithdrawLimit` | `BigDecimal` |  |
| `dailyWithdrawCountLimit` | `Integer` |  |
| `atmWithdrawLimit` | `BigDecimal` |  |
| `isWithdrawable` | `Boolean` |  |
| `isOnlineBankingEnabled` | `Boolean` |  |
| `isMobileBankingEnabled` | `Boolean` |  |
| `isPhoneBankingEnabled` | `Boolean` |  |
| `accountStatus` | `AccountStatus` |  |
| `openedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `dormantAt` | `LocalDate` |  |
| `dormantReleasedAt` | `LocalDate` |  |
| `closedAt` | `LocalDate` |  |
| `statusChangedAt` | `LocalDate` |  |

##### `PATCH` `/accounts/{accountId}/limits`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `accountId` | `Long` |

**요청 본문**: `AccountLimitUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `dailyWithdrawLimit` | `BigDecimal` |  |
| `dailyWithdrawCountLimit` | `Integer` |  |
| `atmWithdrawLimit` | `BigDecimal` |  |

**응답**: `Account`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` |  |
| `version` | `Long` |  |
| `accountNumber` | `String` |  |
| `customerId` | `String` |  |
| `contractId` | `Long` |  |
| `accountType` | `ProductType` |  |
| `savingType` | `SavingType` |  |
| `bankCode` | `String` |  |
| `accountAlias` | `String` |  |
| `balance` | `BigDecimal` |  |
| `totalPaidAmount` | `BigDecimal` |  |
| `totalInterestAmount` | `BigDecimal` |  |
| `lastTransactionAt` | `OffsetDateTime` |  |
| `lastInterestPaidAt` | `OffsetDateTime` |  |
| `currency` | `String` |  |
| `accountPassword` | `String` |  |
| `dailyWithdrawLimit` | `BigDecimal` |  |
| `dailyWithdrawCountLimit` | `Integer` |  |
| `atmWithdrawLimit` | `BigDecimal` |  |
| `isWithdrawable` | `Boolean` |  |
| `isOnlineBankingEnabled` | `Boolean` |  |
| `isMobileBankingEnabled` | `Boolean` |  |
| `isPhoneBankingEnabled` | `Boolean` |  |
| `accountStatus` | `AccountStatus` |  |
| `openedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `dormantAt` | `LocalDate` |  |
| `dormantReleasedAt` | `LocalDate` |  |
| `closedAt` | `LocalDate` |  |
| `statusChangedAt` | `LocalDate` |  |

##### `PATCH` `/accounts/{accountId}/status`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `accountId` | `Long` |

**요청 본문**: `AccountStatusUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountStatus` | `AccountStatus` | 필수 |

**응답**: `Account`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` |  |
| `version` | `Long` |  |
| `accountNumber` | `String` |  |
| `customerId` | `String` |  |
| `contractId` | `Long` |  |
| `accountType` | `ProductType` |  |
| `savingType` | `SavingType` |  |
| `bankCode` | `String` |  |
| `accountAlias` | `String` |  |
| `balance` | `BigDecimal` |  |
| `totalPaidAmount` | `BigDecimal` |  |
| `totalInterestAmount` | `BigDecimal` |  |
| `lastTransactionAt` | `OffsetDateTime` |  |
| `lastInterestPaidAt` | `OffsetDateTime` |  |
| `currency` | `String` |  |
| `accountPassword` | `String` |  |
| `dailyWithdrawLimit` | `BigDecimal` |  |
| `dailyWithdrawCountLimit` | `Integer` |  |
| `atmWithdrawLimit` | `BigDecimal` |  |
| `isWithdrawable` | `Boolean` |  |
| `isOnlineBankingEnabled` | `Boolean` |  |
| `isMobileBankingEnabled` | `Boolean` |  |
| `isPhoneBankingEnabled` | `Boolean` |  |
| `accountStatus` | `AccountStatus` |  |
| `openedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `dormantAt` | `LocalDate` |  |
| `dormantReleasedAt` | `LocalDate` |  |
| `closedAt` | `LocalDate` |  |
| `statusChangedAt` | `LocalDate` |  |

### 상품·금리

#### InterestController

##### `GET` `/contracts/{contractId}/interests`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**응답**: `List<InterestHistory>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `interestId` | `Long` |  |
| `contractId` | `Long` |  |
| `accountId` | `Long` |  |
| `appliedInterestRate` | `BigDecimal` |  |
| `interestCalculationStartDate` | `String` |  |
| `interestCalculationEndDate` | `String` |  |
| `interestOccurredAt` | `OffsetDateTime` |  |
| `interestAmount` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `interestBeforeTax` | `BigDecimal` |  |
| `interestTaxAmount` | `BigDecimal` |  |
| `localIncomeTaxAmount` | `BigDecimal` |  |
| `interestAfterTax` | `BigDecimal` |  |
| `interestReason` | `InterestReason` |  |
| `interestPaidAt` | `OffsetDateTime` |  |

##### `GET` `/interests`

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `contractId` | `Long` | O |

**응답**: `List<InterestHistory>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `interestId` | `Long` |  |
| `contractId` | `Long` |  |
| `accountId` | `Long` |  |
| `appliedInterestRate` | `BigDecimal` |  |
| `interestCalculationStartDate` | `String` |  |
| `interestCalculationEndDate` | `String` |  |
| `interestOccurredAt` | `OffsetDateTime` |  |
| `interestAmount` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `interestBeforeTax` | `BigDecimal` |  |
| `interestTaxAmount` | `BigDecimal` |  |
| `localIncomeTaxAmount` | `BigDecimal` |  |
| `interestAfterTax` | `BigDecimal` |  |
| `interestReason` | `InterestReason` |  |
| `interestPaidAt` | `OffsetDateTime` |  |

##### `POST` `/interests/calculate`

**요청 본문**: `InterestPayRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` | 필수 |
| `accountId` | `Long` | 필수 |
| `interestBeforeTax` | `BigDecimal` | 필수, 양수 |
| `interestTaxAmount` | `BigDecimal` |  |
| `localIncomeTaxAmount` | `BigDecimal` |  |
| `appliedInterestRate` | `BigDecimal` | 필수 |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `interestReason` | `InterestReason` |  |
| `interestCalculationStartDate` | `String` |  |
| `interestCalculationEndDate` | `String` |  |

**응답**: `InterestHistory`

| 필드 | 타입 | 제약 |
|---|---|---|
| `interestId` | `Long` |  |
| `contractId` | `Long` |  |
| `accountId` | `Long` |  |
| `appliedInterestRate` | `BigDecimal` |  |
| `interestCalculationStartDate` | `String` |  |
| `interestCalculationEndDate` | `String` |  |
| `interestOccurredAt` | `OffsetDateTime` |  |
| `interestAmount` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `interestBeforeTax` | `BigDecimal` |  |
| `interestTaxAmount` | `BigDecimal` |  |
| `localIncomeTaxAmount` | `BigDecimal` |  |
| `interestAfterTax` | `BigDecimal` |  |
| `interestReason` | `InterestReason` |  |
| `interestPaidAt` | `OffsetDateTime` |  |

##### `GET` `/interests/{interestId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `interestId` | `Long` |

**응답**: `InterestHistory`

| 필드 | 타입 | 제약 |
|---|---|---|
| `interestId` | `Long` |  |
| `contractId` | `Long` |  |
| `accountId` | `Long` |  |
| `appliedInterestRate` | `BigDecimal` |  |
| `interestCalculationStartDate` | `String` |  |
| `interestCalculationEndDate` | `String` |  |
| `interestOccurredAt` | `OffsetDateTime` |  |
| `interestAmount` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `interestBeforeTax` | `BigDecimal` |  |
| `interestTaxAmount` | `BigDecimal` |  |
| `localIncomeTaxAmount` | `BigDecimal` |  |
| `interestAfterTax` | `BigDecimal` |  |
| `interestReason` | `InterestReason` |  |
| `interestPaidAt` | `OffsetDateTime` |  |

#### ProductController

##### `GET` `/products`

── 공통 상품 ──────────────────────────────────────────────────────────────

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `productType` | `ProductType` | - |
| `productStatus` | `ProductStatus` | - |

**응답**: `List<ProductResponse>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productId` | `Long` |  |
| `productType` | `ProductType` |  |
| `productName` | `String` |  |
| `description` | `String` |  |
| `departmentId` | `Long` |  |
| `baseInterestRate` | `BigDecimal` |  |
| `bestRate` | `BigDecimal` |  |
| `minJoinAmount` | `BigDecimal` |  |
| `maxJoinAmount` | `BigDecimal` |  |
| `minPeriodMonth` | `Integer` |  |
| `maxPeriodMonth` | `Integer` |  |
| `isEarlyTerminationAllowed` | `Boolean` |  |
| `isTaxBenefitAvailable` | `Boolean` |  |
| `isAutoRenewalAvailable` | `Boolean` |  |
| `isPassbookIssued` | `Boolean` |  |
| `releasedAt` | `String` |  |
| `endedAt` | `String` |  |
| `productStatus` | `ProductStatus` |  |
| `targetGroups` | `List<TargetGroupInfo>` |  |

##### `POST` `/products`

── 공통 상품 ──────────────────────────────────────────────────────────────

**요청 본문**: `ProductCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productType` | `ProductType` | 필수 |
| `productName` | `String` | 필수(공백불가) |
| `description` | `String` |  |
| `departmentId` | `Long` |  |
| `baseInterestRate` | `BigDecimal` | 0이상 |
| `minJoinAmount` | `BigDecimal` |  |
| `maxJoinAmount` | `BigDecimal` |  |
| `minPeriodMonth` | `Integer` |  |
| `maxPeriodMonth` | `Integer` |  |
| `isEarlyTerminationAllowed` | `Boolean` |  |
| `isTaxBenefitAvailable` | `Boolean` |  |
| `isAutoRenewalAvailable` | `Boolean` |  |
| `releasedAt` | `String` |  |

**응답**: `ProductResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productId` | `Long` |  |
| `productType` | `ProductType` |  |
| `productName` | `String` |  |
| `description` | `String` |  |
| `departmentId` | `Long` |  |
| `baseInterestRate` | `BigDecimal` |  |
| `bestRate` | `BigDecimal` |  |
| `minJoinAmount` | `BigDecimal` |  |
| `maxJoinAmount` | `BigDecimal` |  |
| `minPeriodMonth` | `Integer` |  |
| `maxPeriodMonth` | `Integer` |  |
| `isEarlyTerminationAllowed` | `Boolean` |  |
| `isTaxBenefitAvailable` | `Boolean` |  |
| `isAutoRenewalAvailable` | `Boolean` |  |
| `isPassbookIssued` | `Boolean` |  |
| `releasedAt` | `String` |  |
| `endedAt` | `String` |  |
| `productStatus` | `ProductStatus` |  |
| `targetGroups` | `List<TargetGroupInfo>` |  |

##### `GET` `/products/{productId:\\d+}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**응답**: `ProductResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productId` | `Long` |  |
| `productType` | `ProductType` |  |
| `productName` | `String` |  |
| `description` | `String` |  |
| `departmentId` | `Long` |  |
| `baseInterestRate` | `BigDecimal` |  |
| `bestRate` | `BigDecimal` |  |
| `minJoinAmount` | `BigDecimal` |  |
| `maxJoinAmount` | `BigDecimal` |  |
| `minPeriodMonth` | `Integer` |  |
| `maxPeriodMonth` | `Integer` |  |
| `isEarlyTerminationAllowed` | `Boolean` |  |
| `isTaxBenefitAvailable` | `Boolean` |  |
| `isAutoRenewalAvailable` | `Boolean` |  |
| `isPassbookIssued` | `Boolean` |  |
| `releasedAt` | `String` |  |
| `endedAt` | `String` |  |
| `productStatus` | `ProductStatus` |  |
| `targetGroups` | `List<TargetGroupInfo>` |  |

##### `PATCH` `/products/{productId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `ProductStatusUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productStatus` | `ProductStatus` | 필수 |

**응답**: `ProductResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productId` | `Long` |  |
| `productType` | `ProductType` |  |
| `productName` | `String` |  |
| `description` | `String` |  |
| `departmentId` | `Long` |  |
| `baseInterestRate` | `BigDecimal` |  |
| `bestRate` | `BigDecimal` |  |
| `minJoinAmount` | `BigDecimal` |  |
| `maxJoinAmount` | `BigDecimal` |  |
| `minPeriodMonth` | `Integer` |  |
| `maxPeriodMonth` | `Integer` |  |
| `isEarlyTerminationAllowed` | `Boolean` |  |
| `isTaxBenefitAvailable` | `Boolean` |  |
| `isAutoRenewalAvailable` | `Boolean` |  |
| `isPassbookIssued` | `Boolean` |  |
| `releasedAt` | `String` |  |
| `endedAt` | `String` |  |
| `productStatus` | `ProductStatus` |  |
| `targetGroups` | `List<TargetGroupInfo>` |  |

##### `PUT` `/products/{productId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `ProductUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productName` | `String` | 필수(공백불가) |
| `description` | `String` |  |
| `baseInterestRate` | `BigDecimal` |  |

**응답**: `ProductResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productId` | `Long` |  |
| `productType` | `ProductType` |  |
| `productName` | `String` |  |
| `description` | `String` |  |
| `departmentId` | `Long` |  |
| `baseInterestRate` | `BigDecimal` |  |
| `bestRate` | `BigDecimal` |  |
| `minJoinAmount` | `BigDecimal` |  |
| `maxJoinAmount` | `BigDecimal` |  |
| `minPeriodMonth` | `Integer` |  |
| `maxPeriodMonth` | `Integer` |  |
| `isEarlyTerminationAllowed` | `Boolean` |  |
| `isTaxBenefitAvailable` | `Boolean` |  |
| `isAutoRenewalAvailable` | `Boolean` |  |
| `isPassbookIssued` | `Boolean` |  |
| `releasedAt` | `String` |  |
| `endedAt` | `String` |  |
| `productStatus` | `ProductStatus` |  |
| `targetGroups` | `List<TargetGroupInfo>` |  |

##### `DELETE` `/products/{productId}/deposit`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

##### `GET` `/products/{productId}/deposit`

── 수신 상품 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**응답**: `DepositProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `depositProductId` | `Long` |  |
| `productId` | `Long` |  |
| `depositType` | `DepositType` |  |
| `isCompoundInterest` | `Boolean` |  |

##### `POST` `/products/{productId}/deposit`

── 수신 상품 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `DepositProductRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `depositType` | `DepositType` | 필수 |
| `isCompoundInterest` | `Boolean` |  |

**응답**: `DepositProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `depositProductId` | `Long` |  |
| `productId` | `Long` |  |
| `depositType` | `DepositType` |  |
| `isCompoundInterest` | `Boolean` |  |

##### `PUT` `/products/{productId}/deposit`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `DepositProductRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `depositType` | `DepositType` | 필수 |
| `isCompoundInterest` | `Boolean` |  |

**응답**: `DepositProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `depositProductId` | `Long` |  |
| `productId` | `Long` |  |
| `depositType` | `DepositType` |  |
| `isCompoundInterest` | `Boolean` |  |

##### `GET` `/products/{productId}/interest-rates`

── 금리 관리 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**응답**: `List<ProductInterestRate>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `rateId` | `Long` |  |
| `productId` | `Long` |  |
| `rateType` | `RateType` |  |
| `minimumContractPeriod` | `Integer` |  |
| `maximumContractPeriod` | `Integer` |  |
| `minimumJoinAmount` | `BigDecimal` |  |
| `maximumJoinAmount` | `BigDecimal` |  |
| `rate` | `BigDecimal` |  |
| `conditionDescription` | `String` |  |
| `effectiveStartDate` | `String` |  |
| `effectiveEndDate` | `String` |  |
| `isActive` | `Boolean` |  |

##### `POST` `/products/{productId}/interest-rates`

── 금리 관리 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `InterestRateCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `rateType` | `RateType` | 필수 |
| `rate` | `BigDecimal` | 필수, 양수 |
| `effectiveStartDate` | `String` | 필수(공백불가) |
| `minimumContractPeriod` | `Integer` |  |
| `maximumContractPeriod` | `Integer` |  |
| `minimumJoinAmount` | `BigDecimal` |  |
| `maximumJoinAmount` | `BigDecimal` |  |
| `conditionDescription` | `String` |  |

**응답**: `ProductInterestRate`

| 필드 | 타입 | 제약 |
|---|---|---|
| `rateId` | `Long` |  |
| `productId` | `Long` |  |
| `rateType` | `RateType` |  |
| `minimumContractPeriod` | `Integer` |  |
| `maximumContractPeriod` | `Integer` |  |
| `minimumJoinAmount` | `BigDecimal` |  |
| `maximumJoinAmount` | `BigDecimal` |  |
| `rate` | `BigDecimal` |  |
| `conditionDescription` | `String` |  |
| `effectiveStartDate` | `String` |  |
| `effectiveEndDate` | `String` |  |
| `isActive` | `Boolean` |  |

##### `GET` `/products/{productId}/interest-rates/{rateId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |
| `rateId` | `Long` |

**응답**: `ProductInterestRate`

| 필드 | 타입 | 제약 |
|---|---|---|
| `rateId` | `Long` |  |
| `productId` | `Long` |  |
| `rateType` | `RateType` |  |
| `minimumContractPeriod` | `Integer` |  |
| `maximumContractPeriod` | `Integer` |  |
| `minimumJoinAmount` | `BigDecimal` |  |
| `maximumJoinAmount` | `BigDecimal` |  |
| `rate` | `BigDecimal` |  |
| `conditionDescription` | `String` |  |
| `effectiveStartDate` | `String` |  |
| `effectiveEndDate` | `String` |  |
| `isActive` | `Boolean` |  |

##### `PUT` `/products/{productId}/interest-rates/{rateId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |
| `rateId` | `Long` |

**요청 본문**: `InterestRateUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `rate` | `BigDecimal` | 필수, 양수 |
| `effectiveEndDate` | `String` |  |

**응답**: `ProductInterestRate`

| 필드 | 타입 | 제약 |
|---|---|---|
| `rateId` | `Long` |  |
| `productId` | `Long` |  |
| `rateType` | `RateType` |  |
| `minimumContractPeriod` | `Integer` |  |
| `maximumContractPeriod` | `Integer` |  |
| `minimumJoinAmount` | `BigDecimal` |  |
| `maximumJoinAmount` | `BigDecimal` |  |
| `rate` | `BigDecimal` |  |
| `conditionDescription` | `String` |  |
| `effectiveStartDate` | `String` |  |
| `effectiveEndDate` | `String` |  |
| `isActive` | `Boolean` |  |

##### `PATCH` `/products/{productId}/interest-rates/{rateId}/expire`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |
| `rateId` | `Long` |

**응답**: `ProductInterestRate`

| 필드 | 타입 | 제약 |
|---|---|---|
| `rateId` | `Long` |  |
| `productId` | `Long` |  |
| `rateType` | `RateType` |  |
| `minimumContractPeriod` | `Integer` |  |
| `maximumContractPeriod` | `Integer` |  |
| `minimumJoinAmount` | `BigDecimal` |  |
| `maximumJoinAmount` | `BigDecimal` |  |
| `rate` | `BigDecimal` |  |
| `conditionDescription` | `String` |  |
| `effectiveStartDate` | `String` |  |
| `effectiveEndDate` | `String` |  |
| `isActive` | `Boolean` |  |

##### `GET` `/products/{productId}/join-channels`

── 가입 방식 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**응답**: `List<ProductJoinChannel>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productJoinChannelId` | `Long` |  |
| `productId` | `Long` |  |
| `joinChannelCode` | `JoinChannel` |  |
| `createdAt` | `OffsetDateTime` |  |

##### `POST` `/products/{productId}/join-channels`

── 가입 방식 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `JoinChannelRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `joinChannelCode` | `JoinChannel` | 필수 |

**응답**: `ProductJoinChannel`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productJoinChannelId` | `Long` |  |
| `productId` | `Long` |  |
| `joinChannelCode` | `JoinChannel` |  |
| `createdAt` | `OffsetDateTime` |  |

##### `DELETE` `/products/{productId}/join-channels/{channelId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |
| `channelId` | `Long` |

##### `GET` `/products/{productId}/savings`

── 적금 상품 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**응답**: `SavingsProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `savingsProductId` | `Long` |  |
| `productId` | `Long` |  |
| `savingType` | `SavingType` |  |
| `monthlyPaymentMinAmount` | `BigDecimal` |  |
| `monthlyPaymentMaxAmount` | `BigDecimal` |  |

##### `POST` `/products/{productId}/savings`

── 적금 상품 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `SavingsProductRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `savingType` | `SavingType` | 필수 |
| `monthlyPaymentMinAmount` | `BigDecimal` |  |
| `monthlyPaymentMaxAmount` | `BigDecimal` |  |

**응답**: `SavingsProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `savingsProductId` | `Long` |  |
| `productId` | `Long` |  |
| `savingType` | `SavingType` |  |
| `monthlyPaymentMinAmount` | `BigDecimal` |  |
| `monthlyPaymentMaxAmount` | `BigDecimal` |  |

##### `PUT` `/products/{productId}/savings`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `SavingsProductRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `savingType` | `SavingType` | 필수 |
| `monthlyPaymentMinAmount` | `BigDecimal` |  |
| `monthlyPaymentMaxAmount` | `BigDecimal` |  |

**응답**: `SavingsProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `savingsProductId` | `Long` |  |
| `productId` | `Long` |  |
| `savingType` | `SavingType` |  |
| `monthlyPaymentMinAmount` | `BigDecimal` |  |
| `monthlyPaymentMaxAmount` | `BigDecimal` |  |

##### `GET` `/products/{productId}/special-terms`

── 특약 연결 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**응답**: `List<ProductSpecialTerm>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productSpecialTermId` | `Long` |  |
| `productId` | `Long` |  |
| `specialTermId` | `Long` |  |
| `isRequired` | `Boolean` |  |

##### `POST` `/products/{productId}/special-terms`

── 특약 연결 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `ProductSpecialTermRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermId` | `Long` | 필수 |
| `isRequired` | `Boolean` |  |

**응답**: `ProductSpecialTerm`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productSpecialTermId` | `Long` |  |
| `productId` | `Long` |  |
| `specialTermId` | `Long` |  |
| `isRequired` | `Boolean` |  |

##### `DELETE` `/products/{productId}/special-terms/{specialTermId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |
| `specialTermId` | `Long` |

##### `GET` `/products/{productId}/subscription`

── 청약 상품 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**응답**: `SubscriptionProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productId` | `Long` |  |
| `monthlyPaymentAmount` | `BigDecimal` |  |
| `minMonthlyPayment` | `BigDecimal` |  |
| `maxMonthlyPayment` | `BigDecimal` |  |
| `maxRecognizedPaymentAmount` | `BigDecimal` |  |

##### `POST` `/products/{productId}/subscription`

── 청약 상품 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `SubscriptionProductRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `monthlyPaymentAmount` | `BigDecimal` | 필수, 양수 |
| `minMonthlyPayment` | `BigDecimal` |  |
| `maxMonthlyPayment` | `BigDecimal` |  |
| `maxRecognizedPaymentAmount` | `BigDecimal` |  |

**응답**: `SubscriptionProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productId` | `Long` |  |
| `monthlyPaymentAmount` | `BigDecimal` |  |
| `minMonthlyPayment` | `BigDecimal` |  |
| `maxMonthlyPayment` | `BigDecimal` |  |
| `maxRecognizedPaymentAmount` | `BigDecimal` |  |

##### `PUT` `/products/{productId}/subscription`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `SubscriptionProductRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `monthlyPaymentAmount` | `BigDecimal` | 필수, 양수 |
| `minMonthlyPayment` | `BigDecimal` |  |
| `maxMonthlyPayment` | `BigDecimal` |  |
| `maxRecognizedPaymentAmount` | `BigDecimal` |  |

**응답**: `SubscriptionProduct`

| 필드 | 타입 | 제약 |
|---|---|---|
| `productId` | `Long` |  |
| `monthlyPaymentAmount` | `BigDecimal` |  |
| `minMonthlyPayment` | `BigDecimal` |  |
| `maxMonthlyPayment` | `BigDecimal` |  |
| `maxRecognizedPaymentAmount` | `BigDecimal` |  |

##### `GET` `/products/{productId}/target-groups`

── 가입 대상 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**응답**: `List<ProductTargetGroup>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `id` | `ProductTargetGroupId` |  |
| `createdAt` | `OffsetDateTime` |  |

##### `POST` `/products/{productId}/target-groups`

── 가입 대상 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |

**요청 본문**: `ProductTargetGroupRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupId` | `Long` | 필수 |

**응답**: `ProductTargetGroup`

| 필드 | 타입 | 제약 |
|---|---|---|
| `id` | `ProductTargetGroupId` |  |
| `createdAt` | `OffsetDateTime` |  |

##### `DELETE` `/products/{productId}/target-groups/{targetGroupId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `productId` | `Long` |
| `targetGroupId` | `Long` |

#### SpecialTermController

##### `GET` `/special-terms`

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `isActive` | `Boolean` | - |

**응답**: `List<SpecialTerm>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermId` | `Long` |  |
| `specialTermName` | `String` |  |
| `specialTermContent` | `String` |  |
| `specialTermSummary` | `String` |  |
| `isRequired` | `Boolean` |  |
| `isElectronicAgreementAllowed` | `Boolean` |  |
| `specialTermVersion` | `String` |  |
| `startedAt` | `String` |  |
| `endedAt` | `String` |  |
| `status` | `SpecialTermStatus` |  |
| `statusChangedAt` | `String` |  |

##### `POST` `/special-terms`

**요청 본문**: `SpecialTermCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermName` | `String` | 필수(공백불가) |
| `specialTermContent` | `String` | 필수(공백불가) |
| `specialTermSummary` | `String` |  |
| `isRequired` | `Boolean` |  |
| `specialTermVersion` | `String` | 필수(공백불가) |
| `startedAt` | `String` |  |
| `endedAt` | `String` |  |

**응답**: `SpecialTerm`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermId` | `Long` |  |
| `specialTermName` | `String` |  |
| `specialTermContent` | `String` |  |
| `specialTermSummary` | `String` |  |
| `isRequired` | `Boolean` |  |
| `isElectronicAgreementAllowed` | `Boolean` |  |
| `specialTermVersion` | `String` |  |
| `startedAt` | `String` |  |
| `endedAt` | `String` |  |
| `status` | `SpecialTermStatus` |  |
| `statusChangedAt` | `String` |  |

##### `GET` `/special-terms/{specialTermId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `specialTermId` | `Long` |

**응답**: `SpecialTerm`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermId` | `Long` |  |
| `specialTermName` | `String` |  |
| `specialTermContent` | `String` |  |
| `specialTermSummary` | `String` |  |
| `isRequired` | `Boolean` |  |
| `isElectronicAgreementAllowed` | `Boolean` |  |
| `specialTermVersion` | `String` |  |
| `startedAt` | `String` |  |
| `endedAt` | `String` |  |
| `status` | `SpecialTermStatus` |  |
| `statusChangedAt` | `String` |  |

##### `PUT` `/special-terms/{specialTermId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `specialTermId` | `Long` |

**요청 본문**: `SpecialTermUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermName` | `String` | 필수(공백불가) |
| `specialTermContent` | `String` | 필수(공백불가) |
| `specialTermVersion` | `String` | 필수(공백불가) |
| `changeReason` | `String` |  |

**응답**: `SpecialTerm`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermId` | `Long` |  |
| `specialTermName` | `String` |  |
| `specialTermContent` | `String` |  |
| `specialTermSummary` | `String` |  |
| `isRequired` | `Boolean` |  |
| `isElectronicAgreementAllowed` | `Boolean` |  |
| `specialTermVersion` | `String` |  |
| `startedAt` | `String` |  |
| `endedAt` | `String` |  |
| `status` | `SpecialTermStatus` |  |
| `statusChangedAt` | `String` |  |

##### `PATCH` `/special-terms/{specialTermId}/status`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `specialTermId` | `Long` |

**요청 본문**: `SpecialTermStatusUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `status` | `SpecialTermStatus` | 필수 |

**응답**: `SpecialTerm`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermId` | `Long` |  |
| `specialTermName` | `String` |  |
| `specialTermContent` | `String` |  |
| `specialTermSummary` | `String` |  |
| `isRequired` | `Boolean` |  |
| `isElectronicAgreementAllowed` | `Boolean` |  |
| `specialTermVersion` | `String` |  |
| `startedAt` | `String` |  |
| `endedAt` | `String` |  |
| `status` | `SpecialTermStatus` |  |
| `statusChangedAt` | `String` |  |

### 계약·가입

#### ContractController

##### `GET` `/contracts`

**헤더**

| 이름 | 필수 |
|---|---|
| `X-Customer-Id` | - |

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `customerId` | `String` | - |
| `contractStatus` | `ContractStatus` | - |

**응답**: `List<Contract>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

##### `POST` `/contracts`

── 계약 ───────────────────────────────────────────────────────────────────

**헤더**

| 이름 | 필수 |
|---|---|
| `X-Customer-Id` | - |

**요청 본문**: `ContractCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `customerId` | `String` | 필수(공백불가) |
| `productId` | `Long` | 필수 |
| `joinAmount` | `BigDecimal` | 필수, 양수 |
| `contractPeriodMonth` | `Integer` | 필수, 양수 |
| `joinChannel` | `JoinChannel` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `branchId` | `Long` |  |
| `managerId` | `Long` |  |
| `savingType` | `SavingType` |  |
| `accountPassword` | `String` | 필수(공백불가) |

**응답**: `Contract`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

##### `GET` `/contracts/{contractId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**응답**: `Contract`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

##### `GET` `/contracts/{contractId}/applied-rates`

── 적용 금리 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**응답**: `List<ContractAppliedRate>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `appliedRateId` | `Long` |  |
| `contractId` | `Long` |  |
| `rateId` | `Long` |  |
| `appliedRate` | `BigDecimal` |  |
| `conditionVerifiedYn` | `Boolean` |  |

##### `POST` `/contracts/{contractId}/applied-rates`

── 적용 금리 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**요청 본문**: `AppliedRateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `rateId` | `Long` | 필수 |
| `appliedRate` | `BigDecimal` | 필수 |
| `conditionVerifiedYn` | `Boolean` |  |

**응답**: `ContractAppliedRate`

| 필드 | 타입 | 제약 |
|---|---|---|
| `appliedRateId` | `Long` |  |
| `contractId` | `Long` |  |
| `rateId` | `Long` |  |
| `appliedRate` | `BigDecimal` |  |
| `conditionVerifiedYn` | `Boolean` |  |

##### `PATCH` `/contracts/{contractId}/auto-transfer-day`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**요청 본문**: `AutoTransferDayUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `autoTransferDay` | `Integer` | 필수, 최소값, 최대값 |

##### `GET` `/contracts/{contractId}/deposit`

── 수신 계약 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**응답**: `Contract`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

##### `POST` `/contracts/{contractId}/deposit`

── 수신 계약 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**요청 본문**: `DepositContractRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |

**응답**: `Contract`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

##### `PUT` `/contracts/{contractId}/deposit`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**요청 본문**: `DepositContractRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |

**응답**: `Contract`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

##### `PATCH` `/contracts/{contractId}/maturity`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**응답**: `Contract`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

##### `GET` `/contracts/{contractId}/preferential-rates`

── 우대금리 적용 ──────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**응답**: `List<ContractAppliedRate>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `appliedRateId` | `Long` |  |
| `contractId` | `Long` |  |
| `rateId` | `Long` |  |
| `appliedRate` | `BigDecimal` |  |
| `conditionVerifiedYn` | `Boolean` |  |

##### `POST` `/contracts/{contractId}/preferential-rates`

── 우대금리 적용 ──────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**요청 본문**: `PreferentialRateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `conditionName` | `String` |  |
| `appliedRate` | `BigDecimal` |  |
| `appliedYn` | `Boolean` |  |

**응답**: `ContractAppliedRate`

| 필드 | 타입 | 제약 |
|---|---|---|
| `appliedRateId` | `Long` |  |
| `contractId` | `Long` |  |
| `rateId` | `Long` |  |
| `appliedRate` | `BigDecimal` |  |
| `conditionVerifiedYn` | `Boolean` |  |

##### `DELETE` `/contracts/{contractId}/preferential-rates/{preferentialRateId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |
| `preferentialRateId` | `Long` |

##### `GET` `/contracts/{contractId}/special-terms`

── 특약 동의 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**응답**: `List<ContractSpecialTermAgreement>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialAgreementId` | `Long` |  |
| `contractId` | `Long` |  |
| `specialTermId` | `Long` |  |
| `isAgreed` | `Boolean` |  |
| `agreedAt` | `String` |  |
| `agreementIpAddress` | `String` |  |
| `agreementDeviceInfo` | `String` |  |
| `isElectronicSigned` | `Boolean` |  |
| `isAgreementWithdrawn` | `Boolean` |  |
| `agreementWithdrawnAt` | `String` |  |

##### `POST` `/contracts/{contractId}/special-terms`

── 특약 동의 ──────────────────────────────────────────────────────────────

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**요청 본문**: `SpecialTermAgreementRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialTermId` | `Long` | 필수 |
| `isAgreed` | `Boolean` | 필수 |
| `agreedAt` | `String` |  |
| `agreementIpAddress` | `String` |  |
| `agreementDeviceInfo` | `String` |  |
| `isElectronicSigned` | `Boolean` |  |

**응답**: `ContractSpecialTermAgreement`

| 필드 | 타입 | 제약 |
|---|---|---|
| `specialAgreementId` | `Long` |  |
| `contractId` | `Long` |  |
| `specialTermId` | `Long` |  |
| `isAgreed` | `Boolean` |  |
| `agreedAt` | `String` |  |
| `agreementIpAddress` | `String` |  |
| `agreementDeviceInfo` | `String` |  |
| `isElectronicSigned` | `Boolean` |  |
| `isAgreementWithdrawn` | `Boolean` |  |
| `agreementWithdrawnAt` | `String` |  |

##### `PATCH` `/contracts/{contractId}/status`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**요청 본문**: `ContractStatusUpdateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractStatus` | `ContractStatus` | 필수 |

**응답**: `Contract`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

##### `PATCH` `/contracts/{contractId}/terminate`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**요청 본문**: `ContractTerminateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `terminationReason` | `String` |  |
| `targetAccountId` | `Long` |  |

**응답**: `Contract`

| 필드 | 타입 | 제약 |
|---|---|---|
| `contractId` | `Long` |  |
| `contractNumber` | `String` |  |
| `customerId` | `String` |  |
| `productId` | `Long` |  |
| `isMonthlyPayment` | `Boolean` |  |
| `paymentCountTotal` | `Integer` |  |
| `monthlyPaymentDay` | `String` |  |
| `joinAmount` | `BigDecimal` |  |
| `contractInterestRate` | `BigDecimal` |  |
| `totalPreferentialRate` | `BigDecimal` |  |
| `finalInterestRate` | `BigDecimal` |  |
| `taxBenefitType` | `TaxBenefitType` |  |
| `appliedTaxRate` | `BigDecimal` |  |
| `expectedInterestAmount` | `BigDecimal` |  |
| `contractPeriodMonth` | `Integer` |  |
| `startedAt` | `LocalDate` |  |
| `maturityAt` | `LocalDate` |  |
| `terminatedAt` | `LocalDate` |  |
| `terminationReason` | `String` |  |
| `isAutoRenewal` | `Boolean` |  |
| `autoTransferEnabled` | `Boolean` |  |
| `autoTransferDay` | `Integer` |  |
| `sourceAccountId` | `Long` |  |
| `consecutiveMissCount` | `Integer` |  |
| `contractStatus` | `ContractStatus` |  |
| `statusChangedAt` | `LocalDate` |  |
| `joinChannel` | `JoinChannel` |  |
| `branchId` | `Long` |  |
| `branchCode` | `String` |  |
| `branchName` | `String` |  |
| `managerId` | `Long` |  |
| `managerName` | `String` |  |
| `isProxyJoined` | `Boolean` |  |
| `isPowerOfAttorneyVerified` | `Boolean` |  |
| `powerOfAttorneyFileUrl` | `String` |  |
| `termsFileUrl` | `String` |  |
| `contractFileUrl` | `String` |  |

#### JoinTargetController

##### `GET` `/join-targets`

**응답**: `List<TargetGroup>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupId` | `Long` |  |
| `targetGroupName` | `String` |  |
| `description` | `String` |  |
| `minAge` | `Integer` |  |
| `maxAge` | `Integer` |  |
| `isActive` | `Boolean` |  |

##### `POST` `/join-targets`

**요청 본문**: `TargetGroupCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupName` | `String` | 필수(공백불가) |
| `description` | `String` |  |

**응답**: `TargetGroup`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupId` | `Long` |  |
| `targetGroupName` | `String` |  |
| `description` | `String` |  |
| `minAge` | `Integer` |  |
| `maxAge` | `Integer` |  |
| `isActive` | `Boolean` |  |

#### TargetGroupController

##### `GET` `/target-groups`

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `isActive` | `Boolean` | - |

**응답**: `List<TargetGroup>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupId` | `Long` |  |
| `targetGroupName` | `String` |  |
| `description` | `String` |  |
| `minAge` | `Integer` |  |
| `maxAge` | `Integer` |  |
| `isActive` | `Boolean` |  |

##### `POST` `/target-groups`

**요청 본문**: `TargetGroupCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupName` | `String` | 필수(공백불가) |
| `description` | `String` |  |

**응답**: `TargetGroup`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupId` | `Long` |  |
| `targetGroupName` | `String` |  |
| `description` | `String` |  |
| `minAge` | `Integer` |  |
| `maxAge` | `Integer` |  |
| `isActive` | `Boolean` |  |

##### `PUT` `/target-groups/{id}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `id` | `Long` |

**요청 본문**: `TargetGroupCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupName` | `String` | 필수(공백불가) |
| `description` | `String` |  |

**응답**: `TargetGroup`

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetGroupId` | `Long` |  |
| `targetGroupName` | `String` |  |
| `description` | `String` |  |
| `minAge` | `Integer` |  |
| `maxAge` | `Integer` |  |
| `isActive` | `Boolean` |  |

#### TermApplicationManagementController

##### `GET` `/term-applications`

약관 적용 목록 조회.

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `businessTypeCode` | `String` | - |
| `commonTermId` | `Long` | - |
| `isRequired` | `String` | - |

**응답**: `List<TermApplicationManagement>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `termApplicationId` | `Long` |  |
| `commonTermId` | `Long` |  |
| `termTargetId` | `Long` |  |
| `businessTypeCode` | `String` |  |
| `isRequired` | `String` |  |
| `registeredAt` | `String` |  |
| `modifiedAt` | `String` |  |

##### `POST` `/term-applications`

약관 적용 등록.

**요청 본문**: `TermApplicationManagementRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `commonTermId` | `Long` |  |
| `termTargetId` | `Long` |  |
| `businessTypeCode` | `String` |  |
| `isRequired` | `String` |  |
| `registeredAt` | `String` |  |
| `modifiedAt` | `String` |  |

**응답**: `TermApplicationManagement`

| 필드 | 타입 | 제약 |
|---|---|---|
| `termApplicationId` | `Long` |  |
| `commonTermId` | `Long` |  |
| `termTargetId` | `Long` |  |
| `businessTypeCode` | `String` |  |
| `isRequired` | `String` |  |
| `registeredAt` | `String` |  |
| `modifiedAt` | `String` |  |

##### `DELETE` `/term-applications/{id}`

약관 적용 삭제.

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `id` | `Long` |

##### `GET` `/term-applications/{id}`

단건 조회.

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `id` | `Long` |

**응답**: `TermApplicationManagement`

| 필드 | 타입 | 제약 |
|---|---|---|
| `termApplicationId` | `Long` |  |
| `commonTermId` | `Long` |  |
| `termTargetId` | `Long` |  |
| `businessTypeCode` | `String` |  |
| `isRequired` | `String` |  |
| `registeredAt` | `String` |  |
| `modifiedAt` | `String` |  |

### 거래·납입

#### PaymentScheduleController

##### `GET` `/payment-schedules/contracts/{contractId}`

계약별 납입 스케줄 전체 조회

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**응답**: `List<PaymentSchedule>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `scheduleId` | `Long` |  |
| `contractId` | `Long` |  |
| `accountId` | `Long` |  |
| `paymentRound` | `Integer` |  |
| `scheduledDate` | `LocalDate` |  |
| `scheduledAmount` | `BigDecimal` |  |
| `isAutoTransfer` | `Boolean` |  |
| `sourceAccountId` | `Long` |  |
| `status` | `PaymentStatus` |  |
| `paidAt` | `OffsetDateTime` |  |
| `actualAmount` | `BigDecimal` |  |
| `transactionId` | `Long` |  |
| `failureReasonCode` | `FailureReasonCode` |  |

##### `POST` `/payment-schedules/contracts/{contractId}/generate`

정기적금 납입 스케줄 생성.

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `accountId` | `Long` | O |
| `contractPeriodMonth` | `Integer` | O |
| `monthlyAmount` | `BigDecimal` | O |
| `isAutoTransfer` | `boolean` | - |
| `sourceAccountId` | `Long` | - |
| `autoTransferDay` | `Integer` | - |
| `startedAt` | `String` | O |

**응답**: `List<PaymentSchedule>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `scheduleId` | `Long` |  |
| `contractId` | `Long` |  |
| `accountId` | `Long` |  |
| `paymentRound` | `Integer` |  |
| `scheduledDate` | `LocalDate` |  |
| `scheduledAmount` | `BigDecimal` |  |
| `isAutoTransfer` | `Boolean` |  |
| `sourceAccountId` | `Long` |  |
| `status` | `PaymentStatus` |  |
| `paidAt` | `OffsetDateTime` |  |
| `actualAmount` | `BigDecimal` |  |
| `transactionId` | `Long` |  |
| `failureReasonCode` | `FailureReasonCode` |  |

##### `GET` `/payment-schedules/contracts/{contractId}/status/{status}`

계약별 납입 스케줄 상태별 조회

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `contractId` | `Long` |
| `status` | `PaymentStatus` |

**응답**: `List<PaymentSchedule>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `scheduleId` | `Long` |  |
| `contractId` | `Long` |  |
| `accountId` | `Long` |  |
| `paymentRound` | `Integer` |  |
| `scheduledDate` | `LocalDate` |  |
| `scheduledAmount` | `BigDecimal` |  |
| `isAutoTransfer` | `Boolean` |  |
| `sourceAccountId` | `Long` |  |
| `status` | `PaymentStatus` |  |
| `paidAt` | `OffsetDateTime` |  |
| `actualAmount` | `BigDecimal` |  |
| `transactionId` | `Long` |  |
| `failureReasonCode` | `FailureReasonCode` |  |

##### `POST` `/payment-schedules/{scheduleId}/pay`

수동 납입 처리.

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `scheduleId` | `Long` |

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `sourceAccountId` | `Long` | O |

**응답**: `PaymentSchedule`

| 필드 | 타입 | 제약 |
|---|---|---|
| `scheduleId` | `Long` |  |
| `contractId` | `Long` |  |
| `accountId` | `Long` |  |
| `paymentRound` | `Integer` |  |
| `scheduledDate` | `LocalDate` |  |
| `scheduledAmount` | `BigDecimal` |  |
| `isAutoTransfer` | `Boolean` |  |
| `sourceAccountId` | `Long` |  |
| `status` | `PaymentStatus` |  |
| `paidAt` | `OffsetDateTime` |  |
| `actualAmount` | `BigDecimal` |  |
| `transactionId` | `Long` |  |
| `failureReasonCode` | `FailureReasonCode` |  |

#### SubscriptionPaymentRecognitionHistoryController

##### `GET` `/subscription-payment-histories`

계약 ID 로 청약 납입 인정 이력 전체 조회.

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `contractId` | `Long` | O |
| `status` | `RecognitionStatus` | - |

**응답**: `List<SubscriptionPaymentRecognitionHistory>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `recognitionId` | `Long` |  |
| `contractId` | `Long` |  |
| `paymentAmount` | `BigDecimal` |  |
| `recognizedAmount` | `BigDecimal` |  |
| `paymentMonth` | `String` |  |
| `recognizedAt` | `OffsetDateTime` |  |
| `recognitionStatus` | `RecognitionStatus` |  |
| `createdAt` | `OffsetDateTime` |  |

##### `GET` `/subscription-payment-histories/{id}`

단건 조회.

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `id` | `Long` |

**응답**: `SubscriptionPaymentRecognitionHistory`

| 필드 | 타입 | 제약 |
|---|---|---|
| `recognitionId` | `Long` |  |
| `contractId` | `Long` |  |
| `paymentAmount` | `BigDecimal` |  |
| `recognizedAmount` | `BigDecimal` |  |
| `paymentMonth` | `String` |  |
| `recognizedAt` | `OffsetDateTime` |  |
| `recognitionStatus` | `RecognitionStatus` |  |
| `createdAt` | `OffsetDateTime` |  |

#### TransactionController

##### `GET` `/transactions`

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `accountId` | `Long` | - |
| `customerId` | `String` | - |
| `startDate` | `OffsetDateTime` | - |
| `endDate` | `OffsetDateTime` | - |

**응답**: `Page<Transaction>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `transactionId` | `Long` |  |
| `transactionNumber` | `String` |  |
| `idempotencyKey` | `String` |  |
| `accountId` | `Long` |  |
| `contractId` | `Long` |  |
| `transactionType` | `TransactionType` |  |
| `directionType` | `DirectionType` |  |
| `amount` | `BigDecimal` |  |
| `balanceBefore` | `BigDecimal` |  |
| `balanceAfter` | `BigDecimal` |  |
| `availableBalanceAfter` | `BigDecimal` |  |
| `feeAmount` | `BigDecimal` |  |
| `currency` | `String` |  |
| `status` | `TransactionStatus` |  |
| `channelType` | `TransactionChannel` |  |
| `ipAddress` | `String` |  |
| `terminalId` | `String` |  |
| `transactionLocation` | `String` |  |
| `transactionMemo` | `String` |  |
| `transactionSummary` | `String` |  |
| `transactionAt` | `OffsetDateTime` |  |
| `postedAt` | `OffsetDateTime` |  |
| `canceledAt` | `OffsetDateTime` |  |
| `depositorCustomerId` | `String` |  |
| `depositorName` | `String` |  |
| `delegateCustomerId` | `String` |  |
| `delegateCustomerName` | `String` |  |
| `transferType` | `TransferType` |  |
| `counterpartyBankCode` | `String` |  |
| `counterpartyBankName` | `String` |  |
| `counterpartyAccountNo` | `String` |  |
| `counterpartyAccountId` | `Long` |  |
| `counterpartyCustomerId` | `String` |  |
| `counterpartyName` | `String` |  |
| `counterpartyNameVerifiedYn` | `Boolean` |  |
| `transferRequestedAt` | `OffsetDateTime` |  |
| `transferCompletedAt` | `OffsetDateTime` |  |
| `paymentMethod` | `PaymentMethod` |  |
| `merchantId` | `String` |  |
| `merchantName` | `String` |  |
| `approvalNumber` | `String` |  |
| `externalTransactionNo` | `String` |  |
| `paymentRound` | `Integer` |  |
| `originalTransactionId` | `Long` |  |
| `failureType` | `FailureType` |  |
| `failureCode` | `String` |  |
| `failureReasonCode` | `FailureReasonCode` |  |
| `failureAt` | `OffsetDateTime` |  |
| `retryCount` | `Integer` |  |

##### `POST` `/transactions/deposit`

**요청 본문**: `DepositRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` | 필수 |
| `amount` | `BigDecimal` | 필수, 양수 |
| `channelType` | `TransactionChannel` |  |
| `transactionMemo` | `String` |  |
| `depositorCustomerId` | `String` |  |
| `depositorName` | `String` |  |

**응답**: `Transaction`

| 필드 | 타입 | 제약 |
|---|---|---|
| `transactionId` | `Long` |  |
| `transactionNumber` | `String` |  |
| `idempotencyKey` | `String` |  |
| `accountId` | `Long` |  |
| `contractId` | `Long` |  |
| `transactionType` | `TransactionType` |  |
| `directionType` | `DirectionType` |  |
| `amount` | `BigDecimal` |  |
| `balanceBefore` | `BigDecimal` |  |
| `balanceAfter` | `BigDecimal` |  |
| `availableBalanceAfter` | `BigDecimal` |  |
| `feeAmount` | `BigDecimal` |  |
| `currency` | `String` |  |
| `status` | `TransactionStatus` |  |
| `channelType` | `TransactionChannel` |  |
| `ipAddress` | `String` |  |
| `terminalId` | `String` |  |
| `transactionLocation` | `String` |  |
| `transactionMemo` | `String` |  |
| `transactionSummary` | `String` |  |
| `transactionAt` | `OffsetDateTime` |  |
| `postedAt` | `OffsetDateTime` |  |
| `canceledAt` | `OffsetDateTime` |  |
| `depositorCustomerId` | `String` |  |
| `depositorName` | `String` |  |
| `delegateCustomerId` | `String` |  |
| `delegateCustomerName` | `String` |  |
| `transferType` | `TransferType` |  |
| `counterpartyBankCode` | `String` |  |
| `counterpartyBankName` | `String` |  |
| `counterpartyAccountNo` | `String` |  |
| `counterpartyAccountId` | `Long` |  |
| `counterpartyCustomerId` | `String` |  |
| `counterpartyName` | `String` |  |
| `counterpartyNameVerifiedYn` | `Boolean` |  |
| `transferRequestedAt` | `OffsetDateTime` |  |
| `transferCompletedAt` | `OffsetDateTime` |  |
| `paymentMethod` | `PaymentMethod` |  |
| `merchantId` | `String` |  |
| `merchantName` | `String` |  |
| `approvalNumber` | `String` |  |
| `externalTransactionNo` | `String` |  |
| `paymentRound` | `Integer` |  |
| `originalTransactionId` | `Long` |  |
| `failureType` | `FailureType` |  |
| `failureCode` | `String` |  |
| `failureReasonCode` | `FailureReasonCode` |  |
| `failureAt` | `OffsetDateTime` |  |
| `retryCount` | `Integer` |  |

##### `POST` `/transactions/savings-payment`

**요청 본문**: `SavingsPaymentRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` | 필수 |
| `contractId` | `Long` | 필수 |
| `amount` | `BigDecimal` | 필수, 양수 |
| `paymentRound` | `Integer` | 필수, 양수 |
| `channelType` | `TransactionChannel` |  |

**응답**: `Transaction`

| 필드 | 타입 | 제약 |
|---|---|---|
| `transactionId` | `Long` |  |
| `transactionNumber` | `String` |  |
| `idempotencyKey` | `String` |  |
| `accountId` | `Long` |  |
| `contractId` | `Long` |  |
| `transactionType` | `TransactionType` |  |
| `directionType` | `DirectionType` |  |
| `amount` | `BigDecimal` |  |
| `balanceBefore` | `BigDecimal` |  |
| `balanceAfter` | `BigDecimal` |  |
| `availableBalanceAfter` | `BigDecimal` |  |
| `feeAmount` | `BigDecimal` |  |
| `currency` | `String` |  |
| `status` | `TransactionStatus` |  |
| `channelType` | `TransactionChannel` |  |
| `ipAddress` | `String` |  |
| `terminalId` | `String` |  |
| `transactionLocation` | `String` |  |
| `transactionMemo` | `String` |  |
| `transactionSummary` | `String` |  |
| `transactionAt` | `OffsetDateTime` |  |
| `postedAt` | `OffsetDateTime` |  |
| `canceledAt` | `OffsetDateTime` |  |
| `depositorCustomerId` | `String` |  |
| `depositorName` | `String` |  |
| `delegateCustomerId` | `String` |  |
| `delegateCustomerName` | `String` |  |
| `transferType` | `TransferType` |  |
| `counterpartyBankCode` | `String` |  |
| `counterpartyBankName` | `String` |  |
| `counterpartyAccountNo` | `String` |  |
| `counterpartyAccountId` | `Long` |  |
| `counterpartyCustomerId` | `String` |  |
| `counterpartyName` | `String` |  |
| `counterpartyNameVerifiedYn` | `Boolean` |  |
| `transferRequestedAt` | `OffsetDateTime` |  |
| `transferCompletedAt` | `OffsetDateTime` |  |
| `paymentMethod` | `PaymentMethod` |  |
| `merchantId` | `String` |  |
| `merchantName` | `String` |  |
| `approvalNumber` | `String` |  |
| `externalTransactionNo` | `String` |  |
| `paymentRound` | `Integer` |  |
| `originalTransactionId` | `Long` |  |
| `failureType` | `FailureType` |  |
| `failureCode` | `String` |  |
| `failureReasonCode` | `FailureReasonCode` |  |
| `failureAt` | `OffsetDateTime` |  |
| `retryCount` | `Integer` |  |

##### `POST` `/transactions/transfer`

**요청 본문**: `TransferRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `fromAccountId` | `Long` | 필수 |
| `toAccountId` | `Long` |  |
| `toAccountNo` | `String` |  |
| `amount` | `BigDecimal` | 필수, 양수 |
| `transferType` | `TransferType` |  |
| `counterpartyBankCode` | `String` |  |
| `counterpartyBankName` | `String` |  |
| `counterpartyName` | `String` |  |
| `channelType` | `TransactionChannel` |  |
| `transactionMemo` | `String` |  |
| `idempotencyKey` | `String` |  |

**응답**: `Transaction`

| 필드 | 타입 | 제약 |
|---|---|---|
| `transactionId` | `Long` |  |
| `transactionNumber` | `String` |  |
| `idempotencyKey` | `String` |  |
| `accountId` | `Long` |  |
| `contractId` | `Long` |  |
| `transactionType` | `TransactionType` |  |
| `directionType` | `DirectionType` |  |
| `amount` | `BigDecimal` |  |
| `balanceBefore` | `BigDecimal` |  |
| `balanceAfter` | `BigDecimal` |  |
| `availableBalanceAfter` | `BigDecimal` |  |
| `feeAmount` | `BigDecimal` |  |
| `currency` | `String` |  |
| `status` | `TransactionStatus` |  |
| `channelType` | `TransactionChannel` |  |
| `ipAddress` | `String` |  |
| `terminalId` | `String` |  |
| `transactionLocation` | `String` |  |
| `transactionMemo` | `String` |  |
| `transactionSummary` | `String` |  |
| `transactionAt` | `OffsetDateTime` |  |
| `postedAt` | `OffsetDateTime` |  |
| `canceledAt` | `OffsetDateTime` |  |
| `depositorCustomerId` | `String` |  |
| `depositorName` | `String` |  |
| `delegateCustomerId` | `String` |  |
| `delegateCustomerName` | `String` |  |
| `transferType` | `TransferType` |  |
| `counterpartyBankCode` | `String` |  |
| `counterpartyBankName` | `String` |  |
| `counterpartyAccountNo` | `String` |  |
| `counterpartyAccountId` | `Long` |  |
| `counterpartyCustomerId` | `String` |  |
| `counterpartyName` | `String` |  |
| `counterpartyNameVerifiedYn` | `Boolean` |  |
| `transferRequestedAt` | `OffsetDateTime` |  |
| `transferCompletedAt` | `OffsetDateTime` |  |
| `paymentMethod` | `PaymentMethod` |  |
| `merchantId` | `String` |  |
| `merchantName` | `String` |  |
| `approvalNumber` | `String` |  |
| `externalTransactionNo` | `String` |  |
| `paymentRound` | `Integer` |  |
| `originalTransactionId` | `Long` |  |
| `failureType` | `FailureType` |  |
| `failureCode` | `String` |  |
| `failureReasonCode` | `FailureReasonCode` |  |
| `failureAt` | `OffsetDateTime` |  |
| `retryCount` | `Integer` |  |

##### `POST` `/transactions/withdraw`

**요청 본문**: `WithdrawRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `accountId` | `Long` | 필수 |
| `amount` | `BigDecimal` | 필수, 양수 |
| `channelType` | `TransactionChannel` |  |
| `transactionMemo` | `String` |  |

**응답**: `Transaction`

| 필드 | 타입 | 제약 |
|---|---|---|
| `transactionId` | `Long` |  |
| `transactionNumber` | `String` |  |
| `idempotencyKey` | `String` |  |
| `accountId` | `Long` |  |
| `contractId` | `Long` |  |
| `transactionType` | `TransactionType` |  |
| `directionType` | `DirectionType` |  |
| `amount` | `BigDecimal` |  |
| `balanceBefore` | `BigDecimal` |  |
| `balanceAfter` | `BigDecimal` |  |
| `availableBalanceAfter` | `BigDecimal` |  |
| `feeAmount` | `BigDecimal` |  |
| `currency` | `String` |  |
| `status` | `TransactionStatus` |  |
| `channelType` | `TransactionChannel` |  |
| `ipAddress` | `String` |  |
| `terminalId` | `String` |  |
| `transactionLocation` | `String` |  |
| `transactionMemo` | `String` |  |
| `transactionSummary` | `String` |  |
| `transactionAt` | `OffsetDateTime` |  |
| `postedAt` | `OffsetDateTime` |  |
| `canceledAt` | `OffsetDateTime` |  |
| `depositorCustomerId` | `String` |  |
| `depositorName` | `String` |  |
| `delegateCustomerId` | `String` |  |
| `delegateCustomerName` | `String` |  |
| `transferType` | `TransferType` |  |
| `counterpartyBankCode` | `String` |  |
| `counterpartyBankName` | `String` |  |
| `counterpartyAccountNo` | `String` |  |
| `counterpartyAccountId` | `Long` |  |
| `counterpartyCustomerId` | `String` |  |
| `counterpartyName` | `String` |  |
| `counterpartyNameVerifiedYn` | `Boolean` |  |
| `transferRequestedAt` | `OffsetDateTime` |  |
| `transferCompletedAt` | `OffsetDateTime` |  |
| `paymentMethod` | `PaymentMethod` |  |
| `merchantId` | `String` |  |
| `merchantName` | `String` |  |
| `approvalNumber` | `String` |  |
| `externalTransactionNo` | `String` |  |
| `paymentRound` | `Integer` |  |
| `originalTransactionId` | `Long` |  |
| `failureType` | `FailureType` |  |
| `failureCode` | `String` |  |
| `failureReasonCode` | `FailureReasonCode` |  |
| `failureAt` | `OffsetDateTime` |  |
| `retryCount` | `Integer` |  |

##### `GET` `/transactions/{transactionId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `transactionId` | `Long` |

**응답**: `Transaction`

| 필드 | 타입 | 제약 |
|---|---|---|
| `transactionId` | `Long` |  |
| `transactionNumber` | `String` |  |
| `idempotencyKey` | `String` |  |
| `accountId` | `Long` |  |
| `contractId` | `Long` |  |
| `transactionType` | `TransactionType` |  |
| `directionType` | `DirectionType` |  |
| `amount` | `BigDecimal` |  |
| `balanceBefore` | `BigDecimal` |  |
| `balanceAfter` | `BigDecimal` |  |
| `availableBalanceAfter` | `BigDecimal` |  |
| `feeAmount` | `BigDecimal` |  |
| `currency` | `String` |  |
| `status` | `TransactionStatus` |  |
| `channelType` | `TransactionChannel` |  |
| `ipAddress` | `String` |  |
| `terminalId` | `String` |  |
| `transactionLocation` | `String` |  |
| `transactionMemo` | `String` |  |
| `transactionSummary` | `String` |  |
| `transactionAt` | `OffsetDateTime` |  |
| `postedAt` | `OffsetDateTime` |  |
| `canceledAt` | `OffsetDateTime` |  |
| `depositorCustomerId` | `String` |  |
| `depositorName` | `String` |  |
| `delegateCustomerId` | `String` |  |
| `delegateCustomerName` | `String` |  |
| `transferType` | `TransferType` |  |
| `counterpartyBankCode` | `String` |  |
| `counterpartyBankName` | `String` |  |
| `counterpartyAccountNo` | `String` |  |
| `counterpartyAccountId` | `Long` |  |
| `counterpartyCustomerId` | `String` |  |
| `counterpartyName` | `String` |  |
| `counterpartyNameVerifiedYn` | `Boolean` |  |
| `transferRequestedAt` | `OffsetDateTime` |  |
| `transferCompletedAt` | `OffsetDateTime` |  |
| `paymentMethod` | `PaymentMethod` |  |
| `merchantId` | `String` |  |
| `merchantName` | `String` |  |
| `approvalNumber` | `String` |  |
| `externalTransactionNo` | `String` |  |
| `paymentRound` | `Integer` |  |
| `originalTransactionId` | `Long` |  |
| `failureType` | `FailureType` |  |
| `failureCode` | `String` |  |
| `failureReasonCode` | `FailureReasonCode` |  |
| `failureAt` | `OffsetDateTime` |  |
| `retryCount` | `Integer` |  |

##### `PATCH` `/transactions/{transactionId}/cancel`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `transactionId` | `Long` |

**요청 본문**: `@RequestBody(required = false) java.util.Map<String, String> body`

**응답**: `Transaction`

| 필드 | 타입 | 제약 |
|---|---|---|
| `transactionId` | `Long` |  |
| `transactionNumber` | `String` |  |
| `idempotencyKey` | `String` |  |
| `accountId` | `Long` |  |
| `contractId` | `Long` |  |
| `transactionType` | `TransactionType` |  |
| `directionType` | `DirectionType` |  |
| `amount` | `BigDecimal` |  |
| `balanceBefore` | `BigDecimal` |  |
| `balanceAfter` | `BigDecimal` |  |
| `availableBalanceAfter` | `BigDecimal` |  |
| `feeAmount` | `BigDecimal` |  |
| `currency` | `String` |  |
| `status` | `TransactionStatus` |  |
| `channelType` | `TransactionChannel` |  |
| `ipAddress` | `String` |  |
| `terminalId` | `String` |  |
| `transactionLocation` | `String` |  |
| `transactionMemo` | `String` |  |
| `transactionSummary` | `String` |  |
| `transactionAt` | `OffsetDateTime` |  |
| `postedAt` | `OffsetDateTime` |  |
| `canceledAt` | `OffsetDateTime` |  |
| `depositorCustomerId` | `String` |  |
| `depositorName` | `String` |  |
| `delegateCustomerId` | `String` |  |
| `delegateCustomerName` | `String` |  |
| `transferType` | `TransferType` |  |
| `counterpartyBankCode` | `String` |  |
| `counterpartyBankName` | `String` |  |
| `counterpartyAccountNo` | `String` |  |
| `counterpartyAccountId` | `Long` |  |
| `counterpartyCustomerId` | `String` |  |
| `counterpartyName` | `String` |  |
| `counterpartyNameVerifiedYn` | `Boolean` |  |
| `transferRequestedAt` | `OffsetDateTime` |  |
| `transferCompletedAt` | `OffsetDateTime` |  |
| `paymentMethod` | `PaymentMethod` |  |
| `merchantId` | `String` |  |
| `merchantName` | `String` |  |
| `approvalNumber` | `String` |  |
| `externalTransactionNo` | `String` |  |
| `paymentRound` | `Integer` |  |
| `originalTransactionId` | `Long` |  |
| `failureType` | `FailureType` |  |
| `failureCode` | `String` |  |
| `failureReasonCode` | `FailureReasonCode` |  |
| `failureAt` | `OffsetDateTime` |  |
| `retryCount` | `Integer` |  |

### 추천 에이전트

#### RecommendAgentController

##### `GET` `/products/recommend-agent`

현금흐름 기반 수신 상품 추천.

**헤더**

| 이름 | 필수 |
|---|---|
| `X-Customer-Id` | - |

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `customerId` | `String` | O |
| `periodMonth` | `int` | - |
| `birthYear` | `Integer` | - |

**응답**: `ProductRecommendResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `customerId` | `String` |  |
| `analysisPeriodMonth` | `int` |  |
| `cashFlow` | `CashFlowSummary` |  |
| `recommendations` | `List<RecommendedProduct>` |  |
| `fallbackReason` | `String` |  |

### 부서·홈

#### DepartmentController

##### `GET` `/departments`

**응답**: `List<Department>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `departmentId` | `Long` |  |
| `departmentCode` | `String` |  |
| `departmentName` | `String` |  |
| `parentDepartmentId` | `Long` |  |
| `departmentType` | `DepartmentType` |  |
| `isActive` | `Boolean` |  |

##### `POST` `/departments`

**요청 본문**: `DepartmentCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `departmentCode` | `String` | 필수(공백불가) |
| `departmentName` | `String` | 필수(공백불가) |
| `departmentType` | `DepartmentType` | 필수 |
| `parentDepartmentId` | `Long` |  |

**응답**: `Department`

| 필드 | 타입 | 제약 |
|---|---|---|
| `departmentId` | `Long` |  |
| `departmentCode` | `String` |  |
| `departmentName` | `String` |  |
| `parentDepartmentId` | `Long` |  |
| `departmentType` | `DepartmentType` |  |
| `isActive` | `Boolean` |  |

##### `DELETE` `/departments/{departmentId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `departmentId` | `Long` |

##### `GET` `/departments/{departmentId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `departmentId` | `Long` |

**응답**: `Department`

| 필드 | 타입 | 제약 |
|---|---|---|
| `departmentId` | `Long` |  |
| `departmentCode` | `String` |  |
| `departmentName` | `String` |  |
| `parentDepartmentId` | `Long` |  |
| `departmentType` | `DepartmentType` |  |
| `isActive` | `Boolean` |  |

##### `PUT` `/departments/{departmentId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `departmentId` | `Long` |

**요청 본문**: `DepartmentCreateRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `departmentCode` | `String` | 필수(공백불가) |
| `departmentName` | `String` | 필수(공백불가) |
| `departmentType` | `DepartmentType` | 필수 |
| `parentDepartmentId` | `Long` |  |

**응답**: `Department`

| 필드 | 타입 | 제약 |
|---|---|---|
| `departmentId` | `Long` |  |
| `departmentCode` | `String` |  |
| `departmentName` | `String` |  |
| `parentDepartmentId` | `Long` |  |
| `departmentType` | `DepartmentType` |  |
| `isActive` | `Boolean` |  |

#### HomeController

##### `GET` `/`

**응답**: `Map (동적 필드)`
