# 대출 프론트 ↔ 백엔드 전체 연결 계획

> 작성일: 2026-05-27  
> 브랜치: `loan`

---

## 현재 연결 상태

| 연결됨 ✅ | 미연결 ❌ |
|---|---|
| `/products/loan/credit` (목록) | `/products/loan/mortgage`, `jeonse`, `auto`, `group`, `khfc` (5개 카테고리) |
| `/products/loan/credit/[id]` (상세) | `/products/loan/status` (진행현황) |
| `/loans/apply` (신청) | `/products/loan/status/[slug]` (서류제출/동의/전자서명) |
| `/loans/apply/result` (결과) | `/products/loan/manage/[slug]` (13개 대출관리 화면) |
| | `/products/loan/credit-eval/[slug]` (신용평가 자료제출) |
| | `/products/loan/guide/[slug]` (가이드) |
| | `/products/loan` (대출 메인) |

---

## 백엔드 API 카탈로그 (고객용)

| 영역 | 엔드포인트 prefix |
|---|---|
| 상품 | `/api/loan-products`, `/api/loan-products/{prodId}/preferential-rate-policies` |
| 신청 | `/api/loan-applications`, `/api/loan-applications/{applId}/journey` |
| 신청 단계 | `/credit-consents`, `/identity-verifications`, `/prescreening`, `/credit-evaluation`, `/dsr-calculation`, `/collaterals`, `/documents`, `/guarantor-agreements`, `/review` (모두 `/api/loan-applications/{applId}` 하위) |
| 담보 | `/api/collaterals`, `/api/collaterals/{colId}/ltv-calculation` |
| 약정/실행 | `/api/loan-contracts`, `/api/loan-contracts/{cntrId}/executions`, `/repayment-schedules`, `/repayment-account` |
| 상환 | `/repayments`, `/repayments/partial`, `/prepayments`, `/repayments/{rtxId}/reversal` (모두 `/api/loan-contracts/{cntrId}` 하위) |
| 금리/이자 | `/interest-accruals`, `/rate-changes` |
| 만기/해지 | `/maturity`, `/closure` |
| 부수 | `/certificates`, `/credit-info-reports`, `/delinquency`, `/guarantee-insurance` |
| 공용 | `/api/credit-score`, `/api/business-calendar`, `/api/status-history`, `/api/notifications`, `/api/loan-reviews` |

---

## Phase별 작업 계획

### ✅ Phase 0 — 완료

- `/products/loan/credit` 목록
- `/products/loan/credit/[id]` 상세
- `/loans/apply` 신청
- `/loans/apply/result` 결과 (journey)

---

### 🔴 Phase 1 — 신청 플로우 보강 (1순위)

신청 단계의 실제 결과를 화면에 표시.

| 페이지 | 추가할 API |
|---|---|
| `/loans/apply` (개선) | 신청 전: `POST /credit-consents` (동의 후 진행) → `POST /identity-verifications` (본인인증) |
| `/loans/apply/result` (확장) | journey 응답의 `prescreening`, `creditEvaluation`, `dsr` 단계별 결과 카드 시각화 |
| 신규 `/loans/apply/[applId]/documents` | `POST/GET /documents` — 재직·소득증명 업로드 |
| `/products/loan/credit/[id]` (개선) | `GET /preferential-rate-policies` — 우대금리 항목 표시 |

---

### 🟠 Phase 2 — 상품 카테고리 확장 (2순위)

현재 5개 페이지가 정적 UI. 모두 `loanTypeCd` 필터만 다르게 해서 재활용 가능.

| 페이지 | loanTypeCd 값 |
|---|---|
| `/products/loan/mortgage` | `MORTGAGE` |
| `/products/loan/jeonse` | `CHARTER` |
| `/products/loan/auto` | `AUTO` |
| `/products/loan/group` | `GROUP` |
| `/products/loan/khfc` | `KHFC` |
| `/products/loan` (메인) | 카테고리별 대표 상품 1~2건씩 표시 |

> **리팩토링 권장:** `credit/page.tsx`를 공용 `LoanListPage(loanTypeCd)` 컴포넌트로 추출 후 5개 페이지에서 재활용.

---

### 🟡 Phase 3 — 진행현황 페이지 (3순위, 백엔드 보강 필요)

| 페이지 | 필요 API | 비고 |
|---|---|---|
| `/products/loan/status` | `GET /api/loan-applications?customerId={id}` | ⚠️ 백엔드에 없음 — 신규 추가 필요 |
| `/products/loan/status/[slug=docs]` | `POST /documents` | 사후 서류 제출 |
| `/products/loan/status/[slug=spouse/household/collateral]` | `POST /credit-consents` | 동의 제출 |
| `/products/loan/status/[slug=sign]` | `POST /contracts` | 약정 체결 (전자서명) |
| `/products/loan/credit-eval/[slug]` | `POST /documents` + `GET /credit-evaluation` | 신용평가 자료 |

#### ⚠️ 차단 이슈 — 백엔드 신규 endpoint 2개 필요

Phase 3·4 전체의 전제 조건. 고객 본인의 신청/약정 목록 조회 API가 없음.

```
GET /api/loan-applications?customerId=...
GET /api/loan-contracts?customerId=...
```

---

### 🟢 Phase 4 — 대출관리 `/products/loan/manage/[slug]` (4순위, 13개 화면)

약정 체결 이후 화면들. Phase 3의 약정 목록 API에 의존.

| slug | API |
|---|---|
| `rate` 적용금리조회 | `GET /loan-contracts/{cntrId}/interest-accruals` + `/rate-changes` |
| `payment` 이자/월부금입금 | `GET /repayment-schedules` + `POST /repayments` |
| `repay` 대출상환 | `POST /repayments`, `/repayments/partial`, `/prepayments` |
| `withdraw` 계약철회/완제 | `POST /closure` |
| `extend` 기한연장 | `POST /maturity` |
| `rate-cut` 금리인하요구 | `POST /rate-changes` |
| `closed` 해지계좌조회 | `GET /closure` 이력 |
| `rate-detail` 금리산정내역서 | `GET /certificates` (`RATE_DETAIL` 타입) |
| `debt-relief` 소멸시효 | `GET /credit-info-reports` |
| `notify` 통지서비스 | `GET/PATCH /notifications` |
| `payment-method` 납입방법 | `PATCH /repayment-account` |
| `limit` 한도변경 | ⚠️ 백엔드 신규 endpoint 필요 |
| `auto-interest` 통장자동대출 | `GET /interest-accruals` 별도 필터 |

---

### 🔵 Phase 5 — 부수 기능 (5순위)

- 증명서 발급 화면 신규: `POST /api/loan-certificates` 발급 + `GET` 다운로드
- 알림함: `GET /api/notifications`
- 연체 정보: `GET /loan-contracts/{cntrId}/delinquency`
- 영업일 캘린더: `GET /api/business-calendar` (이체일자 선택 UI에 활용)

---

### ⚫ Phase 6 — 어드민 (선택)

`/admin/*` 페이지에 본심사 처리, 보류 큐, 통계 API 연결.

---

## 권장 진행 순서

1. **공용 클라이언트 헬퍼 추가** — `web/lib/loan-api.ts` : 50개 endpoint를 메서드로 정리
2. **Phase 1** — 신청 동의·본인인증·우대금리 → 신청 완성도 ↑
3. **백엔드 작업** — `customerId` 기반 신청/약정 조회 API 2개 추가 ← Phase 3·4 전제 조건
4. **Phase 2** — 5개 카테고리 페이지를 공용 컴포넌트로 일괄 처리
5. **Phase 3** — 진행현황
6. **Phase 4** — 대출관리 13개 화면 (slug별 점진 연결)
7. **Phase 5·6** — 부수기능, 어드민
