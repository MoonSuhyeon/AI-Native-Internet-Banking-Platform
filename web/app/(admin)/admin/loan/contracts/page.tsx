'use client'

import { useState, useEffect, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { loanContractApi } from '@/lib/loan-api'

const STATUS_MAP: Record<string, { text: string; cls: string }> = {
  SIGNED: { text: '약정',   cls: 'bg-blue-100 text-blue-700 border-blue-300' },
  ACTIVE: { text: '실행중', cls: 'bg-green-100 text-green-700 border-green-300' },
  CLOSED: { text: '종결',   cls: 'bg-gray-200 text-gray-600 border-gray-300' },
}

function StatusBadge({ status }: { status: string }) {
  const s = STATUS_MAP[status] ?? { text: status, cls: 'bg-gray-100 text-gray-500 border-gray-300' }
  return <span className={`text-[11px] px-2 py-0.5 rounded border ${s.cls}`}>{s.text}</span>
}

function fmtAmt(v: number) {
  return v ? (v / 10000).toLocaleString('ko-KR') + '만' : '-'
}

function fmtRate(bps: number) {
  return bps != null ? (bps / 100).toFixed(2) + '%' : '-'
}

function fmtDate(s: string) {
  if (!s || s.length !== 8) return s ?? '-'
  return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
}

export default function AdminLoanContractsPage() {
  const [rows, setRows] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState('')
  const [totalCount, setTotalCount] = useState(0)
  const [totalPages, setTotalPages] = useState(0)

  const [statusFilter, setStatusFilter] = useState('')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [page, setPage] = useState(0)
  const size = 20

  const load = useCallback(async () => {
    setLoading(true)
    setErr('')
    try {
      const params: any = { page, size }
      if (statusFilter) params.cntrStatusCd = statusFilter
      if (dateFrom) params.dateFrom = dateFrom.replace(/-/g, '')
      if (dateTo)   params.dateTo   = dateTo.replace(/-/g, '')
      const { data: res } = await loanContractApi.adminList(params)
      setRows(res.data?.items ?? [])
      setTotalCount(Number(res.data?.totalCount ?? 0))
      setTotalPages(Number(res.data?.totalPages ?? 0))
    } catch {
      setErr('계약 목록을 불러오지 못했습니다.')
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [statusFilter, dateFrom, dateTo, page, size])

  useEffect(() => { load() }, [load])

  function handleSearch() {
    setPage(0)
    load()
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">계약 모니터링</span>
        </div>

        <div className="px-6 py-5 max-w-6xl">
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-lg font-bold text-gray-800">계약 모니터링</h1>
            {!loading && <span className="text-[12px] text-gray-500">총 {totalCount.toLocaleString()}건</span>}
          </div>

          {/* 필터 바 */}
          <div className="flex flex-wrap gap-3 mb-5 bg-white border border-gray-200 rounded-lg px-4 py-3">
            <div className="flex items-center gap-2">
              <label className="text-[12px] text-gray-600 whitespace-nowrap">상태</label>
              <select
                value={statusFilter}
                onChange={e => setStatusFilter(e.target.value)}
                className="border border-gray-300 rounded px-2 py-1 text-[12px] focus:outline-none"
              >
                <option value="">전체</option>
                <option value="SIGNED">약정</option>
                <option value="ACTIVE">실행중</option>
                <option value="CLOSED">종결</option>
              </select>
            </div>
            <div className="flex items-center gap-2">
              <label className="text-[12px] text-gray-600 whitespace-nowrap">시작일</label>
              <input type="date" value={dateFrom} onChange={e => setDateFrom(e.target.value)}
                className="border border-gray-300 rounded px-2 py-1 text-[12px] focus:outline-none" />
              <span className="text-[12px] text-gray-400">~</span>
              <input type="date" value={dateTo} onChange={e => setDateTo(e.target.value)}
                className="border border-gray-300 rounded px-2 py-1 text-[12px] focus:outline-none" />
            </div>
            <button
              onClick={handleSearch}
              className="px-4 py-1 text-[12px] bg-kb-admin text-white rounded hover:opacity-90"
            >
              조회
            </button>
          </div>

          {err && (
            <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>
          )}

          <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
            {loading ? (
              <p className="py-10 text-center text-sm text-gray-400">불러오는 중...</p>
            ) : rows.length === 0 ? (
              <p className="py-10 text-center text-sm text-gray-400">계약 데이터가 없습니다.</p>
            ) : (
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    {['계약ID', '계약번호', '고객ID', '상태', '약정금액', '기간(월)', '금리', '상환방식', '시작일', '종료일', '서명일시'].map(h => (
                      <th key={h} className="px-3 py-3 text-left text-xs text-gray-600 font-semibold whitespace-nowrap">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {rows.map((r: any) => (
                    <tr key={r.cntrId} className="hover:bg-gray-50 transition-colors">
                      <td className="px-3 py-2 text-gray-400 text-xs">{r.cntrId}</td>
                      <td className="px-3 py-2 font-mono text-[11px] text-gray-700">{r.cntrNo}</td>
                      <td className="px-3 py-2 text-gray-600">{r.customerId}</td>
                      <td className="px-3 py-2"><StatusBadge status={r.cntrStatusCd} /></td>
                      <td className="px-3 py-2 text-right font-medium text-gray-800">{fmtAmt(r.contractedAmount)}</td>
                      <td className="px-3 py-2 text-center text-gray-600">{r.contractedPeriodMo}</td>
                      <td className="px-3 py-2 text-right text-gray-700">{fmtRate(r.totalRateBps)}</td>
                      <td className="px-3 py-2 text-gray-500 text-[11px]">{r.repaymentMethodCd}</td>
                      <td className="px-3 py-2 text-gray-500 text-[11px]">{fmtDate(r.cntrStartDate)}</td>
                      <td className="px-3 py-2 text-gray-500 text-[11px]">{fmtDate(r.cntrEndDate)}</td>
                      <td className="px-3 py-2 text-gray-400 text-[11px]">
                        {r.signedAt ? r.signedAt.slice(0, 16).replace('T', ' ') : '-'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          {/* 페이지네이션 */}
          {totalPages > 1 && (
            <div className="flex justify-center gap-2 mt-4">
              <button
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={page === 0}
                className="px-3 py-1 text-[12px] border border-gray-300 rounded disabled:opacity-40 hover:bg-gray-50"
              >
                이전
              </button>
              <span className="px-3 py-1 text-[12px] text-gray-600">{page + 1} / {totalPages}</span>
              <button
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="px-3 py-1 text-[12px] border border-gray-300 rounded disabled:opacity-40 hover:bg-gray-50"
              >
                다음
              </button>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
