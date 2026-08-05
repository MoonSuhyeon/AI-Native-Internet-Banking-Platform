# Deposit Service

작성자: 정혜영  
수정일: 2026-06-12

Deposit Service는 예금, 적금, 입출금, 청약 상품과 예금 계좌, 계약, 거래 내역을 담당하는 백엔드 서비스입니다. 프론트엔드의 예금 상품 조회, 상품 상세, 계좌이체, 이체 결과 조회, 거래내역 조회 화면과 연동됩니다.

## 수정 요약

이번 수정은 deposit 담당 범위만 포함합니다.

| 구분 | 변경 내용 |
| --- | --- |
| **payment-service 연동 API (v1)** | `docs/deposit-payment-api-spec.md` 명세에 따라 payment-service가 호출하는 `/api/v1/` 엔드포인트 신규 구현 |
| **출금 가능 잔액 체크 수정** | `Account.withdraw()` 잔액 검증 시 `holdAmount`(지급 보류액)를 차감한 출금 가능 잔액 기준으로 변경 |
| **fraud_flag · hold_amount 컬럼 추가 (V17)** | `deposit_accounts` 테이블에 사기계좌 플래그(`fraud_flag`)와 지급보류금액(`hold_amount`) 컬럼 추가 |

| 구분 | 변경 내용 |
| --- | --- |
| 상품 조회 | 상품 목록과 상품 상세 응답에 `bestRate`를 추가했습니다. |
| 최고금리 계산 | 활성화된 금리 row 중 기본금리 또는 기간기본금리의 최댓값에 우대금리를 합산해 최고금리를 계산합니다. |
| 프론트 상품 표시 | 상품 카드와 상품 상세 화면에서 `최고 연 n%`, `기본 연 n%`를 구분해 표시합니다. |
| 계좌이체 단일화 | 이체 실행 지점을 result 페이지 하나로 단일화했습니다. confirm 페이지의 중복 호출을 제거하고, result 페이지에서 `/transactions/transfer` API만 호출합니다. |
| 이체 계좌 조회 fallback | deposit API 실패 시 localStorage의 `joinedAccounts`로 fallback해 출금계좌 목록을 표시합니다. |
| 이체 가능 계좌 | `rawAccountType === 'DEPOSIT'`이고 출금 가능하며 해지되지 않은 계좌만 출금계좌로 선택합니다. |
| 이체 결과 | 이체 처리 중 로딩 표시를 추가했습니다. 이체 성공 후 계좌 잔액을 다시 조회하고, 실패 시 오류 메시지를 화면에 표시합니다. |
| 이체 조회 | 계좌별 거래내역을 조회해 즉시이체 결과조회에 반영합니다. |
| 거래내역 | 기본 계좌 자동 선택, 이체 메모 문구 정리, 거래 후 잔액 표시를 추가했습니다. |
| 거래 채널 | `TransactionChannel`에 `CHATBOT` 값을 추가했습니다. |
| 테스트 | 상품 목록/상세 응답의 `bestRate` 계산과 컨트롤러 응답 검증을 추가했습니다. |
| 테스트 보정 | 최신 계약/거래 서비스 시그니처와 계좌 조회 방식에 맞춰 기존 테스트 fixture를 보정했습니다. |
| 이체 시나리오 테스트 | INTERNAL 토AccountId null, 존재하지 않는 계좌, CLOSED 계좌, 계좌번호 불일치, 타행이체 잔액 차감 검증 추가. |
| 챗봇 상품 추천 우대금리 표시 | 상품 추천 카드에 우대금리 수치(+X%)와 조건을 함께 표시합니다. `banking_deposit_product_interest_rates` 테이블의 PREFERENTIAL 금리 합산값과 조건을 카드에 노출합니다. |
| 이체 API 중복 함수 제거 | `web/lib/deposit-api.ts`의 `executeDepositTransfer` 중복 정의를 제거했습니다. |
| 당행이체 계좌번호 조회 자동화 | `TransactionService.transfer()`에서 INTERNAL 이체 시 `toAccountId`가 null이면 throw 대신 `accountRepository.findByAccountNumber(toAccountNo)`로 계좌를 조회해 ID를 자동 매핑합니다. 타인 당행 계좌 이체 시 프론트가 내부 ID를 알 수 없는 구조적 한계를 백엔드에서 해소합니다. |
| **챗봇·상담 테이블 소유권 이관 (V5 → V12)** | V5 마이그레이션에 포함됐던 `chatbot_*` · `consultation` 테이블 6개를 deposit-service 관할에서 제거합니다. 해당 테이블의 실제 소유자는 consultation-service이며, 서비스 기동 시 SQLAlchemy `create_all()`로 올바른 스키마로 자동 생성됩니다. V12 마이그레이션에서 기존 deposit-db의 불일치 테이블을 DROP해 충돌을 해소합니다. |
| **이체 일일 한도 검증** | ERD에 정의된 `daily_withdraw_limit`(하루 금액 한도), `daily_withdraw_count_limit`(하루 횟수 한도)를 이체 실행 시점에 실제로 검증합니다. 한도 초과 시 `BusinessException`을 던지고 이체를 차단합니다. |

## 백엔드 변경 상세

### 상품 응답 최고금리

`ProductResponse`에 `bestRate` 필드를 추가했습니다.

- 기존 `baseInterestRate`는 상품에 등록된 기본 표시 금리입니다.
- 신규 `bestRate`는 실제 활성 금리 조건을 기준으로 계산한 최고 금리입니다.
- 활성 금리 row가 없거나 계산 가능한 금리가 없으면 `bestRate`는 `null`입니다.

계산 기준:

1. `RateType.BASE` 또는 `RateType.PERIOD_BASE` 중 가장 높은 금리를 기준금리로 사용합니다.
2. `RateType.PREFERENTIAL` 금리는 모두 합산합니다.
3. 기준금리가 없고 우대금리만 있으면 상품의 `baseInterestRate`를 기준금리로 대체합니다.
4. 기준금리와 우대금리를 더해 `bestRate`로 반환합니다.

관련 파일:

| 파일 | 내용 |
| --- | --- |
| `src/main/java/com/bank/deposit/dto/response/ProductResponse.java` | `bestRate` 필드와 변환 메서드 추가 |
| `src/main/java/com/bank/deposit/service/ProductService.java` | 상품 목록/상세 응답 생성 및 최고금리 계산 로직 추가 |
| `src/main/java/com/bank/deposit/controller/ProductController.java` | 컨트롤러가 `ProductResponse` 응답 메서드를 사용하도록 변경 |
| `src/main/java/com/bank/deposit/domain/enums/TransactionChannel.java` | `CHATBOT` 채널 추가 |

### 상품 API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/products` | 상품 목록 조회. `bestRate` 포함 |
| `GET` | `/products/{productId}` | 상품 단건 조회. `bestRate` 포함 |

응답 예시:

```json
{
  "productId": 1,
  "productType": "DEPOSIT",
  "productName": "AXful 정기예금",
  "baseInterestRate": 3.5,
  "bestRate": 4.0,
  "productStatus": "SELLING"
}
```

## 프론트엔드 연동 변경

### 상품 화면

상품 목록, 메인 상품 쇼케이스, 예금 상품 상세 화면에서 `bestRate`가 있으면 최고금리를 우선 표시합니다.

- `bestRate`가 있으면 `최고 연 n%`
- `bestRate`가 없고 `baseInterestRate`가 있으면 `기본 연 n%`
- 둘 다 없으면 기존 fallback 값 사용

관련 파일:

| 파일 | 내용 |
| --- | --- |
| `web/lib/deposit-api.ts` | `DepositProduct.bestRate` 타입 추가 |
| `web/components/home/ProductShowcase.tsx` | 상품 카드 금리 표시 변경 |
| `web/app/(personal)/products/deposit/[id]/page.tsx` | 상품 상세 금리 표시 변경 |

### 계좌이체

이체 결과 페이지에서 실제 deposit-service 이체 API를 호출하도록 변경했습니다.

처리 흐름:

1. 이체 입력 페이지에서 출금계좌, 입금계좌, 금액, 이체 유형을 `sessionStorage.pendingTransfer`에 저장합니다.
2. 이체 결과 페이지 진입 시 `/transactions/transfer` API를 호출합니다.
3. 성공하면 계좌 목록을 다시 조회해 잔액을 갱신합니다.
4. 실패하면 실패 메시지를 결과 영역에 표시합니다.
5. 처리 후 `pendingTransfer`를 제거해 중복 실행을 막습니다.

#### 당행이체 라우팅 개선 — `toAccountId` 자동 조회

**배경**

프론트엔드는 본인 계좌 목록만 보유하므로(`fetchDepositAccountViewModels(customerId)`), 타인 당행 계좌의 내부 ID(`toAccountId`)를 알 수 없습니다. 기존 코드는 `toAccountId`가 null이면 즉시 `ACCOUNT_NOT_FOUND`를 throw해 타인 당행 계좌 이체가 항상 실패했습니다.

**변경 내용**

`TransactionService.transfer()`에서 INTERNAL 이체 시 `toAccountId`가 null이면 throw 대신 `toAccountNo`로 계좌를 조회해 ID를 자동으로 채웁니다.

```java
// 변경 전
if (resolvedType == TransferType.INTERNAL && toAccountId == null) {
    throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
}

// 변경 후
if (resolvedType == TransferType.INTERNAL && toAccountId == null) {
    toAccountId = accountRepository.findByAccountNumber(toAccountNo)
            .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND))
            .getAccountId();
}
```

**효과**

- 프론트는 계좌번호와 은행코드만 전달하면 됩니다. INTERNAL/EXTERNAL 라우팅을 추측할 필요가 없어집니다.
- 계좌번호가 실제로 존재하지 않으면 기존과 동일하게 `ACCOUNT_NOT_FOUND`를 반환합니다. 안전장치는 유지됩니다.
- 관련 이슈 #87·#89·#90의 타인 당행 계좌 이체 실패 버그가 한 곳 수정으로 해소됩니다.

관련 파일:

| 파일 | 내용 |
| --- | --- |
| `services/deposit-service/src/main/java/com/bank/deposit/service/TransactionService.java` | INTERNAL `toAccountId` null 시 `findByAccountNumber` 조회로 변경 |

관련 파일:

| 파일 | 내용 |
| --- | --- |
| `web/lib/deposit-api.ts` | `executeDepositTransfer` API 함수와 거래 응답 잔액 필드 추가 |
| `web/app/(personal)/transfer/account/page.tsx` | 출금 가능 계좌 필터링, API 실패 시 localStorage fallback, 내부이체 대상 계좌 ID 저장 |
| `web/app/(personal)/transfer/result/page.tsx` | 실제 이체 API 호출, 성공/실패 표시, 잔액 갱신 |
| `web/app/(personal)/transfer/inquiry/page.tsx` | 계좌별 거래내역 기반 이체 결과 조회 |

### 거래내역

거래내역 화면에서 사용자가 계좌를 직접 고르지 않아도 기본 계좌가 선택되도록 보정했습니다. 또한 이체 메모 문구와 거래 후 잔액 표시를 개선했습니다.

관련 파일:

| 파일 | 내용 |
| --- | --- |
| `web/app/(personal)/inquiry/transactions/page.tsx` | 기본 계좌 선택, 이체 메모 표시 정리, 거래 후 잔액 표시 |
| `web/lib/deposit-api.ts` | `DepositTransaction.balanceAfter`, `availableBalanceAfter` 타입 추가 |

## 테스트

추가/수정된 테스트 (전체 261개 PASS, BUILD SUCCESSFUL):

### 거래 서비스 (`TransactionServiceTest`)

| 케이스 | 검증 내용 |
| --- | --- |
| 타행이체 출금 거래 생성 | OUT 방향, TRF- 번호, 잔액 차감 검증 |
| 당행이체 양방향 거래 생성 | OUT+IN 각각 생성, 채널 SYSTEM, "이체 수신" 메모 |
| 잔액 부족 이체 예외 | `BusinessException` 발생 |
| INTERNAL toAccountId null → 계좌번호 조회 성공 | `findByAccountNumber`로 ID 매핑 후 이체 정상 처리 |
| INTERNAL toAccountId null + 존재하지 않는 계좌번호 | `BusinessException(ACCOUNT_NOT_FOUND)` 발생 |
| 존재하지 않는 출금 계좌 예외 | `BusinessException` 발생 |
| CLOSED 계좌 이체 예외 | `BusinessException` 발생 |
| 당행이체 계좌번호 불일치 예외 | `BusinessException` 발생 |
| 타행이체 잔액 정확히 차감 | 잔액 = 이전잔액 - 이체금액 |
| 전액 이체 후 잔액 0 | 잔액 0 검증 |
| 순차 이체 두 번 후 잔액 | 누적 차감 정확성 |
| 음수 잔액 방지 | 실패 시 잔액 불변 |
| 멀티스레드 순차 호출 잔액 | 잔액 ≥ 0 보장 |
| DEPOSIT 타입 거래 취소 불가 | `BusinessException` 발생 |
| 이미 취소된 거래 재취소 불가 | `BusinessException` 발생 |
| 취소 거래 생성 | REVERSAL 타입, REV- 번호 |

### 거래 컨트롤러 (`TransactionControllerTest`)

| 케이스 | 검증 내용 |
| --- | --- |
| 이체 정상 | 201 Created, `TRANSFER` 타입 반환 |
| fromAccountId 누락 | 400 Bad Request |
| 금액 0원 | 400 Bad Request |
| 금액 음수 | 400 Bad Request |
| 서비스 예외 (잔액 부족) | 4xx 반환 |
| 없는 거래 취소 | 404 Not Found |
| 입금/출금/적금납입/취소 | 201/200 정상 반환 |
| 없는 거래 조회 | 404 Not Found |

### 계좌 서비스 (`AccountServiceTest`)

| 케이스 | 검증 내용 |
| --- | --- |
| 계좌번호로 정상 조회 | accountNumber, customerId 일치 |
| 없는 계좌번호 조회 예외 | `BusinessException` 발생 |
| 고객 계좌 없을 때 빈 리스트 | 빈 리스트 반환 |

### 계좌 컨트롤러 (`AccountControllerTest`)

| 케이스 | 검증 내용 |
| --- | --- |
| `GET /accounts/by-number/{accountNo}` 정상 | 200, accountNumber/customerId 반환 |
| `GET /accounts/by-number/없는번호` | 404 Not Found |
| 인증 헤더 없이 계좌 생성 | 403 Forbidden |

### 기존 테스트 (보정 포함)

| 파일 | 검증 내용 |
| --- | --- |
| `ProductControllerTest` | 상품 목록/상세 응답에 `bestRate` 포함 검증 |
| `ProductServiceTest` | 활성 금리 row 기준 `bestRate` 계산 검증 |
| `ContractControllerTest` | 해지 API mock 인자 보정 |
| `ContractServiceTest` | 계약 생성 시그니처·Clock 기준 보정 |

테스트 실행:

```bash
./gradlew :services:deposit-service:test
./gradlew :services:deposit-service:build
```

## 이체 일일 한도 검증

ERD에 정의된 계좌 이체 한도를 이체 실행 시점에 실제로 검증합니다.

### 한도 항목

| 필드 | DB 컬럼 | 설명 |
|---|---|---|
| 하루 금액 한도 | `daily_withdraw_limit` | 당일 출금·이체 합산 금액이 이 값을 초과하면 이체 차단 |
| 하루 횟수 한도 | `daily_withdraw_count_limit` | 당일 출금·이체 건수가 이 값에 도달하면 이체 차단 |

한도 값이 `null`이면 해당 항목은 무제한으로 처리됩니다.

### 검증 흐름

```
transfer() 호출
  → 출금 계좌 조회 (FOR UPDATE 락)
  → validateDailyTransferLimit()
      → 오늘 00:00 ~ 24:00 UTC 기준 OUT 방향 거래 합계 금액 조회
      → 합계 + 이체금액 > daily_withdraw_limit  →  DAILY_TRANSFER_AMOUNT_EXCEEDED 예외
      → 오늘 OUT 방향 거래 건수 조회
      → 건수 >= daily_withdraw_count_limit  →  DAILY_TRANSFER_COUNT_EXCEEDED 예외
  → 잔액 차감 및 거래 기록
```

### 에러 코드

| 에러 코드 | HTTP 상태 | 메시지 |
|---|---|---|
| `DAILY_TRANSFER_AMOUNT_EXCEEDED` | 400 Bad Request | 하루 이체 금액 한도를 초과했습니다. |
| `DAILY_TRANSFER_COUNT_EXCEEDED` | 400 Bad Request | 하루 이체 횟수 한도를 초과했습니다. |

### 관련 파일

| 파일 | 변경 내용 |
|---|---|
| `src/main/java/com/bank/deposit/service/TransactionService.java` | `validateDailyTransferLimit()` 메서드 추가, `transfer()` 앞단에서 호출 |
| `src/main/java/com/bank/deposit/repository/TransactionRepository.java` | 당일 OUT 방향 합계 금액·건수 조회 쿼리 추가 |
| `src/main/java/com/bank/deposit/exception/ErrorCode.java` | `DAILY_TRANSFER_AMOUNT_EXCEEDED`, `DAILY_TRANSFER_COUNT_EXCEEDED` 추가 |

---

## 챗봇 상품 추천 우대금리 표시

챗봇 상품 추천 카드에 우대금리 수치와 조건을 함께 표시합니다.

### 데이터 출처

`banking_deposit_product_interest_rates` 테이블(deposit DB)에서 `rate_type = 'PREFERENTIAL'`인 행을 상품별로 집계합니다.

- 우대금리 수치: `SUM(interest_rate)` → 카드에 `+X%` 형식으로 표시
- 우대금리 조건: `STRING_AGG(condition_description)` → 카드에 조건 텍스트로 표시

### 표시 예시

```
🎁 우대금리 +0.6% 조건: 자동이체 설정 우대
```

DB에 조건 데이터가 없는 상품은 상품명 키워드 기반 fallback 조건을 사용합니다.

| 키워드 | fallback 조건 |
| --- | --- |
| 내맘대로 | 자동이체 설정 |
| 자유적금 | 자동이체 설정 |
| 맑은하늘 | 맑은하늘 앱 설치 후 인증코드 등록 |
| 직장인우대 | 급여이체 실적 등록 |
| 달러 | 달러 환전 실적 보유 |
| 청년도약 | 소득 요건 충족 확인 |
| 수퍼정기 | 비대면 가입 |
| 정기예금 | 비대면(인터넷·스타뱅킹) 가입 |
| 꿈적금 | 만기 유지 |
| 함께적금 | 2인 이상 공동 가입 |

### 관련 파일

| 파일 | 내용 |
| --- | --- |
| `web/components/chatbot/ChatbotWidget.tsx` | 추천 카드에 `pref_rate`, `pref_condition` 표시 추가 |

---

## 변경 파일 목록

백엔드:

- `services/deposit-service/src/main/resources/db/migration/V5__full_erd_schema.sql` (chatbot·consultation 테이블 제거)
- `services/deposit-service/src/main/resources/db/migration/V12__drop_chatbot_consultation_tables.sql` (신규)
- `services/deposit-service/src/main/java/com/bank/deposit/service/TransactionService.java` (일일 한도 검증 추가)
- `services/deposit-service/src/main/java/com/bank/deposit/repository/TransactionRepository.java` (당일 합계·건수 쿼리 추가)
- `services/deposit-service/src/main/java/com/bank/deposit/exception/ErrorCode.java` (한도 초과 에러 코드 추가)
- `services/deposit-service/src/main/resources/db/migration/V13__add_idempotency_key_to_transactions.sql` (신규)
- `services/deposit-service/src/main/java/com/bank/deposit/domain/entity/Transaction.java` (idempotencyKey 필드 추가)
- `services/deposit-service/src/main/java/com/bank/deposit/dto/request/TransferRequest.java` (idempotencyKey 필드 추가)
- `services/deposit-service/src/main/java/com/bank/deposit/controller/ProductController.java`
- `services/deposit-service/src/main/java/com/bank/deposit/domain/enums/TransactionChannel.java`
- `services/deposit-service/src/main/java/com/bank/deposit/dto/response/ProductResponse.java`
- `services/deposit-service/src/main/java/com/bank/deposit/service/ProductService.java`
- `services/deposit-service/src/test/java/com/bank/deposit/controller/AccountControllerTest.java`
- `services/deposit-service/src/test/java/com/bank/deposit/controller/ProductControllerTest.java`
- `services/deposit-service/src/test/java/com/bank/deposit/controller/ContractControllerTest.java`
- `services/deposit-service/src/test/java/com/bank/deposit/controller/TransactionControllerTest.java`
- `services/deposit-service/src/test/java/com/bank/deposit/service/AccountServiceTest.java`
- `services/deposit-service/src/test/java/com/bank/deposit/service/ContractServiceTest.java`
- `services/deposit-service/src/test/java/com/bank/deposit/service/ProductServiceTest.java`
- `services/deposit-service/src/test/java/com/bank/deposit/service/TransactionServiceTest.java`

프론트엔드 deposit 연동:

- `web/lib/deposit-api.ts` (중복 함수 제거)
- `web/components/chatbot/ChatbotWidget.tsx` (우대금리 수치 표시 추가)
- `web/components/home/ProductShowcase.tsx`
- `web/app/(personal)/products/deposit/[id]/page.tsx`
- `web/app/(personal)/transfer/account/page.tsx`
- `web/app/(personal)/transfer/result/page.tsx`
- `web/app/(personal)/transfer/inquiry/page.tsx`
- `web/app/(personal)/inquiry/transactions/page.tsx`

## DB 마이그레이션 구조

### 챗봇·상담 테이블 소유권 정리 (V5 → V12)

**배경**

V5(`V5__full_erd_schema.sql`)는 전체 ERD를 일괄 생성하는 마이그레이션으로, 챗봇·상담 관련 테이블도 포함돼 있었습니다.
그러나 이 테이블들의 실제 소유자는 **consultation-service**이며, deposit-service Java 코드는 해당 테이블을 직접 참조하지 않습니다.
V5의 테이블 스키마(컬럼명 `id`)와 consultation-service SQLAlchemy 모델(컬럼명 `node_id` 등)이 달라 consultation-service가 기동 실패하는 문제가 있었습니다.

**해결**

| 마이그레이션 | 처리 내용 |
|---|---|
| `V5__full_erd_schema.sql` | `chatbot_scenario`, `chatbot_intent`, `chatbot_node`, `consultation`, `chatbot_consultation`, `chatbot_conversation_history` 6개 테이블 정의 제거 |
| `V12__drop_chatbot_consultation_tables.sql` | 이미 실행된 deposit-db의 해당 테이블을 FK 순서대로 DROP (IF EXISTS CASCADE) |

**기동 흐름**

```
deposit-service 기동
  → Flyway V12 실행: 불일치 chatbot·consultation 테이블 DROP
  → consultation-service 기동
  → SQLAlchemy create_all(): 올바른 컬럼명으로 chatbot·consultation 테이블 재생성
```

**영향 없음 확인**

- deposit-service Java 코드에서 chatbot·consultation 테이블 참조 없음
- V6~V11 마이그레이션에서 해당 테이블 참조 없음
- consultation-service는 deposit-db의 `deposit_*` 테이블(상품·계좌·거래)을 직접 조회하므로 DB 분리 불가 — 동일 deposit-db 유지

### 현재 마이그레이션 목록

| 버전 | 내용 |
|---|---|
| V1 | 전체 스키마 초기화 |
| V2 | Postman 시드 데이터 |
| V3 | 상품 인덱스 추가 |
| V4 | 상품 금리 제약 추가 |
| V5 | 전체 ERD 스키마 (chatbot·consultation 제외) |
| V6 | 약관 신청 관리 테이블 |
| V7 | 정기적금 시드 데이터 |
| V8 | 고객 프론트 상품 시드 데이터 |
| V9 | 계좌 version 컬럼 추가 |
| V10 | 계좌 날짜·번호 시퀀스 |
| V11 | 예약이체 스케줄 테이블 |
| **V12** | **chatbot·consultation 테이블 DROP (consultation-service 소유권 이관)** |
| **V13** | **`deposit_transactions.idempotency_key` 컬럼 추가 및 부분 UNIQUE 인덱스** |
| **V17** | **`deposit_accounts`에 `fraud_flag`, `hold_amount` 컬럼 추가** |

---

## 이체 중복·누락 방지 (멱등성 키)

네트워크 타임아웃, 재시도, 화면 새로고침 등으로 동일한 이체 요청이 두 번 이상 서버에 도달할 수 있습니다. 클라이언트가 `idempotencyKey`를 포함해 전송하면 동일 키로 이미 완료된 이체를 재처리하지 않고 기존 결과를 반환합니다.

### 작동 방식

```
POST /transactions/transfer
  { ..., "idempotencyKey": "uuid-or-any-64-char-string" }

  → transfer() 진입
  → idempotencyKey 존재 → DB에서 동일 키 조회
      → 이미 있음 → 기존 Transaction 반환 (이체 재실행 없음)
      → 없음      → 정상 이체 수행 후 idempotencyKey 저장
```

- 키가 null이거나 빈 문자열이면 멱등성 검사를 건너뜁니다(기존 동작 유지).
- 키는 최대 64자이며, `NOT NULL` 행 사이에서만 UNIQUE 제약이 적용됩니다(부분 인덱스).

### 멱등성 키 생성 권장 방식

클라이언트는 이체 시도마다 새 UUID를 생성해 키로 사용합니다. 재시도 시에는 **같은 키**를 그대로 전송합니다.

```ts
// 프론트엔드 예시
const idempotencyKey = crypto.randomUUID(); // 처음 시도 시 생성, sessionStorage에 보관
// 재시도 시에도 동일 키 사용
```

### 보호하는 시나리오

| 시나리오 | 결과 |
|---|---|
| 네트워크 타임아웃 후 재시도 | 두 번째 요청에서 기존 이체 결과 반환 — 중복 출금 없음 |
| 화면 새로고침으로 이체 페이지 재진입 | 동일 키로 이미 처리됐으면 기존 결과 반환 |
| 클라이언트 미전송(키 없음) | 멱등성 검사 없이 정상 이체 수행 |

### 관련 파일

| 파일 | 변경 내용 |
|---|---|
| `src/main/resources/db/migration/V13__add_idempotency_key_to_transactions.sql` | `idempotency_key VARCHAR(64) NULL` 컬럼 추가, 부분 UNIQUE 인덱스 생성 |
| `src/main/java/com/bank/deposit/domain/entity/Transaction.java` | `idempotencyKey` 필드 추가 |
| `src/main/java/com/bank/deposit/repository/TransactionRepository.java` | `findByIdempotencyKey(String)` 메서드 추가 |
| `src/main/java/com/bank/deposit/dto/request/TransferRequest.java` | `idempotencyKey` 필드 추가 |
| `src/main/java/com/bank/deposit/controller/TransactionController.java` | `req.idempotencyKey()` 서비스로 전달 |
| `src/main/java/com/bank/deposit/service/TransactionService.java` | 이체 시작 시 멱등성 키 조회, OUT 거래 저장 시 키 포함 |

---

---

## payment-service 연동 API (v1)

`docs/deposit-payment-api-spec.md` 명세를 기준으로 payment-service가 deposit-service를 직접 호출하는 서비스간 내부 API를 구현합니다.

### 추가된 엔드포인트

| Method | Path | 명세 | 설명 |
|---|---|---|---|
| `GET` | `/api/v1/accounts/{accountNo}` | A-1 | 계좌 상태·사기계좌 플래그 조회 |
| `GET` | `/api/v1/accounts/{accountNo}/holder` | A-2 | 예금주명·사망 여부 조회 |
| `GET` | `/api/v1/balances/{accountNo}` | B-1 | 출금 가능 잔액 조회 |
| `GET` | `/api/v1/limits/{accountNo}` | B-2 | 1회·일·월 이체 한도 조회 |
| `POST` | `/api/v1/balances/withdraw` | B-3 | 출금 처리 (멱등성 키 지원) |
| `POST` | `/api/v1/balances/deposit` | B-4 | 입금 처리 — 자행 이체 수신 시만 호출 (멱등성 키 지원) |
| `POST` | `/api/v1/balances/withdraw/cancel` | B-5 | 출금 취소 — Saga 보상 트랜잭션 (멱등성 키 지원) |

### 호출 흐름 예시 (자행 이체)

```
payment-service
  → GET  /api/v1/accounts/{fromAccountNo}   // A-1: ACTIVE 여부, fraudFlag 확인
  → GET  /api/v1/accounts/{toAccountNo}     // A-1: 수신 계좌 상태 확인
  → GET  /api/v1/accounts/{toAccountNo}/holder  // A-2: 예금주명 일치 확인
  → GET  /api/v1/balances/{fromAccountNo}   // B-1: availableBalance 확인
  → GET  /api/v1/limits/{fromAccountNo}     // B-2: 한도 확인
  → POST /api/v1/balances/withdraw          // B-3: 출금
  → POST /api/v1/balances/deposit           // B-4: 입금
       실패 시 →
  → POST /api/v1/balances/withdraw/cancel   // B-5: 출금 보상
```

### BalanceResponse — availableBalance 계산

```
availableBalance = balance - holdAmount
```

`holdAmount`는 지급 보류 중인 금액으로, 출금 가능 잔액 계산 시 반드시 차감합니다.

payment-service는 `availableBalance < 이체금액`이면 `INSUFFICIENT_BALANCE`로 처리합니다.

### 출금 가능 잔액 검증 수정 (Account.withdraw)

기존 `Account.withdraw()`는 `balance`만 비교해 `holdAmount`가 있어도 전액 출금이 가능했습니다.

```java
// 수정 전 — holdAmount 무시
if (this.balance.compareTo(amount) < 0) { throw ... }

// 수정 후 — holdAmount 차감 후 비교
BigDecimal available = this.balance.subtract(holdAmount);
if (available.compareTo(amount) < 0) { throw ... }
```

예시: balance 100만원, holdAmount 50만원인 계좌에서 80만원 출금 시도

| | 수정 전 | 수정 후 |
|---|---|---|
| 검증 통과 여부 | ✅ 통과 (100 ≥ 80) | ❌ 차단 (50 < 80) |
| 출금 후 availableBalance | -30만원 (버그) | — |

### DB 마이그레이션 (V17)

```sql
ALTER TABLE deposit_accounts
    ADD COLUMN IF NOT EXISTS fraud_flag  BOOLEAN        NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS hold_amount NUMERIC(18, 2) NOT NULL DEFAULT 0.00;
```

- `fraud_flag`: payment-service A-1 명세의 사기계좌 여부 필드. 기본값 `false`.
- `hold_amount`: 지급 보류 금액. 출금 가능 잔액 계산에 사용. 기본값 `0`.

### 관련 파일

| 파일 | 내용 |
|---|---|
| `src/main/java/com/bank/deposit/controller/v1/AccountV1Controller.java` | A-1, A-2 엔드포인트 |
| `src/main/java/com/bank/deposit/controller/v1/BalanceV1Controller.java` | B-1 ~ B-5 엔드포인트 |
| `src/main/java/com/bank/deposit/service/DepositV1Service.java` | v1 API 비즈니스 로직, 멱등성 처리 |
| `src/main/java/com/bank/deposit/dto/interservice/` | 요청·응답 DTO 10개 |
| `src/main/java/com/bank/deposit/domain/entity/Account.java` | `fraudFlag`, `holdAmount` 필드 추가 및 `withdraw()` 잔액 검증 수정 |
| `src/main/resources/db/migration/V17__add_fraud_flag_and_hold_amount.sql` | `fraud_flag`, `hold_amount` 컬럼 추가 |

---

## CORS 허용 출처 확장 (127.0.0.1 추가)

### 변경 배경

브라우저에서 `http://127.0.0.1:3001`로 웹 앱에 접속하면 deposit-service의 `/api/products/{id}` 호출이 **403 Forbidden**으로 차단되었습니다.

원인: `CorsConfig`의 `allowedOrigins`에 `localhost`만 허용하고 `127.0.0.1`은 누락되어 있었기 때문입니다. 브라우저는 `localhost`와 `127.0.0.1`을 별도 Origin으로 구분합니다.

### 증상

챗봇이 상품을 추천한 뒤 **가입하기** 버튼을 누르면 가입 페이지의 `fetchDepositProduct(productId)`가 CORS로 실패 → 상품 이름이 기본값(`AXful 정기예금`)으로 고정되는 버그가 발생했습니다.

### 변경 내용

`127.0.0.1:3000`, `127.0.0.1:3001`을 허용 출처로 추가했습니다.

```java
// 변경 전
.allowedOrigins("http://localhost:3000", "http://localhost:3001")

// 변경 후
.allowedOrigins(
    "http://localhost:3000", "http://localhost:3001",
    "http://127.0.0.1:3000", "http://127.0.0.1:3001"
)
```

### 관련 파일

| 파일 | 변경 내용 |
|---|---|
| `src/main/java/com/bank/deposit/config/CorsConfig.java` | `127.0.0.1:3000`, `127.0.0.1:3001` 허용 출처 추가 |

---

## 담당 범위 확인

이번 커밋에는 customer-service 변경을 포함하지 않습니다. 인증, 고객 서비스 담당 영역의 파일은 제외하고 deposit-service와 deposit 프론트 연동 파일만 포함합니다.
