import { GATEWAY_BASE_URL } from '@/lib/gateway-url'
import axios from 'axios'
import { getAdminGatewayHeaders } from '@/lib/admin-loan-auth'
import { readAccessToken } from '@/lib/token'

// 자문 호출도 게이트웨이를 거친다.
//
// 서비스 주소를 직접 가리키면 게이트웨이를 건너뛰게 되고, 그러면 검증된 신원
// 헤더(X-Customer-Id·X-Employee-Id·X-User-Role)가 붙지 않는다. 그 헤더 위에
// 서 있는 인가가 통째로 무의미해진다.
//
// 서비스별 NEXT_PUBLIC_*_API_URL 오버라이드를 없앤 것도 같은 이유다 —
// 남겨 두면 환경변수 한 줄로 우회로가 되살아난다.
const advisoryApi = axios.create({
  baseURL: GATEWAY_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
})

advisoryApi.interceptors.request.use((config) => {
  if (typeof window === 'undefined') return config
  const token = readAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  } else {
    // 어드민 목업 세션 → 게이트웨이 헤더 폴백
    Object.assign(config.headers, getAdminGatewayHeaders())
  }
  return config
})

export type AdvisoryReportSummary = {
  advrId: number
  revId: number
  ruleId?: number
  advisoryTypeCd: string
  severityCd: string
  advrStatusCd: string
  advrTitle: string
  targetReviewerId?: number
  generatedAt?: string
  firstViewedAt?: string
  resolvedAt?: string
}

export type AckResponseCd = 'MAINTAIN' | 'OVERTURN' | 'ESCALATE' | 'NEEDS_MORE_INFO'

export type AdvisoryAckBody = {
  ackResponseCd: AckResponseCd
  decisionChangeYn?: boolean
  ackReasonCd?: string
  ackRemark?: string
  beforeDecisionCd?: string
  afterDecisionCd?: string
}

// ── 어드바이저리 리포트 ────────────────────────────────────────────────────

export async function getAdvisoryReports(params?: Record<string, unknown>): Promise<AdvisoryReportSummary[]> {
  const { data } = await advisoryApi.get('/api/advisory/reports', { params })
  // ApiResponse<{totalCount, items}>
  return data?.data?.items ?? []
}

export async function getAdvisoryReport(advrId: number) {
  const { data } = await advisoryApi.get(`/api/advisory/reports/${advrId}`)
  return data?.data ?? data
}

export async function viewAdvisoryReport(advrId: number) {
  const { data } = await advisoryApi.post(`/api/advisory/reports/${advrId}/view`)
  return data?.data ?? data
}

export async function ackAdvisoryReport(advrId: number, body: AdvisoryAckBody) {
  const { data } = await advisoryApi.post(`/api/advisory/reports/${advrId}/ack`, body)
  return data?.data ?? data
}

// ── 어드바이저리 규칙 ─────────────────────────────────────────────────────

export type AdvisoryRule = {
  ruleId: number
  ruleName: string
  ruleContent: string
  isActive: boolean
}

export async function getAdvisoryRules() {
  const { data } = await advisoryApi.get('/api/advisory/rules', {
    headers: { 'X-Actor-Role': 'AUDITOR' },
  })
  return (data?.data?.items ?? data?.data ?? []) as AdvisoryRuleResponse[]
}

export type AdvisoryRuleResponse = {
  ruleId: number
  ruleCd: string
  ruleName: string
  advisoryTypeCd: string
  ruleCategoryCd: string
  severityCd: string
  ruleParams?: string
  ruleVersion?: string
  activeYn: boolean
  effectiveStartDate?: string
  effectiveEndDate?: string
  ruleDesc?: string
}

export type UpdateAdvisoryRuleBody = {
  activeYn?: boolean
  ruleParams?: string
  ruleVersion?: string
  effectiveStartDate?: string
  effectiveEndDate?: string
  ruleDesc?: string
  changeReasonCd?: string
  changeRemark?: string
}

export async function updateAdvisoryRule(ruleId: number, payload: UpdateAdvisoryRuleBody) {
  const { data } = await advisoryApi.put(`/api/advisory/rules/${ruleId}`, payload, {
    headers: { 'X-Actor-Role': 'ADMIN' },
  })
  return data?.data ?? data
}

// ── 감사 의견 ─────────────────────────────────────────────────────────────

const AUDITOR_HDR = { headers: { 'X-Actor-Role': 'AUDITOR' } }

export async function getAuditOpinionsByReport(advrId: number) {
  const { data } = await advisoryApi.get(`/api/advisory/audit/opinions/by-report/${advrId}`, AUDITOR_HDR)
  return (data?.data ?? []) as Record<string, unknown>[]
}

export async function getAuditOpinionsByReviewer(reviewerId: number) {
  const { data } = await advisoryApi.get(`/api/advisory/audit/opinions/by-reviewer/${reviewerId}`, AUDITOR_HDR)
  return (data?.data ?? []) as Record<string, unknown>[]
}

export async function getReviewerRiskScore(reviewerId: number) {
  const { data } = await advisoryApi.get(`/api/advisory/audit/risk-scores/${reviewerId}`, AUDITOR_HDR)
  return data?.data ?? data
}

export async function getRecentAuditOpinions(limit = 20) {
  const { data } = await advisoryApi.get('/api/advisory/audit/opinions/recent', {
    ...AUDITOR_HDR, params: { limit },
  })
  return (data?.data ?? []) as Record<string, unknown>[]
}

export async function getTopBiasRiskScores(limit = 20) {
  const { data } = await advisoryApi.get('/api/advisory/audit/risk-scores/top/bias', {
    ...AUDITOR_HDR, params: { limit },
  })
  return (data?.data ?? []) as Record<string, unknown>[]
}

export async function getTopComplianceRiskScores(limit = 20) {
  const { data } = await advisoryApi.get('/api/advisory/audit/risk-scores/top/compliance', {
    ...AUDITOR_HDR, params: { limit },
  })
  return (data?.data ?? []) as Record<string, unknown>[]
}

export async function getQuarantineList() {
  const { data } = await advisoryApi.get('/api/advisory/audit/quarantine', AUDITOR_HDR)
  return (data?.data ?? []) as Record<string, unknown>[]
}

// ── 통계 ──────────────────────────────────────────────────────────────────

export async function getReviewerStats(reviewerId: number) {
  const { data } = await advisoryApi.get(`/api/advisory/stats/reviewers/${reviewerId}`)
  return data?.data ?? data
}
