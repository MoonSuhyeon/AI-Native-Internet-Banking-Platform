'use client'

import { useCallback, useEffect, useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import {
  listFraudCases, runInvestigation, approveInvestigation,
  CaseSummary, InvestigateResponse, TraceStep, ApproveResponse,
  SCENARIO_LABEL, STATUS_LABEL, ACTION_LABEL, DECISIVE_LABEL, errMsg,
} from '@/lib/fraud-agent-api'

const STATUS_CLS: Record<string, string> = {
  CONFIRMED:   'bg-red-100 text-red-700 border-red-300',
  FAIL_CLOSED: 'bg-rose-200 text-rose-900 border-rose-400',
  PROVISIONAL: 'bg-amber-100 text-amber-700 border-amber-300',
  HOLD:        'bg-gray-100 text-gray-600 border-gray-300',
  BENIGN:      'bg-green-100 text-green-700 border-green-300',
}

const GRADE_CLS: Record<string, string> = {
  L4: 'bg-rose-600 text-white',
  L3: 'bg-red-500 text-white',
  L2: 'bg-orange-500 text-white',
  L1: 'bg-yellow-500 text-white',
  L0: 'bg-gray-400 text-white',
}

function won(n: number) { return n.toLocaleString('ko-KR') + '원' }

export default function FraudInvestigationPage() {
  const [cases, setCases]       = useState<CaseSummary[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [result, setResult]     = useState<InvestigateResponse | null>(null)
  const [running, setRunning]   = useState(false)
  const [loadingCases, setLoadingCases] = useState(false)
  const [error, setError]       = useState<string | null>(null)

  // HITL
  const [asFraudOfficer, setAsFraudOfficer] = useState(true)
  const [approveResult, setApproveResult]   = useState<ApproveResponse | null>(null)
  const [approving, setApproving]           = useState(false)

  const loadCases = useCallback(async () => {
    setLoadingCases(true); setError(null)
    try {
      const cs = await listFraudCases()
      setCases(cs)
    } catch (e) {
      setError(errMsg(e, '에이전트 서비스에 연결하지 못했습니다. (python scripts/serve.py 실행 여부 확인)'))
    } finally {
      setLoadingCases(false)
    }
  }, [])

  useEffect(() => { loadCases() }, [loadCases])

  async function investigate(name: string) {
    setSelected(name); setRunning(true); setError(null)
    setResult(null); setApproveResult(null)
    try {
      setResult(await runInvestigation(name))
    } catch (e) {
      setError(errMsg(e, '조사 실행에 실패했습니다.'))
    } finally {
      setRunning(false)
    }
  }

  async function approve(approved: boolean) {
    if (!result) return
    setApproving(true); setError(null)
    try {
      const roles = asFraudOfficer ? ['FRAUD_OFFICER'] : []
      setApproveResult(await approveInvestigation(result.thread_id, roles, approved))
    } catch (e) {
      setError(errMsg(e, '승인 처리에 실패했습니다.'))
    } finally {
      setApproving(false)
    }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          이상거래 조사 &gt; <span className="text-gray-800 font-medium">조사 에이전트</span>
        </div>

        <div className="px-6 py-5 max-w-7xl">
          <div className="flex items-center justify-between mb-1">
            <h1 className="text-lg font-bold text-gray-800">이상거래 조사 에이전트 (FDS)</h1>
            <button onClick={loadCases} disabled={loadingCases}
              className="px-4 py-1.5 text-[13px] border border-gray-300 text-gray-600 rounded hover:bg-gray-100 disabled:opacity-50">
              {loadingCases ? '조회 중...' : '큐 새로고침'}
            </button>
          </div>
          <p className="text-xs text-gray-500 mb-4">
            경쟁 가설 5개를 유지하며 증거에 따라 도구를 골라 조사하고, 책임 등급과 함께 권고합니다.
            동작(지급정지·STR)은 분석가 승인(HITL)+RBAC 통과 시에만 실행됩니다 — 에이전트는 권고까지만.
          </p>

          {error && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{error}</div>}

          <div className="grid grid-cols-[300px_1fr] gap-5">
            {/* ── 조사 큐 (트리아지 입력) ── */}
            <div>
              <h2 className="text-[13px] font-semibold text-gray-700 mb-2">조사 큐 (사건 선택)</h2>
              <div className="space-y-2">
                {cases.map(c => (
                  <button key={c.name} onClick={() => investigate(c.name)} disabled={running}
                    className={`w-full text-left border rounded-lg p-3 transition-colors disabled:opacity-60
                      ${selected === c.name ? 'border-kb-admin bg-blue-50' : 'border-gray-200 bg-white hover:border-gray-300'}`}>
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-[12px] font-bold text-gray-800 font-mono">{c.alert_id}</span>
                      <span className={`text-[10px] px-1.5 py-0.5 rounded font-semibold ${
                        c.anomaly_score >= 70 ? 'bg-red-100 text-red-700' :
                        c.anomaly_score >= 40 ? 'bg-orange-100 text-orange-700' : 'bg-gray-100 text-gray-500'}`}>
                        이상도 {c.anomaly_score.toFixed(0)}
                      </span>
                    </div>
                    <p className="text-[11px] text-gray-600 mb-1">{won(c.amount)} → {c.payee ?? '-'} ({c.channel ?? '-'})</p>
                    {c.description && <p className="text-[10px] text-gray-400 line-clamp-2">{c.description}</p>}
                  </button>
                ))}
                {!loadingCases && cases.length === 0 && (
                  <p className="text-[12px] text-gray-400 py-4 text-center">큐가 비었습니다.</p>
                )}
              </div>
            </div>

            {/* ── 조사 트레이스 + 권고 ── */}
            <div>
              {running && (
                <div className="bg-white border border-gray-200 rounded-lg p-8 text-center text-gray-400 text-sm">
                  조사 루프 실행 중…
                </div>
              )}

              {!running && !result && (
                <div className="bg-white border border-gray-200 rounded-lg p-8 text-center text-gray-400 text-sm">
                  왼쪽 큐에서 사건을 선택하면 조사 루프를 실행합니다.
                </div>
              )}

              {!running && result && <TraceView result={result} />}

              {/* HITL */}
              {!running && result && (
                <div className="mt-4 bg-white border border-gray-200 rounded-lg p-5">
                  <h2 className="text-[13px] font-semibold text-gray-700 mb-3 pb-2 border-b border-gray-100">
                    분석가 승인 (HITL) — 권고 동작 발동
                  </h2>
                  {/*
                    이 체크박스는 더 이상 권한을 주지 않는다.
                    지급정지·STR 발동 여부는 로그인한 JWT 의 역할로만 결정된다 —
                    게이트웨이가 클라이언트발 역할 헤더를 제거하고 토큰 클레임으로 주입한다.
                    여기서 보내는 값은 감사에 "자칭 역할(claimed_roles)"로만 남는다.
                  */}
                  <div className="flex items-center gap-4 mb-3">
                    <label className="flex items-center gap-2 text-[12px] text-gray-600">
                      <input type="checkbox" checked={asFraudOfficer}
                        onChange={e => setAsFraudOfficer(e.target.checked)} />
                      FRAUD_OFFICER 권한 주장 (참고용 — 실제 발동 권한은 로그인 계정의 역할로 결정)
                    </label>
                  </div>
                  <p className="text-[11px] text-gray-400 mb-3">
                    지급정지·STR 은 FRAUD_OFFICER 역할로 로그인한 경우에만 실행됩니다.
                    권한이 없으면 승인해도 해당 동작은 거부로 기록됩니다.
                  </p>
                  <div className="flex gap-2">
                    <button onClick={() => approve(true)} disabled={approving}
                      className="px-5 py-1.5 text-[13px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50">
                      {approving ? '처리 중...' : '승인 → 동작 실행'}
                    </button>
                    <button onClick={() => approve(false)} disabled={approving}
                      className="px-5 py-1.5 text-[13px] border border-red-400 text-red-600 rounded hover:bg-red-50 disabled:opacity-50">
                      거부
                    </button>
                  </div>

                  {approveResult && (
                    <div className="mt-3 border-t border-gray-100 pt-3">
                      <p className="text-[11px] text-gray-500 mb-1">
                        실행 결과 {approveResult.approved ? '(승인됨)' : '(거부됨)'}:
                      </p>
                      <ul className="space-y-1">
                        {approveResult.executed_actions.map((a, i) => (
                          <li key={i} className={`text-[12px] px-2 py-1 rounded ${
                            a.startsWith('실행') ? 'bg-green-50 text-green-700' :
                            a.startsWith('거부') ? 'bg-red-50 text-red-600' : 'bg-gray-50 text-gray-600'}`}>
                            {a}
                          </li>
                        ))}
                        {approveResult.executed_actions.length === 0 && (
                          <li className="text-[12px] text-gray-400">실행된 동작 없음 (조치 불요).</li>
                        )}
                      </ul>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}

// ── 트레이스 렌더 ────────────────────────────────────────────────────────────

/**
 * 탐지된 거래 자체를 먼저 보여준다.
 *
 * 조사 결과부터 보여주면 "무엇을 조사한 것인지" 를 화면에서 알 수 없다. 분석가가
 * 가장 먼저 확인하는 것은 금액·시각·채널이고, 그 셋이 사기 판단의 출발점이다.
 * 값은 전부 탐지 경보에서 온다 — 화면이 지어내는 숫자는 없다.
 */
function DetectionCard({ result }: { result: InvestigateResponse }) {
  const a = result.alert
  const tx = a.tx_context
  const rec = result.recommendation
  const score = a.anomaly_score

  // 이상도 구간. 목록 배지(70/40)와 같은 기준을 쓴다 — 두 벌이면 같은 거래가
  // 목록과 상세에서 다른 등급으로 보인다.
  const level = score >= 70 ? 'HIGH' : score >= 40 ? 'MEDIUM' : 'LOW'
  const levelCls = score >= 70 ? 'bg-red-100 text-red-700 border-red-300'
    : score >= 40 ? 'bg-orange-100 text-orange-700 border-orange-300'
    : 'bg-gray-100 text-gray-600 border-gray-300'

  // 결정은 권고 상태에서 온다. 조사가 확정했거나 fail-closed 면 막힌 것이고,
  // 정상 판정이면 진행된 것이다. 화면이 따로 판단하지 않는다.
  const blocked = rec.status === 'CONFIRMED' || rec.status === 'FAIL_CLOSED'
  const pending = rec.status === 'PROVISIONAL' || rec.status === 'HOLD'

  return (
    <div className="bg-white border border-gray-200 rounded-lg p-5">
      <div className="flex items-start justify-between gap-4 mb-4 pb-3 border-b border-gray-100">
        <div>
          <p className="text-[11px] font-semibold text-gray-500 mb-0.5">거래 탐지</p>
          <p className="text-[15px] font-bold text-gray-800 font-mono">{a.id}</p>
        </div>
        <div className="text-right flex-shrink-0">
          <p className="text-[11px] text-gray-500 mb-1">이상도</p>
          <div className="flex items-center gap-2">
            <span className="text-[20px] font-bold text-gray-800 tabular-nums">
              {score.toFixed(0)}
            </span>
            <span className={`text-[11px] px-2 py-0.5 rounded border font-bold ${levelCls}`}>
              {level}
            </span>
          </div>
        </div>
      </div>

      <dl className="grid grid-cols-2 gap-x-6 gap-y-2 mb-4">
        <Field label="고객" value={a.customer_id} />
        <Field label="계좌" value={a.account} mono />
        <Field label="금액" value={won(tx.amount)} strong />
        <Field label="수취" value={tx.payee ?? '—'} />
        <Field label="시간" value={tx.time ?? '—'} mono />
        <Field label="채널" value={tx.channel ?? '—'} />
      </dl>

      <p className="text-[11px] font-semibold text-gray-500 mb-1.5">탐지 근거</p>
      <ul className="mb-4 space-y-0.5">
        {result.steps.map((s, i) => (
          <li key={s.loop} className="text-[12px] text-gray-600 flex gap-2">
            <span className="text-gray-300 font-mono flex-shrink-0">
              {i === result.steps.length - 1 ? '└─' : '├─'}
            </span>
            <span>{s.signal}</span>
          </li>
        ))}
        {result.steps.length === 0 && (
          <li className="text-[12px] text-gray-400">수집된 근거 없음</li>
        )}
      </ul>

      <div className="flex items-center gap-2 pt-3 border-t border-gray-100">
        <span className="text-[11px] font-semibold text-gray-500">결정</span>
        <span className={`text-[12px] font-bold ${blocked ? 'text-red-600' : pending ? 'text-amber-600' : 'text-green-700'}`}>
          ● {blocked ? '차단 — 자금 이동 없음' : pending ? '보류 — 판단 미확정' : '정상 — 진행'}
        </span>
        {result.hitl_pending && (
          <span className="ml-auto text-[11px] text-amber-600 font-medium">
            직원 승인 대기
          </span>
        )}
      </div>
    </div>
  )
}

function Field({ label, value, mono, strong }: {
  label: string; value: string; mono?: boolean; strong?: boolean
}) {
  return (
    <div className="flex gap-3">
      <dt className="text-[12px] text-gray-500 w-12 flex-shrink-0">{label}</dt>
      <dd className={`text-[12px] min-w-0 break-words ${mono ? 'font-mono' : ''} ${strong ? 'font-bold text-gray-800' : 'text-gray-700'}`}>
        {value}
      </dd>
    </div>
  )
}

function TraceView({ result }: { result: InvestigateResponse }) {
  const rec = result.recommendation
  return (
    <div className="space-y-4">
      <DetectionCard result={result} />

      {/* 권고 요약 (상단) */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <div className="flex items-center gap-3 mb-3">
          <span className={`text-[11px] px-2 py-0.5 rounded font-bold ${GRADE_CLS[rec.liability_grade]}`}>
            책임등급 {rec.liability_grade}
          </span>
          <span className={`text-[11px] px-2 py-0.5 rounded border font-semibold ${STATUS_CLS[rec.status]}`}>
            {STATUS_LABEL[rec.status] ?? rec.status}
          </span>
          <span className="text-[14px] font-bold text-gray-800">
            {rec.status === 'FAIL_CLOSED' && rec.decisive_fact
              ? (DECISIVE_LABEL[rec.decisive_fact.kind] ?? rec.decisive_fact.kind)
              : (SCENARIO_LABEL[rec.scenario] ?? rec.scenario)}
          </span>
          {rec.tags.length > 0 && (
            <span className="text-[10px] text-gray-400">태그: {rec.tags.join(', ')}</span>
          )}
        </div>

        <p className="text-[11px] font-semibold text-gray-500 mb-1">근거 사슬</p>
        <ol className="space-y-0.5 mb-3">
          {rec.rationale_chain.map((line, i) => (
            <li key={i} className="text-[12px] text-gray-600">
              <span className="text-gray-300 mr-1.5">{i + 1}.</span>{line}
            </li>
          ))}
        </ol>

        <p className="text-[11px] font-semibold text-gray-500 mb-1">권고 동작 (제안 — 실행 아님)</p>
        <div className="flex flex-wrap gap-1.5">
          {rec.actions.map((a, i) => (
            <span key={i} className="text-[11px] px-2 py-0.5 rounded bg-blue-50 text-blue-700 border border-blue-200">
              {ACTION_LABEL[a.type] ?? a.type}{a.reason ? ` — ${a.reason}` : ''}
            </span>
          ))}
        </div>
        <p className="mt-3 text-[11px] text-amber-600 font-medium">
          ▶ 분석가 승인 대기 (HITL) — 에이전트는 권고까지만
        </p>
      </div>

      {/* 단계별 트레이스 */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-[13px] font-semibold text-gray-700 mb-3 pb-2 border-b border-gray-100">
          조사 루프 트레이스 ({result.steps.length}단계)
        </h2>

        <DistBars title="초기 분포 (균등)" scenarios={result.initial_scenarios} closed={[]} />

        {result.steps.map(step => <StepCard key={step.loop} step={step} />)}
      </div>
    </div>
  )
}

function StepCard({ step }: { step: TraceStep }) {
  return (
    <div className="mt-3 border-l-2 border-cyan-300 pl-3">
      <div className="flex items-center gap-2 mb-1">
        <span className="text-[11px] font-bold text-cyan-700">루프 {step.loop}</span>
        <span className="text-[12px] font-mono text-yellow-700 bg-yellow-50 px-1.5 rounded">{step.tool}</span>
        {step.source === 'real' && (
          <span className="text-[9px] px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700 border border-emerald-300 font-semibold"
            title="customer-service 인증보안계 실 백엔드 호출 (목 아님)">
            ● 실연결
          </span>
        )}
        <span className="ml-auto text-[10px] text-gray-400">예산 {step.budget_left} 남음</span>
        <span className={`text-[10px] px-1.5 py-0.5 rounded font-semibold ${
          step.gate === 'recommend' ? 'bg-green-100 text-green-700' : 'bg-blue-100 text-blue-700'}`}>
          {step.gate === 'recommend' ? '종료 → 권고' : '경합 → 재계획'}
        </span>
      </div>
      <p className="text-[11px] text-gray-500 mb-0.5"><span className="text-gray-400">이유:</span> {step.reason}</p>
      <p className="text-[11px] text-gray-600 mb-1"><span className="text-gray-400">결과:</span> {step.signal}</p>
      {step.decisive_fact && (
        <p className="text-[11px] text-rose-600 font-semibold mb-1">
          결정적 사실: {step.decisive_fact} → fail-closed (예산·가설 무관 즉시 종료)
        </p>
      )}
      <DistBars title="갱신 분포" scenarios={step.scenarios} closed={step.closed_scenarios} />
    </div>
  )
}

function DistBars({ title, scenarios, closed }: { title: string; scenarios: Record<string, number>; closed: string[] }) {
  const entries = Object.entries(scenarios).sort((a, b) => b[1] - a[1])
  const top = entries[0]?.[0]
  return (
    <div className="my-2">
      <p className="text-[10px] text-gray-400 mb-1">{title}</p>
      <div className="space-y-0.5">
        {entries.map(([s, v]) => {
          const isClosed = closed.includes(s)
          const isTop = s === top && !isClosed
          return (
            <div key={s} className="flex items-center gap-2">
              <span className={`text-[10px] w-24 flex-shrink-0 ${isTop ? 'text-green-700 font-semibold' : isClosed ? 'text-gray-300' : 'text-gray-500'}`}>
                {SCENARIO_LABEL[s] ?? s}
              </span>
              <span className={`text-[10px] w-9 text-right tabular-nums ${isClosed ? 'text-gray-300' : 'text-gray-600'}`}>{v.toFixed(2)}</span>
              <div className="flex-1 h-2.5 bg-gray-100 rounded-sm overflow-hidden">
                <div className={`h-full rounded-sm ${isTop ? 'bg-green-500' : isClosed ? 'bg-gray-200' : 'bg-gray-400'}`}
                  style={{ width: `${Math.round(v * 100)}%` }} />
              </div>
              {isClosed && <span className="text-[9px] text-gray-300">닫힘</span>}
            </div>
          )
        })}
      </div>
    </div>
  )
}
