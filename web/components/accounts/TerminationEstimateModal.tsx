'use client'

import { useEffect, useState } from 'react'

import { fetchTerminationEstimate, type EarlyTerminationEstimate } from '@/lib/deposit-api'
import { KB_BORDER, KB_DANGER, KB_PRIMARY, KB_TEXT_MUTED } from '@/lib/theme'

function won(n: number) {
  return n.toLocaleString('ko-KR') + '원'
}

function Row({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex justify-between gap-4 text-[13px]">
      <dt style={{ color: KB_TEXT_MUTED }}>{label}</dt>
      <dd className={strong ? 'font-bold text-kb-text' : 'text-kb-text'}>{value}</dd>
    </div>
  )
}

/**
 * 해지 예상 명세.
 *
 * <p>해지 화면의 "해지상세조회" 버튼에 핸들러가 없었다. 중도해지는 되돌릴 수 없는데
 * 고객은 얼마를 손해 보는지 모른 채 "해지" 를 눌러야 했다.
 *
 * <p>기준일을 함께 밝힌다. 실제 지급액은 해지 당일의 잔액과 세율로 확정되고 하루만
 * 지나도 달라진다 — 확정 금액처럼 보여 주면 1원이라도 어긋났을 때 고객은 은행이
 * 속였다고 읽는다.
 */
export default function TerminationEstimateModal({
  accountId,
  accountName,
  onClose,
}: {
  accountId: number
  accountName: string
  onClose: () => void
}) {
  const [est, setEst] = useState<EarlyTerminationEstimate | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    fetchTerminationEstimate(accountId)
      .then(e => { if (alive) setEst(e) })
      .catch(() => { if (alive) setError('해지 예상 명세를 불러오지 못했습니다.') })
    return () => { alive = false }
  }, [accountId])

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[480px] max-h-[86vh] flex flex-col"
        onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">해지상세조회</span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-4">
          <p className="text-[12px]" style={{ color: KB_TEXT_MUTED }}>{accountName}</p>

          {error && <p className="text-[13px] text-kb-red">{error}</p>}
          {!est && !error && (
            <p className="text-[13px]" style={{ color: KB_TEXT_MUTED }}>계산 중…</p>
          )}

          {est && (
            <>
              <div className="border rounded-lg p-4" style={{ borderColor: KB_BORDER }}>
                <p className="text-[12px] mb-1" style={{ color: KB_TEXT_MUTED }}>예상 실수령액</p>
                <p className="text-[22px] font-bold" style={{ color: KB_PRIMARY }}>{won(est.netAmount)}</p>
                <p className="text-[11px] mt-1" style={{ color: KB_TEXT_MUTED }}>
                  {est.estimatedOn} 기준 · 실제 금액은 해지 당일에 확정됩니다.
                </p>
              </div>

              <dl className="space-y-1.5">
                <Row label="원금" value={won(est.principal)} />
                <Row label="약정이율" value={`연 ${est.contractRate}%`} />
                <Row
                  label={est.earlyTermination ? '중도해지 적용이율' : '적용이율'}
                  value={`연 ${est.appliedRate}%`}
                  strong
                />
                <Row label="적용 근거" value={est.rateDescription} />
                <Row label="세전 이자" value={won(est.interestBeforeTax)} />
                <Row label={`세금 (${est.taxRate}%)`} value={`- ${won(est.taxAmount)}`} />
              </dl>

              {est.earlyTermination && est.forgoneInterest > 0 && (
                <div className="border rounded-lg p-3" style={{ borderColor: KB_DANGER }}>
                  <p className="text-[13px] font-bold" style={{ color: KB_DANGER }}>
                    만기까지 유지하면 {won(est.forgoneInterest)}을 더 받습니다
                  </p>
                  <p className="text-[12px] mt-1" style={{ color: KB_TEXT_MUTED }}>
                    만기 {est.maturityAt ?? '-'} · 만기 시 세전 이자 {won(est.interestIfHeldToMaturity)}
                  </p>
                </div>
              )}

              {!est.terminable && (
                <p className="text-[12px]" style={{ color: KB_DANGER }}>
                  이 상품은 중도해지가 제한됩니다. 영업점에 문의해 주세요.
                </p>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
