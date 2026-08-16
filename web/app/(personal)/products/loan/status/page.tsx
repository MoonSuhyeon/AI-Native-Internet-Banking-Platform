'use client'

import Link from 'next/link'
import { useState, useEffect } from 'react'
import LoanSidebar from '@/components/inquiry/LoanSidebar'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import { loanApplicationApi, getCustomerId } from '@/lib/loan-api'

const CANCELABLE = ['SUBMITTED', 'PRESCREENED', 'REVIEWING']

const STATUS_LABEL: Record<string, string> = {
  SUBMITTED: '접수완료', PRESCREENED: '가심사완료', REVIEWING: '심사중',
  APPROVED: '승인', REJECTED: '거절', CANCELLED: '취소', EXPIRED: '만료',
}
const STATUS_COLOR: Record<string, string> = {
  SUBMITTED: 'text-kb-primary', PRESCREENED: 'text-kb-primary', REVIEWING: 'text-kb-gold',
  APPROVED: 'text-green-600', REJECTED: 'text-red-500', CANCELLED: 'text-kb-text-muted', EXPIRED: 'text-kb-text-muted',
}

interface Application {
  applId: number
  applNo: string
  applStatusCd: string
  prodId: number
  requestedAmount: number
  appliedAt: string
}

export default function LoanStatusPage() {
  const [applications, setApplications] = useState<Application[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [canceling, setCanceling] = useState<number | null>(null)
  const [cancelMsg, setCancelMsg] = useState('')

  async function load() {
    const customerId = getCustomerId()
    if (!customerId) { setLoading(false); return }
    loanApplicationApi.list({ customerId, size: 20 })
      .then(({ data: res }) => setApplications(res.data?.items ?? []))
      .catch(() => setError('진행현황을 불러오지 못했습니다.'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  async function handleCancel(applId: number) {
    if (!confirm('신청을 취소하시겠습니까?')) return
    setCanceling(applId)
    try {
      await loanApplicationApi.cancel(applId, { cancelReasonCd: 'CUSTOMER_REQUEST' })
      setCancelMsg('신청이 취소되었습니다.')
      await load()
    } catch (e: any) {
      setCancelMsg(e?.response?.data?.message ?? '취소 처리 중 오류가 발생했습니다.')
    } finally {
      setCanceling(null)
      setTimeout(() => setCancelMsg(''), 3000)
    }
  }

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <LoanSidebar />

        <main className="flex-1 pl-8 pt-4 pb-12">
          <AutoBreadcrumb align="end" leaf="진행현황조회/실행/예약" />

          <h1 className="text-[20px] font-bold text-kb-text mb-6">진행현황조회/실행/예약</h1>

          {cancelMsg && <p className="mb-4 text-[13px] text-green-600 font-medium">{cancelMsg}</p>}
          {loading && <p className="py-12 text-center text-[13px] text-kb-text-muted">불러오는 중...</p>}
          {error && <p className="py-12 text-center text-[13px] text-kb-red">{error}</p>}

          {!loading && !error && (
            <table className="w-full border-collapse text-[13px]">
              <thead>
                <tr className="bg-kb-primary-bg">
                  <th className="border border-kb-primary-border px-4 py-3 text-center font-semibold">신청일자</th>
                  <th className="border border-kb-primary-border px-4 py-3 text-center font-semibold">신청번호</th>
                  <th className="border border-kb-primary-border px-4 py-3 text-center font-semibold">신청금액</th>
                  <th className="border border-kb-primary-border px-4 py-3 text-center font-semibold">진행상태</th>
                  <th className="border border-kb-primary-border px-4 py-3 text-center font-semibold">처리</th>
                </tr>
              </thead>
              <tbody>
                {applications.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="border border-kb-primary-border py-12 text-center text-[13px] text-kb-text-muted">
                      조회 가능한 대출이 없습니다.
                    </td>
                  </tr>
                ) : (
                  applications.map(appl => {
                    const statusCd = appl.applStatusCd
                    const canSubmitDocs = ['SUBMITTED', 'PRESCREENED', 'REVIEWING'].includes(statusCd)
                    const canSign = statusCd === 'APPROVED'
                    return (
                      <tr key={appl.applId} className="hover:bg-kb-primary-bg">
                        <td className="border border-kb-primary-border px-4 py-3 text-center">
                          {appl.appliedAt ? appl.appliedAt.slice(0, 10) : '-'}
                        </td>
                        <td className="border border-kb-primary-border px-4 py-3 text-center">
                          <Link href={`/loans/apply/result?applId=${appl.applId}`}
                            className="text-kb-primary hover:underline font-medium">
                            {appl.applNo ?? appl.applId}
                          </Link>
                        </td>
                        <td className="border border-kb-primary-border px-4 py-3 text-right">
                          {appl.requestedAmount.toLocaleString('ko-KR')}원
                        </td>
                        <td className={`border border-kb-primary-border px-4 py-3 text-center font-bold ${STATUS_COLOR[statusCd] ?? ''}`}>
                          {STATUS_LABEL[statusCd] ?? statusCd}
                        </td>
                        <td className="border border-kb-primary-border px-4 py-3 text-center">
                          <div className="flex items-center justify-center gap-1 flex-wrap">
                            {canSubmitDocs && (
                              <Link href={`/loans/apply/${appl.applId}/documents`}
                                className="px-3 py-1 text-[11px] border border-kb-primary-border rounded-lg text-kb-text hover:bg-kb-primary-bg">
                                서류제출
                              </Link>
                            )}
                            {canSign && (
                              <Link href={`/products/loan/status/sign?applId=${appl.applId}`}
                                className="px-3 py-1 text-[11px] bg-kb-primary text-white rounded-lg font-bold hover:opacity-85">
                                전자서명
                              </Link>
                            )}
                            {CANCELABLE.includes(statusCd) && (
                              <button onClick={() => handleCancel(appl.applId)}
                                disabled={canceling === appl.applId}
                                className="px-3 py-1 text-[11px] border border-red-300 text-red-600 hover:bg-red-50 disabled:opacity-50">
                                {canceling === appl.applId ? '취소중...' : '신청취소'}
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    )
                  })
                )}
              </tbody>
            </table>
          )}
        </main>
      </div>
    </div>
  )
}
