'use client'

import { useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { identityVerificationApi } from '@/lib/loan-api'

const STATUS_MAP: Record<string, { text: string; cls: string }> = {
  VERIFIED: { text: '인증완료', cls: 'bg-green-100 text-green-700 border-green-300' },
  FAILED:   { text: '실패',     cls: 'bg-red-100 text-red-700 border-red-300' },
  PENDING:  { text: '대기',     cls: 'bg-yellow-100 text-yellow-700 border-yellow-300' },
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

type DetailField = {
  label: string
  key: string
  render?: (val: any) => React.ReactNode
}

const FIELDS: DetailField[] = [
  { label: 'idvId',    key: 'idvId' },
  { label: 'applId',   key: 'applId' },
  { label: 'customerId', key: 'customerId' },
  { label: '인증방식', key: 'idvMethodCd' },
  { label: '인증대상', key: 'idvTargetCd' },
  { label: '상태',     key: 'idvStatusCd', render: (v) => <StatusBadge status={v} /> },
  { label: '휴대폰',   key: 'mobileNo' },
  { label: '인증일시', key: 'verifiedAt', render: formatDt },
  { label: '요청일시', key: 'createdAt',  render: formatDt },
]

export default function AdminIdentityPage() {
  const [idvId, setIdvId] = useState('')
  const [result, setResult] = useState<any>(null)
  const [notFound, setNotFound] = useState(false)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  function fail(m: string) { setErr(m); setTimeout(() => setErr(''), 3000) }

  async function handleSearch() {
    if (!idvId) return
    setLoading(true)
    setResult(null)
    setNotFound(false)
    setErr('')
    try {
      const { data: res } = await identityVerificationApi.get(Number(idvId))
      setResult(res.data ?? res)
    } catch (e: any) {
      const status = e?.response?.status
      if (status === 404) setNotFound(true)
      else fail(e?.response?.data?.message ?? '조회 중 오류가 발생했습니다.')
    }
    finally { setLoading(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">본인인증 조회</span>
        </div>
        <div className="px-6 py-5 max-w-5xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">본인인증 조회</h1>

          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {/* 검색 */}
          <div className="bg-white border border-gray-200 rounded-lg p-4 mb-5">
            <div className="flex gap-3 items-center">
              <label className="text-[12px] text-gray-600">idvId</label>
              <input
                type="number"
                value={idvId}
                onChange={e => setIdvId(e.target.value)}
                placeholder="인증 ID 입력"
                onKeyDown={e => e.key === 'Enter' && handleSearch()}
                className="border border-gray-300 rounded px-3 py-1.5 text-[13px] w-40 focus:outline-none"
              />
              <button
                onClick={handleSearch}
                disabled={loading || !idvId}
                className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50"
              >
                {loading ? '조회 중...' : '조회'}
              </button>
            </div>
          </div>

          {/* 결과 없음 */}
          {notFound && (
            <div className="bg-white border border-gray-200 rounded-lg px-6 py-10 text-center text-[13px] text-gray-500">
              해당 인증 이력이 없습니다.
            </div>
          )}

          {/* 결과 카드 */}
          {result && (
            <div className="bg-white border border-gray-200 rounded-lg p-6">
              <h2 className="text-[13px] font-semibold text-gray-700 mb-5">인증 상세 정보</h2>
              <dl className="divide-y divide-gray-100">
                {FIELDS.map(({ label, key, render }) => {
                  const raw = result[key]
                  const display = render ? render(raw) : (raw ?? '-')
                  return (
                    <div key={key} className="flex items-center py-3 gap-4">
                      <dt className="w-36 text-[12px] text-gray-500 shrink-0">{label}</dt>
                      <dd className="text-[13px] text-gray-800 font-medium">{display}</dd>
                    </div>
                  )
                })}
              </dl>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
