/**
 * Fraud Investigation Agent — 어드민 콘솔 ↔ Python/LangGraph 사이드카 클라이언트.
 *
 * **게이트웨이(8080) 경유로만 호출한다.** 사이드카를 직접 부르지 않는다.
 * 인증: api.ts 인터셉터가 localStorage('accessToken')의 JWT를 Bearer로 첨부 →
 * 게이트웨이가 클라이언트발 X-Employee-Id·X-User-Role 을 제거한 뒤 JWT 클레임으로
 * 주입 → 사이드카가 그 신원으로 RBAC 판정·감사 기록.
 *
 * 왜 직접 호출을 버렸는가:
 *   조사 승인(/approve)은 지급정지·STR 을 발동시키는 지점이라 "누가 승인했는가"가
 *   감사의 핵심이다. 브라우저가 사이드카를 직접 부르면 신원을 클라이언트가 자칭하게
 *   되고(예전 화면의 역할 체크박스), 그 승인 기록은 증거가 되지 못한다.
 *   사이드카는 이제 compose 의 internal 네트워크에 있어 호스트에서 닿지도 않는다.
 *
 * HITL: investigate → (분석가 검토) → approve. 동작(지급정지·STR)은 approve 가
 * RBAC(FRAUD_OFFICER) 통과 시에만 실행(목). 에이전트는 권고까지만.
 */
import axios from 'axios'

import { api } from './api'

/** 게이트웨이의 fraud 라우트 접두어 — application.yml 의 fraud-agent 라우트와 짝. */
const FRAUD_BASE = '/api/v1/internal/fraud'

// ── 타입 (src/agent/api.py 응답 스키마 미러) ──────────────────────────────

export type AttackScenario =
  | 'H1_VOICE_PHISHING'
  | 'H2_ACCOUNT_TAKEOVER'
  | 'H3_LAUNDERING'
  | 'H4_INSIDER'
  | 'H5_BENIGN'

export type RecommendationStatus =
  | 'CONFIRMED' | 'FAIL_CLOSED' | 'PROVISIONAL' | 'HOLD' | 'BENIGN'

export type LiabilityGrade = 'L4' | 'L3' | 'L2' | 'L1' | 'L0'

export type ActionType =
  | 'FREEZE_PAYMENT' | 'FILE_STR' | 'ESCALATE' | 'NONE'

export type CaseSummary = {
  name: string
  description?: string | null
  alert_id: string
  account: string
  customer_id: string
  amount: number
  payee?: string | null
  channel?: string | null
  anomaly_score: number
}

export type TraceStep = {
  loop: number
  tool: string
  reason: string
  signal: string
  source?: string | null  // "real"=실 백엔드 호출 / null=목
  decisive_fact?: string | null
  scenarios: Record<string, number>
  closed_scenarios: string[]
  budget_left: number
  gate: 'plan' | 'recommend'
}

export type ProposedAction = {
  type: ActionType
  target?: string | null
  reason?: string | null
}

export type DecisiveFact = {
  kind: 'DEATH' | 'GUARDIANSHIP'
  source: string
  detail?: string | null
}

/** 근거 자료 한 건. 원문 위치를 반드시 남긴다 — 되짚을 수 없으면 근거가 아니다. */
export type EvidenceSource = {
  /** STATUTE(법령) · PRECEDENT(판례) · GUIDANCE(당국) · MANUAL(업무자료) */
  type: 'STATUTE' | 'PRECEDENT' | 'GUIDANCE' | 'MANUAL'
  ref: string          // 원문 위치 — 조/항 또는 문서·페이지
  note?: string | null // 자료에 적힌 내용 (해석 아님)
}

/**
 * 조사 우선순위 등급의 근거. 네 층으로 나뉜다.
 *
 *   ① fact        관찰된 사실
 *   ② sources     근거 자료 (원문 위치)
 *   ③ implication 자료가 말하는 것 — 확인이 필요한 사항
 *   ④ grade       **우리가 정한** 내부 조사 우선순위
 *
 * ③에서 멈춘다. "그러므로 은행이 배상한다" 는 사건 경위·다른 법률이 함께 걸리는
 * 판단이라 여기서 결론짓지 않는다. `grade` 는 법에 있는 등급이 아니다.
 */
export type TriageBasis = {
  rule_id: string
  fact: string
  sources: EvidenceSource[]
  implication: string[]
  grade: LiabilityGrade
  weight: number
  track: string
  /** 법령·판례로 확인됐는가. 거짓이면 아직 업무자료 수준의 근거다. */
  verified: boolean
  verified_note?: string | null
}

export type Recommendation = {
  scenario: AttackScenario
  status: RecommendationStatus
  tags: string[]
  rationale_chain: string[]
  liability_grade: LiabilityGrade
  /**
   * 등급의 규정 근거. 없으면 null — 규정 위반이 아닌 사건(정상)도 있고,
   * 아직 등급표에 없는 사건도 있다.
   *
   * 이게 없으면 "왜 L4인가" 의 답이 "결정적 사실이라서" 가 되는데, 등급을 정한
   * 근거가 등급을 정한 규칙 자신이라 감사에서 순환이 된다.
   */
  regulation?: TriageBasis | null
  actions: ProposedAction[]
  decisive_fact?: DecisiveFact | null   // fail-closed(사망·후견) 헤드라인 근거
}

export type InvestigateResponse = {
  case: string
  description?: string | null
  alert: {
    id: string
    account: string
    customer_id: string
    tx_context: { amount: number; payee?: string | null; time?: string | null; channel?: string | null }
    anomaly_score: number
  }
  initial_scenarios: Record<string, number>
  steps: TraceStep[]
  recommendation: Recommendation
  thread_id: string
  hitl_pending: boolean
}

export type ApproveResponse = {
  thread_id: string
  approved: boolean
  executed_actions: string[]
}

// ── 호출 ──────────────────────────────────────────────────────────────────

/** 조사 입력 후보(트리아지 큐 대용) — data/cases/*.json 목록. */
export async function listFraudCases(): Promise<CaseSummary[]> {
  const { data } = await api.get<CaseSummary[]>(`${FRAUD_BASE}/cases`)
  return data
}

/** 한 사건을 조사 루프에 태워 단계별 트레이스 + 권고를 받는다. 동작은 HITL 대기. */
export async function runInvestigation(caseName: string): Promise<InvestigateResponse> {
  const { data } = await api.post<InvestigateResponse>(`${FRAUD_BASE}/investigate`, { case: caseName })
  return data
}

/** 분석가 승인(HITL) + RBAC. approved=false 면 거부. 동작 실행은 목. */
export async function approveInvestigation(
  threadId: string,
  actorRoles: string[],
  approved: boolean,
): Promise<ApproveResponse> {
  const { data } = await api.post<ApproveResponse>(`${FRAUD_BASE}/approve`, {
    thread_id: threadId,
    actor_roles: actorRoles,
    approved,
  })
  return data
}

// ── 표시 라벨 ──────────────────────────────────────────────────────────────

export const SCENARIO_LABEL: Record<string, string> = {
  H1_VOICE_PHISHING:   'H1 보이스피싱',
  H2_ACCOUNT_TAKEOVER: 'H2 계정탈취',
  H3_LAUNDERING:       'H3 자금세탁',
  H4_INSIDER:          'H4 내부자',
  H5_BENIGN:           'H5 정상(오탐)',
}

// fail-closed 결정적 사실 → 헤드라인 라벨 (경합 시나리오와 다른 책임 축, L4)
export const DECISIVE_LABEL: Record<string, string> = {
  DEATH:        '사망계좌 · 권리자 적격성(L4)',
  GUARDIANSHIP: '성년후견 · 단독거래 무효(L4)',
}

export const STATUS_LABEL: Record<string, string> = {
  CONFIRMED:   '확정',
  FAIL_CLOSED: 'Fail-closed(즉시차단)',
  PROVISIONAL: '잠정(예산소진·인계)',
  HOLD:        '판단보류',
  BENIGN:      '정상(오탐)',
}

export const ACTION_LABEL: Record<string, string> = {
  FREEZE_PAYMENT: '지급정지',
  FILE_STR:       'STR 보고',
  ESCALATE:       '분석가 에스컬레이션',
  NONE:           '조치 불요',
}

export function errMsg(e: unknown, fallback: string): string {
  if (axios.isAxiosError(e)) {
    // 응답 온 에러는 서버 메시지(detail) 우선. detail 이 없으면(예: 500) axios 내부 문구
    // ('Request failed with status code 500')를 UI에 노출하지 않고 안내 폴백을 쓴다.
    // 연결 실패(사이드카 다운 등 — 응답 없음)도 'Network Error' 대신 폴백(serve.py 실행 안내).
    return e.response ? (e.response.data?.detail || fallback) : fallback
  }
  return fallback
}
