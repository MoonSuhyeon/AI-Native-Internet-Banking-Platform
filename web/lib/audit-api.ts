import { api } from './api'

export type ReviewerRiskScoreDto = {
  reviewerId: number
  biasScore: number
  complianceScore: number
  evaluationCount: number
  lastEvaluatedAt: string | null
}

export type AiAuditOpinionDto = {
  opinionId: number
  advrId: number | null
  revId: number
  reviewerId: number
  analysisTypeCd: string
  conclusionCd: string
  reasoningSummary: string
  confidenceScore: number
  inputTokens: number
  outputTokens: number
  generatedAt: string
}

export type QuarantineReportDto = {
  advrId: number
  revId: number
  targetReviewerId: number
  advisoryTypeCd: string
  severityCd: 'WARN' | 'CRITICAL'
  advrTitle: string
  quarantinedAt: string
  generatedAt: string
}

function unwrap<T>(res: { data: { data: T } }): T {
  return res.data.data
}

export async function fetchTopBiasRiskScores(limit = 20): Promise<ReviewerRiskScoreDto[]> {
  return unwrap(await api.get('/api/advisory/audit/risk-scores/top/bias', { params: { limit } }))
}

export async function fetchRecentOpinions(limit = 20): Promise<AiAuditOpinionDto[]> {
  return unwrap(await api.get('/api/advisory/audit/opinions/recent', { params: { limit } }))
}

export async function fetchQuarantineReports(): Promise<QuarantineReportDto[]> {
  return unwrap(await api.get('/api/advisory/audit/quarantine'))
}

/** 격리 처분. 사유는 비울 수 없다 — 서버가 400 으로 막는다. */
export type QuarantineDisposition = 'RELEASED' | 'REVIEW_REASSIGNED' | 'AUDIT_REFERRED'

/**
 * 격리를 처분한다.
 *
 * 행위자는 보내지 않는다. 게이트웨이가 검증한 신원만 서버가 쓴다 — 본문에 사번을
 * 넣게 두면 "누가 풀었는가" 가 자칭이 된다.
 */
export async function disposeQuarantine(
  advrId: number,
  disposition: QuarantineDisposition,
  note: string,
): Promise<QuarantineReportDto> {
  return unwrap(
    await api.post(`/api/advisory/audit/quarantine/${advrId}/disposition`, { disposition, note }),
  )
}
