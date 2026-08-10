import axios from 'axios'

// 자동심사 호출도 게이트웨이를 거친다.
//
// 서비스 주소를 직접 가리키면 게이트웨이를 건너뛰게 되고, 그러면 검증된 신원
// 헤더(X-Customer-Id·X-Employee-Id·X-User-Role)가 붙지 않는다. 그 헤더 위에
// 서 있는 인가가 통째로 무의미해진다.
//
// 서비스별 NEXT_PUBLIC_*_API_URL 오버라이드를 없앤 것도 같은 이유다 —
// 남겨 두면 환경변수 한 줄로 우회로가 되살아난다.
const autoReviewApi = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8088',
  headers: { 'Content-Type': 'application/json' },
})

autoReviewApi.interceptors.request.use((config) => {
  if (typeof window === 'undefined') return config
  const token = localStorage.getItem('accessToken')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export type AutoReviewInput = {
  // Layer 1: persona
  age: number
  sex?: string
  maritalStatus?: string
  occupation?: string
  district?: string
  province?: string
  housingType?: string
  educationLevel?: string
  applicantSegment?: string
  // Layer 2: financial
  annualIncomeKw?: number
  totalDebtKw?: number
  creditDebtKw?: number
  totalAssetKw?: number
  dsr?: number
  ltv?: number
  creditScoreProxy?: number
  delinquencyHistory24m?: number
  // Layer 3: application
  productCode?: string
  requestedAmountKw?: number
  requestedPeriodMo?: number
  purposeCd?: string
  purposeRedFlag?: boolean
}

export type AutoReviewEvaluateResult = {
  modelVersion: string
  pdModelVersion?: string
  pd: number
  decisionScore?: number
  proba?: Record<string, number>
  track: string
  trackDisplayName: string
  pdThreshold: number
  safetyMarginThreshold: number
  hardFailCodes: string[]
  hardFailMessages: string[]
  rationale: string
  reportStatus: string
  shadow: boolean
}

export async function evaluateAutoReview(body: AutoReviewInput): Promise<AutoReviewEvaluateResult> {
  const { data } = await autoReviewApi.post('/api/ai/auto-review/evaluate', body)
  return data?.data ?? data
}
