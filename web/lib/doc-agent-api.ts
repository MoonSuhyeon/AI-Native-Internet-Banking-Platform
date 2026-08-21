import axios from 'axios'
import { readAccessToken } from '@/lib/token'

// 서류심사 호출도 게이트웨이를 거친다.
//
// 서비스 주소를 직접 가리키면 게이트웨이를 건너뛰게 되고, 그러면 검증된 신원
// 헤더(X-Customer-Id·X-Employee-Id·X-User-Role)가 붙지 않는다. 그 헤더 위에
// 서 있는 인가가 통째로 무의미해진다.
//
// 서비스별 NEXT_PUBLIC_*_API_URL 오버라이드를 없앤 것도 같은 이유다 —
// 남겨 두면 환경변수 한 줄로 우회로가 되살아난다.
const docAgentApi = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8088',
  headers: { 'Content-Type': 'application/json' },
})

docAgentApi.interceptors.request.use((config) => {
  if (typeof window === 'undefined') return config
  const token = readAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export type DocSubmission = {
  submission_id: string
  application_id: string
  doc_code: string
  verify_status: string
  human_review_status: string
  forgery_score: number | '-'
  legal_hold: boolean
  created_at: string
}

export type HumanReviewDecision = 'CLEARED' | 'CONFIRMED_FORGERY'

// ── 휴먼리뷰 큐 ──────────────────────────────────────────────────────────

export async function getHumanReviewQueue(): Promise<DocSubmission[]> {
  const { data } = await docAgentApi.get('/api/documents/queue')
  return Array.isArray(data) ? data : []
}

export async function decideHumanReview(submissionId: string, decision: HumanReviewDecision, reviewerId: string) {
  const { data } = await docAgentApi.post(`/api/documents/${submissionId}/review`, {
    decision,
    reviewer_id: reviewerId,
  })
  return data
}

// ── 리걸홀드 ──────────────────────────────────────────────────────────────

export async function enableLegalHold(submissionId: string) {
  const { data } = await docAgentApi.patch(`/api/documents/${submissionId}/legal-hold/enable`)
  return data
}

export async function disableLegalHold(submissionId: string) {
  const { data } = await docAgentApi.patch(`/api/documents/${submissionId}/legal-hold/disable`)
  return data
}
