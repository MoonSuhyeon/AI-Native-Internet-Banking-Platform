'use client'

import { useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { evaluateAutoReview, AutoReviewInput, AutoReviewEvaluateResult } from '@/lib/auto-review-api'

const TRACK_CLS: Record<string, string> = {
  TRACK_1: 'bg-green-100 text-green-800 border-green-300',
  TRACK_2: 'bg-red-100 text-red-800 border-red-300',
  TRACK_3: 'bg-yellow-100 text-yellow-800 border-yellow-300',
}

const SEX_OPTIONS    = ['M', 'F']
const MARITAL        = ['SINGLE', 'MARRIED', 'DIVORCED', 'WIDOWED']
const OCCUPATION     = ['EMPLOYED', 'SELF_EMPLOYED', 'UNEMPLOYED', 'STUDENT', 'RETIRED']
const HOUSING        = ['OWN', 'RENT', 'FAMILY', 'OTHER']
const EDUCATION      = ['HIGH_SCHOOL', 'BACHELOR', 'MASTER', 'DOCTOR', 'OTHER']
const SEGMENT        = ['PREMIUM', 'GENERAL', 'YOUTH', 'SENIOR']
const PURPOSE        = ['HOME_PURCHASE', 'HOME_IMPROVEMENT', 'VEHICLE', 'EDUCATION', 'BUSINESS', 'OTHER']

function numOrUndef(v: string): number | undefined {
  const n = Number(v)
  return v === '' || isNaN(n) ? undefined : n
}

export default function AutoReviewSimPage() {
  const [form, setForm] = useState<Partial<AutoReviewInput>>({ age: 35 })
  const [result, setResult] = useState<AutoReviewEvaluateResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  function setF<K extends keyof AutoReviewInput>(k: K, v: AutoReviewInput[K]) {
    setForm(prev => ({ ...prev, [k]: v }))
  }
  function setNum(k: keyof AutoReviewInput, v: string) {
    setF(k, numOrUndef(v) as any)
  }

  async function run() {
    if (!form.age) return
    setLoading(true); setErr(''); setResult(null)
    try {
      const res = await evaluateAutoReview(form as AutoReviewInput)
      setResult(res)
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? e?.message ?? '평가 실패')
    } finally { setLoading(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">자동심사 시뮬레이터</span>
        </div>
        <div className="px-6 py-5 max-w-5xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">자동심사 시뮬레이터 (Dry-run)</h1>

          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          <div className="grid grid-cols-2 gap-5">
            {/* 입력 폼 */}
            <div className="space-y-4">
              <FormCard title="Layer 1 · 인적사항">
                <Row label="나이 *">
                  <input type="number" value={form.age ?? ''} onChange={e => setNum('age', e.target.value)}
                    className={INPUT} />
                </Row>
                <Row label="성별">
                  <Select value={form.sex ?? ''} onChange={v => setF('sex', v || undefined)} options={['', ...SEX_OPTIONS]} />
                </Row>
                <Row label="결혼 상태">
                  <Select value={form.maritalStatus ?? ''} onChange={v => setF('maritalStatus', v || undefined)} options={['', ...MARITAL]} />
                </Row>
                <Row label="직업">
                  <Select value={form.occupation ?? ''} onChange={v => setF('occupation', v || undefined)} options={['', ...OCCUPATION]} />
                </Row>
                <Row label="주거 유형">
                  <Select value={form.housingType ?? ''} onChange={v => setF('housingType', v || undefined)} options={['', ...HOUSING]} />
                </Row>
                <Row label="학력">
                  <Select value={form.educationLevel ?? ''} onChange={v => setF('educationLevel', v || undefined)} options={['', ...EDUCATION]} />
                </Row>
                <Row label="고객 세그먼트">
                  <Select value={form.applicantSegment ?? ''} onChange={v => setF('applicantSegment', v || undefined)} options={['', ...SEGMENT]} />
                </Row>
                <Row label="지역 (시도)">
                  <input type="text" value={form.province ?? ''} onChange={e => setF('province', e.target.value || undefined)}
                    placeholder="예: SEOUL" className={INPUT} />
                </Row>
              </FormCard>

              <FormCard title="Layer 2 · 재무정보">
                <Row label="연소득 (만원)">
                  <input type="number" value={form.annualIncomeKw ?? ''} onChange={e => setNum('annualIncomeKw', e.target.value)} className={INPUT} />
                </Row>
                <Row label="총부채 (만원)">
                  <input type="number" value={form.totalDebtKw ?? ''} onChange={e => setNum('totalDebtKw', e.target.value)} className={INPUT} />
                </Row>
                <Row label="신용부채 (만원)">
                  <input type="number" value={form.creditDebtKw ?? ''} onChange={e => setNum('creditDebtKw', e.target.value)} className={INPUT} />
                </Row>
                <Row label="DSR (소수)">
                  <input type="number" step="0.01" value={form.dsr ?? ''} onChange={e => setNum('dsr', e.target.value)}
                    placeholder="예: 0.35" className={INPUT} />
                </Row>
                <Row label="LTV (소수)">
                  <input type="number" step="0.01" value={form.ltv ?? ''} onChange={e => setNum('ltv', e.target.value)}
                    placeholder="예: 0.6" className={INPUT} />
                </Row>
                <Row label="신용점수 (proxy)">
                  <input type="number" value={form.creditScoreProxy ?? ''} onChange={e => setNum('creditScoreProxy', e.target.value)}
                    placeholder="0~1000" className={INPUT} />
                </Row>
                <Row label="24개월 연체 횟수">
                  <input type="number" value={form.delinquencyHistory24m ?? ''} onChange={e => setNum('delinquencyHistory24m', e.target.value)} className={INPUT} />
                </Row>
              </FormCard>

              <FormCard title="Layer 3 · 신청 정보">
                <Row label="상품코드">
                  <input type="text" value={form.productCode ?? ''} onChange={e => setF('productCode', e.target.value || undefined)}
                    placeholder="예: P001" className={INPUT} />
                </Row>
                <Row label="신청금액 (만원)">
                  <input type="number" value={form.requestedAmountKw ?? ''} onChange={e => setNum('requestedAmountKw', e.target.value)} className={INPUT} />
                </Row>
                <Row label="신청기간 (개월)">
                  <input type="number" value={form.requestedPeriodMo ?? ''} onChange={e => setNum('requestedPeriodMo', e.target.value)} className={INPUT} />
                </Row>
                <Row label="목적">
                  <Select value={form.purposeCd ?? ''} onChange={v => setF('purposeCd', v || undefined)} options={['', ...PURPOSE]} />
                </Row>
                <Row label="목적 위험 플래그">
                  <label className="flex items-center gap-2 text-[12px]">
                    <input type="checkbox" checked={form.purposeRedFlag ?? false} onChange={e => setF('purposeRedFlag', e.target.checked)} />
                    RED FLAG
                  </label>
                </Row>
              </FormCard>

              <button onClick={run} disabled={loading || !form.age}
                className="w-full py-2.5 bg-kb-admin text-white text-[13px] font-semibold rounded hover:opacity-90 disabled:opacity-50">
                {loading ? '평가 중...' : '자동심사 평가 실행'}
              </button>
            </div>

            {/* 결과 패널 */}
            <div>
              {result ? (
                <div className="space-y-4 sticky top-5">
                  <div className={`border-2 rounded-lg p-5 ${TRACK_CLS[result.track] ?? 'bg-gray-100 border-gray-300'}`}>
                    <div className="text-[11px] font-bold uppercase tracking-wider mb-1 opacity-70">트랙 결정</div>
                    <div className="text-2xl font-bold mb-0.5">{result.track}</div>
                    <div className="text-[14px] font-semibold">{result.trackDisplayName}</div>
                  </div>

                  <FormCard title="스코어">
                    <div className="grid grid-cols-2 gap-3 text-[12px]">
                      <KV k="PD (부도확률)" v={`${(result.pd * 100).toFixed(2)}%`} />
                      <KV k="PD 임계값"    v={`${(result.pdThreshold * 100).toFixed(2)}%`} />
                      {result.decisionScore != null && (
                        <KV k="승인확률(Decision)" v={`${(result.decisionScore * 100).toFixed(2)}%`} />
                      )}
                      <KV k="Safety Margin" v={`${(result.safetyMarginThreshold * 100).toFixed(2)}%`} />
                      <KV k="모델 버전"    v={result.modelVersion} />
                      {result.pdModelVersion && <KV k="PD 모델"  v={result.pdModelVersion} />}
                      {result.shadow && <KV k="모드" v="Shadow (측정 전용)" />}
                    </div>
                  </FormCard>

                  {result.proba && Object.keys(result.proba).length > 0 && (
                    <FormCard title="클래스 확률">
                      <div className="text-[12px] space-y-1">
                        {Object.entries(result.proba).map(([k, v]) => (
                          <div key={k} className="flex justify-between">
                            <span className="text-gray-500">{k}</span>
                            <span className="font-mono">{(v * 100).toFixed(2)}%</span>
                          </div>
                        ))}
                      </div>
                    </FormCard>
                  )}

                  {result.hardFailMessages.length > 0 && (
                    <FormCard title={`Hard Fail (${result.hardFailMessages.length}건)`}>
                      <ul className="space-y-1">
                        {result.hardFailMessages.map((m, i) => (
                          <li key={i} className="text-[12px] text-red-700 flex gap-2">
                            <span className="font-mono text-[10px] text-red-400 mt-0.5">{result.hardFailCodes[i]}</span>
                            <span>{m}</span>
                          </li>
                        ))}
                      </ul>
                    </FormCard>
                  )}

                  {result.rationale && (
                    <FormCard title="결정 근거">
                      <p className="text-[13px] text-gray-700 leading-relaxed">{result.rationale}</p>
                    </FormCard>
                  )}
                </div>
              ) : (
                <div className="bg-white border border-dashed border-gray-300 rounded-lg p-10 text-center text-sm text-gray-400 h-64 flex items-center justify-center">
                  왼쪽 폼을 채우고 평가를 실행하세요.
                </div>
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}

const INPUT = 'w-full border border-gray-300 rounded px-2 py-1 text-[12px] focus:outline-none'

function FormCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-white border border-gray-200 rounded-lg p-4">
      <h3 className="text-[12px] font-semibold text-gray-600 mb-3 pb-1.5 border-b border-gray-100">{title}</h3>
      <div className="space-y-2">{children}</div>
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2">
      <span className="text-[11px] text-gray-500 w-28 flex-shrink-0">{label}</span>
      <div className="flex-1">{children}</div>
    </div>
  )
}

function Select({ value, onChange, options }: { value: string; onChange: (v: string) => void; options: string[] }) {
  return (
    <select value={value} onChange={e => onChange(e.target.value)}
      className="w-full border border-gray-300 rounded px-2 py-1 text-[12px] focus:outline-none">
      {options.map(o => <option key={o} value={o}>{o || '(선택 안함)'}</option>)}
    </select>
  )
}

function KV({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <p className="text-[10px] text-gray-400 mb-0.5">{k}</p>
      <p className="font-semibold text-gray-800">{v}</p>
    </div>
  )
}
