# Internet Banking — 전체 API 명세서

> **이 문서는 `scripts/extract_api_spec.py` 가 소스에서 뽑아 쓴다.**
> 손으로 고치지 말고 스크립트를 돌린다 — 예전에 손으로 관리하다가 서비스 병합을
> 반영하지 못해, 문서를 보고 붙이면 **없는 서비스를 부르게** 되는 상태였다.
>
> ```
> python scripts/extract_api_spec.py          # 다시 쓴다
> python scripts/extract_api_spec.py --check  # 어긋나면 실패한다
> ```
>
> **경로 인벤토리이지 계약서가 아니다.** 요청·응답 규약은 서비스별 문서가 맡는다:
> [customer](customer-service-api-spec.md) ·
> core-banking [수신](core-banking-deposit-api-spec.md)·[이체](core-banking-payment-api-spec.md) ·
> [loan](loan-service-api-spec.md) · [소규모 서비스](misc-services-api-spec.md).
>
> 정규식으로 읽으므로 상수로 조립한 경로와 런타임 등록 라우트는 못 본다.

## 지금의 서비스 구성

| 서비스 | 포트 | 비고 |
|---|---|---|
| `api-gateway` | 8088(로컬) / 8080 | 유일한 외부 진입점. JWT 검증 후 신원 헤더를 주입한다 |
| `customer-service` | 8081 | 고객·인증·인증서·뱅킹 편의기능 |
| `core-banking` | 8082 | **`deposit-service` + `payment-service` 병합** ([결정](decisions/core-banking-merge.md)). `context-path: /api` |
| `loan-service` | 8083 | **`advisory-service` 를 포함한다** — 별도 서비스가 아니다 |
| `fds-detector` | — | 결제 이벤트 소비. 조사 에이전트와 분리 |
| 사이드카(상담·조사·서류·자동심사 등) | 8087·8090 등 | 호스트 포트를 열지 않는다. 게이트웨이 경유 |

> 예전 문서가 소개하던 `deposit-service`·`payment-service`·`advisory-service`·
> `master-service` 는 **지금 없다.** 앞의 셋은 병합됐고 `master-service` 는 레포에
> 존재한 적이 없다.

- **서비스 수**: 11
- **컨트롤러 수**: 135
- **엔드포인트 수**: 466

---

## 목차
- [Customer Service (고객·인증·인증서)](#customer-service) — 120개
- [Core Banking (수신·계좌·예적금·이체)](#core-banking) — 114개
- [Loan Service (여신·대출·심사자문)](#loan-service) — 159개
- [FDS Detector (이상거래 탐지)](#fds-detector) — 1개
- [Auto Loan Review (AI 자동심사)](#auto-loan-review) — 4개
- [Review AI Gateway (심사 AI 게이트웨이)](#review-ai-gateway) — 2개
- [Doc Agent (서류 제출·검토)](#doc-agent) — 6개
- [Consultation (상담·챗봇)](#consultation) — 27개
- [Fraud Investigation (조사 에이전트)](#fraud-investigation) — 7개
- [Goal Agent (목표 상담)](#goal-agent) — 23개
- [Inference Server (모델 추론)](#inference-server) — 3개

---

<a id="customer-service"></a>

## Customer Service (고객·인증·인증서)

### AccountRecoveryController

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/find-id` |
| `POST` | `/api/v1/auth/reset-password` |

### AuthMethodController

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/auth-methods` |
| `DELETE` | `/api/v1/customers/me/auth-methods/{authMethodId}` |
| `PATCH` | `/api/v1/customers/me/auth-methods/{authMethodId}/alias` |
| `PATCH` | `/api/v1/customers/me/auth-methods/{authMethodId}/primary` |

### BranchController

| Method | Path |
|---|---|
| `GET` | `/api/v1/branches` |
| `GET` | `/api/v1/branches/reservations` |
| `POST` | `/api/v1/branches/reservations` |
| `DELETE` | `/api/v1/branches/reservations/{reservationId}` |

### CertIssueController

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/cert/issue` |
| `PUT` | `/api/v1/auth/cert/pin` |

### CertLoginController

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/cert-login` |

### CertManageController

| Method | Path |
|---|---|
| `GET` | `/api/v1/cert/manage` |
| `PUT` | `/api/v1/cert/manage/pin` |
| `DELETE` | `/api/v1/cert/manage/{serialNumber}` |
| `GET` | `/api/v1/cert/manage/{serialNumber}` |

### CustomerAccessLogController

| Method | Path |
|---|---|
| `GET` | `/api/v1/internal/customers/access-logs` |
| `POST` | `/api/v1/internal/customers/{customerId}/access-log` |

### CustomerLifecycleController

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

### DepositAlertController

| Method | Path |
|---|---|
| `GET` | `/api/v1/notifications/deposit-alerts` |
| `POST` | `/api/v1/notifications/deposit-alerts` |
| `DELETE` | `/api/v1/notifications/deposit-alerts/{subscriptionId}` |

### FavoriteTransferController

| Method | Path |
|---|---|
| `GET` | `/api/v1/banking/favorites` |
| `POST` | `/api/v1/banking/favorites` |
| `PUT` | `/api/v1/banking/favorites/order` |
| `DELETE` | `/api/v1/banking/favorites/{id}` |

### FdsController

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

### InternalAudienceController

| Method | Path |
|---|---|
| `GET` | `/api/v1/internal/audience/{customerId}` |

### InternalAuthEventsController

| Method | Path |
|---|---|
| `GET` | `/api/v1/internal/auth/{customerId}/events` |

### InternalHolderController

| Method | Path |
|---|---|
| `GET` | `/api/internal/customers/{customerId}/holder-info` |

### InternalTransferLimitController

| Method | Path |
|---|---|
| `GET` | `/api/internal/customers/{customerId}/transfer-limit` |

### LoginController

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/login` |
| `POST` | `/api/v1/auth/refresh` |

### MobileAuthController

| Method | Path |
|---|---|
| `POST` | `/api/v1/mobile-auth/send` |
| `POST` | `/api/v1/mobile-auth/verify` |

### MyPageController

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me` |

### NotificationController

| Method | Path |
|---|---|
| `GET` | `/api/v1/notifications` |
| `POST` | `/api/v1/notifications/{notificationId}/read` |

### PartyController

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

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/pin-login` |
| `DELETE` | `/api/v1/customers/me/pin` |
| `POST` | `/api/v1/customers/me/pin` |

### QrCertController

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/qr-cert/approve` |
| `POST` | `/api/v1/auth/qr-cert/generate` |
| `GET` | `/api/v1/auth/qr-cert/status` |

### QrLoginController

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/qr/approve` |
| `POST` | `/api/v1/auth/qr/generate` |
| `GET` | `/api/v1/auth/qr/status` |

### RegisterController

| Method | Path |
|---|---|
| `POST` | `/api/v1/auth/register` |
| `POST` | `/api/v1/auth/register/corporate` |

### RegisteredDeviceController

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/devices` |
| `POST` | `/api/v1/customers/me/devices` |
| `DELETE` | `/api/v1/customers/me/devices/{deviceId}` |
| `PATCH` | `/api/v1/customers/me/devices/{deviceId}/designate` |
| `PATCH` | `/api/v1/customers/me/devices/{deviceId}/trust` |
| `PATCH` | `/api/v1/customers/me/devices/{deviceId}/untrust` |

### SettingsController

| Method | Path |
|---|---|
| `POST` | `/api/v1/customers/me/internet-banking/cancel` |
| `PUT` | `/api/v1/customers/me/notification` |
| `PUT` | `/api/v1/customers/me/password` |
| `PUT` | `/api/v1/customers/me/profile` |
| `GET` | `/api/v1/customers/me/settings` |
| `POST` | `/api/v1/customers/me/withdraw` |

### TransactionApprovalController

| Method | Path |
|---|---|
| `POST` | `/api/internal/transaction-approvals/verify` |
| `POST` | `/api/v1/customers/me/transaction-approvals` |

### TransferLimitController

| Method | Path |
|---|---|
| `GET` | `/api/v1/customers/me/transfer-limit` |
| `PATCH` | `/api/v1/customers/me/transfer-limit` |

### WithdrawalAccountController

| Method | Path |
|---|---|
| `GET` | `/api/v1/banking/withdrawal-accounts` |
| `POST` | `/api/v1/banking/withdrawal-accounts` |
| `PUT` | `/api/v1/banking/withdrawal-accounts/order` |
| `DELETE` | `/api/v1/banking/withdrawal-accounts/{id}` |

---

<a id="core-banking"></a>

## Core Banking (수신·계좌·예적금·이체)

### AccountController

| Method | Path |
|---|---|
| `GET` | `/api/accounts` |
| `POST` | `/api/accounts` |
| `GET` | `/api/accounts/by-number/{accountNo}` |
| `GET` | `/api/accounts/{accountId}` |
| `PATCH` | `/api/accounts/{accountId}/alias` |
| `PATCH` | `/api/accounts/{accountId}/limits` |
| `PATCH` | `/api/accounts/{accountId}/status` |
| `GET` | `/api/accounts/{accountId}/termination-estimate` |

### AccountV1Controller

| Method | Path |
|---|---|
| `GET` | `/api/v1/accounts/{accountNo}` |
| `GET` | `/api/v1/accounts/{accountNo}/holder` |

### BalanceV1Controller

| Method | Path |
|---|---|
| `POST` | `/api/v1/balances/deposit` |
| `POST` | `/api/v1/balances/withdraw` |
| `POST` | `/api/v1/balances/withdraw/cancel` |
| `GET` | `/api/v1/balances/{accountNo}` |
| `GET` | `/api/v1/limits/{accountNo}` |

### ContractController

| Method | Path |
|---|---|
| `GET` | `/api/contracts` |
| `POST` | `/api/contracts` |
| `GET` | `/api/contracts/{contractId}` |
| `GET` | `/api/contracts/{contractId}/applied-rates` |
| `POST` | `/api/contracts/{contractId}/applied-rates` |
| `PATCH` | `/api/contracts/{contractId}/auto-transfer-day` |
| `GET` | `/api/contracts/{contractId}/deposit` |
| `POST` | `/api/contracts/{contractId}/deposit` |
| `PUT` | `/api/contracts/{contractId}/deposit` |
| `PATCH` | `/api/contracts/{contractId}/maturity` |
| `GET` | `/api/contracts/{contractId}/preferential-rates` |
| `POST` | `/api/contracts/{contractId}/preferential-rates` |
| `DELETE` | `/api/contracts/{contractId}/preferential-rates/{preferentialRateId}` |
| `GET` | `/api/contracts/{contractId}/special-terms` |
| `POST` | `/api/contracts/{contractId}/special-terms` |
| `PATCH` | `/api/contracts/{contractId}/status` |
| `PATCH` | `/api/contracts/{contractId}/terminate` |

### DepartmentController

| Method | Path |
|---|---|
| `GET` | `/api/departments` |
| `POST` | `/api/departments` |
| `DELETE` | `/api/departments/{departmentId}` |
| `GET` | `/api/departments/{departmentId}` |
| `PUT` | `/api/departments/{departmentId}` |

### HomeController

| Method | Path |
|---|---|
| `GET` | `/api/` |

### InterestController

| Method | Path |
|---|---|
| `GET` | `/api/contracts/{contractId}/interests` |
| `GET` | `/api/interests` |
| `POST` | `/api/interests/calculate` |
| `GET` | `/api/interests/income-statement` |
| `GET` | `/api/interests/{interestId}` |

### InternalPaymentController

| Method | Path |
|---|---|
| `GET` | `/api/v1/internal/payments/{paymentInstructionId}` |

### InternalReconciliationController

| Method | Path |
|---|---|
| `GET` | `/api/v1/internal/reconciliation/breaks` |
| `POST` | `/api/v1/internal/reconciliation/run` |

### JoinTargetController

| Method | Path |
|---|---|
| `GET` | `/api/join-targets` |
| `POST` | `/api/join-targets` |

### PaymentController

| Method | Path |
|---|---|
| `POST` | `/api/v1/payments` |
| `GET` | `/api/v1/payments/inbound` |
| `POST` | `/api/v1/payments/scheduled` |
| `POST` | `/api/v1/payments/scheduled/{piId}/cancel` |
| `POST` | `/api/v1/payments/{piId}/operator-cancel` |

### PaymentScheduleController

| Method | Path |
|---|---|
| `GET` | `/api/payment-schedules/accounts/{accountId}` |
| `GET` | `/api/payment-schedules/contracts/{contractId}` |
| `POST` | `/api/payment-schedules/contracts/{contractId}/generate` |
| `GET` | `/api/payment-schedules/contracts/{contractId}/status/{status}` |
| `POST` | `/api/payment-schedules/{scheduleId}/pay` |

### ProductController

| Method | Path |
|---|---|
| `GET` | `/api/products` |
| `POST` | `/api/products` |
| `GET` | `/api/products/{productId:\\d+}` |
| `PATCH` | `/api/products/{productId}` |
| `PUT` | `/api/products/{productId}` |
| `DELETE` | `/api/products/{productId}/deposit` |
| `GET` | `/api/products/{productId}/deposit` |
| `POST` | `/api/products/{productId}/deposit` |
| `PUT` | `/api/products/{productId}/deposit` |
| `GET` | `/api/products/{productId}/interest-rates` |
| `POST` | `/api/products/{productId}/interest-rates` |
| `GET` | `/api/products/{productId}/interest-rates/{rateId}` |
| `PUT` | `/api/products/{productId}/interest-rates/{rateId}` |
| `PATCH` | `/api/products/{productId}/interest-rates/{rateId}/expire` |
| `GET` | `/api/products/{productId}/join-channels` |
| `POST` | `/api/products/{productId}/join-channels` |
| `DELETE` | `/api/products/{productId}/join-channels/{channelId}` |
| `GET` | `/api/products/{productId}/savings` |
| `POST` | `/api/products/{productId}/savings` |
| `PUT` | `/api/products/{productId}/savings` |
| `GET` | `/api/products/{productId}/special-terms` |
| `POST` | `/api/products/{productId}/special-terms` |
| `DELETE` | `/api/products/{productId}/special-terms/{specialTermId}` |
| `GET` | `/api/products/{productId}/subscription` |
| `POST` | `/api/products/{productId}/subscription` |
| `PUT` | `/api/products/{productId}/subscription` |
| `GET` | `/api/products/{productId}/target-groups` |
| `POST` | `/api/products/{productId}/target-groups` |
| `DELETE` | `/api/products/{productId}/target-groups/{targetGroupId}` |

### RecommendAgentController

| Method | Path |
|---|---|
| `GET` | `/api/products/recommend-agent` |

### SpecialTermController

| Method | Path |
|---|---|
| `GET` | `/api/special-terms` |
| `POST` | `/api/special-terms` |
| `GET` | `/api/special-terms/{specialTermId}` |
| `PUT` | `/api/special-terms/{specialTermId}` |
| `PATCH` | `/api/special-terms/{specialTermId}/status` |

### SubscriptionPaymentRecognitionHistoryController

| Method | Path |
|---|---|
| `GET` | `/api/subscription-payment-histories` |
| `GET` | `/api/subscription-payment-histories/{id}` |

### TargetGroupController

| Method | Path |
|---|---|
| `GET` | `/api/target-groups` |
| `POST` | `/api/target-groups` |
| `PUT` | `/api/target-groups/{id}` |

### TermApplicationManagementController

| Method | Path |
|---|---|
| `GET` | `/api/term-applications` |
| `POST` | `/api/term-applications` |
| `DELETE` | `/api/term-applications/{id}` |
| `GET` | `/api/term-applications/{id}` |

### TransactionCertificateController

| Method | Path |
|---|---|
| `POST` | `/api/transactions/certificates/batch` |
| `GET` | `/api/transactions/certificates/{certificateNo}` |
| `GET` | `/api/transactions/{transactionId}/certificates` |
| `POST` | `/api/transactions/{transactionId}/certificates` |

### TransactionController

| Method | Path |
|---|---|
| `GET` | `/api/transactions` |
| `POST` | `/api/transactions/deposit` |
| `POST` | `/api/transactions/savings-payment` |
| `POST` | `/api/transactions/transfer` |
| `POST` | `/api/transactions/withdraw` |
| `GET` | `/api/transactions/{transactionId}` |
| `PATCH` | `/api/transactions/{transactionId}/cancel` |
| `PATCH` | `/api/transactions/{transactionId}/memo` |

---

<a id="loan-service"></a>

## Loan Service (여신·대출·심사자문)

### AccountingSummaryBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/accounting-summary/run` |

### AdvisoryRagController

| Method | Path |
|---|---|
| `GET` | `/api/advisory/reports/{advrId}/citations` |
| `GET` | `/api/advisory/reports/{advrId}/similar-cases` |

### AdvisoryReportController

| Method | Path |
|---|---|
| `GET` | `/api/advisory/reports` |
| `GET` | `/api/advisory/reports/{advrId}` |
| `POST` | `/api/advisory/reports/{advrId}/ack` |
| `POST` | `/api/advisory/reports/{advrId}/view` |

### AdvisoryRuleController

| Method | Path |
|---|---|
| `GET` | `/api/advisory/rules` |
| `PUT` | `/api/advisory/rules/{ruleId}` |

### AdvisoryStatsController

| Method | Path |
|---|---|
| `GET` | `/api/advisory/stats/reviewers/{reviewerId}` |

### ApplicationExpiryController

| Method | Path |
|---|---|
| `POST` | `/api/internal/application-expiry/run` |

### AuditLogController

| Method | Path |
|---|---|
| `GET` | `/api/audit/access-logs` |
| `GET` | `/api/audit/break-glass` |

### AuditOpinionController

| Method | Path |
|---|---|
| `GET` | `/api/advisory/audit/opinions/by-report/{advrId}` |
| `GET` | `/api/advisory/audit/opinions/by-reviewer/{reviewerId}` |
| `GET` | `/api/advisory/audit/opinions/recent` |
| `GET` | `/api/advisory/audit/quarantine` |
| `POST` | `/api/advisory/audit/quarantine/{advrId}/disposition` |
| `GET` | `/api/advisory/audit/risk-scores/top/bias` |
| `GET` | `/api/advisory/audit/risk-scores/top/compliance` |
| `GET` | `/api/advisory/audit/risk-scores/{reviewerId}` |

### AutoDebitCallbackController

| Method | Path |
|---|---|
| `POST` | `/api/internal/auto-debit/payment-result` |

### AutoDebitController

| Method | Path |
|---|---|
| `POST` | `/api/internal/auto-debit/run` |

### BiasResultCallbackController

| Method | Path |
|---|---|
| `POST` | `/api/loans/reviews/{revId}/bias-result` |

### BreakGlassController

| Method | Path |
|---|---|
| `POST` | `/api/break-glass` |

### BusinessCalendarController

| Method | Path |
|---|---|
| `GET` | `/api/business-calendar` |
| `POST` | `/api/business-calendar` |
| `GET` | `/api/business-calendar/by-date` |
| `GET` | `/api/business-calendar/check` |
| `DELETE` | `/api/business-calendar/{calId}` |
| `PUT` | `/api/business-calendar/{calId}` |

### CalendarSeederController

| Method | Path |
|---|---|
| `POST` | `/api/internal/calendar-seeder/run` |

### CollateralController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/collaterals` |
| `POST` | `/api/loan-applications/{applId}/collaterals` |

### CollateralDirectController

| Method | Path |
|---|---|
| `PATCH` | `/api/collaterals/{colId}` |
| `POST` | `/api/collaterals/{colId}/evaluations` |
| `POST` | `/api/collaterals/{colId}/release` |

### CommonSyncDispatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/common-sync/backfill/contracts` |
| `POST` | `/api/internal/common-sync/backfill/products` |
| `POST` | `/api/internal/common-sync/dispatch` |

### CreditConsentController

| Method | Path |
|---|---|
| `POST` | `/api/loan-applications/{applId}/credit-consents` |

### CreditEvaluationController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/credit-evaluation` |
| `POST` | `/api/loan-applications/{applId}/credit-evaluation` |

### CreditInfoReportController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/credit-info-reports` |
| `POST` | `/api/loan-contracts/{cntrId}/credit-info-reports` |

### CreditInfoReportDirectController

| Method | Path |
|---|---|
| `GET` | `/api/credit-info-reports` |
| `GET` | `/api/credit-info-reports/{crptId}` |
| `POST` | `/api/credit-info-reports/{crptId}/ack` |
| `POST` | `/api/credit-info-reports/{crptId}/retry` |

### CreditInfoReportDispatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/credit-info-reports/dispatch` |

### CreditScorePreviewController

| Method | Path |
|---|---|
| `POST` | `/api/credit-score/preview` |

### DelinquencyController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/delinquency` |
| `GET` | `/api/loan-contracts/{cntrId}/delinquency/snapshots` |

### DelinquencyRolloverController

| Method | Path |
|---|---|
| `POST` | `/api/internal/delinquency/rollover` |

### DsrCalculationController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/dsr-calculation` |
| `POST` | `/api/loan-applications/{applId}/dsr-calculation` |

### EclCalculationBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/ecl/run` |

### EodBatchController

| Method | Path |
|---|---|
| `GET` | `/api/internal/eod/history` |
| `POST` | `/api/internal/eod/restart` |
| `POST` | `/api/internal/eod/run` |

### EomBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/eom/run` |

### GuaranteeInsuranceController

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/guarantee-insurance` |
| `GET` | `/api/loan-contracts/{cntrId}/guarantee-insurance/{ginsId}` |
| `POST` | `/api/loan-contracts/{cntrId}/guarantee-insurance/{ginsId}/cancel` |

### GuaranteeInsuranceExpiryController

| Method | Path |
|---|---|
| `POST` | `/api/internal/guarantee-insurance-expiry/run` |

### GuarantorAgreementController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/guarantor-agreements` |
| `POST` | `/api/loan-applications/{applId}/guarantor-agreements` |
| `POST` | `/api/loan-applications/{applId}/guarantor-agreements/{gagrId}/cancel` |
| `POST` | `/api/loan-applications/{applId}/guarantor-agreements/{gagrId}/sign` |

### InterestAccrualBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/interest-accrual/run` |

### InterestAccrualController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/interest-accruals` |

### InternalAdvisoryBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/advisory/batch-evaluate` |
| `POST` | `/api/internal/advisory/snapshot` |

### InternalAdvisoryRagController

| Method | Path |
|---|---|
| `GET` | `/api/internal/advisory/documents` |
| `POST` | `/api/internal/advisory/documents` |
| `GET` | `/api/internal/advisory/documents/stats` |
| `PUT` | `/api/internal/advisory/documents/{docId}/activate` |
| `POST` | `/api/internal/advisory/index/cases` |
| `POST` | `/api/internal/advisory/rag/case-index/backfill` |

### InternalAdvisoryToolController

| Method | Path |
|---|---|
| `GET` | `/api/internal/advisory/cohort-stats` |
| `GET` | `/api/internal/advisory/policy-citations` |
| `GET` | `/api/internal/advisory/reviewer-history` |
| `GET` | `/api/internal/advisory/similar-cases` |

### InternalDocumentBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/loan-documents/purge-expired` |

### InternalReviewBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/loan-reviews/expire-bias-reviewing` |
| `POST` | `/api/internal/loan-reviews/expire-pending` |
| `POST` | `/api/internal/loan-reviews/expire-pending-approver` |
| `POST` | `/api/internal/loan-reviews/{revId}/bias-ops-note` |

### LoanApplicationController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications` |
| `POST` | `/api/loan-applications` |
| `GET` | `/api/loan-applications/{applId}` |
| `POST` | `/api/loan-applications/{applId}/cancel` |

### LoanApplicationJourneyController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/journey` |

### LoanCertificateController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/certificates` |
| `POST` | `/api/loan-contracts/{cntrId}/certificates` |

### LoanCertificateDirectController

| Method | Path |
|---|---|
| `GET` | `/api/loan-certificates/{certId}` |

### LoanClosureController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/closure` |
| `POST` | `/api/loan-contracts/{cntrId}/closure` |

### LoanContractAdminController

| Method | Path |
|---|---|
| `GET` | `/api/admin/loan-contracts` |

### LoanContractController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts` |
| `POST` | `/api/loan-contracts` |
| `GET` | `/api/loan-contracts/{cntrId}` |

### LoanDocumentController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/documents` |
| `POST` | `/api/loan-applications/{applId}/documents` |

### LoanDocumentDirectController

| Method | Path |
|---|---|
| `DELETE` | `/api/loan-documents/{docId}` |
| `GET` | `/api/loan-documents/{docId}/download` |

### LoanExecutionController

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/executions` |

### LoanIdentityVerificationController

| Method | Path |
|---|---|
| `POST` | `/api/loan-applications/{applId}/identity-verifications` |
| `GET` | `/api/loan-applications/{applId}/identity-verifications/{idvId}` |

### LoanPrescreeningController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/prescreening` |
| `POST` | `/api/loan-applications/{applId}/prescreening` |

### LoanProductController

| Method | Path |
|---|---|
| `GET` | `/api/loan-products` |
| `POST` | `/api/loan-products` |
| `GET` | `/api/loan-products/{prodId}` |
| `PATCH` | `/api/loan-products/{prodId}` |
| `POST` | `/api/loan-products/{prodId}/discontinue` |

### LoanReviewBiasReportController

| Method | Path |
|---|---|
| `POST` | `/api/internal/loan-reviews/{revId}/bias-report` |
| `GET` | `/api/loan-reviews/{revId}/advices` |
| `GET` | `/api/loan-reviews/{revId}/advisory-reports` |
| `POST` | `/api/loan-reviews/{revId}/bias-override` |

### LoanReviewController

| Method | Path |
|---|---|
| `GET` | `/api/loan-applications/{applId}/review` |
| `PATCH` | `/api/loan-applications/{applId}/review` |
| `POST` | `/api/loan-applications/{applId}/review` |
| `POST` | `/api/loan-applications/{applId}/review/acknowledge-bias` |
| `POST` | `/api/loan-applications/{applId}/review/approver-approve` |
| `POST` | `/api/loan-applications/{applId}/review/auto-decide` |
| `POST` | `/api/loan-applications/{applId}/review/confirm` |
| `POST` | `/api/loan-applications/{applId}/review/escalate-to-hq` |

### LoanStatusHistoryController

| Method | Path |
|---|---|
| `GET` | `/api/status-history` |

### LtvCalculationController

| Method | Path |
|---|---|
| `GET` | `/api/collaterals/{colId}/ltv-calculation` |
| `POST` | `/api/collaterals/{colId}/ltv-calculation` |

### MaturityBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/maturity/run` |

### MaturityController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/maturity` |
| `POST` | `/api/loan-contracts/{cntrId}/maturity/extend` |

### NotificationDispatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/notifications/dispatch` |

### NotificationOutboxController

| Method | Path |
|---|---|
| `GET` | `/api/notifications` |
| `GET` | `/api/notifications/{outboxId}` |
| `POST` | `/api/notifications/{outboxId}/retry` |

### PartialRepaymentController

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/repayments/partial` |

### PendingReviewController

| Method | Path |
|---|---|
| `GET` | `/api/loan-reviews/escalated` |
| `GET` | `/api/loan-reviews/pending` |
| `GET` | `/api/loan-reviews/pending-approver` |
| `GET` | `/api/loan-reviews/stats` |

### PreferentialRatePolicyController

| Method | Path |
|---|---|
| `GET` | `/api/loan-products/{prodId}/preferential-rate-policies` |
| `POST` | `/api/loan-products/{prodId}/preferential-rate-policies` |

### PrepaymentController

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/prepayments` |

### RateChangeController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/rate-changes` |
| `POST` | `/api/loan-contracts/{cntrId}/rate-changes` |

### RepaymentAccountController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/repayment-account` |
| `POST` | `/api/loan-contracts/{cntrId}/repayment-account` |
| `POST` | `/api/loan-contracts/{cntrId}/repayment-account/verify` |

### RepaymentController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/repayments` |
| `POST` | `/api/loan-contracts/{cntrId}/repayments` |
| `POST` | `/api/loan-contracts/{cntrId}/repayments/online` |

### RepaymentScheduleController

| Method | Path |
|---|---|
| `GET` | `/api/loan-contracts/{cntrId}/repayment-schedules` |

### ReversalController

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/repayments/{rtxId}/reversal` |

### ReviewCheckLogController

| Method | Path |
|---|---|
| `GET` | `/api/loan-reviews/{revId}/checks` |
| `POST` | `/api/loan-reviews/{revId}/checks` |

### VirtualAccountController

| Method | Path |
|---|---|
| `POST` | `/api/loan-contracts/{cntrId}/virtual-account` |

---

<a id="fds-detector"></a>

## FDS Detector (이상거래 탐지)

### InternalPreCheckController

| Method | Path |
|---|---|
| `POST` | `/api/v1/internal/fds/precheck` |

---

<a id="auto-loan-review"></a>

## Auto Loan Review (AI 자동심사)

### AutoReviewController

| Method | Path |
|---|---|
| `POST` | `/api/ai/auto-review` |
| `POST` | `/api/ai/auto-review/evaluate` |

### EmbeddingBatchController

| Method | Path |
|---|---|
| `POST` | `/api/internal/embeddings/batch` |

### HealthController

| Method | Path |
|---|---|
| `GET` | `/health` |

---

<a id="review-ai-gateway"></a>

## Review AI Gateway (심사 AI 게이트웨이)

### AuditAnalysisController

| Method | Path |
|---|---|
| `POST` | `/internal/audit/analyze` |

### HealthController

| Method | Path |
|---|---|
| `GET` | `/internal/ping` |

---

<a id="doc-agent"></a>

## Doc Agent (서류 제출·검토)

### DocumentSubmissionController

| Method | Path |
|---|---|
| `POST` | `/api/documents/submit` |

### HealthController

| Method | Path |
|---|---|
| `GET` | `/health` |

### HumanReviewController

| Method | Path |
|---|---|
| `GET` | `/api/documents/queue` |
| `POST` | `/api/documents/{submissionId}/review` |

### LegalHoldController

| Method | Path |
|---|---|
| `PATCH` | `/api/documents/{submissionId}/legal-hold/disable` |
| `PATCH` | `/api/documents/{submissionId}/legal-hold/enable` |

---

<a id="consultation"></a>

## Consultation (상담·챗봇)

### main

| Method | Path |
|---|---|
| `GET` | `/agents` |
| `POST` | `/agents` |
| `DELETE` | `/agents/{employee_id}` |
| `PATCH` | `/agents/{employee_id}` |
| `POST` | `/auth/agent/login` |
| `GET` | `/chat` |
| `GET` | `/chat/consultations/{chat_consultation_id}` |
| `POST` | `/chat/consultations/{chat_consultation_id}/connect` |
| `POST` | `/chat/consultations/{chat_consultation_id}/end` |
| `GET` | `/chat/consultations/{chat_consultation_id}/messages` |
| `POST` | `/chat/consultations/{chat_consultation_id}/messages` |
| `GET` | `/chat/history` |
| `GET` | `/chat/queue` |
| `POST` | `/chat/request` |
| `GET` | `/chatbot/categories` |
| `POST` | `/chatbot/consultations/start` |
| `POST` | `/chatbot/consultations/{chatbot_consultation_id}/messages` |
| `POST` | `/chatbot/documents/upload` |
| `GET` | `/chatbot/features` |
| `GET` | `/chatbot/features/{feature_code}` |
| `POST` | `/chatbot/features/{feature_code}/execute` |
| `POST` | `/chatbot/file/analyze` |
| `POST` | `/chatbot/scenarios/default` |
| `POST` | `/chatbot/transfer` |
| `GET` | `/email-inquiries` |
| `POST` | `/email-inquiries` |
| `GET` | `/health` |

---

<a id="fraud-investigation"></a>

## Fraud Investigation (조사 에이전트)

### api

| Method | Path |
|---|---|
| `POST` | `/api/approve` |
| `GET` | `/api/cases` |
| `POST` | `/api/consult` |
| `POST` | `/api/guidance` |
| `POST` | `/api/investigate` |
| `GET` | `/health` |
| `GET` | `/metrics` |

---

<a id="goal-agent"></a>

## Goal Agent (목표 상담)

### main

| Method | Path |
|---|---|
| `POST` | `/agent/goal/analyze` |
| `POST` | `/agent/goal/chat` |
| `POST` | `/agent/maturity/chat` |
| `GET` | `/agent/maturity/upcoming` |
| `POST` | `/agent/maturity/{contract_id}/process` |
| `GET` | `/agent/maturity/{contract_id}/recommendations` |
| `POST` | `/agent/spending/chat` |
| `GET` | `/api/meta/tables` |
| `GET` | `/api/{table_name}` |
| `POST` | `/api/{table_name}` |
| `DELETE` | `/api/{table_name}/{row_id}` |
| `GET` | `/api/{table_name}/{row_id}` |
| `PATCH` | `/api/{table_name}/{row_id}` |
| `PATCH` | `/deposit_banking_products/{banking_product_id}/status` |
| `POST` | `/deposit_contracts` |
| `POST` | `/deposit_transactions/deposit` |
| `POST` | `/deposit_transactions/payment` |
| `POST` | `/deposit_transactions/savings-payment` |
| `POST` | `/deposit_transactions/transfer` |
| `POST` | `/deposit_transactions/withdraw` |
| `POST` | `/deposit_transactions/{transaction_id}/reversal` |
| `GET` | `/health` |
| `POST` | `/interests/pay` |

---

<a id="inference-server"></a>

## Inference Server (모델 추론)

### main

| Method | Path |
|---|---|
| `GET` | `/health` |
| `POST` | `/predict` |
| `POST` | `/predict/pd` |
