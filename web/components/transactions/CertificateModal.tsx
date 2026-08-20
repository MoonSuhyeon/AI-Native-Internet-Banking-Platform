'use client'

import { KB_BORDER, KB_PRIMARY, KB_TEXT_MUTED } from '@/lib/theme'
import type { TransactionCertificate } from '@/lib/deposit-api'

const LABEL: Record<string, string> = {
  RECEIPT: '거래영수증',
  TRANSFER_CONFIRMATION: '이체확인증',
}

function won(n: number) {
  return n.toLocaleString('ko-KR') + '원'
}

function dt(v: string) {
  return v.slice(0, 16).replace('T', ' ')
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="shrink-0" style={{ color: KB_TEXT_MUTED }}>{label}</dt>
      <dd className="text-right text-kb-text">{value}</dd>
    </div>
  )
}

/**
 * 발급된 증빙을 보여준다.
 *
 * 증빙번호를 가장 크게 둔다 — 제출받은 쪽이 이 번호로 대조하므로 문서에서 제일
 * 중요한 값이다. 일괄 발급하면 장마다 번호가 다르다.
 */
export default function CertificateModal({
  certificates,
  onClose,
}: {
  certificates: TransactionCertificate[]
  onClose: () => void
}) {
  if (certificates.length === 0) return null
  const kind = LABEL[certificates[0].certificateType] ?? '증빙'

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 p-4"
      onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[520px] max-h-[86vh] flex flex-col"
        onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">
            {kind}{certificates.length > 1 && ` ${certificates.length}건`}
          </span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-4">
          {certificates.map(c => (
            <div key={c.certificateNo} className="border rounded-lg p-4" style={{ borderColor: KB_BORDER }}>
              <p className="text-[11px]" style={{ color: KB_TEXT_MUTED }}>증빙번호</p>
              <p className="text-[15px] font-bold tracking-wider mb-3" style={{ color: KB_PRIMARY }}>
                {c.certificateNo}
              </p>

              <dl className="text-[13px] space-y-1.5">
                <Row label="거래번호" value={c.transactionNumber} />
                <Row label="거래일시" value={dt(c.transactionAt)} />
                <Row label="거래금액" value={won(c.amount)} />
                {c.feeAmount > 0 && <Row label="수수료" value={won(c.feeAmount)} />}
                <Row label="거래후잔액" value={won(c.balanceAfter)} />
                {c.counterpartyBankName && <Row label="받는은행" value={c.counterpartyBankName} />}
                {c.counterpartyAccountNo && <Row label="받는계좌" value={c.counterpartyAccountNo} />}
                {c.counterpartyName && <Row label="받는분" value={c.counterpartyName} />}
                {c.transactionMemo && <Row label="메모" value={c.transactionMemo} />}
                <Row label="발급일시" value={dt(c.issuedAt)} />
              </dl>
            </div>
          ))}
        </div>

        <div className="flex justify-end gap-2 px-5 py-3 border-t" style={{ borderColor: KB_BORDER }}>
          <button onClick={() => window.print()}
            className="border rounded-lg px-4 py-1.5 text-[13px]"
            style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
            인쇄
          </button>
          <button onClick={onClose}
            className="rounded-lg px-4 py-1.5 text-[13px] text-white"
            style={{ backgroundColor: KB_PRIMARY }}>
            닫기
          </button>
        </div>
      </div>
    </div>
  )
}
