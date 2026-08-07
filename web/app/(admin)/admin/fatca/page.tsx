'use client'
import { useEffect, useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { listFatcaCrs, FatcaReportable, fmtYmd, errMsg } from '@/lib/admin-customer-api'

export default function FATCAPage() {
  const [rows, setRows] = useState<FatcaReportable[]>([])
  const [total, setTotal] = useState(0)
  const [typeFilter, setTypeFilter] = useState('전체')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    listFatcaCrs(0, 100)
      .then(res => { setRows(res.content); setTotal(res.totalElements) })
      .catch(e => setError(errMsg(e, 'FATCA/CRS 목록을 불러오지 못했습니다.')))
      .finally(() => setLoading(false))
  }, [])

  const fatcaCount = rows.filter(r => r.fatcaReportableYn).length
  const crsCount = rows.filter(r => r.crsReportableYn).length
  const filtered = rows.filter(r =>
    typeFilter === '전체' ||
    (typeFilter === 'FATCA' && r.fatcaReportableYn) ||
    (typeFilter === 'CRS' && r.crsReportableYn))

  return (
    <div className="flex min-h-screen bg-kb-beige-light">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-kb-border px-6 py-3 text-xs text-kb-text-muted">
          정책 &gt; <span className="text-gray-700 font-medium">FATCA/CRS</span>
        </div>
        <div className="px-6 py-5">
          <h1 className="text-lg font-bold text-gray-800 mb-1">FATCA / CRS 해외 금융계좌 신고</h1>
          <p className="text-xs text-gray-400 mb-4">미국 세금보고 의무(FATCA) 및 공통보고기준(CRS) 보고대상 고객 현황</p>

          <div className="grid grid-cols-3 gap-4 mb-5">
            {[
              { label: 'FATCA 보고대상', count: fatcaCount, color: 'bg-blue-50 border-blue-200 text-blue-700' },
              { label: 'CRS 보고대상', count: crsCount, color: 'bg-purple-50 border-purple-200 text-purple-700' },
              { label: '보고대상 합계', count: total, color: 'bg-gray-50 border-gray-200 text-gray-700' },
            ].map(card => (
              <div key={card.label} className={`border rounded p-4 ${card.color}`}>
                <p className="text-xs mb-1">{card.label}</p>
                <p className="text-2xl font-bold">{card.count}<span className="text-sm font-normal ml-1">건</span></p>
              </div>
            ))}
          </div>

          <div className="flex items-center gap-3 mb-4 bg-white border border-kb-border rounded-lg px-4 py-3 shadow-sm">
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-gray-500">유형</span>
              <select value={typeFilter} onChange={e => setTypeFilter(e.target.value)} className="border border-gray-300 text-xs px-2 py-1 rounded bg-white">
                {['전체', 'FATCA', 'CRS'].map(o => <option key={o}>{o}</option>)}
              </select>
            </div>
            <span className="ml-auto text-xs text-gray-400">{loading ? '조회 중…' : `${filtered.length}건`}</span>
          </div>

          {error && <div className="mb-3 bg-red-50 border border-red-200 rounded px-4 py-2.5 text-xs text-red-700">{error}</div>}

          <div className="bg-white border border-kb-border rounded-lg overflow-hidden shadow-sm">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-kb-beige-light border-b border-kb-border text-xs text-kb-text-muted">
                  {['Party ID', '이름', '생년월일', '국적', 'FATCA', 'CRS', '최근 검토'].map(h => (
                    <th key={h} className="px-3 py-2.5 text-left font-medium">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {filtered.map(r => (
                  <tr key={r.partyId} className="hover:bg-kb-beige-light">
                    <td className="px-3 py-2.5 text-xs text-blue-600 font-mono">{r.partyId}</td>
                    <td className="px-3 py-2.5 font-medium">{r.partyName}</td>
                    <td className="px-3 py-2.5 text-gray-500">{fmtYmd(r.birthDate)}</td>
                    <td className="px-3 py-2.5 text-gray-600">{r.nationalityCode ?? '-'}</td>
                    <td className="px-3 py-2.5">
                      {r.fatcaReportableYn
                        ? <span className="text-xs px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-700 font-medium">{r.fatcaStatusCode}</span>
                        : <span className="text-xs text-gray-300">-</span>}
                    </td>
                    <td className="px-3 py-2.5">
                      {r.crsReportableYn
                        ? <span className="text-xs px-1.5 py-0.5 rounded-full bg-purple-100 text-purple-700 font-medium">{r.crsStatusCode}</span>
                        : <span className="text-xs text-gray-300">-</span>}
                    </td>
                    <td className="px-3 py-2.5 text-xs text-gray-400">{r.fatcaLastReviewedAt ? r.fatcaLastReviewedAt.slice(0, 10) : '-'}</td>
                  </tr>
                ))}
                {!loading && filtered.length === 0 && !error && (
                  <tr><td colSpan={7} className="px-3 py-8 text-center text-gray-400 text-sm">보고대상이 없습니다.</td></tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="mt-3 text-xs text-gray-400">※ TIN·자가증명(W-9)·보고서 제출 워크플로는 별도 도메인으로 추후 연동됩니다.</p>
        </div>
      </main>
    </div>
  )
}
