/* eslint-disable @typescript-eslint/no-explicit-any -- 대출 API 응답 타입 미정의 구간, 빌드 차단 방지용 임시 처리 */
import axios from "axios";
import { getAdminGatewayHeaders } from "@/lib/admin-loan-auth";
import type { Loan } from "@/lib/generated";

// loan-service 전용 axios 인스턴스.
// 인증·고객 API(@/lib/api)는 customer-service를 가리키지만, 대출 엔드포인트는
// loan-service(기본 8083)를 직접 호출한다. (게이트웨이 미경유 로컬 개발 구성)
// 인증 우선순위:
//   1. accessToken(JWT) → Authorization: Bearer <token>  (개인 뱅킹 로그인)
//   2. admin_user(목업) → X-User-Id / X-User-Role 헤더   (어드민 목업 로그인)
const api = axios.create({
  baseURL: process.env.NEXT_PUBLIC_LOAN_API_URL || "http://localhost:8083",
  headers: { "Content-Type": "application/json" },
});

api.interceptors.request.use((config) => {
  if (typeof window !== "undefined") {
    // /admin 화면에선 admin 신원(게이트웨이 헤더) 우선, 그 외엔 개인 JWT
    const adminHeaders = getAdminGatewayHeaders();
    if (Object.keys(adminHeaders).length > 0) {
      Object.assign(config.headers, adminHeaders);
    } else {
      const token = localStorage.getItem("accessToken");
      if (token) config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401 && typeof window !== "undefined") {
      localStorage.removeItem("accessToken");
      localStorage.removeItem("access_token");
      window.location.href = "/login";
    }
    return Promise.reject(err);
  },
);

// ─── 공통 타입 ───────────────────────────────────────────────

export interface LoanProduct {
  prodId: number;
  prodName: string;
  loanTypeCd: string;
  baseRateBps: number;
  minRateBps: number;
  maxRateBps: number;
  minAmount: number;
  maxAmount: number;
  minPeriodMo: number;
  maxPeriodMo: number;
  repaymentMethodCd: string;
  targetCustomerCd: string;
  prodStatusCd: string;
}

export interface PreferentialRatePolicy {
  policyId: number;
  policyName: string;
  preferentialRateBps: number;
  conditionCd: string;
}

export interface LoanApplication {
  applId: number;
  applNo: string;
  applStatusCd: string;
  prodId: number;
  customerId: number;
  requestedAmount: number;
  requestedPeriodMo: number;
  channelCd: string;
  loanPurposeCd: string;
  repaymentMethodCd: string;
  estimatedIncomeAmt: number;
  employmentTypeCd: string;
  createdAt: string;
}

export interface LoanJourney {
  application: LoanApplication;
  prescreening?: {
    prescResultCd: string;
    estimatedLimitAmt: number | null;
    estimatedRateBps: number | null;
    estimatedScore: number | null;
    estimatedGrade: string | null;
    rejectReasonCd: string | null;
  };
  creditEvaluation?: {
    cevalDecisionCd: string;
    cevalScore: number | null;
    cevalGrade: string | null;
    pdBps: number | null;
    evalLimitAmount: number | null;
    evalRateBps: number | null;
  };
  dsr?: {
    dsrStatusCd: string;
    dsrRatioBps: number | null;
    dsrLimitBps: number | null;
  };
  review?: { revResultCd: string; reviewerComment: string };
}

export interface LoanContract {
  cntrId: number;
  cntrNo: string;
  applId: number;
  customerId: number;
  cntrStatusCd: string;
  contractedAmount: number;
  totalRateBps: number;
  contractedPeriodMo: number;
  cntrStartDate: string;
  cntrEndDate: string;
  repaymentMethodCd: string;
  signedAt: string;
}

/**
 * 상환 스케줄 한 회차.
 *
 * **손으로 쓰지 않는다.** 백엔드 RepaymentScheduleResponse 에서 생성한 타입을 그대로 쓴다
 * (lib/generated). 예전에는 이 인터페이스를 손으로 적었고 전 필드가 백엔드와 어긋난 채
 * 3개월 넘게 방치됐다 — 응답을 가공 없이 담으므로 어긋나면 전부 undefined 가 되는데,
 * `api.get<any>` 라 컴파일러가 아무 말도 하지 않았다.
 *
 * optional 을 걷는 처리는 lib/generated 파사드가 한다(DeepRequired) — 이유는 거기 주석 참고.
 * 실제로 그 필드들이 오는지는 e2e/loan-schedule.contract.spec.ts 가 실제 백엔드로 확인한다.
 */
export type RepaymentSchedule = Loan['RepaymentScheduleResponse']

/**
 * 통지 발송함 한 건.
 *
 * **손으로 쓰지 않는다.** 예전에는 notifId·title·content·readYn·createdAt 을 적어 뒀는데
 * 백엔드 NotificationOutboxResponse 에는 그 이름이 하나도 없다(outboxId·eventTypeCd·
 * status·sentAt ...). 알림함 화면 전체가 undefined 를 그리고 있었다.
 */
// 목록 응답의 원소다. 단건 조회(NotificationOutboxResponse)에는 payload·lastError 가 더 있다.
export type Notification = Loan['NotificationOutboxListItem']

// ─── 상품 ─────────────────────────────────────────────────────

export const loanProductApi = {
  list: (params?: {
    loanTypeCd?: string;
    prodStatusCd?: string;
    page?: number;
    size?: number;
  }) => api.get<Loan['ApiResponseLoanProductListResponse']>("/api/loan-products", { params }),

  get: (prodId: number) =>
    api.get<Loan['ApiResponseLoanProductResponse']>(`/api/loan-products/${prodId}`),

  preferentialRates: (prodId: number) =>
    api.get<Loan['ApiResponseListPreferentialRatePolicyResponse']>(`/api/loan-products/${prodId}/preferential-rate-policies`),

  create: (body: object) =>
    api.post<Loan['ApiResponseLoanProductResponse']>("/api/loan-products", body),

  update: (prodId: number, body: object) =>
    api.patch<Loan['ApiResponseLoanProductResponse']>(`/api/loan-products/${prodId}`, body),

  discontinue: (prodId: number, body?: object) =>
    api.post<Loan['ApiResponseLoanProductResponse']>(`/api/loan-products/${prodId}/discontinue`, body ?? {}),
};

// ─── 신청 ─────────────────────────────────────────────────────

export const loanApplicationApi = {
  create: (body: {
    customerId: number;
    prodId: number;
    channelCd: string;
    requestedAmount: number;
    requestedPeriodMo: number;
    loanPurposeCd: string;
    repaymentMethodCd: string;
    estimatedIncomeAmt: number;
    employmentTypeCd: string;
  }) => api.post<Loan['ApiResponseLoanApplicationResponse']>("/api/loan-applications", body),

  list: (params: { customerId: number; page?: number; size?: number }) =>
    api.get<Loan['ApiResponseLoanApplicationListResponse']>("/api/loan-applications", { params }),

  journey: (applId: number) =>
    api.get<Loan['ApiResponseLoanApplicationJourneyResponse']>(`/api/loan-applications/${applId}/journey`),

  submitConsent: (
    applId: number,
    body: { consentTypeCd: string; consentScopeCd: string; consentTargetCd: string; consentMethodCd?: string },
  ) =>
    api.post<Loan['ApiResponseCreditConsentResponse']>(`/api/loan-applications/${applId}/credit-consents`, body),

  verifyIdentity: (applId: number, body: { idvMethodCd: string; idvTargetCd: string; mobileNo: string }) =>
    api.post<Loan['ApiResponseIdentityVerificationResponse']>(`/api/loan-applications/${applId}/identity-verifications`, body),

  get: (applId: number) =>
    api.get<Loan['ApiResponseLoanApplicationResponse']>(`/api/loan-applications/${applId}`),

  cancel: (applId: number, body?: { cancelReasonCd?: string }) =>
    api.post<Loan['ApiResponseLoanApplicationResponse']>(`/api/loan-applications/${applId}/cancel`, body ?? {}),

  runPrescreening: (applId: number, body?: { prescResultCd?: string; estimatedLimit?: number }) =>
    api.post<Loan['ApiResponseLoanPrescreeningResponse']>(`/api/loan-applications/${applId}/prescreening`, body ?? {}),

  getPrescreening: (applId: number) =>
    api.get<Loan['ApiResponseLoanPrescreeningResponse']>(`/api/loan-applications/${applId}/prescreening`),

  runCreditEvaluation: (applId: number, body: { cevalEngine: string; cevalDecisionCd: string; cevalEngineVersion?: string; cevalGrade?: string; cevalScore?: number; evalLimitAmount?: number; evalRateBps?: number }) =>
    api.post<Loan['ApiResponseCreditEvaluationResponse']>(`/api/loan-applications/${applId}/credit-evaluation`, body),

  getCreditEvaluation: (applId: number) =>
    api.get<Loan['ApiResponseCreditEvaluationResponse']>(`/api/loan-applications/${applId}/credit-evaluation`),

  runDsr: (applId: number, body: { annualIncomeAmt: number; newAnnualRepayAmt?: number; existingAnnualRepayAmt?: number }) =>
    api.post<Loan['ApiResponseDsrCalculationResponse']>(`/api/loan-applications/${applId}/dsr-calculation`, body),

  getDsr: (applId: number) =>
    api.get<Loan['ApiResponseDsrCalculationResponse']>(`/api/loan-applications/${applId}/dsr-calculation`),

  uploadDocument: (applId: number, formData: FormData) =>
    api.post<Loan['ApiResponseLoanDocumentResponse']>(`/api/loan-applications/${applId}/documents`, formData, {
      headers: { "Content-Type": "multipart/form-data" },
    }),

  getDocuments: (applId: number) =>
    api.get<Loan['ApiResponseLoanDocumentListResponse']>(`/api/loan-applications/${applId}/documents`),

  getReview: (applId: number) =>
    api.get<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review`),
};

// ─── 보증인 동의 ───────────────────────────────────────────────

export const guarantorApi = {
  list: (applId: number) =>
    api.get<Loan['ApiResponseGuarantorAgreementListResponse']>(`/api/loan-applications/${applId}/guarantor-agreements`),

  register: (applId: number, body: object) =>
    api.post<Loan['ApiResponseGuarantorAgreementResponse']>(`/api/loan-applications/${applId}/guarantor-agreements`, body),

  sign: (applId: number, gagrId: number, body: { signedDocUrl: string; signedDocHash: string }) =>
    api.post<Loan['ApiResponseGuarantorAgreementResponse']>(`/api/loan-applications/${applId}/guarantor-agreements/${gagrId}/sign`, body),

  cancel: (applId: number, gagrId: number, body?: { cancelReasonCd?: string; cancelRemark?: string }) =>
    api.post<Loan['ApiResponseGuarantorAgreementResponse']>(`/api/loan-applications/${applId}/guarantor-agreements/${gagrId}/cancel`, body ?? {}),
};

// ─── 담보 ─────────────────────────────────────────────────────

export const collateralApi = {
  list: (applId: number) =>
    api.get<Loan['ApiResponseCollateralListResponse']>(`/api/loan-applications/${applId}/collaterals`),

  create: (applId: number, body: object) =>
    api.post<Loan['ApiResponseCollateralResponse']>(`/api/loan-applications/${applId}/collaterals`, body),

  evaluate: (colId: number, body: object) =>
    api.post<Loan['ApiResponseCollateralEvaluationResponse']>(`/api/collaterals/${colId}/evaluations`, body),

  calculateLtv: (colId: number, body?: object) =>
    api.post<Loan['ApiResponseLtvCalculationResponse']>(`/api/collaterals/${colId}/ltv-calculation`, body ?? {}),

  getLtv: (colId: number) =>
    api.get<Loan['ApiResponseLtvCalculationResponse']>(`/api/collaterals/${colId}/ltv-calculation`),
};

// ─── 약정 ─────────────────────────────────────────────────────

export const loanContractApi = {
  create: (applId: number, body: object) =>
    api.post<Loan['ApiResponseLoanContractResponse']>("/api/loan-contracts", { applId, ...body }),

  list: (params: { customerId: number; page?: number; size?: number }) =>
    api.get<Loan['ApiResponseLoanContractListResponse']>("/api/loan-contracts", { params }),

  adminList: (params: {
    cntrStatusCd?: string; dateFrom?: string; dateTo?: string;
    page?: number; size?: number;
  }) =>
    api.get<Loan['ApiResponseLoanContractAdminListResponse']>("/api/admin/loan-contracts", { params }),

  get: (cntrId: number) =>
    api.get<Loan['ApiResponseLoanContractResponse']>(`/api/loan-contracts/${cntrId}`),

  execute: (cntrId: number, body: object) =>
    api.post<Loan['ApiResponseLoanExecutionResponse']>(`/api/loan-contracts/${cntrId}/executions`, body),

  getRepaymentSchedules: (cntrId: number) =>
    api.get<Loan['ApiResponseRepaymentScheduleListResponse']>(`/api/loan-contracts/${cntrId}/repayment-schedules`),

  registerRepaymentAccount: (cntrId: number, body: { accountNo: string }) =>
    api.post<Loan['ApiResponseRepaymentAccountResponse']>(`/api/loan-contracts/${cntrId}/repayment-account`, body),
};

// ─── 상환 ─────────────────────────────────────────────────────

export const repaymentApi = {
  pay: (cntrId: number, body: { installmentNo: number; channelCd?: string; valueDate?: string }) =>
    api.post<Loan['ApiResponseRepaymentTransactionResponse']>(`/api/loan-contracts/${cntrId}/repayments`, body),

  partialPrepay: (cntrId: number, body: { prepaymentAmt: number }) =>
    api.post<Loan['ApiResponsePartialRepaymentResponse']>(`/api/loan-contracts/${cntrId}/repayments/partial`, body),

  fullPrepay: (cntrId: number, body: object) =>
    api.post<Loan['ApiResponsePrepaymentResponse']>(`/api/loan-contracts/${cntrId}/prepayments`, body),

  list: (cntrId: number) =>
    api.get<Loan['ApiResponseRepaymentTransactionListResponse']>(`/api/loan-contracts/${cntrId}/repayments`),

  reverse: (cntrId: number, rtxId: number, body?: { reversalReasonCd?: string; reversalRemark?: string }) =>
    api.post<Loan['ApiResponseReversalResponse']>(`/api/loan-contracts/${cntrId}/repayments/${rtxId}/reversal`, body ?? {}),
};

// ─── 금리/이자 ────────────────────────────────────────────────

export const rateApi = {
  getInterestAccruals: (cntrId: number) =>
    api.get<Loan['ApiResponseInterestAccrualListResponse']>(`/api/loan-contracts/${cntrId}/interest-accruals`),

  requestRateChange: (cntrId: number, body: { requestedRateBps: number; reasonCd: string }) =>
    api.post<Loan['ApiResponseRateChangeApplyResponse']>(`/api/loan-contracts/${cntrId}/rate-changes`, body),

  getRateChanges: (cntrId: number) =>
    api.get<Loan['ApiResponseRateChangeHistoryListResponse']>(`/api/loan-contracts/${cntrId}/rate-changes`),
};

// ─── 만기/해지 ────────────────────────────────────────────────

export const closureApi = {
  extendMaturity: (cntrId: number, body: { newMaturityDt: string }) =>
    api.post<Loan['ApiResponseMaturityResponse']>(`/api/loan-contracts/${cntrId}/maturity/extend`, body),

  getMaturity: (cntrId: number) =>
    api.get<Loan['ApiResponseMaturityResponse']>(`/api/loan-contracts/${cntrId}/maturity`),

  close: (cntrId: number, body: { closureReasonCd: string }) =>
    api.post<Loan['ApiResponseLoanClosureResponse']>(`/api/loan-contracts/${cntrId}/closure`, body),

  getClosure: (cntrId: number) =>
    api.get<Loan['ApiResponseLoanClosureResponse']>(`/api/loan-contracts/${cntrId}/closure`),
};

// ─── 부수 기능 ────────────────────────────────────────────────

export const loanMiscApi = {
  // ⚠ 백엔드 OpenAPI 스펙에 이 엔드포인트가 없다(lib/generated 생성 시 미매칭).
  //   호출하면 404 다. 타입을 붙일 수 없어 any 로 남긴다.
  // 스펙에 있는 것은 POST /api/credit-score/preview 뿐이다. 화면에서 쓰이지 않는다.
  getCreditScore: (customerId: number) =>
    api.get<any>(`/api/credit-score`, { params: { customerId } }),

  // ⚠ 백엔드 OpenAPI 스펙에 이 엔드포인트가 없다(lib/generated 생성 시 미매칭).
  //   호출하면 404 다. 타입을 붙일 수 없어 any 로 남긴다.
  // 스펙에는 GET /api/business-calendar 와 /by-date 가 있다. 화면에서 쓰이지 않는다.
  getBusinessCalendar: (params: { yearMonth: string }) =>
    api.get<Loan['ApiResponseBusinessCalendarListResponse']>("/api/business-calendar", { params }),

  getStatusHistory: (targetTable: string, targetId: number) =>
    api.get<Loan['ApiResponseStatusHistoryListResponse']>(`/api/status-history`, { params: { targetTable, targetId } }),

  getDelinquencySnapshots: (cntrId: number) =>
    api.get<Loan['ApiResponseDelinquencySnapshotListResponse']>(`/api/loan-contracts/${cntrId}/delinquency/snapshots`),

  getNotifications: (customerId: number) =>
    api.get<Loan['ApiResponseNotificationOutboxListResponse']>("/api/notifications", { params: { customerId } }),

  // ⚠ 백엔드 OpenAPI 스펙에 이 엔드포인트가 없다(lib/generated 생성 시 미매칭).
  //   호출하면 404 다. 타입을 붙일 수 없어 any 로 남긴다.
  // 스펙에 읽음 처리 엔드포인트가 없다(GET 목록·단건, POST retry 뿐).
  //   대출관리 알림함의 읽음/미읽음 토글이 이걸 부르므로 그 버튼은 동작하지 않는다.
  updateNotification: (notifId: number, body: { readYn: string }) =>
    api.patch<any>(`/api/notifications/${notifId}`, body),

  getCertificate: (cntrId: number, certTypeCd: string) =>
    api.get<Loan['ApiResponseLoanCertificateListResponse']>(`/api/loan-contracts/${cntrId}/certificates`, {
      params: { certTypeCd },
    }),

  getCreditInfoReport: (cntrId: number) =>
    api.get<Loan['ApiResponseCreditInfoReportListResponse']>(`/api/loan-contracts/${cntrId}/credit-info-reports`),

  getDelinquency: (cntrId: number) =>
    api.get<Loan['ApiResponseDelinquencyResponse']>(`/api/loan-contracts/${cntrId}/delinquency`),
};

// ─── 보증보험 ─────────────────────────────────────────────────

export const guaranteeInsuranceApi = {
  issue: (cntrId: number, body: object) =>
    api.post<Loan['ApiResponseGuaranteeInsuranceResponse']>(`/api/loan-contracts/${cntrId}/guarantee-insurance`, body),

  get: (cntrId: number, ginsId: number) =>
    api.get<Loan['ApiResponseGuaranteeInsuranceResponse']>(`/api/loan-contracts/${cntrId}/guarantee-insurance/${ginsId}`),

  cancel: (cntrId: number, ginsId: number, body?: { cancelReasonCd?: string }) =>
    api.post<Loan['ApiResponseGuaranteeInsuranceResponse']>(`/api/loan-contracts/${cntrId}/guarantee-insurance/${ginsId}/cancel`, body ?? {}),
};

// ─── 신용점수 미리보기 ────────────────────────────────────────

export const creditScorePreviewApi = {
  preview: (body: {
    customerId: number;
    loanTypeCd: string;
    requestedAmount: number;
    requestedPeriodMo: number;
    loanPurposeCd?: string;
    employmentTypeCd?: string;
    estimatedIncomeAmt?: number;
    consentYn: boolean;
  }) => api.post<any>('/api/credit-score/preview', body),
};

// ─── 헬퍼 ────────────────────────────────────────────────────

export function bpsToRate(bps: number): string {
  return (bps / 100).toFixed(2);
}

export function formatAmount(amt: number): string {
  if (amt >= 100_000_000) return `${(amt / 100_000_000).toLocaleString("ko-KR")}억원`;
  if (amt >= 10_000) return `${(amt / 10_000).toLocaleString("ko-KR")}만원`;
  return `${amt.toLocaleString("ko-KR")}원`;
}

// ─── 어드민 - 본심사 ──────────────────────────────────────────

export const adminReviewApi = {
  listPending: () =>
    api.get<any>('/api/loan-reviews/pending'),

  listPendingApprover: () =>
    api.get<any>('/api/loan-reviews/pending-approver'),

  stats: (from: string, to: string) =>
    api.get<any>('/api/loan-reviews/stats', { params: { from, to } }),

  get: (applId: number) =>
    api.get<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review`),

  run: (applId: number, body: object) =>
    api.post<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review`, body),

  autoDecide: (applId: number) =>
    api.post<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review/auto-decide`, {}),

  confirm: (applId: number, body: { confirmRemark?: string }) =>
    api.post<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review/confirm`, body),

  acknowledgeBias: (applId: number, body?: { acknowledgeRemark?: string }) =>
    api.post<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review/acknowledge-bias`, body ?? {}),

  approverApprove: (applId: number, body: object) =>
    api.post<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review/approver-approve`, body),

  revise: (applId: number, body: object) =>
    api.patch<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review`, body),

  getAdvices: (revId: number) =>
    api.get<Loan['ApiResponseListAiReviewAdviceResponse']>(`/api/loan-reviews/${revId}/advices`),

  getChecks: (revId: number) =>
    api.get<Loan['ApiResponseListReviewCheckLogResponse']>(`/api/loan-reviews/${revId}/checks`),

  addCheck: (revId: number, body: object) =>
    api.post<Loan['ApiResponseReviewCheckLogResponse']>(`/api/loan-reviews/${revId}/checks`, body),

  biasOverride: (revId: number, body: { overrideReason: string }) =>
    api.post<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-reviews/${revId}/bias-override`, body),

  getAdvisoryReports: (revId: number) =>
    api.get<Loan['ApiResponseListAdvisoryReportSummary']>(`/api/loan-reviews/${revId}/advisory-reports`),

  // 본사 상신 건 목록 (ROLE_HQ_REVIEWER) — Page 응답(content/totalElements 등)
  listEscalated: (page = 0, size = 20) =>
    api.get<any>('/api/loan-reviews/escalated', { params: { page, size } }),

  // 이상거래 본사 상신 (ROLE_BRANCH_MANAGER)
  escalateToHq: (applId: number, body: { escalateReason: string }) =>
    api.post<Loan['ApiResponseLoanReviewResponse']>(`/api/loan-applications/${applId}/review/escalate-to-hq`, body),
};

// ─── 어드민 - EOD 배치 (ROLE_OPS) ────────────────────────────
// 응답은 ApiResponse 래핑 → res.data.data. baseDate/from/to 는 YYYYMMDD.
export const eodApi = {
  run: (baseDate: string) =>
    api.post<any>('/api/internal/eod/run', null, { params: { baseDate } }),

  restart: (baseDate: string) =>
    api.post<any>('/api/internal/eod/restart', null, { params: { baseDate } }),

  history: (from?: string, to?: string) =>
    api.get<any>('/api/internal/eod/history', { params: { from, to } }),
};

// ─── 어드민 - 감사로그 (ROLE_COMPLIANCE) ──────────────────────
// 컨트롤러가 List 를 그대로 반환(ApiResponse 미사용) → res.data 가 곧 배열.
export const auditApi = {
  listBreakGlass: (actorId?: number) =>
    api.get<any>('/api/audit/break-glass', { params: { actorId } }),

  listByTarget: (targetType: string, targetId: number) =>
    api.get<any>('/api/audit/access-logs', { params: { targetType, targetId } }),
};

// ─── 어드민 - break-glass 긴급 접근 (CUSTOMER 제외 전 직원) ────
// ResponseEntity<BreakGlassResponse> 반환(ApiResponse 미사용) → res.data 가 곧 응답.
export const breakGlassApi = {
  request: (body: { applId: number; reason: string }) =>
    api.post<any>('/api/break-glass', body),
};

// ─── 어드민 - 담보·서류·우대금리 ─────────────────────────────

export const adminLoanApi = {
  updateCollateral: (colId: number, body: object) =>
    api.patch<Loan['ApiResponseCollateralResponse']>(`/api/collaterals/${colId}`, body),

  releaseCollateral: (colId: number, body: object) =>
    api.post<Loan['ApiResponseCollateralResponse']>(`/api/collaterals/${colId}/release`, body),

  deleteDocument: (docId: number) =>
    api.delete<Loan['ApiResponseLoanDocumentResponse']>(`/api/loan-documents/${docId}`),

  downloadDocumentUrl: (docId: number) =>
    `/api/loan-documents/${docId}/download`,

  getPreferentialPolicies: (prodId: number) =>
    api.get<Loan['ApiResponseListPreferentialRatePolicyResponse']>(`/api/loan-products/${prodId}/preferential-rate-policies`),

  addPreferentialPolicy: (prodId: number, body: object) =>
    api.post<Loan['ApiResponsePreferentialRatePolicyResponse']>(`/api/loan-products/${prodId}/preferential-rate-policies`, body),
};

// ─── 영업일 캘린더 ────────────────────────────────────────────

export const businessCalendarApi = {
  list: (params?: { from?: string; to?: string; page?: number; size?: number }) =>
    api.get<Loan['ApiResponseBusinessCalendarListResponse']>("/api/business-calendar", { params }),

  get: (calId: number) =>
    api.get<any>(`/api/business-calendar/${calId}`),

  create: (body: object) =>
    api.post<Loan['ApiResponseBusinessCalendarResponse']>("/api/business-calendar", body),

  update: (calId: number, body: object) =>
    api.put<Loan['ApiResponseBusinessCalendarResponse']>(`/api/business-calendar/${calId}`, body),

  delete: (calId: number) =>
    api.delete<void>(`/api/business-calendar/${calId}`),
};

// ─── 신용정보 보고서 ──────────────────────────────────────────

export const creditInfoReportApi = {
  list: (params?: { statusCd?: string; page?: number; size?: number }) =>
    api.get<Loan['ApiResponseAdminCreditInfoReportListResponse']>("/api/credit-info-reports", { params }),

  retry: (crptId: number) =>
    api.post<Loan['ApiResponseCreditInfoReportResponse']>(`/api/credit-info-reports/${crptId}/retry`, {}),

  ack: (crptId: number, body?: object) =>
    api.post<Loan['ApiResponseCreditInfoReportResponse']>(`/api/credit-info-reports/${crptId}/ack`, body ?? {}),
};

// ─── 알림 발송함 ──────────────────────────────────────────────

export const notificationOutboxApi = {
  get: (outboxId: number) =>
    api.get<Loan['ApiResponseNotificationOutboxResponse']>(`/api/notifications/${outboxId}`),

  list: (params?: { page?: number; size?: number }) =>
    api.get<Loan['ApiResponseNotificationOutboxListResponse']>("/api/notifications", { params }),

  retry: (outboxId: number) =>
    api.post<Loan['ApiResponseNotificationOutboxResponse']>(`/api/notifications/${outboxId}/retry`, {}),
};

// ─── 본인인증 ─────────────────────────────────────────────────

export const identityVerificationApi = {
  get: (idvId: number) =>
    // ⚠ 스펙의 경로는 /api/loan-applications/{applId}/identity-verifications/{idvId} 다.
    //   이 경로로는 404 다.
    api.get<any>(`/api/identity-verifications/${idvId}`),
};

export function getCustomerId(): number | null {
  if (typeof window === "undefined") return null;
  const val = localStorage.getItem("customerId");
  return val ? parseInt(val) : null;
}
