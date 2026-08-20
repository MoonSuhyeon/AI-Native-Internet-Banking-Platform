# core-banking — 이체 도메인 API 명세서

> **`core-banking` 의 이체 도메인**(`com.bank.payment` 패키지) API 명세다.
>
> 예전에는 `payment-service` 라는 별도 서비스(8080)였다. `deposit-service` 와 함께
> `core-banking` 으로 합쳐졌다([결정 문서](decisions/core-banking-merge.md)).
>
> | | 예전 | 지금 |
> |---|---|---|
> | 서비스 | `payment-service` | `core-banking` |
> | 포트 | 8080 | **8082** |
> | 모듈 | `services/payment-service` | `services/core-banking` |
> | 수신계 호출 | HTTP (`DepositBalanceClient`) | 같은 프로세스 (`LocalLedgerAdapter`) |
>
> **엔드포인트 경로와 요청·응답 규약은 바뀌지 않았다.** 문서에서 두 서비스를
> *서로 호출하는 것처럼* 설명한 부분은 지금은 같은 프로세스 안의 호출이다.
> 경로 전체 목록은 [api-spec.md](api-spec.md) 가 소스에서 뽑아 유지한다.



이체(결제계) 서비스의 외부 노출 REST 엔드포인트 상세 명세. 컨트롤러·DTO·예외 핸들러 소스에서 추출해 정리한다.

> 결제계 ↔ deposit-service 내부 연동 규약은 별도 문서 [deposit-payment-api-spec.md](deposit-payment-api-spec.md) 참조.
> 엔드포인트 전체 목록은 [api-spec.md](api-spec.md) 참조.
> 결제계 도메인 설계 산출물(정책·enum·상태전이)은 `services/payment-service/docs/*.xlsx` 가 진실의 원천이다.

> ⚠️ **주의**: payment-service 는 다른 서비스와 응답 규약이 다르다.
> - 공통 `ApiResponse` envelope 를 **쓰지 않고** 응답 DTO 를 직접 반환한다.
> - 에러는 단순 `{ "error": "..." }` 맵을 쓴다(아래).

---

## 공통 사항

### 인증·인가

API Gateway 가 JWT 를 검증하고, payment-service 는 모든 요청을 `permitAll` 로 통과시킨다(게이트웨이 신뢰, Spring Security 역할 검사 없음). 신원은 헤더로 전달된다.

| 헤더 | 설명 | 사용 엔드포인트 |
|---|---|---|
| `X-User-Id` | 인증 사용자 ID(요청자/본인검증) | 즉시·예약·예약취소 |
| `X-Auth-Token-Id` | 인증 토큰 ID(2차 인증 스냅샷) | 즉시·예약 |
| `X-Idempotency-Key` | 멱등키. 재시도 시 직전 결과 반환 | 즉시·예약 |

> 멱등키는 결제계 책임으로 발급하며 형식은 `{API}-{거래ID}-{시도번호}`.

### 성공 응답

각 엔드포인트의 응답 DTO 를 **그대로** 반환한다(envelope 없음).
- 자행 동기 완결(`COMPLETED`/`FAILED`) → `200 OK`
- 타행 청산 대기(`CLEARING`) → `202 Accepted`

> `FAILED` 는 비즈니스 거절(잔액부족 등)도 정상 처리 결과이므로 HTTP `200` 으로 반환되고, 원인은 `failureCategory` 에 담긴다.

### 에러 응답

취소 계열 예외는 단순 맵으로 응답한다.

```json
{ "error": "사유 메시지" }
```

| HTTP | 예외 | 의미 |
|---|---|---|
| `400` | (검증) | 필수값 누락(`operatorId`/`reason` 공백), 예약시각 누락/과거 |
| `403` | `PaymentUnauthorizedException` | 본인(`X-User-Id`) 소유 건이 아님 |
| `404` | `PaymentNotFoundException` | 결제지시(PI) 없음 |
| `409` | `PaymentCancelConflictException` | 취소 가능 상태 아님 또는 claim 경합 |

> `200 OK` 응답이라도 본문 `status=FAILED` 면 비즈니스 거절이다. `failureCategory` 예: `INSUFFICIENT_BALANCE`, `KFTC_REJECTED`, `BOK_REJECTED`.

### 공통 enum 값

| 필드 | 값 |
|---|---|
| `status` (진행상태) | `COMPLETED` · `FAILED` · `CLEARING` · `SCHEDULED` · `CANCELLED` 등 (enum 상태전이도 v9 기준) |
| `channel` | `WEB` · `MOBILE` · `BRANCH` · `ATM` · `OPEN_BANKING` · `INBOUND` |

---

## 엔드포인트

`base: /api/v1/payments`

### `POST` `/api/v1/payments`

즉시 이체. 헤더(신원/멱등키) + 본문(이체 지시) → Orchestrator 처리. 자행 동기 완결 `200`, 타행 청산 대기 `202`.

**헤더**

| 이름 | 필수 |
|---|---|
| `X-Idempotency-Key` | O |
| `X-User-Id` | O |
| `X-Auth-Token-Id` | O |

**요청 본문**: `PaymentRequest`

| 필드 | 타입 | 설명 |
|---|---|---|
| `senderAccountId` | String | 출금 계좌 ID |
| `receiverBankCode` | String | 수취 은행코드 |
| `receiverAccountNo` | String | 수취 계좌번호 |
| `receiverHolderName` | String | 수취인명 |
| `transferAmount` | BigDecimal | 이체 금액 |
| `receiverMemo` | String | 받는분 통장 메모 |
| `senderMemo` | String | 내 통장 메모 |
| `channel` | String | 채널 (`MOBILE` 등) |
| `receiverPassbookSenderDisplay` | String | 받는분 통장에 표시할 보내는분 |

**응답**: `PaymentResponse` (`200` 완결 / `202` 청산대기)

| 필드 | 타입 | 설명 |
|---|---|---|
| `paymentInstructionId` | String | 결제지시 ID |
| `transactionNo` | String | 거래번호 |
| `status` | String | 진행상태 |
| `completedAt` | LocalDateTime | 완결 시각(미완결 시 null) |
| `failureCategory` | String | 실패 원인코드(`FAILED` 시), 정상 시 null |

---

### `POST` `/api/v1/payments/scheduled`

예약 이체 등록. 즉시이체 본문 + `scheduledExecutionAt`(필수, 현재 이후). 정상 등록 시 `200 OK`, `status=SCHEDULED`.

**헤더**: 즉시이체와 동일 (`X-Idempotency-Key`, `X-User-Id`, `X-Auth-Token-Id` 모두 필수)

**요청 본문**: `ScheduledPaymentRequest` (= `PaymentRequest` 전체 필드 + 아래)

| 필드 | 타입 | 설명 |
|---|---|---|
| `scheduledExecutionAt` | LocalDateTime | 예약 실행 시각. **null 또는 현재 이하면 `400`** (PI 미생성) |

**응답**: `PaymentResponse` — `status=SCHEDULED`

---

### `POST` `/api/v1/payments/scheduled/{piId}/cancel`

사용자 예약 취소. `SCHEDULED` 상태 PI 만 허용하며 본인(`X-User-Id`) 검증.

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `piId` | String |

**헤더**

| 이름 | 필수 |
|---|---|
| `X-User-Id` | O |

**요청 본문**: `CancelScheduledRequest` (선택, 생략 가능)

| 필드 | 타입 | 설명 |
|---|---|---|
| `reason` | String | 취소 사유(선택) |

**응답**: `PaymentResponse`
**오류**: `404`(PI 없음) · `403`(본인 아님) · `409`(SCHEDULED 아님/claim 경합)

---

### `POST` `/api/v1/payments/{piId}/operator-cancel`

운영자 강제 취소. `CLEARING` 상태 PI 만 허용. 강제 취소는 `CANCEL_REQUESTED` 경유(P-030).

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `piId` | String |

**요청 본문**: `OperatorCancelRequest`

| 필드 | 타입 | 설명 |
|---|---|---|
| `operatorId` | String | 운영자 ID. **공백이면 `400`** |
| `reason` | String | 취소 사유. **공백이면 `400`** |

**응답**: `PaymentResponse`
**오류**: `400`(operatorId/reason 공백) · `404`(PI 없음) · `409`(CLEARING 아님)

---

### `GET` `/api/v1/payments/inbound`

수신계좌 기준 입금(`COMPLETED`) 내역 조회. 다온(수취 화면) 전용. 인증 헤더 불필요(`permitAll`).

**Query 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `receiverAccountNo` | String | O |

**응답**: `List<InboundPaymentResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| `paymentInstructionId` | String | 결제지시 ID |
| `transactionNo` | String | 거래번호 |
| `transferAmount` | BigDecimal | 이체 금액 |
| `status` | String | 진행상태 |
| `requestedAt` | LocalDateTime | 요청 시각 |
| `completedAt` | LocalDateTime | 완결 시각 |
| `senderAccountNoSnap` | String | 보내는분 계좌번호(박제) |
| `receiverPassbookSenderDisplay` | String | 받는분 통장 표시 보내는분 |
| `receiverMemo` | String | 받는분 메모 |
