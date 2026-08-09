# Internet Banking — 전체 API 명세서

> ## ⚠️ 이 문서는 서비스 병합 전에 쓰였다
>
> `deposit-service` 와 `payment-service` 는 **`core-banking` 한 서비스로 합쳐졌다**
> ([결정 문서](decisions/core-banking-merge.md)). 자행이체를 로컬 트랜잭션으로 끝내기
> 위한 선택이고, 타행이체 Saga 는 그대로 남아 있다.
>
> **엔드포인트 경로는 바뀌지 않았다.** 이 문서의 요청·응답 규약은 지금도 유효하다.
> 달라진 것은 어디에 붙느냐다.
>
> | | 문서 기준 | 현재 |
> |---|---|---|
> | 서비스 | `deposit-service` / `payment-service` | **`core-banking`** |
> | 포트 | 8082 / 8080 | **8082** |
> | 모듈 | `services/deposit-service` / `services/payment-service` | `services/core-banking` (`com.bank.deposit` · `com.bank.payment` 패키지) |
>
> 문서에서 두 서비스를 <b>서로 호출하는 것처럼</b> 설명한 부분은 지금은 같은 프로세스
> 안의 호출이다. 특히 이체 잔액 반영은 HTTP 가 아니라 `LocalLedgerAdapter` 를 탄다.
>
> 본문 갱신은 [`OPEN_ITEMS.md`](OPEN_ITEMS.md) 에 남겨 둔 후속 작업이다.


> 전 서비스 REST 엔드포인트 통합 레퍼런스. 소스 컨트롤러에서 자동 추출 후 정리.
- **서비스 수**: 9
- **컨트롤러 수**: 118
- **엔드포인트 수**: 372

---

## 목차
- [Customer Service (고객·인증·인증서)](#customer-service) — 104개
- [Deposit Service (수신·계좌·예적금)](#deposit-service) — 91개
- [Payment Service (이체)](#payment-service) — 5개
- [Loan Service (여신·대출)](#loan-service) — 128개
- [Advisory Service (심사 자문 RAG)](#advisory-service) — 27개
- [Auto Loan Review (AI 자동심사)](#auto-loan-review) — 4개
- [Review AI Gateway (심사 AI 게이트웨이)](#review-ai-gateway) — 2개
- [Doc Agent (서류 제출·검토)](#doc-agent) — 6개
- [Master Service (공통코드)](#master-service) — 5개

---

<a id="customer-service"></a>

## Customer Service (고객·인증·인증서)

### AccountRecoveryController

`base: /api/v1/auth`

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/find-id` |
| `POST` | `/api/v1/auth/reset-password` |

### AuthMethodController

`base: /api/v1/customers/me/auth-methods`

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/auth-methods` |
| `DELETE` | `/api/v1/customers/me/auth-methods/{authMethodId}` |
| `PATCH` | `/api/v1/customers/me/auth-methods/{authMethodId}/alias` |
| `PATCH` | `/api/v1/customers/me/auth-methods/{authMethodId}/primary` |

### CertIssueController

`base: /api/v1/auth`

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/cert/issue` |
| `PUT` | `/api/v1/auth/cert/pin` |

### CertLoginController

`base: /api/v1/auth`

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/cert-login` |

### CertManageController

`base: /api/v1/cert/manage`

| Method | Path |
|---|---|
| `GET` | `/api/v1/cert/manage` |
| `PUT` | `/api/v1/cert/manage/pin` |
| `GET` | `/api/v1/cert/manage/{serialNumber}` |
| `DELETE` | `/api/v1/cert/manage/{serialNumber}` |

### CodeController

`base: /api/v1/codes`

| Method | Path |
|---|---|
| `GET` | `/api/v1/codes/{groupId}` |
| `GET` | `/api/v1/codes/{groupId}/all` |

### CustomerAccessLogController

`base: /api/v1/internal/customers` — 고객 조회 접근 감사로그 — 명시적 기록(연락처 열람 등)과 감사 화면 조회.

| Method | Path |
|---|---|
| `GET` | `/api/v1/internal/customers/access-logs` |
| `POST` | `/api/v1/internal/customers/{customerId}/access-log` |

### CustomerLifecycleController

`base: /api/v1`

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/grade-history` |
| `GET` | `/api/v1/customers/me/status-history` |
| `GET` | `/api/v1/internal/customers` |
| `GET` | `/api/v1/internal/customers/join-stats` |
| `GET` | `/api/v1/internal/customers/{customerId}` |
| `PATCH` | `/api/v1/internal/customers/{customerId}/close` |
| `PATCH` | `/api/v1/internal/customers/{customerId}/credit-rating` |
| `PATCH` | `/api/v1/internal/customers/{customerId}/dormant` |
| `PATCH` | `/api/v1/internal/customers/{customerId}/grade` |
| `PATCH` | `/api/v1/internal/customers/{customerId}/reactivate` |
| `PATCH` | `/api/v1/internal/customers/{customerId}/suspend` |

### FdsController

`base: /api/v1/internal/fds` — FDS 관리 API — 직원 전용.

| Method | Path |
|---|---|
| `GET` | `/api/v1/internal/fds/detections/pending` |
| `PATCH` | `/api/v1/internal/fds/detections/{detectionId}/confirm` |
| `PATCH` | `/api/v1/internal/fds/detections/{detectionId}/false-positive` |
| `POST` | `/api/v1/internal/fds/incidents` |
| `GET` | `/api/v1/internal/fds/incidents/open` |
| `PATCH` | `/api/v1/internal/fds/incidents/{incidentId}/close` |
| `PATCH` | `/api/v1/internal/fds/incidents/{incidentId}/report-fss` |
| `GET` | `/api/v1/internal/fds/rules` |
| `POST` | `/api/v1/internal/fds/rules` |
| `PATCH` | `/api/v1/internal/fds/rules/{ruleId}/activate` |
| `PATCH` | `/api/v1/internal/fds/rules/{ruleId}/deactivate` |

### InternalAuthEventsController

`base: /api/v1/internal/auth` — 인증 이벤트 조회 — 직원 전용 읽기 전용 내부 API.

| Method | Path |
|---|---|
| `GET` | `/api/v1/internal/auth/{customerId}/events` |

### LoginController

`base: /api/v1/auth`

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/login` |
| `POST` | `/api/v1/auth/refresh` |

### MobileAuthController

`base: /api/v1/mobile-auth`

| Method | Path |
|---|---|
| `POST` | `/api/v1/mobile-auth/send` |
| `POST` | `/api/v1/mobile-auth/verify` |

### MyPageController

`base: /api/v1/customers`

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me` |

### PartyController

`base: /api/v1`

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/roles` |
| `GET` | `/api/v1/internal/compliance/edd-pending` |
| `GET` | `/api/v1/internal/compliance/fatca-crs` |
| `GET` | `/api/v1/internal/compliance/kyc-expiring` |
| `GET` | `/api/v1/internal/compliance/sanctioned` |
| `GET` | `/api/v1/internal/compliance/screening-hits/pending` |
| `PATCH` | `/api/v1/internal/compliance/screening-hits/{hitId}/clear` |
| `PATCH` | `/api/v1/internal/compliance/screening-hits/{hitId}/confirm` |
| `GET` | `/api/v1/internal/party/duplicates/pending` |
| `PATCH` | `/api/v1/internal/party/duplicates/{caseId}/distinct` |
| `PATCH` | `/api/v1/internal/party/duplicates/{caseId}/duplicate` |
| `GET` | `/api/v1/internal/party/minors` |
| `GET` | `/api/v1/internal/party/relations/review-pending` |
| `PATCH` | `/api/v1/internal/party/relations/{relationId}/approve` |
| `PATCH` | `/api/v1/internal/party/relations/{relationId}/end` |
| `PATCH` | `/api/v1/internal/party/relations/{relationId}/reject` |
| `PATCH` | `/api/v1/internal/party/roles/{roleId}/close` |
| `POST` | `/api/v1/internal/party/{fromPartyId}/relations` |
| `GET` | `/api/v1/internal/party/{partyId}/compliance` |
| `PATCH` | `/api/v1/internal/party/{partyId}/compliance/aml-risk` |
| `PATCH` | `/api/v1/internal/party/{partyId}/compliance/kyc-complete` |
| `GET` | `/api/v1/internal/party/{partyId}/relations` |

### PersonInfoController

`base: /api/v1/customers/me`

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/foreigner-info` |
| `PUT` | `/api/v1/customers/me/foreigner-info/passport` |
| `PUT` | `/api/v1/customers/me/foreigner-info/stay` |
| `GET` | `/api/v1/customers/me/person-info` |
| `PUT` | `/api/v1/customers/me/person-info` |
| `GET` | `/api/v1/customers/me/tax-residencies` |
| `POST` | `/api/v1/customers/me/tax-residencies` |
| `DELETE` | `/api/v1/customers/me/tax-residencies/{taxResidencyId}` |

### PinController

`base: /api/v1`

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/pin-login` |
| `POST` | `/api/v1/customers/me/pin` |
| `DELETE` | `/api/v1/customers/me/pin` |

### QrCertController

`base: /api/v1/auth/qr-cert`

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/qr-cert/approve` |
| `POST` | `/api/v1/auth/qr-cert/generate` |
| `GET` | `/api/v1/auth/qr-cert/status` |

### QrLoginController

`base: /api/v1/auth/qr`

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/qr/approve` |
| `POST` | `/api/v1/auth/qr/generate` |
| `GET` | `/api/v1/auth/qr/status` |

### RegisterController

`base: /api/v1/auth`

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/register` |
| `POST` | `/api/v1/auth/register/corporate` |

### RegisteredDeviceController

`base: /api/v1/customers/me/devices`

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/devices` |
| `POST` | `/api/v1/customers/me/devices` |
| `DELETE` | `/api/v1/customers/me/devices/{deviceId}` |
| `PATCH` | `/api/v1/customers/me/devices/{deviceId}/designate` |
| `PATCH` | `/api/v1/customers/me/devices/{deviceId}/trust` |
| `PATCH` | `/api/v1/customers/me/devices/{deviceId}/untrust` |

### SettingsController

`base: /api/v1/customers/me`

| Method | Path |
|---|---|
| `PUT` | `/api/v1/customers/me/notification` |
| `PUT` | `/api/v1/customers/me/password` |
| `PUT` | `/api/v1/customers/me/profile` |
| `GET` | `/api/v1/customers/me/settings` |
| `POST` | `/api/v1/customers/me/withdraw` |
| `POST` | `/api/v1/customers/me/internet-banking/cancel` |

### TransferLimitController

`base: /api/v1/customers/me/transfer-limit`

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/transfer-limit` |
| `PATCH` | `/api/v1/customers/me/transfer-limit` |

### WithdrawalAccountController

`base: /api/v1/banking/withdrawal-accounts`

| Method | Path |
|---|---|
| `GET` | `/api/v1/banking/withdrawal-accounts` |
| `POST` | `/api/v1/banking/withdrawal-accounts` |
| `PUT` | `/api/v1/banking/withdrawal-accounts/order` |
| `DELETE` | `/api/v1/banking/withdrawal-accounts/{id}` |

<a id="deposit-service"></a>

## Deposit Service (수신·계좌·예적금)

### AccountController

`base: /accounts`

| Method | Path |
|---|---|
| `GET` | `/accounts` |
| `POST` | `/accounts` |
| `GET` | `/accounts/by-number/{accountNo}` |
| `GET` | `/accounts/{accountId}` |
| `PATCH` | `/accounts/{accountId}/alias` |
| `PATCH` | `/accounts/{accountId}/limits` |
| `PATCH` | `/accounts/{accountId}/status` |

### ContractController

`base: (루트)`

| Method | Path |
|---|---|
| `GET` | `/contracts` |
| `POST` | `/contracts` |
| `GET` | `/contracts/{contractId}` |
| `GET` | `/contracts/{contractId}/applied-rates` |
| `POST` | `/contracts/{contractId}/applied-rates` |
| `PATCH` | `/contracts/{contractId}/auto-transfer-day` |
| `GET` | `/contracts/{contractId}/deposit` |
| `POST` | `/contracts/{contractId}/deposit` |
| `PUT` | `/contracts/{contractId}/deposit` |
| `PATCH` | `/contracts/{contractId}/maturity` |
| `GET` | `/contracts/{contractId}/preferential-rates` |
| `POST` | `/contracts/{contractId}/preferential-rates` |
| `DELETE` | `/contracts/{contractId}/preferential-rates/{preferentialRateId}` |
| `GET` | `/contracts/{contractId}/special-terms` |
| `POST` | `/contracts/{contractId}/special-terms` |
| `PATCH` | `/contracts/{contractId}/status` |
| `PATCH` | `/contracts/{contractId}/terminate` |

### DepartmentController

`base: /departments`

| Method | Path |
|---|---|
| `GET` | `/departments` |
| `POST` | `/departments` |
| `GET` | `/departments/{departmentId}` |
| `PUT` | `/departments/{departmentId}` |
| `DELETE` | `/departments/{departmentId}` |

### HomeController

`base: (루트)`

| Method | Path |
|---|---|
| `GET` | `/` |

### InterestController

`base: (루트)`

| Method | Path |
|---|---|
| `GET` | `/contracts/{contractId}/interests` |
| `GET` | `/interests` |
| `POST` | `/interests/calculate` |
| `GET` | `/interests/{interestId}` |

### JoinTargetController

`base: /join-targets`

| Method | Path |
|---|---|
| `GET` | `/join-targets` |
| `POST` | `/join-targets` |

### PaymentScheduleController

`base: /payment-schedules`

| Method | Path |
|---|---|
| `GET` | `/payment-schedules/contracts/{contractId}` |
| `POST` | `/payment-schedules/contracts/{contractId}/generate` |
| `GET` | `/payment-schedules/contracts/{contractId}/status/{status}` |
| `POST` | `/payment-schedules/{scheduleId}/pay` |

### ProductController

`base: (루트)`

| Method | Path |
|---|---|
| `GET` | `/products` |
| `POST` | `/products` |
| `GET` | `/products/{productId:\\d+}` |
| `PUT` | `/products/{productId}` |
| `PATCH` | `/products/{productId}` |
| `GET` | `/products/{productId}/deposit` |
| `POST` | `/products/{productId}/deposit` |
| `PUT` | `/products/{productId}/deposit` |
| `DELETE` | `/products/{productId}/deposit` |
| `GET` | `/products/{productId}/interest-rates` |
| `POST` | `/products/{productId}/interest-rates` |
| `GET` | `/products/{productId}/interest-rates/{rateId}` |
| `PUT` | `/products/{productId}/interest-rates/{rateId}` |
| `PATCH` | `/products/{productId}/interest-rates/{rateId}/expire` |
| `GET` | `/products/{productId}/join-channels` |
| `POST` | `/products/{productId}/join-channels` |
| `DELETE` | `/products/{productId}/join-channels/{channelId}` |
| `GET` | `/products/{productId}/savings` |
| `POST` | `/products/{productId}/savings` |
| `PUT` | `/products/{productId}/savings` |
| `GET` | `/products/{productId}/special-terms` |
| `POST` | `/products/{productId}/special-terms` |
| `DELETE` | `/products/{productId}/special-terms/{specialTermId}` |
| `GET` | `/products/{productId}/subscription` |
| `POST` | `/products/{productId}/subscription` |
| `PUT` | `/products/{productId}/subscription` |
| `GET` | `/products/{productId}/target-groups` |
| `POST` | `/products/{productId}/target-groups` |
| `DELETE` | `/products/{productId}/target-groups/{targetGroupId}` |

### RecommendAgentController

`base: (루트)`

| Method | Path |
|---|---|
| `GET` | `/products/recommend-agent` |

### SpecialTermController

`base: /special-terms`

| Method | Path |
|---|---|
| `GET` | `/special-terms` |
| `POST` | `/special-terms` |
| `GET` | `/special-terms/{specialTermId}` |
| `PUT` | `/special-terms/{specialTermId}` |
| `PATCH` | `/special-terms/{specialTermId}/status` |

### SubscriptionPaymentRecognitionHistoryController

`base: /subscription-payment-histories`

| Method | Path |
|---|---|
| `GET` | `/subscription-payment-histories` |
| `GET` | `/subscription-payment-histories/{id}` |

### TargetGroupController

`base: /target-groups`

| Method | Path |
|---|---|
| `GET` | `/target-groups` |
| `POST` | `/target-groups` |
| `PUT` | `/target-groups/{id}` |

### TermApplicationManagementController

`base: /term-applications`

| Method | Path |
|---|---|
| `GET` | `/term-applications` |
| `POST` | `/term-applications` |
| `GET` | `/term-applications/{id}` |
| `DELETE` | `/term-applications/{id}` |

### TransactionController

`base: /transactions`

| Method | Path |
|---|---|
| `GET` | `/transactions` |
| `POST` | `/transactions/deposit` |
| `POST` | `/transactions/savings-payment` |
| `POST` | `/transactions/transfer` |
| `POST` | `/transactions/withdraw` |
| `GET` | `/transactions/{transactionId}` |
| `PATCH` | `/transactions/{transactionId}/cancel` |

<a id="payment-service"></a>

## Payment Service (이체)

### PaymentController

`base: /api/v1/payments` — 결제 API. POST /api/v1/payments.

| Method | Path |
|---|---|
| `POST` | `/api/v1/payments` |
| `GET` | `/api/v1/payments/inbound` |
| `POST` | `/api/v1/payments/scheduled` |
| `POST` | `/api/v1/payments/scheduled/{piId}/cancel` |
| `POST` | `/api/v1/payments/{piId}/operator-cancel` |

<a id="loan-service"></a>

## Loan Service (여신·대출)

### AccountingSummaryBatchController

`base: /api/internal/accounting-summary` — 일일 회계 요약 배치 트리거 (internal).

| Method | Path |
|---|---|
| `POST` | `/api/internal/accounting-summary/run` |

### ApplicationExpiryController

`base: /api/internal/application-expiry` — 승인 만료 일배치 트리거 (운영자/스케줄러용).

| Method | Path |
|---|---|
| `POST` | `/api/internal/application-expiry/run` |

### AuditLogController

`base: /api/audit`

| Method | Path |
|---|---|
| `GET` | `/api/audit/access-logs` |
| `GET` | `/api/audit/break-glass` |

### AutoDebitCallbackController

`base: /api/internal/auto-debit` — payment-service → loan-service CLEARING 완결 콜백 수신.

| Method | Path |
|---|---|
| `POST` | `/api/internal/auto-debit/payment-result` |

### AutoDebitController

`base: /api/internal/auto-debit` — 자동이체 배치 트리거 (운영자/스케줄러용). 실제 운영에서는 매일 새벽 외부 스케줄러가 호출.

| Method | Path |
|---|---|
| `POST` | `/api/internal/auto-debit/run` |

### BiasResultCallbackController

`base: /api/loans/reviews` — review-ai-gateway → loan-service 편향 검증 결과 콜백 수신.

| Method | Path |
|---|---|
| `POST` | `/api/loans/reviews/{revId}/bias-result` |

### BreakGlassController

`base: /api/break-glass`

| Method | Path |
|---|---|
| `POST` | `/api/break-glass` |

### BusinessCalendarController

`base: /api/business-calendar`

| Method | Path |
|---|---|
| `GET` | `/api/business-calendar` |
| `POST` | `/api/business-calendar` |
| `GET` | `/api/business-calendar/by-date` |
| `GET` | `/api/business-calendar/check` |
| `PUT` | `/api/business-calendar/{calId}` |
| `DELETE` | `/api/business-calendar/{calId}` |

### CalendarSeederController

`base: /api/internal/calendar-seeder` — 영업일 캘린더 시드 트리거 (internal).

| Method | Path |
|---|---|
| `POST` | `/api/internal/calendar-seeder/run` |

### CollateralController

`base: /api/loan-applications/{applId}/collaterals`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/collaterals` |
| `POST` | `/api/loan-applications/{applId}/collaterals` |

### CollateralDirectController

`base: /api/collaterals` — 담보 ID 기반 직접 접근 엔드포인트. 수정·해제 등 신청 경로 없이 colId 로 식별.

| Method | Path |
|---|---|
| `PATCH` | `/api/collaterals/{colId}` |
| `POST` | `/api/collaterals/{colId}/evaluations` |
| `POST` | `/api/collaterals/{colId}/release` |

### CommonSyncDispatchController

`base: /api/internal/common-sync` — common_db 동기화 디스패치 + 백필 운영 엔드포인트 (internal).

| Method | Path |
|---|---|
| `POST` | `/api/internal/common-sync/backfill/contracts` |
| `POST` | `/api/internal/common-sync/backfill/products` |
| `POST` | `/api/internal/common-sync/dispatch` |

### CreditConsentController

`base: /api/loan-applications/{applId}/credit-consents`

| Method | Path |
|---|---|
| `POST` | `/api/loan-applications/{applId}/credit-consents` |

### CreditEvaluationController

`base: /api/loan-applications/{applId}/credit-evaluation`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/credit-evaluation` |
| `POST` | `/api/loan-applications/{applId}/credit-evaluation` |

### CreditInfoReportController

`base: /api/loan-contracts/{cntrId}/credit-info-reports`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/credit-info-reports` |
| `POST` | `/api/loan-contracts/{cntrId}/credit-info-reports` |

### CreditInfoReportDirectController

`base: /api/credit-info-reports` — 신고 ID 기반 직접 접근. 계약 경로 없이 crptId 단건 조회.

| Method | Path |
|---|---|
| `GET` | `/api/credit-info-reports` |
| `GET` | `/api/credit-info-reports/{crptId}` |
| `POST` | `/api/credit-info-reports/{crptId}/ack` |
| `POST` | `/api/credit-info-reports/{crptId}/retry` |

### CreditInfoReportDispatchController

`base: /api/internal/credit-info-reports` — 신용정보 신고 outbox 디스패치 트리거 (internal).

| Method | Path |
|---|---|
| `POST` | `/api/internal/credit-info-reports/dispatch` |

### CreditScorePreviewController

`base: /api/credit-score`

| Method | Path |
|---|---|
| `POST` | `/api/credit-score/preview` |

### DelinquencyController

`base: /api/loan-contracts/{cntrId}/delinquency`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/delinquency` |
| `GET` | `/api/loan-contracts/{cntrId}/delinquency/snapshots` |

### DelinquencyRolloverController

`base: /api/internal/delinquency` — 연체 일배치 트리거 (internal). 보통 매일 새벽 자동이체 직후 호출된다.

| Method | Path |
|---|---|
| `POST` | `/api/internal/delinquency/rollover` |

### DsrCalculationController

`base: /api/loan-applications/{applId}/dsr-calculation`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/dsr-calculation` |
| `POST` | `/api/loan-applications/{applId}/dsr-calculation` |

### EclCalculationBatchController

`base: /api/internal/ecl`

| Method | Path |
|---|---|
| `POST` | `/api/internal/ecl/run` |

### EodBatchController

`base: /api/internal/eod`

| Method | Path |
|---|---|
| `GET` | `/api/internal/eod/history` |
| `POST` | `/api/internal/eod/restart` |
| `POST` | `/api/internal/eod/run` |

### EomBatchController

`base: /api/internal/eom`

| Method | Path |
|---|---|
| `POST` | `/api/internal/eom/run` |

### GuaranteeInsuranceController

`base: /api/loan-contracts/{cntrId}/guarantee-insurance`

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/guarantee-insurance` |
| `GET` | `/api/loan-contracts/{cntrId}/guarantee-insurance/{ginsId}` |
| `POST` | `/api/loan-contracts/{cntrId}/guarantee-insurance/{ginsId}/cancel` |

### GuaranteeInsuranceExpiryController

`base: /api/internal/guarantee-insurance-expiry` — 보증보험 만기 일배치 트리거 (운영자/스케줄러용).

| Method | Path |
|---|---|
| `POST` | `/api/internal/guarantee-insurance-expiry/run` |

### GuarantorAgreementController

`base: /api/loan-applications/{applId}/guarantor-agreements`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/guarantor-agreements` |
| `POST` | `/api/loan-applications/{applId}/guarantor-agreements` |
| `POST` | `/api/loan-applications/{applId}/guarantor-agreements/{gagrId}/cancel` |
| `POST` | `/api/loan-applications/{applId}/guarantor-agreements/{gagrId}/sign` |

### InterestAccrualBatchController

`base: /api/internal/interest-accrual`

| Method | Path |
|---|---|
| `POST` | `/api/internal/interest-accrual/run` |

### InterestAccrualController

`base: /api/loan-contracts/{cntrId}/interest-accruals`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/interest-accruals` |

### InternalReviewBatchController

`base: /api/internal/loan-reviews`

| Method | Path |
|---|---|
| `POST` | `/api/internal/loan-reviews/expire-bias-reviewing` |
| `POST` | `/api/internal/loan-reviews/expire-pending` |
| `POST` | `/api/internal/loan-reviews/expire-pending-approver` |
| `POST` | `/api/internal/loan-reviews/{revId}/bias-ops-note` |

### LoanApplicationController

`base: /api/loan-applications`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications` |
| `POST` | `/api/loan-applications` |
| `GET` | `/api/loan-applications/{applId}` |
| `POST` | `/api/loan-applications/{applId}/cancel` |

### LoanApplicationJourneyController

`base: /api/loan-applications/{applId}/journey`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/journey` |

### LoanCertificateController

`base: /api/loan-contracts/{cntrId}/certificates`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/certificates` |
| `POST` | `/api/loan-contracts/{cntrId}/certificates` |

### LoanCertificateDirectController

`base: /api/loan-certificates` — 증명서 ID 기반 직접 접근. 계약 경로 없이 certId 단건 조회.

| Method | Path |
|---|---|
| `GET` | `/api/loan-certificates/{certId}` |

### LoanClosureController

`base: /api/loan-contracts/{cntrId}/closure`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/closure` |
| `POST` | `/api/loan-contracts/{cntrId}/closure` |

### LoanContractAdminController

`base: /api/admin/loan-contracts`

| Method | Path |
|---|---|
| `GET` | `/api/admin/loan-contracts` |

### LoanContractController

`base: /api/loan-contracts`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts` |
| `POST` | `/api/loan-contracts` |
| `GET` | `/api/loan-contracts/{cntrId}` |

### LoanDocumentController

`base: /api/loan-applications/{applId}/documents`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/documents` |
| `POST` | `/api/loan-applications/{applId}/documents` |

### LoanDocumentDirectController

`base: /api/loan-documents`

| Method | Path |
|---|---|
| `DELETE` | `/api/loan-documents/{docId}` |

### LoanExecutionController

`base: /api/loan-contracts/{cntrId}/executions`

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/executions` |

### LoanIdentityVerificationController

`base: /api/loan-applications/{applId}/identity-verifications`

| Method | Path |
|---|---|
| `POST` | `/api/loan-applications/{applId}/identity-verifications` |
| `GET` | `/api/loan-applications/{applId}/identity-verifications/{idvId}` |

### LoanPrescreeningController

`base: /api/loan-applications/{applId}/prescreening`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/prescreening` |
| `POST` | `/api/loan-applications/{applId}/prescreening` |

### LoanProductController

`base: /api/loan-products`

| Method | Path |
|---|---|
| `GET` | `/api/loan-products` |
| `POST` | `/api/loan-products` |
| `GET` | `/api/loan-products/{prodId}` |
| `PATCH` | `/api/loan-products/{prodId}` |
| `POST` | `/api/loan-products/{prodId}/discontinue` |

### LoanReviewBiasReportController

`base: (루트)`

| Method | Path |
|---|---|
| `POST` | `/api/internal/loan-reviews/{revId}/bias-report` |
| `GET` | `/api/loan-reviews/{revId}/advices` |
| `GET` | `/api/loan-reviews/{revId}/advisory-reports` |
| `POST` | `/api/loan-reviews/{revId}/bias-override` |

### LoanReviewController

`base: /api/loan-applications/{applId}/review`

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/review` |
| `POST` | `/api/loan-applications/{applId}/review` |
| `PATCH` | `/api/loan-applications/{applId}/review` |
| `POST` | `/api/loan-applications/{applId}/review/acknowledge-bias` |
| `POST` | `/api/loan-applications/{applId}/review/approver-approve` |
| `POST` | `/api/loan-applications/{applId}/review/auto-decide` |
| `POST` | `/api/loan-applications/{applId}/review/confirm` |
| `POST` | `/api/loan-applications/{applId}/review/escalate-to-hq` |

### LoanStatusHistoryController

`base: /api/status-history`

| Method | Path |
|---|---|
| `GET` | `/api/status-history` |

### LtvCalculationController

`base: /api/collaterals/{colId}/ltv-calculation`

| Method | Path |
|---|---|
| `GET` | `/api/collaterals/{colId}/ltv-calculation` |
| `POST` | `/api/collaterals/{colId}/ltv-calculation` |

### MaturityBatchController

`base: /api/internal/maturity` — 만기 도래 일배치 트리거 (internal).

| Method | Path |
|---|---|
| `POST` | `/api/internal/maturity/run` |

### MaturityController

`base: /api/loan-contracts/{cntrId}/maturity`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/maturity` |
| `POST` | `/api/loan-contracts/{cntrId}/maturity/extend` |

### NotificationDispatchController

`base: /api/internal/notifications` — 알림 outbox 디스패치 트리거 (internal).

| Method | Path |
|---|---|
| `POST` | `/api/internal/notifications/dispatch` |

### NotificationOutboxController

`base: /api/notifications` — 운영자용 알림 outbox 조회·재전송 엔드포인트.

| Method | Path |
|---|---|
| `GET` | `/api/notifications` |
| `GET` | `/api/notifications/{outboxId}` |
| `POST` | `/api/notifications/{outboxId}/retry` |

### PartialRepaymentController

`base: /api/loan-contracts/{cntrId}/repayments/partial`

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/repayments/partial` |

### PendingReviewController

`base: /api/loan-reviews`

| Method | Path |
|---|---|
| `GET` | `/api/loan-reviews/escalated` |
| `GET` | `/api/loan-reviews/pending` |
| `GET` | `/api/loan-reviews/pending-approver` |
| `GET` | `/api/loan-reviews/stats` |

### PreferentialRatePolicyController

`base: /api/loan-products/{prodId}/preferential-rate-policies`

| Method | Path |
|---|---|
| `GET` | `/api/loan-products/{prodId}/preferential-rate-policies` |
| `POST` | `/api/loan-products/{prodId}/preferential-rate-policies` |

### PrepaymentController

`base: /api/loan-contracts/{cntrId}/prepayments`

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/prepayments` |

### RateChangeController

`base: /api/loan-contracts/{cntrId}/rate-changes`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/rate-changes` |
| `POST` | `/api/loan-contracts/{cntrId}/rate-changes` |

### RepaymentAccountController

`base: /api/loan-contracts/{cntrId}/repayment-account`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/repayment-account` |
| `POST` | `/api/loan-contracts/{cntrId}/repayment-account` |
| `POST` | `/api/loan-contracts/{cntrId}/repayment-account/verify` |

### RepaymentController

`base: /api/loan-contracts/{cntrId}/repayments`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/repayments` |
| `POST` | `/api/loan-contracts/{cntrId}/repayments` |
| `POST` | `/api/loan-contracts/{cntrId}/repayments/online` |

### RepaymentScheduleController

`base: /api/loan-contracts/{cntrId}/repayment-schedules`

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/repayment-schedules` |

### ReversalController

`base: /api/loan-contracts/{cntrId}/repayments/{rtxId}/reversal`

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/repayments/{rtxId}/reversal` |

### ReviewCheckLogController

`base: /api/loan-reviews/{revId}/checks`

| Method | Path |
|---|---|
| `GET` | `/api/loan-reviews/{revId}/checks` |
| `POST` | `/api/loan-reviews/{revId}/checks` |

### VirtualAccountController

`base: /api/loan-contracts/{cntrId}/virtual-account` — 대출 상환용 가상계좌.

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/virtual-account` |

<a id="advisory-service"></a>

## Advisory Service (심사 자문 RAG)

### AdvisoryRagController

`base: /api/advisory/reports` — RAG 외부 API (plan §11.5 — Task 6-8).

| Method | Path |
|---|---|
| `GET` | `/api/advisory/reports/{advrId}/citations` |
| `GET` | `/api/advisory/reports/{advrId}/similar-cases` |

### AdvisoryReportController

`base: /api/advisory/reports`

| Method | Path |
|---|---|
| `GET` | `/api/advisory/reports` |
| `GET` | `/api/advisory/reports/{advrId}` |
| `POST` | `/api/advisory/reports/{advrId}/ack` |
| `POST` | `/api/advisory/reports/{advrId}/view` |

### AdvisoryRuleController

`base: /api/advisory/rules`

| Method | Path |
|---|---|
| `GET` | `/api/advisory/rules` |
| `PUT` | `/api/advisory/rules/{ruleId}` |

### AdvisoryStatsController

`base: /api/advisory/stats`

| Method | Path |
|---|---|
| `GET` | `/api/advisory/stats/reviewers/{reviewerId}` |

### AuditOpinionController

`base: /api/advisory/audit`

| Method | Path |
|---|---|
| `GET` | `/api/advisory/audit/opinions/by-report/{advrId}` |
| `GET` | `/api/advisory/audit/opinions/by-reviewer/{reviewerId}` |
| `GET` | `/api/advisory/audit/opinions/recent` |
| `GET` | `/api/advisory/audit/quarantine` |
| `GET` | `/api/advisory/audit/risk-scores/top/bias` |
| `GET` | `/api/advisory/audit/risk-scores/top/compliance` |
| `GET` | `/api/advisory/audit/risk-scores/{reviewerId}` |

### InternalAdvisoryBatchController

`base: /api/internal/advisory`

| Method | Path |
|---|---|
| `POST` | `/api/internal/advisory/batch-evaluate` |
| `POST` | `/api/internal/advisory/snapshot` |

### InternalAdvisoryRagController

`base: /api/internal/advisory` — RAG 내부 관리 API (plan §11.5 — Task 6-8).

| Method | Path |
|---|---|
| `GET` | `/api/internal/advisory/documents` |
| `POST` | `/api/internal/advisory/documents` |
| `PUT` | `/api/internal/advisory/documents/{docId}/activate` |
| `POST` | `/api/internal/advisory/index/cases` |
| `POST` | `/api/internal/advisory/rag/case-index/backfill` |

### InternalAdvisoryToolController

`base: /api/internal/advisory`

| Method | Path |
|---|---|
| `GET` | `/api/internal/advisory/cohort-stats` |
| `GET` | `/api/internal/advisory/policy-citations` |
| `GET` | `/api/internal/advisory/reviewer-history` |
| `GET` | `/api/internal/advisory/similar-cases` |

<a id="auto-loan-review"></a>

## Auto Loan Review (AI 자동심사)

### AutoReviewController

`base: /api/ai`

| Method | Path |
|---|---|
| `POST` | `/api/ai/auto-review` |
| `POST` | `/api/ai/auto-review/evaluate` |

### EmbeddingBatchController

`base: /api/internal/embeddings` — 내부 임베딩 배치 적재 엔드포인트 — D3-1.

| Method | Path |
|---|---|
| `POST` | `/api/internal/embeddings/batch` |

### HealthController

`base: (루트)`

| Method | Path |
|---|---|
| `GET` | `/health` |

<a id="review-ai-gateway"></a>

## Review AI Gateway (심사 AI 게이트웨이)

### AuditAnalysisController

`base: /internal/audit`

| Method | Path |
|---|---|
| `POST` | `/internal/audit/analyze` |

### HealthController

`base: /internal`

| Method | Path |
|---|---|
| `GET` | `/internal/ping` |

<a id="doc-agent"></a>

## Doc Agent (서류 제출·검토)

### DocumentSubmissionController

`base: /api/documents`

| Method | Path |
|---|---|
| `POST` | `/api/documents/submit` |

### HealthController

`base: (루트)`

| Method | Path |
|---|---|
| `GET` | `/health` |

### HumanReviewController

`base: /api/documents`

| Method | Path |
|---|---|
| `GET` | `/api/documents/queue` |
| `POST` | `/api/documents/{submissionId}/review` |

### LegalHoldController

`base: /api/documents`

| Method | Path |
|---|---|
| `PATCH` | `/api/documents/{submissionId}/legal-hold/disable` |
| `PATCH` | `/api/documents/{submissionId}/legal-hold/enable` |

<a id="master-service"></a>

## Master Service (공통코드)

### CodeMasterController

`base: /api/codes`

| Method | Path |
|---|---|
| `GET` | `/api/codes` |
| `POST` | `/api/codes` |
| `PUT` | `/api/codes/{codeId}` |
| `DELETE` | `/api/codes/{codeId}` |
| `GET` | `/api/codes/{groupCd}/{codeCd}` |
