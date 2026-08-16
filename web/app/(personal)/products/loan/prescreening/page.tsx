'use client'
import { KB_DANGER, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE } from '@/lib/theme'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import LoanSidebar from '@/components/inquiry/LoanSidebar'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import { api } from '@/lib/api'

type LoanApplication = {
  applId: number
  applNo: string
  requestedAmount: number
  requestedPeriodMo: number
  applStatusCd: string
  appliedAt: string
}

type PrescreeningResult = {
  prescreeningId: number
  applId: number
  prescreeningResultCd: string
  estimatedLimit: number
  estimatedRate: number
}

export default function PrescreeningPage() {
  const [applications, setApplications] = useState<LoanApplication[]>([])
  const [loading, setLoading]           = useState(true)
  const [selected, setSelected]         = useState<LoanApplication | null>(null)
  const [result, setResult]             = useState<PrescreeningResult | null>(null)
  const [running, setRunning]           = useState(false)
  const [error, setError]               = useState('')

  useEffect(() => {
    api.get('/api/loan-applications')
      .then(res => {
        const items: LoanApplication[] = res.data.data?.items ?? []
        setApplications(items.filter(a => a.applStatusCd === 'SUBMITTED'))
      })
      .catch(() => setError('신청 내역을 불러오지 못했습니다.'))
      .finally(() => setLoading(false))
  }, [])

  async function handleRun() {
    if (!selected) return
    setRunning(true); setResult(null); setError('')
    try {
      const res = await api.post(`/api/loan-applications/${selected.applId}/prescreening`, {
        prescreeningResultCd: 'PASS',
      })
      setResult(res.data.data)
    } catch {
      setError('가심사 실행에 실패했습니다. 신청 상태를 확인해주세요.')
    } finally { setRunning(false) }
  }

  return (
    <main className="pb-16">
      <div className="max-w-kb-container mx-auto px-6 pt-6">
        <AutoBreadcrumb as="/products/loan/credit" leaf="한도조회(가심사)" />

        <div className="flex gap-8">
          <LoanSidebar />
          <div className="flex-1 min-w-0">
            <h1 className="text-[22px] font-bold text-kb-text mb-5">한도조회(가심사)</h1>

            <div className="rounded-xl px-5 py-4 mb-5 text-[12px] space-y-1.5"
              style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <p className="text-kb-text-muted">· 접수 중인 대출 신청에 대해 예상 한도와 금리를 미리 확인합니다.</p>
              <p className="text-kb-text-muted">· 가심사는 신용점수에 영향을 주지 않습니다.</p>
              <p className="font-medium" style={{ color: KB_PRIMARY }}>· 가심사 결과는 참고용이며, 실제 심사 결과와 다를 수 있습니다.</p>
            </div>

            {loading && <p className="text-[13px] text-kb-text-muted py-10 text-center">불러오는 중...</p>}
            {error   && <p className="text-[13px] py-4" style={{ color: KB_DANGER }}>{error}</p>}

            {!loading && (
              <>
                {/* 신청 목록 */}
                <div className="rounded-xl overflow-hidden mb-5" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <div className="px-5 py-3 text-[13px] font-semibold"
                    style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>
                    가심사 대상 신청 선택
                  </div>
                  {applications.length === 0 ? (
                    <div className="px-5 py-10 text-center">
                      <p className="text-[13px] text-kb-text-muted mb-3">접수 중인 대출 신청이 없습니다.</p>
                      <Link href="/products/loan/credit"
                        className="inline-block px-8 py-2 text-[13px] font-bold text-white rounded-xl hover:opacity-85"
                        style={{ backgroundColor: KB_PRIMARY }}>대출 신청하기</Link>
                    </div>
                  ) : (
                    <table className="w-full border-collapse text-[13px]">
                      <thead>
                        <tr style={{ backgroundColor: KB_PRIMARY_SURFACE }}>
                          {['선택', '신청번호', '신청금액', '기간', '신청일'].map(h => (
                            <th key={h} className="px-4 py-2.5 text-center font-semibold text-[12px]"
                              style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>{h}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {applications.map((app, i, arr) => (
                          <tr key={app.applId}
                            className="hover:bg-kb-primary-surface cursor-pointer transition-colors"
                            onClick={() => setSelected(app)}
                            style={{
                              borderBottom: i < arr.length - 1 ? `1px solid ${KB_PRIMARY_BORDER}` : 'none',
                              backgroundColor: selected?.applId === app.applId ? KB_PRIMARY_BG : undefined,
                            }}>
                            <td className="px-4 py-3 text-center">
                              <input type="radio" checked={selected?.applId === app.applId}
                                onChange={() => setSelected(app)} style={{ accentColor: KB_PRIMARY }} />
                            </td>
                            <td className="px-4 py-3 text-center font-medium" style={{ color: KB_PRIMARY }}>{app.applNo}</td>
                            <td className="px-4 py-3 text-right pr-5 font-semibold">{app.requestedAmount.toLocaleString('ko-KR')}원</td>
                            <td className="px-4 py-3 text-center">{app.requestedPeriodMo}개월</td>
                            <td className="px-4 py-3 text-center text-kb-text-muted">{app.appliedAt?.slice(0, 10).replace(/-/g, '.') ?? '-'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>

                {applications.length > 0 && (
                  <div className="flex justify-center mb-6">
                    <button onClick={handleRun} disabled={!selected || running}
                      className="px-16 py-3 text-[15px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity disabled:opacity-40"
                      style={{ backgroundColor: KB_PRIMARY }}>
                      {running ? '가심사 실행 중...' : '가심사 실행'}
                    </button>
                  </div>
                )}

                {result && (
                  <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                    <div className="px-5 py-3" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `2px solid ${KB_PRIMARY_BORDER}` }}>
                      <span className="text-[14px] font-bold" style={{ color: KB_PRIMARY }}>가심사 결과</span>
                    </div>
                    <div className="grid grid-cols-3" style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                      {[
                        { label: '심사 결과',    value: result.prescreeningResultCd === 'PASS' ? '통과' : '거절',
                          color: result.prescreeningResultCd === 'PASS' ? KB_PRIMARY : '#E05555' },
                        { label: '예상 한도',    value: `${(result.estimatedLimit ?? 0).toLocaleString('ko-KR')}원`, color: KB_PRIMARY },
                        { label: '예상 금리',    value: `연 ${((result.estimatedRate ?? 0) / 100).toFixed(2)}%`, color: KB_PRIMARY },
                      ].map(({ label, value, color }, i, arr) => (
                        <div key={label} className="px-6 py-5 text-center"
                          style={{ borderRight: i < arr.length - 1 ? `1px solid ${KB_PRIMARY_BORDER}` : 'none' }}>
                          <p className="text-[12px] text-kb-text-muted mb-1">{label}</p>
                          <p className="text-[18px] font-bold" style={{ color }}>{value}</p>
                        </div>
                      ))}
                    </div>
                    <div className="px-5 py-3 text-[12px] text-kb-text-muted" style={{ backgroundColor: KB_PRIMARY_SURFACE }}>
                      ※ 가심사 결과는 참고용이며, 실제 심사 결과와 다를 수 있습니다.
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </main>
  )
}
