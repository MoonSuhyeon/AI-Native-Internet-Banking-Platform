'use client'

import { useState, useEffect, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { creditInfoReportApi } from '@/lib/loan-api'

const STATUS_MAP: Record<string, { text: string; cls: string }> = {
  REQUESTED: { text: '신청',    cls: 'bg-yellow-100 text-yellow-700 border-yellow-300' },
  SENT:      { text: '전송완료', cls: 'bg-blue-100 text-blue-700 border-blue-300' },
  ACKED:     { text: 'ACK완료', cls: 'bg-green-100 text-green-700 border-green-300' },
  FAILED:    { text: '실패',    cls: 'bg-red-100 text-red-700 border-red-300' },
  DEAD:      { text: '폐기',    cls: 'bg-gray-200 text-gray-600 border-gray-300' },
}

function StatusBadge({ status }: { status: string }) {
  const s = STATUS_MAP[status] ?? { text: status, cls: 'bg-gray-100 text-gray-500 border-gray-300' }
  return (
    <span className={`text-[11px] px-2 py-0.5 rounded border ${s.cls}`}>{s.text}</span>
  )
}

function formatDt(dt: string | null) {
  if (!dt) return '-'
  return dt.slice(0, 16).replace('T', ' ')
}

export default function AdminCreditReportPage() {
  const [reports, setReports] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<number | null>(null)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  // ACK 모달
  const [ackTarget, setAckTarget] = useState<number | null>(null)
  const [ackNo, setAckNo] = useState('')

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string) { setErr(m); setTimeout(() => setErr(''), 3000) }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const { data: res } = await creditInfoReportApi.list({ size: 50 })
      setReports(res.data?.items ?? [])
    } catch { fail('목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  async function handleRetry(crptId: number) {
    setBusy(crptId)
    try {
      await creditInfoReportApi.retry(crptId)
      notify(`crptId ${crptId} 재시도 요청을 보냈습니다.`)
      await load()
    } catch (e: any) { fail(e?.response?.data?.message ?? '재시도 실패') }
    finally { setBusy(null) }
  }

  function openAck(crptId: number) {
    setAckTarget(crptId)
    setAckNo(`ACK-${crptId}-${Date.now()}`)
  }

  async function submitAck() {
    if (!ackTarget || !ackNo) return
    setBusy(ackTarget)
    try {
      await creditInfoReportApi.ack(ackTarget, {
        externalAckNo: ackNo,
        ackedAt: new Date().toISOString(),
      })
      notify(`crptId ${ackTarget} ACK 처리 완료.`)
      setAckTarget(null)
      await load()
    } catch (e: any) { fail(e?.response?.data?.message ?? 'ACK 처리 실패') }
    finally { setBusy(null) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">신용정보 보고서 관리</span>
        </div>
        <div className="px-6 py-5 max-w-5xl">
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-lg font-bold text-gray-800">신용정보 보고서 관리</h1>
            {!loading && (
              <span className="text-[12px] text-gray-500">총 {reports.length}건</span>
            )}
          </div>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
            {loading ? (
              <p className="py-10 text-center text-sm text-gray-400">불러오는 중...</p>
            ) : reports.length === 0 ? (
              <p className="py-10 text-center text-sm text-gray-400">보고서가 없습니다.</p>
            ) : (
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    {['crptId', 'cntrId', '상태', '외부기관', '신고일시', 'ACK일시', '처리'].map(h => (
                      <th key={h} className="px-4 py-3 text-left text-xs text-gray-600 font-semibold">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {reports.map((r: any) => (
                    <tr key={r.crptId} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 text-gray-400 text-xs">{r.crptId}</td>
                      <td className="px-4 py-3 font-mono font-bold text-gray-800">{r.cntrId}</td>
                      <td className="px-4 py-3"><StatusBadge status={r.crptStatusCd} /></td>
                      <td className="px-4 py-3 text-gray-600">{r.crptAgencyCd ?? '-'}</td>
                      <td className="px-4 py-3 text-gray-500 text-xs">{formatDt(r.reportedAt)}</td>
                      <td className="px-4 py-3 text-gray-500 text-xs">{formatDt(r.ackAt)}</td>
                      <td className="px-4 py-3">
                        <div className="flex gap-2">
                          {(r.crptStatusCd === 'FAILED' || r.crptStatusCd === 'DEAD') && (
                            <button
                              onClick={() => handleRetry(r.crptId)}
                              disabled={busy === r.crptId}
                              className="px-3 py-1 text-[11px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50"
                            >
                              재시도
                            </button>
                          )}
                          {r.crptStatusCd === 'SENT' && (
                            <button
                              onClick={() => openAck(r.crptId)}
                              disabled={busy === r.crptId}
                              className="px-3 py-1 text-[11px] border border-blue-300 text-blue-600 rounded hover:bg-blue-50 disabled:opacity-50"
                            >
                              ACK 처리
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </main>

      {ackTarget && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50" onClick={() => setAckTarget(null)}>
          <div className="bg-white rounded-xl shadow-xl w-full max-w-md p-6" onClick={e => e.stopPropagation()}>
            <h3 className="text-[14px] font-bold text-gray-800 mb-4">ACK 처리 — crptId {ackTarget}</h3>
            <label className="block text-[13px] text-gray-600 mb-1">외부 ACK 번호</label>
            <input
              type="text"
              value={ackNo}
              onChange={e => setAckNo(e.target.value)}
              className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] mb-4 focus:outline-none"
              placeholder="외부 기관 ACK 번호"
            />
            <p className="text-[11px] text-gray-400 mb-4">처리 시각은 현재 시각으로 자동 설정됩니다.</p>
            <div className="flex justify-end gap-2">
              <button onClick={() => setAckTarget(null)} className="px-4 py-2 text-[13px] border border-gray-300 rounded hover:bg-gray-50">취소</button>
              <button onClick={submitAck} disabled={!ackNo || busy === ackTarget}
                className="px-4 py-2 text-[13px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50">확인</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
