'use client'

import { useEffect, useState } from 'react'

import { fetchSavingsPaymentStatus, type SavingsPaymentStatus } from '@/lib/deposit-api'
import { KB_BORDER, KB_DANGER, KB_PRIMARY, KB_TEXT_MUTED } from '@/lib/theme'

const STATUS_LABEL: Record<string, string> = {
  PENDING: '예정',
  PAID: '완료',
  OVERDUE: '연체',
  FAILED: '이체실패',
  SUSPENDED: '이체중단',
}

function won(n: number | null) {
  return (n ?? 0).toLocaleString('ko-KR') + '원'
}

/**
 * 적금 납입현황.
 *
 * <p>계좌 목록의 "납입현황" 버튼에 핸들러가 없었다. 회차별 스케줄은 이미 DB 에
 * 쌓이고 있었는데 고객이 볼 길이 없었다.
 *
 * <p>미납 회차를 맨 위에 크게 둔다. 회차 목록만 보여 주면 스무 줄을 눈으로 훑어야
 * 밀렸는지 알 수 있고, 그러면 대개 알아채지 못한 채 만기까지 간다.
 */
export default function SavingsPaymentStatusModal({
  accountId,
  accountName,
  onClose,
}: {
  accountId: number
  accountName: string
  onClose: () => void
}) {
  const [status, setStatus] = useState<SavingsPaymentStatus | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    fetchSavingsPaymentStatus(accountId)
      .then(s => { if (alive) setStatus(s) })
      .catch(() => { if (alive) setError('납입현황을 불러오지 못했습니다.') })
    return () => { alive = false }
  }, [accountId])

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[560px] max-h-[86vh] flex flex-col"
        onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">납입현황</span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-4">
          <p className="text-[12px]" style={{ color: KB_TEXT_MUTED }}>{accountName}</p>

          {error && <p className="text-[13px] text-kb-red">{error}</p>}
          {!status && !error && (
            <p className="text-[13px]" style={{ color: KB_TEXT_MUTED }}>불러오는 중…</p>
          )}

          {status && (
            <>
              <div className="border rounded-lg p-4" style={{ borderColor: KB_BORDER }}>
                <div className="flex items-baseline gap-2">
                  <span className="text-[22px] font-bold" style={{ color: KB_PRIMARY }}>
                    {status.paidRounds}
                  </span>
                  <span className="text-[13px]" style={{ color: KB_TEXT_MUTED }}>
                    / {status.totalRounds}회 납입
                  </span>
                  {status.missedRounds > 0 && (
                    <span className="ml-auto text-[13px] font-bold" style={{ color: KB_DANGER }}>
                      미납 {status.missedRounds}회
                    </span>
                  )}
                </div>

                <dl className="mt-3 text-[13px] space-y-1.5">
                  <div className="flex justify-between">
                    <dt style={{ color: KB_TEXT_MUTED }}>월 납입금액</dt>
                    <dd className="text-kb-text">{won(status.monthlyAmount)}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt style={{ color: KB_TEXT_MUTED }}>누적 납입액</dt>
                    <dd className="text-kb-text">{won(status.totalPaidAmount)}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt style={{ color: KB_TEXT_MUTED }}>다음 납입일</dt>
                    <dd className="text-kb-text">{status.nextScheduledDate ?? '없음'}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt style={{ color: KB_TEXT_MUTED }}>만기일</dt>
                    <dd className="text-kb-text">{status.maturityAt ?? '-'}</dd>
                  </div>
                </dl>
              </div>

              <div>
                <p className="text-[13px] font-semibold text-kb-text mb-2">회차별 내역</p>
                {status.rounds.length === 0 ? (
                  <p className="text-[13px]" style={{ color: KB_TEXT_MUTED }}>등록된 납입 회차가 없습니다.</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-[12px]">
                      <thead>
                        <tr className="border-b" style={{ borderColor: KB_BORDER, color: KB_TEXT_MUTED }}>
                          <th className="text-left py-1.5 font-medium">회차</th>
                          <th className="text-left py-1.5 font-medium">예정일</th>
                          <th className="text-right py-1.5 font-medium">금액</th>
                          <th className="text-right py-1.5 font-medium">상태</th>
                        </tr>
                      </thead>
                      <tbody>
                        {status.rounds.map(r => {
                          const late = r.status !== 'PAID' && r.status !== 'PENDING'
                          return (
                            <tr key={r.paymentRound} className="border-b" style={{ borderColor: KB_BORDER }}>
                              <td className="py-1.5 text-kb-text">{r.paymentRound}</td>
                              <td className="py-1.5" style={{ color: KB_TEXT_MUTED }}>{r.scheduledDate}</td>
                              <td className="py-1.5 text-right text-kb-text">
                                {won(r.actualAmount ?? r.scheduledAmount)}
                              </td>
                              <td className="py-1.5 text-right font-medium"
                                style={{ color: late ? KB_DANGER : r.status === 'PAID' ? KB_PRIMARY : KB_TEXT_MUTED }}>
                                {STATUS_LABEL[r.status] ?? r.status}
                                {r.autoTransfer && r.status === 'PAID' && ' (자동)'}
                              </td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
