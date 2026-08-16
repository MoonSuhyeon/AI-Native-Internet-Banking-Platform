'use client'

import { useState } from 'react'
import { KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE } from '@/lib/theme'

interface Anomaly {
  category: string
  this_month_amount: number
  prev_avg_amount: number
  ratio: number | null
  alert_type: string
  message: string
}

interface PlanItem {
  category: string
  alert_type: string
  current_amount: number
  estimated_saving: number
  actions: string[]
}

interface SpendingGoal {
  category: string
  current_amount: number
  prev_avg_amount: number
  target_amount: number
  reduction_rate: number
  cost_type: string
}

interface AgentStep {
  tool: string
  result_summary: string
}

interface SpendingResult {
  agent_type: string
  this_month_summary: Record<string, number>
  this_month_total: number
  prev_avg_total: number
  anomalies: Anomaly[]
  opportunities: unknown[]
  plan_items: PlanItem[]
  spending_goals: SpendingGoal[]
  total_estimated_saving: number
  message?: string
  behavior_analysis?: string
  behavior_categories?: string[]
  warning?: string
  agent_steps?: AgentStep[]
}

const TOOL_LABELS: Record<string, string> = {
  get_transaction_history: '거래내역 조회',
  analyze_spending_by_category: '카테고리별 집계',
  detect_anomalies: '이상 지출 탐지',
  find_saving_opportunities: '절약 가능 항목 탐색',
  generate_improvement_plan: '개선 방안 생성',
  set_monthly_spending_goal: '다음 달 목표 설정',
}

function fmt(n: number) {
  return n.toLocaleString('ko-KR')
}

function CategoryBadge({ type }: { type: string }) {
  const colors: Record<string, string> = {
    '식비': '#F59E0B',
    '교통': '#3B82F6',
    '쇼핑': '#EC4899',
    '문화/여가': '#8B5CF6',
    '공과금': '#6B7280',
    '의료': '#10B981',
    '금융': '#EF4444',
    '기타': '#9CA3AF',
  }
  return (
    <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full text-white"
      style={{ backgroundColor: colors[type] ?? '#9CA3AF' }}>
      {type}
    </span>
  )
}

function AlertBadge({ type }: { type: string }) {
  if (type === 'SPIKE') return (
    <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-600">급증</span>
  )
  if (type === 'NEW_SPENDING') return (
    <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-orange-100 text-orange-600">신규</span>
  )
  return (
    <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-blue-100 text-blue-600">절약기회</span>
  )
}

function MarkdownSection({ text }: { text: string }) {
  const lines = text.split('\n')
  return (
    <div className="space-y-1 text-[13px]">
      {lines.map((line, i) => {
        if (line.startsWith('### ')) {
          return (
            <p key={i} className="font-bold text-[14px] mt-4 mb-1 first:mt-0" style={{ color: KB_PRIMARY }}>
              {line.replace('### ', '')}
            </p>
          )
        }
        if (line.startsWith('- ')) {
          return (
            <div key={i} className="flex items-start gap-1.5 text-kb-text">
              <span className="mt-1 w-1.5 h-1.5 rounded-full flex-shrink-0 bg-gray-400" />
              <span>{line.replace('- ', '')}</span>
            </div>
          )
        }
        if (line === '') return <div key={i} className="h-1" />
        return <p key={i} className="text-kb-text">{line}</p>
      })}
    </div>
  )
}

export default function SpendingPage() {
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<SpendingResult | null>(null)
  const [error, setError] = useState('')
  const [currentStep, setCurrentStep] = useState<string | null>(null)
  const [completedSteps, setCompletedSteps] = useState<string[]>([])
  const [userMessage, setUserMessage] = useState('')
  const [mode, setMode] = useState<'data' | 'chat'>('data')

  const STEPS = [
    'get_transaction_history',
    'analyze_spending_by_category',
    'detect_anomalies',
    'find_saving_opportunities',
    'generate_improvement_plan',
    'set_monthly_spending_goal',
  ]

  async function runAnalysis(message?: string) {
    setLoading(true)
    setResult(null)
    setError('')
    setCompletedSteps([])

    const accessToken = localStorage.getItem('accessToken')
    const customerId = localStorage.getItem('customerId')
    if (!accessToken || !customerId) { setError('로그인이 필요합니다.'); setLoading(false); return }

    const finalMessage = message || userMessage || '지출 패턴을 분석하고 개선 방안을 알려주세요.'

    setCurrentStep('분석 중...')

    try {
      const res = await fetch('/api/agent/spending', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`,
          'X-Customer-Id': customerId,
        },
        body: JSON.stringify({ message: finalMessage }),
      })
      const data = await res.json()
      setCompletedSteps(STEPS)
      setCurrentStep(null)
      if (data.error) { setError(data.error); return }
      setResult(data)
    } catch {
      setError('분석 서버와 통신할 수 없습니다.')
    } finally {
      setLoading(false)
    }
  }

  const totalSaving = result?.total_estimated_saving ?? 0
  const thisTotal = result?.this_month_total ?? 0
  const prevAvg = result?.prev_avg_total ?? 0
  const changeRate = prevAvg > 0 ? ((thisTotal - prevAvg) / prevAvg * 100) : 0
  const isBehaviorMode = !!result?.behavior_analysis

  return (
    <div className="max-w-kb-container mx-auto px-6 py-10 pb-16">
      {/* 브레드크럼 */}
      <div className="flex justify-end mb-4 text-[12px] text-kb-text-muted gap-1">
        <span>개인뱅킹</span><span>&gt;</span>
        <span className="font-semibold text-kb-text">지출 패턴 분석</span>
      </div>

      {/* 헤더 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-kb-text mb-1">지출 패턴 관리 에이전트</h1>
        <p className="text-[13px] text-kb-text-muted">
          거래 데이터 분석 또는 소비 고민을 직접 입력해 분석받으세요.
        </p>
      </div>

      {/* 모드 탭 */}
      <div className="flex gap-2 mb-4">
        {(['data', 'chat'] as const).map(m => (
          <button key={m} onClick={() => { setMode(m); setResult(null); setError('') }}
            className="px-5 py-2 text-[13px] font-semibold rounded-full border transition-colors"
            style={mode === m
              ? { backgroundColor: KB_PRIMARY, color: '#fff', borderColor: KB_PRIMARY }
              : { backgroundColor: '#fff', color: '#6B7280', borderColor: '#D1D5DB' }}>
            {m === 'data' ? '거래 데이터 분석' : '소비 고민 입력'}
          </button>
        ))}
      </div>

      {/* 자연어 입력 (chat 모드) */}
      {mode === 'chat' && (
        <div className="rounded-xl border p-4 mb-4" style={{ borderColor: KB_PRIMARY_BORDER, backgroundColor: KB_PRIMARY_SURFACE }}>
          <p className="text-[12px] text-kb-text-muted mb-2">소비 고민을 자유롭게 입력하세요</p>
          <div className="flex gap-2">
            <input
              type="text"
              value={userMessage}
              onChange={e => setUserMessage(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && !loading && runAnalysis()}
              placeholder="예: 요즘 배달앱만 계속 써, 카페 지출이 너무 많아"
              className="flex-1 border rounded-lg px-3 py-2 text-[13px] outline-none focus:ring-1"
              style={{ borderColor: '#D1D5DB' }}
            />
            <button onClick={() => runAnalysis()} disabled={loading || !userMessage.trim()}
              className="px-5 py-2 text-[13px] font-bold text-white rounded-lg disabled:opacity-50"
              style={{ backgroundColor: KB_PRIMARY }}>
              분석
            </button>
          </div>
          <div className="flex gap-2 mt-2 flex-wrap">
            {['요즘 배달앱만 계속 써', '카페 지출이 너무 많아', '편의점을 자주 가게 돼', '구독 서비스가 많아'].map(s => (
              <button key={s} onClick={() => { setUserMessage(s); runAnalysis(s) }}
                className="text-[11px] px-3 py-1 rounded-full border hover:bg-white transition-colors"
                style={{ borderColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                {s}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 데이터 분석 버튼 (data 모드) */}
      {mode === 'data' && !result && !loading && (
        <div className="rounded-xl border p-10 text-center mb-4" style={{ borderColor: KB_PRIMARY_BORDER, backgroundColor: KB_PRIMARY_SURFACE }}>
          <div className="text-4xl mb-4">📊</div>
          <p className="text-[15px] font-semibold text-kb-text mb-2">지출 패턴 분석을 시작해보세요</p>
          <p className="text-[13px] text-kb-text-muted mb-6">
            최근 3개월 거래내역을 기반으로 이상 지출을 탐지하고<br />
            카테고리별 절약 방안과 다음 달 목표를 제안합니다.
          </p>
          <button onClick={() => runAnalysis()} className="px-10 py-3 text-[14px] font-bold text-white rounded-xl hover:opacity-85"
            style={{ backgroundColor: KB_PRIMARY }}>
            지출 분석 시작
          </button>
        </div>
      )}

      {/* 에러 */}
      {error && (
        <div className="rounded-xl px-5 py-4 mb-4 text-[13px] text-red-600 bg-red-50 border border-red-200">
          {error}
        </div>
      )}

      {/* 로딩 — 에이전트 진행 단계 */}
      {loading && (
        <div className="rounded-xl border p-6 mb-6" style={{ borderColor: KB_PRIMARY_BORDER, backgroundColor: KB_PRIMARY_SURFACE }}>
          <p className="text-[13px] font-semibold mb-4" style={{ color: KB_PRIMARY }}>에이전트 분석 진행 중</p>
          <div className="space-y-3">
            {STEPS.map((step) => {
              const done = completedSteps.includes(step)
              const active = currentStep === step
              return (
                <div key={step} className="flex items-center gap-3">
                  <div className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 text-[11px] font-bold transition-colors
                    ${done ? 'bg-green-500 text-white' : active ? 'bg-yellow-400 text-white animate-pulse' : 'bg-gray-200 text-gray-400'}`}>
                    {done ? '✓' : active ? '→' : ''}
                  </div>
                  <span className={`text-[13px] ${done ? 'text-green-600 font-medium' : active ? 'text-kb-text font-semibold' : 'text-kb-text-muted'}`}>
                    {TOOL_LABELS[step]}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* 분석 결과 */}
      {result && (
        <div className="space-y-6">

          {/* 행동 기반 추정 분석 (데이터 없을 때) */}
          {isBehaviorMode && result.behavior_analysis && (
            <>
              <div className="rounded-xl px-5 py-3 text-[12px] text-orange-700 bg-orange-50 border border-orange-200 flex items-center gap-2">
                <span>⚠</span>
                <span>거래 데이터가 없어 메시지 기반 행동 추정 분석을 수행했습니다. 실제 거래 내역이 있으면 더 정확한 분석이 가능합니다.</span>
              </div>
              {result.behavior_categories && result.behavior_categories.length > 0 && (
                <div className="flex items-center gap-2">
                  <span className="text-[12px] text-kb-text-muted">탐지된 소비 패턴:</span>
                  {result.behavior_categories.map(cat => <CategoryBadge key={cat} type={cat} />)}
                </div>
              )}
              <section className="rounded-xl border overflow-hidden" style={{ borderColor: KB_PRIMARY_BORDER }}>
                <div className="px-5 py-3" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <span className="text-[13px] font-bold" style={{ color: KB_PRIMARY }}>행동 기반 소비 분석 결과</span>
                </div>
                <div className="px-5 py-5">
                  <MarkdownSection text={result.behavior_analysis} />
                </div>
              </section>
            </>
          )}

          {/* 데이터 기반 분석 결과 */}
          {!isBehaviorMode && (
            <>
              {/* 1. 현재 소비 분석 */}
              <section className="rounded-xl border overflow-hidden" style={{ borderColor: KB_PRIMARY_BORDER }}>
                <div className="px-5 py-3" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <span className="text-[13px] font-bold" style={{ color: KB_PRIMARY }}>1. 현재 소비 상태</span>
                </div>
                <div className="px-5 py-5">
                  <div className="grid grid-cols-3 gap-4">
                    <div className="rounded-xl p-4 text-center" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                      <p className="text-[12px] text-kb-text-muted mb-1">이번 달 총 지출</p>
                      <p className="text-[22px] font-bold text-kb-text">{fmt(thisTotal)}<span className="text-[14px] font-normal ml-1">원</span></p>
                    </div>
                    <div className="rounded-xl p-4 text-center" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                      <p className="text-[12px] text-kb-text-muted mb-1">직전 월 평균</p>
                      <p className="text-[22px] font-bold text-kb-text">{fmt(prevAvg)}<span className="text-[14px] font-normal ml-1">원</span></p>
                    </div>
                    <div className="rounded-xl p-4 text-center" style={{
                      backgroundColor: changeRate > 0 ? '#FEF2F2' : '#F0FDF4',
                      border: `1px solid ${changeRate > 0 ? '#FECACA' : '#BBF7D0'}`
                    }}>
                      <p className="text-[12px] text-kb-text-muted mb-1">전월 대비</p>
                      <p className="text-[22px] font-bold" style={{ color: changeRate > 0 ? '#DC2626' : '#16A34A' }}>
                        {changeRate > 0 ? '+' : ''}{changeRate.toFixed(1)}%
                      </p>
                    </div>
                  </div>
                  {Object.keys(result.this_month_summary).length > 0 && (
                    <div className="mt-4">
                      <p className="text-[12px] text-kb-text-muted mb-2">카테고리별 지출</p>
                      <div className="flex flex-wrap gap-2">
                        {Object.entries(result.this_month_summary).sort((a, b) => b[1] - a[1]).map(([cat, amt]) => (
                          <div key={cat} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white border text-[12px]"
                            style={{ borderColor: KB_PRIMARY_BORDER }}>
                            <CategoryBadge type={cat} />
                            <span className="text-kb-text-muted">{cat}</span>
                            <span className="font-semibold text-kb-text">{fmt(amt)}원</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </section>

              {/* 2. 이상 소비 판단 */}
              <section className="rounded-xl border overflow-hidden" style={{ borderColor: KB_PRIMARY_BORDER }}>
                <div className="px-5 py-3 flex items-center gap-2" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <span className="text-[13px] font-bold" style={{ color: KB_PRIMARY }}>2. 이상 소비 판단</span>
                  {result.anomalies.length > 0 && (
                    <span className="text-[11px] font-bold px-2 py-0.5 rounded-full bg-red-100 text-red-600">{result.anomalies.length}건</span>
                  )}
                </div>
                <div className="px-5 py-4">
                  {result.anomalies.length === 0 ? (
                    <p className="text-[13px] text-kb-text-muted py-2">이상 지출 없음 — 이번 달 소비 패턴이 평소와 유사합니다.</p>
                  ) : (
                    <div className="space-y-3">
                      {result.anomalies.map((a, i) => (
                        <div key={i} className="flex items-start gap-4 p-4 rounded-xl" style={{ backgroundColor: '#FEF2F2', border: '1px solid #FECACA' }}>
                          <div className="flex-1">
                            <div className="flex items-center gap-2 mb-1">
                              <CategoryBadge type={a.category} />
                              <AlertBadge type={a.alert_type} />
                            </div>
                            <p className="text-[12px] text-kb-text-muted mt-1">{a.message}</p>
                          </div>
                          <div className="text-right flex-shrink-0">
                            <p className="text-[18px] font-bold text-red-600">{fmt(a.this_month_amount)}원</p>
                            {a.ratio && <p className="text-[11px] text-red-500">평균 대비 {(a.ratio * 100 - 100).toFixed(0)}% 증가</p>}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </section>

              {/* 3~5. 원인분석 + 절약 + 개선방안 — 구조화 텍스트 */}
              {result.message && (
                <section className="rounded-xl border overflow-hidden" style={{ borderColor: KB_PRIMARY_BORDER }}>
                  <div className="px-5 py-3" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                    <span className="text-[13px] font-bold" style={{ color: KB_PRIMARY }}>원인 분석 · 절약 · 개선 방안</span>
                  </div>
                  <div className="px-5 py-5">
                    <MarkdownSection text={result.message.split('### 3.')[1]
                      ? '### 3.' + result.message.split('### 3.').slice(1).join('### 3.')
                      : result.message} />
                  </div>
                </section>
              )}

              {/* 다음 달 목표 */}
              {result.spending_goals.length > 0 && (
                <section className="rounded-xl border overflow-hidden" style={{ borderColor: KB_PRIMARY_BORDER }}>
                  <div className="px-5 py-3 flex items-center justify-between" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                    <span className="text-[13px] font-bold" style={{ color: KB_PRIMARY }}>다음 달 소비 목표</span>
                    <span className="text-[13px] font-bold text-green-600">월 {fmt(totalSaving)}원 절약 가능</span>
                  </div>
                  <div className="px-5 py-4 space-y-3">
                    {result.spending_goals.map((goal, i) => {
                      const pct = goal.current_amount > 0 ? Math.min((goal.target_amount / goal.current_amount) * 100, 100) : 100
                      return (
                        <div key={i}>
                          <div className="flex items-center justify-between mb-1">
                            <div className="flex items-center gap-2">
                              <CategoryBadge type={goal.category} />
                              <span className="text-[13px] font-medium text-kb-text">{goal.category}</span>
                              <span className="text-[11px] text-kb-text-muted">{goal.cost_type}</span>
                            </div>
                            <div className="text-right text-[12px]">
                              <span className="text-kb-text-muted">{fmt(goal.current_amount)}원</span>
                              <span className="mx-1 text-kb-text-muted">→</span>
                              <span className="font-bold" style={{ color: KB_PRIMARY }}>{fmt(goal.target_amount)}원</span>
                              <span className="ml-1 text-green-600">(-{goal.reduction_rate}%)</span>
                            </div>
                          </div>
                          <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
                            <div className="h-full rounded-full" style={{ width: `${pct}%`, backgroundColor: KB_PRIMARY }} />
                          </div>
                        </div>
                      )
                    })}
                  </div>
                </section>
              )}
            </>
          )}

          {/* 에이전트 처리 단계 */}
          {result.agent_steps && result.agent_steps.length > 0 && (
            <section className="rounded-xl border overflow-hidden" style={{ borderColor: KB_PRIMARY_BORDER }}>
              <div className="px-5 py-3" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <span className="text-[13px] font-bold" style={{ color: KB_PRIMARY }}>에이전트 처리 단계</span>
              </div>
              <div className="px-5 py-3 space-y-2">
                {result.agent_steps.map((step, i) => (
                  <div key={i} className="flex items-start gap-3 text-[12px]">
                    <span className="w-5 h-5 rounded-full bg-green-500 text-white flex items-center justify-center text-[10px] font-bold flex-shrink-0 mt-0.5">✓</span>
                    <div>
                      <span className="font-semibold text-kb-text">{TOOL_LABELS[step.tool] ?? step.tool}</span>
                      <span className="text-kb-text-muted ml-2">— {step.result_summary}</span>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}

          {/* 재분석 */}
          <div className="flex justify-center gap-3 pt-2">
            <button onClick={() => runAnalysis()} disabled={loading}
              className="px-10 py-3 text-[14px] font-bold text-white rounded-xl hover:opacity-85 disabled:opacity-60"
              style={{ backgroundColor: KB_PRIMARY }}>
              다시 분석하기
            </button>
            <button onClick={() => { setResult(null); setUserMessage('') }}
              className="px-8 py-3 text-[14px] font-medium border rounded-xl hover:bg-gray-50"
              style={{ borderColor: '#D1D5DB', color: '#6B7280' }}>
              초기화
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
