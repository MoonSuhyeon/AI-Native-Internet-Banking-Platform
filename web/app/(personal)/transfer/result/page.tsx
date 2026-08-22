'use client'
import { KB_BORDER, KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_TEXT_MUTED } from '@/lib/theme'

import Link from 'next/link'
import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { formatNumber } from '@/lib/mock-data'
import { fetchDepositAccountViewModels, getCurrentDepositCustomerId } from '@/lib/deposit-api'
import TransferSidebar from '@/components/inquiry/TransferSidebar'

type PendingTransfer = {
  fromNumber: string
  fromName: string
  toBank: string
  toBankCode: string
  toAccount: string
  amount: number
  receiverName: string
  fee: number
}

type PaymentResult = {
  /**
   * `CONSULT` 는 실패가 아니다. 이상거래로 멈춘 이체를 고객이 상담원에게 넘긴
   * 상태다 — 접수됐고 사람이 볼 것이다. 빨간 실패 배너로 보여주면 고객은
   * 안 된 줄 알고 다시 시도하게 되고, 상담 요청은 큐에 그대로 쌓인다.
   */
  status: 'COMPLETED' | 'FAILED' | 'CLEARING' | 'ERROR' | 'CONSULT'
  piId?: string
  txNo?: string
  failureCategory?: string | null
  message?: string
  /** 상담 케이스 번호. 고객이 문의할 때 댈 수 있어야 한다. */
  caseId?: string
}

const FAILURE_MESSAGES: Record<string, string> = {
  INSUFFICIENT_BALANCE: '잔액이 부족합니다.',
  INVALID_ACCOUNT: '유효하지 않은 계좌입니다.',
  ACCOUNT_CLOSED: '출금 계좌가 해지된 상태입니다.',
  DAILY_LIMIT_EXCEEDED: '일일 이체 한도를 초과했습니다.',
  RECEIVE_ACCOUNT_SUSPENDED: '수취 계좌가 정지 상태입니다.',
}

export default function TransferResultPage() {
  const router = useRouter()
  const [data, setData] = useState<PendingTransfer | null>(null)
  const [paymentResult, setPaymentResult] = useState<PaymentResult | null>(null)
  const [remainingBalance, setRemainingBalance] = useState<number | null>(null)
  const initialized = useRef(false)

  useEffect(() => {
    if (initialized.current) return
    initialized.current = true

    const raw = sessionStorage.getItem('pendingTransfer')
    if (!raw) {
      router.replace('/transfer/account')
      return
    }

    const transfer: PendingTransfer = JSON.parse(raw)
    setData(transfer)

    const resultRaw = sessionStorage.getItem('paymentResult')
    if (resultRaw) {
      setPaymentResult(JSON.parse(resultRaw))
      sessionStorage.removeItem('paymentResult')
    }

    // 이체 이력 저장
    try {
      const prev = JSON.parse(localStorage.getItem('transferHistory') || '[]')
      const now = new Date()
      prev.unshift({
        id: String(now.getTime()),
        datetime: `${now.getFullYear()}.${String(now.getMonth()+1).padStart(2,'0')}.${String(now.getDate()).padStart(2,'0')} ${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}`,
        bank: transfer.toBank,
        account: transfer.toAccount,
        receiver: transfer.receiverName,
        amount: transfer.amount,
        memo: '',
      })
      localStorage.setItem('transferHistory', JSON.stringify(prev.slice(0, 50)))
    } catch {}

    sessionStorage.removeItem('pendingTransfer')

    // 잔액 조회
    fetchDepositAccountViewModels(getCurrentDepositCustomerId())
      .then(accs => {
        const acc = accs.find(a => a.number === transfer.fromNumber)
        if (acc) setRemainingBalance(Number(acc.balance) - Number(transfer.amount))
      })
      .catch(() => {})
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!data) return null

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <TransferSidebar />

        <main className="flex-1 pl-8 pt-6 pb-12">
          <h1 className="text-[22px] font-bold text-kb-text mb-6">계좌이체</h1>

          {/* 완료/실패 배너 */}
          {paymentResult?.status === 'CONSULT' ? (
            <div className="rounded-xl p-6 mb-6 flex items-center gap-5" style={{ backgroundColor: '#FFF4E5', border: '1px solid #FCD9A6' }}>
              <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0 bg-amber-500">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/>
                </svg>
              </div>
              <div>
                <p className="text-[16px] font-bold mb-1 text-amber-700">상담 요청이 접수되었습니다.</p>
                <p className="text-[12px] text-kb-text-muted">
                  {paymentResult.message ?? '검토 후 연락드립니다.'}
                </p>
                <p className="text-[12px] text-kb-text-muted">이체는 진행되지 않았습니다. 돈은 그대로 있습니다.</p>
                {paymentResult.caseId && (
                  <p className="mt-1 text-[12px] text-kb-text-muted">접수번호: {paymentResult.caseId}</p>
                )}
              </div>
            </div>
          ) : (!paymentResult || paymentResult.status === 'COMPLETED' || paymentResult.status === 'CLEARING') ? (
            <div className="rounded-xl p-6 mb-6 flex items-center gap-5" style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: KB_PRIMARY }}>
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="20 6 9 17 4 12"/>
                </svg>
              </div>
              <div>
                <p className="text-[16px] font-bold mb-1" style={{ color: KB_PRIMARY }}>
                  {paymentResult?.status === 'CLEARING' ? '이체 요청이 접수되었습니다.' : '즉시이체가 완료되었습니다.'}
                </p>
                {paymentResult?.txNo && (
                  <p className="text-[12px] text-kb-text-muted">거래번호: {paymentResult.txNo}</p>
                )}
                <p className="text-[12px] text-kb-text-muted">타행계좌로의 이체는 해당 은행 사정에 따라 입금이 다소 지연될 수 있습니다.</p>
              </div>
            </div>
          ) : (
            <div className="rounded-xl p-6 mb-6 flex items-center gap-5" style={{ backgroundColor: '#FEF2F2', border: '1px solid #FECACA' }}>
              <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0 bg-red-500">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </div>
              <div>
                <p className="text-[16px] font-bold mb-1 text-red-600">
                  {paymentResult.status === 'ERROR' ? '이체 처리 중 오류가 발생했습니다.' : '이체가 실패하였습니다.'}
                </p>
                <p className="text-[12px] text-kb-text-muted">
                  {paymentResult.status === 'ERROR'
                    ? (paymentResult.message ?? '잠시 후 다시 시도해 주세요.')
                    : (paymentResult.failureCategory
                        ? (FAILURE_MESSAGES[paymentResult.failureCategory] ?? paymentResult.failureCategory)
                        : '이체를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.')}
                </p>
              </div>
            </div>
          )}

          {/* 이체결과 테이블 */}
          <div className="mb-6">
            <p className="text-[15px] font-bold text-kb-text mb-3">이체결과</p>
            <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <table className="w-full border-collapse text-[13px]">
                <thead>
                  <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                    {['출금계좌번호', '입금계좌번호', '이체금액', '수수료', '받는분', '결과'].map(h => (
                      <th key={h} className="px-4 py-3 text-center font-semibold text-[12px]"
                        style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td className="px-4 py-4 text-center text-kb-text">{data.fromNumber}</td>
                    <td className="px-4 py-4 text-center">
                      <p className="font-medium text-kb-text">{data.toBank}</p>
                      <p className="text-kb-text-muted text-[12px]">{data.toAccount}</p>
                    </td>
                    <td className="px-4 py-4 text-right font-bold text-[15px]" style={{ color: KB_PRIMARY }}>
                      {formatNumber(Number(data.amount))}원
                    </td>
                    <td className="px-4 py-4 text-center text-kb-text-muted">
                      {data.fee === 0 ? '면제' : `${formatNumber(data.fee)}원`}
                    </td>
                    <td className="px-4 py-4 text-center text-kb-text">{data.receiverName}</td>
                    <td className="px-4 py-4 text-center font-bold">
                      {(!paymentResult || paymentResult.status === 'COMPLETED') ? (
                        <span style={{ color: KB_MINT }}>정상</span>
                      ) : paymentResult.status === 'CLEARING' ? (
                        <span style={{ color: '#F59E0B' }}>처리중</span>
                      ) : (
                        <span className="text-red-600">실패</span>
                      )}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            {remainingBalance !== null && (
              <p className="text-right text-[12px] text-kb-text-muted mt-2">
                이체 후 잔액 : <span className="font-semibold" style={{ color: KB_PRIMARY }}>{formatNumber(remainingBalance)}원</span>
              </p>
            )}
          </div>

          {/* 버튼 그룹 */}
          <div className="flex justify-center gap-3 mb-8">
            <Link href="/transfer/inquiry"
              className="px-8 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity"
              style={{ backgroundColor: KB_PRIMARY }}>
              이체결과 조회
            </Link>
            <Link href="/transfer/account"
              className="border rounded-xl px-8 py-2.5 text-[14px] font-medium transition-colors hover:bg-kb-primary-bg"
              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
              이체 다시하기
            </Link>
            <Link href="/"
              className="border rounded-xl px-8 py-2.5 text-[14px] font-medium transition-colors hover:bg-gray-50"
              style={{ borderColor: KB_BORDER, color: KB_TEXT_MUTED }}>
              홈으로
            </Link>
          </div>

          {/* 안내 */}
          <div className="rounded-xl px-5 py-4 text-[12px] text-kb-text-muted space-y-1" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <p>· 인터넷뱅킹 종료 시 반드시 [로그아웃] 버튼을 눌러 종료하시기 바랍니다.</p>
            <p>· 평소 이체금액을 감안하여 이체한도를 조정하실 수 있습니다.{' '}
              <Link href="/banking/transfer-limit" className="underline font-medium" style={{ color: KB_PRIMARY }}>
                이체한도 조회/변경
              </Link>
            </p>
          </div>
        </main>
      </div>
    </div>
  )
}
