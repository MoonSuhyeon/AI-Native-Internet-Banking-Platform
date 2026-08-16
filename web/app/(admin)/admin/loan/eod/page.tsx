'use client'

import { useState, useEffect, useCallback, Fragment } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { eodApi } from '@/lib/loan-api'
import { useAdminRoles } from '@/components/admin/RoleGate'
import { hasAnyRole, BankRole } from '@/lib/admin-auth'

const JOB_STATUS: Record<string, { text: string; cls: string }> = {
  COMPLETED: { text: '완료',   cls: 'bg-green-100  text-green-700  border-green-300' },
  FAILED:    { text: '실패',   cls: 'bg-red-100    text-red-700    border-red-300' },
  STARTED:   { text: '실행중', cls: 'bg-blue-100   text-blue-700   border-blue-300' },
  STOPPED:   { text: '중단',   cls: 'bg-gray-100   text-gray-600   border-gray-300' },
  SKIPPED:   { text: '건너뜀', cls: 'bg-gray-100   text-gray-500   border-gray-300' },
  REJECTED:  { text: '거부',   cls: 'bg-orange-100 text-orange-700 border-orange-300' },
  NOT_FOUND: { text: '없음',   cls: 'bg-gray-100   text-gray-500   border-gray-300' },
}

type StepInfo = {
  stepExecutionId: number
  stepName: string
  status: string
  exitCode: string
  durationMs: number | null
  exitDescription: string | null
}
type EodHistory = {
  jobExecutionId: number
  baseDate: string
  status: string
  exitCode: string
  startTime: string | null
  endTime: string | null
  durationMs: number | null
  steps: StepInfo[]
}

function todayYmd() {
  const d = new Date()
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
}

function StatusBadge({ status }: { status: string }) {
  const s = JOB_STATUS[status]
  return (
    <span className={`text-[11px] px-2 py-0.5 rounded border ${s?.cls ?? 'bg-gray-100 text-gray-500 border-gray-300'}`}>
      {s?.text ?? status}
    </span>
  )
}

export default function LoanEodPage() {
  const roles = useAdminRoles()
  const canRun = hasAnyRole(roles, BankRole.OPS) // EOD 배치 — 운영팀 전용

  const [baseDate, setBaseDate] = useState(todayYmd())
  const [from, setFrom] = useState('')
  const [to, setTo]     = useState('')
  const [history, setHistory] = useState<EodHistory[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')
  const [openId, setOpenId] = useState<number | null>(null)

  const loadHistory = useCallback(async () => {
    setLoading(true)
    try {
      const res = await eodApi.history(from || undefined, to || undefined)
      setHistory(res.data?.data ?? [])
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? '이력 조회 실패')
    } finally { setLoading(false) }
  }, [from, to])

  useEffect(() => { if (canRun) loadHistory() }, [canRun, loadHistory])

  function notify(m: string) { setMsg(m); setErr(''); setTimeout(() => setMsg(''), 4000) }
  function fail(m: string)   { setErr(m); setMsg(''); setTimeout(() => setErr(''), 5000) }

  async function run(kind: 'run' | 'restart') {
    if (!/^\d{8}$/.test(baseDate)) { fail('baseDate 는 YYYYMMDD 8자리여야 합니다.'); return }
    setBusy(true); setMsg(''); setErr('')
    try {
      const res = kind === 'run' ? await eodApi.run(baseDate) : await eodApi.restart(baseDate)
      const r = res.data?.data
      notify(`[${r?.jobStatus}] ${r?.message ?? '처리되었습니다.'}`)
      await loadHistory()
    } catch (e: any) {
      fail(e?.response?.data?.message ?? '실행 실패')
    } finally { setBusy(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">EOD 배치</span>
        </div>

        <div className="px-6 py-5 max-w-5xl">
          <h1 className="text-lg font-bold text-gray-800 mb-1">EOD 일마감 배치</h1>
          <p className="text-[12px] text-gray-500 mb-5">이자발생 → 자동이체 → 연체롤오버 → 승인만료 순으로 실행됩니다. (운영팀 전용)</p>

          {roles.length === 0 ? (
            <p className="py-10 text-center text-sm text-gray-400">권한 확인 중...</p>
          ) : !canRun ? (
            <div className="px-4 py-3 bg-yellow-50 border border-yellow-300 text-yellow-800 text-sm rounded">
              이 기능은 운영팀(ROLE_OPS) 권한이 필요합니다.
            </div>
          ) : (
            <>
              {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
              {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

              {/* 실행 패널 */}
              <div className="bg-white border border-gray-200 rounded-lg p-4 mb-6">
                <div className="flex flex-wrap items-end gap-3">
                  <label className="text-[12px] text-gray-600">
                    기준일(baseDate)
                    <input type="text" value={baseDate} onChange={e => setBaseDate(e.target.value)} placeholder="YYYYMMDD"
                      className="ml-2 border border-gray-300 rounded px-3 py-1.5 text-[13px] w-32 focus:outline-none" />
                  </label>
                  <button onClick={() => run('run')} disabled={busy}
                    className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                    {busy ? '처리 중...' : '배치 실행'}
                  </button>
                  <button onClick={() => run('restart')} disabled={busy}
                    className="px-5 py-1.5 border border-kb-admin text-kb-admin text-[13px] rounded hover:bg-blue-50 disabled:opacity-50">
                    실패 잡 재처리
                  </button>
                </div>
              </div>

              {/* 이력 */}
              <div className="flex items-center justify-between mb-3">
                <h2 className="text-[14px] font-bold text-gray-800">실행 이력</h2>
                <div className="flex items-center gap-2">
                  <input type="text" value={from} onChange={e => setFrom(e.target.value)} placeholder="from YYYYMMDD"
                    className="border border-gray-300 px-2 py-1 text-[12px] rounded w-32 focus:outline-none" />
                  <span className="text-gray-400 text-xs">~</span>
                  <input type="text" value={to} onChange={e => setTo(e.target.value)} placeholder="to YYYYMMDD"
                    className="border border-gray-300 px-2 py-1 text-[12px] rounded w-32 focus:outline-none" />
                  <button onClick={loadHistory} disabled={loading}
                    className="px-4 py-1 bg-gray-700 text-white text-[12px] rounded hover:opacity-90 disabled:opacity-50">
                    {loading ? '조회 중...' : '조회'}
                  </button>
                </div>
              </div>

              <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
                {loading ? (
                  <p className="py-10 text-center text-sm text-gray-400">불러오는 중...</p>
                ) : history.length === 0 ? (
                  <p className="py-10 text-center text-sm text-gray-400">실행 이력이 없습니다.</p>
                ) : (
                  <table className="w-full text-[13px]">
                    <thead>
                      <tr className="bg-gray-50 border-b border-gray-200">
                        {['실행ID', '기준일', '상태', 'exitCode', '시작', '종료', '소요', ''].map(h => (
                          <th key={h} className="px-4 py-3 text-left font-semibold text-gray-600">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {history.map(h => (
                        <Fragment key={h.jobExecutionId}>
                          <tr className="hover:bg-gray-50">
                            <td className="px-4 py-3 text-gray-400 text-xs">{h.jobExecutionId}</td>
                            <td className="px-4 py-3 font-mono font-bold text-gray-800">{h.baseDate}</td>
                            <td className="px-4 py-3"><StatusBadge status={h.status} /></td>
                            <td className="px-4 py-3 text-gray-600">{h.exitCode}</td>
                            <td className="px-4 py-3 text-gray-400 text-xs">{h.startTime?.slice(0, 19).replace('T', ' ') ?? '-'}</td>
                            <td className="px-4 py-3 text-gray-400 text-xs">{h.endTime?.slice(0, 19).replace('T', ' ') ?? '-'}</td>
                            <td className="px-4 py-3 text-gray-500 text-xs">{h.durationMs != null ? `${(h.durationMs / 1000).toFixed(1)}s` : '-'}</td>
                            <td className="px-4 py-3">
                              {h.steps?.length > 0 && (
                                <button onClick={() => setOpenId(openId === h.jobExecutionId ? null : h.jobExecutionId)}
                                  className="text-[12px] text-kb-admin hover:underline">
                                  스텝 {openId === h.jobExecutionId ? '▴' : '▾'}
                                </button>
                              )}
                            </td>
                          </tr>
                          {openId === h.jobExecutionId && (
                            <tr>
                              <td colSpan={8} className="px-4 py-3 bg-gray-50">
                                <table className="w-full text-[12px]">
                                  <thead>
                                    <tr className="text-gray-500">
                                      {['스텝', '상태', 'exitCode', '소요', '설명'].map(s => (
                                        <th key={s} className="px-2 py-1 text-left font-medium">{s}</th>
                                      ))}
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {h.steps.map(st => (
                                      <tr key={st.stepExecutionId} className="border-t border-gray-200">
                                        <td className="px-2 py-1 text-gray-700">{st.stepName}</td>
                                        <td className="px-2 py-1"><StatusBadge status={st.status} /></td>
                                        <td className="px-2 py-1 text-gray-600">{st.exitCode}</td>
                                        <td className="px-2 py-1 text-gray-500">{st.durationMs != null ? `${(st.durationMs / 1000).toFixed(1)}s` : '-'}</td>
                                        <td className="px-2 py-1 text-gray-500">{st.exitDescription || '-'}</td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </td>
                            </tr>
                          )}
                        </Fragment>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  )
}
