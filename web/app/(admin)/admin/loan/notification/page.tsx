'use client'

import { useState, useEffect, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { notificationOutboxApi } from '@/lib/loan-api'

const STATUS_MAP: Record<string, { text: string; cls: string }> = {
  SENT:    { text: '발송완료', cls: 'bg-green-100 text-green-700 border-green-300' },
  FAILED:  { text: '실패',     cls: 'bg-red-100 text-red-700 border-red-300' },
  PENDING: { text: '대기',     cls: 'bg-yellow-100 text-yellow-700 border-yellow-300' },
}

function StatusBadge({ status }: { status: string }) {
  const s = STATUS_MAP[status] ?? { text: status, cls: 'bg-gray-100 text-gray-500 border-gray-300' }
  return (
    <span className={`text-[11px] px-2 py-0.5 rounded border ${s.cls}`}>{s.text}</span>
  )
}

function formatDt(dt: string | null | undefined) {
  if (!dt) return '-'
  return dt.slice(0, 16).replace('T', ' ')
}

export default function AdminNotificationPage() {
  const [list, setList] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<number | null>(null)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  // single lookup
  const [searchId, setSearchId] = useState('')
  const [detail, setDetail] = useState<any>(null)
  const [lookupMode, setLookupMode] = useState(false)
  const [lookupLoading, setLookupLoading] = useState(false)

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string) { setErr(m); setTimeout(() => setErr(''), 3000) }

  const loadList = useCallback(async () => {
    setLoading(true)
    try {
      const { data: res } = await notificationOutboxApi.list({ size: 30 })
      setList(res.data?.items ?? [])
    } catch { fail('목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { loadList() }, [loadList])

  async function handleLookup() {
    if (!searchId) return
    setLookupLoading(true)
    setDetail(null)
    try {
      const { data: res } = await notificationOutboxApi.get(Number(searchId))
      setDetail(res.data ?? res)
      setLookupMode(true)
    } catch (e: any) {
      const status = e?.response?.status
      if (status === 404) fail('해당 발송 이력이 없습니다.')
      else fail(e?.response?.data?.message ?? '조회 실패')
      setLookupMode(false)
    }
    finally { setLookupLoading(false) }
  }

  function clearLookup() {
    setDetail(null)
    setLookupMode(false)
    setSearchId('')
  }

  async function handleRetry(outboxId: number) {
    setBusy(outboxId)
    try {
      await notificationOutboxApi.retry(outboxId)
      notify(`outboxId ${outboxId} 재시도 요청 완료.`)
      if (lookupMode && detail?.outboxId === outboxId) {
        const { data: res } = await notificationOutboxApi.get(outboxId)
        setDetail(res.data ?? res)
      } else {
        await loadList()
      }
    } catch (e: any) { fail(e?.response?.data?.message ?? '재시도 실패') }
    finally { setBusy(null) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">알림 발송함 관리</span>
        </div>
        <div className="px-6 py-5 max-w-5xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">알림 발송함 관리</h1>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {/* 단건 조회 */}
          <div className="bg-white border border-gray-200 rounded-lg p-4 mb-5">
            <div className="flex gap-3 items-center">
              <label className="text-[12px] text-gray-600">outboxId</label>
              <input
                type="number"
                value={searchId}
                onChange={e => setSearchId(e.target.value)}
                placeholder="번호 입력"
                onKeyDown={e => e.key === 'Enter' && handleLookup()}
                className="border border-gray-300 rounded px-3 py-1.5 text-[13px] w-36 focus:outline-none"
              />
              <button
                onClick={handleLookup}
                disabled={lookupLoading || !searchId}
                className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50"
              >
                {lookupLoading ? '조회 중...' : '조회'}
              </button>
              {lookupMode && (
                <button onClick={clearLookup}
                  className="px-3 py-1.5 text-[12px] border border-gray-300 rounded hover:bg-gray-50">
                  목록으로
                </button>
              )}
            </div>
          </div>

          {/* 단건 상세 카드 */}
          {lookupMode && detail && (
            <div className="bg-white border border-gray-200 rounded-lg p-5 mb-5">
              <h2 className="text-[13px] font-semibold text-gray-700 mb-4">발송 상세</h2>
              <dl className="grid grid-cols-2 gap-x-8 gap-y-3 text-[13px]">
                {[
                  ['outboxId', detail.outboxId],
                  ['유형(eventTypeCd)', detail.eventTypeCd],
                  ['수신자(referenceId)', detail.referenceId],
                  ['상태', <StatusBadge key="s" status={detail.status} />],
                  ['발송일시', formatDt(detail.sentAt)],
                  ['생성일시', formatDt(detail.createdAt)],
                ].map(([label, value]) => (
                  <div key={String(label)} className="flex gap-3">
                    <dt className="w-40 text-gray-500 shrink-0">{label}</dt>
                    <dd className="text-gray-800 font-medium">{value as any}</dd>
                  </div>
                ))}
              </dl>
              {detail.status === 'FAILED' && (
                <div className="mt-4">
                  <button
                    onClick={() => handleRetry(detail.outboxId)}
                    disabled={busy === detail.outboxId}
                    className="px-4 py-1.5 text-[12px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50"
                  >
                    재시도
                  </button>
                </div>
              )}
            </div>
          )}

          {/* 목록 테이블 */}
          {!lookupMode && (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              {loading ? (
                <p className="py-10 text-center text-sm text-gray-400">불러오는 중...</p>
              ) : list.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-400">발송 이력이 없습니다.</p>
              ) : (
                <table className="w-full text-[13px]">
                  <thead>
                    <tr className="bg-gray-50 border-b border-gray-200">
                      {['outboxId', '유형', '수신자', '상태', '발송일시', '처리'].map(h => (
                        <th key={h} className="px-4 py-3 text-left text-xs text-gray-600 font-semibold">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {list.map((r: any) => (
                      <tr key={r.outboxId} className="hover:bg-gray-50 transition-colors">
                        <td className="px-4 py-3 text-gray-400 text-xs">{r.outboxId}</td>
                        <td className="px-4 py-3 text-gray-600">{r.eventTypeCd}</td>
                        <td className="px-4 py-3 text-gray-700">{r.referenceId}</td>
                        <td className="px-4 py-3"><StatusBadge status={r.status} /></td>
                        <td className="px-4 py-3 text-gray-500 text-xs">{formatDt(r.sentAt)}</td>
                        <td className="px-4 py-3">
                          {r.status === 'FAILED' && (
                            <button
                              onClick={() => handleRetry(r.outboxId)}
                              disabled={busy === r.outboxId}
                              className="px-3 py-1 text-[11px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50"
                            >
                              재시도
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
