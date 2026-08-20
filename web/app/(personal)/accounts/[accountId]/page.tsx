'use client'

import Link from 'next/link'
import { useState, useEffect, useCallback } from 'react'
import { useParams } from 'next/navigation'
import { formatNumber } from '@/lib/mock-data'
import DepositAlertModal from '@/components/accounts/DepositAlertModal'
import {
  fetchDepositAccountViewModels,
  getCurrentDepositCustomerId,
  fetchTransactions,
  paySavings,
  cancelTransaction,
  isCancelableTransaction,
  nextSavingsPaymentRound,
  DepositViewAccount,
  DepositTransaction,
} from '@/lib/deposit-api'

const DATE_PRESETS = ['1개월', '3개월', '6개월', '1년', '직접입력']
const TX_TYPE_OPTS = ['전체', '입금', '출금']

function canTransferFrom(account: DepositViewAccount) {
  return account.type === '입출금'
}

export default function AccountDetailPage() {
  const { accountId } = useParams<{ accountId: string }>()
  const [account, setAccount] = useState<DepositViewAccount | null>(null)
  const [transactions, setTransactions] = useState<DepositTransaction[]>([])
  const [loading, setLoading] = useState(true)
  const [datePreset, setDatePreset] = useState('1개월')
  const [txType, setTxType] = useState('전체')
  const [balanceVisible, setBalanceVisible] = useState(true)
  const [keyword, setKeyword] = useState('')

  // 적금 납입 모달
  const [payOpen, setPayOpen] = useState(false)
  const [alertOpen, setAlertOpen] = useState(false)
  const [payAmount, setPayAmount] = useState('')
  const [paying, setPaying] = useState(false)
  // 거래 취소
  const [cancelingId, setCancelingId] = useState<number | null>(null)
  const [feedback, setFeedback] = useState<{ type: 'ok' | 'err'; msg: string } | null>(null)

  const numericId = accountId.startsWith('deposit-') ? Number(accountId.replace('deposit-', '')) : NaN

  const loadData = useCallback(async () => {
    try {
      const customerId = getCurrentDepositCustomerId()
      const accs = await fetchDepositAccountViewModels(customerId)
      const found = accs.find(a => a.id === accountId)
      setAccount(found ?? null)
    } catch {
      setAccount(null)
    }
    if (!isNaN(numericId)) {
      try {
        const txs = await fetchTransactions({ accountId: numericId })
        setTransactions(txs)
      } catch {
        setTransactions([])
      }
    }
    setLoading(false)
  }, [accountId, numericId])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handlePay = async () => {
    if (!account?.apiAccountId || !account.contractId) return
    const amount = Number(payAmount.replace(/[^0-9]/g, ''))
    if (!amount || amount <= 0) {
      setFeedback({ type: 'err', msg: '납입 금액을 정확히 입력해주세요.' })
      return
    }
    setPaying(true)
    setFeedback(null)
    try {
      await paySavings({
        accountId: account.apiAccountId,
        contractId: account.contractId,
        amount,
        paymentRound: nextSavingsPaymentRound(transactions),
      })
      setPayOpen(false)
      setPayAmount('')
      setFeedback({ type: 'ok', msg: '적금 납입이 완료되었습니다.' })
      await loadData()
    } catch {
      setFeedback({ type: 'err', msg: '적금 납입에 실패했습니다. 잠시 후 다시 시도해주세요.' })
    } finally {
      setPaying(false)
    }
  }

  const handleCancel = async (tx: DepositTransaction) => {
    if (!window.confirm('해당 거래를 취소하시겠습니까?')) return
    setCancelingId(tx.transactionId)
    setFeedback(null)
    try {
      await cancelTransaction(tx.transactionId)
      setFeedback({ type: 'ok', msg: '거래가 취소되었습니다.' })
      await loadData()
    } catch {
      setFeedback({ type: 'err', msg: '거래 취소에 실패했습니다. 출금·이체 거래만 취소할 수 있습니다.' })
    } finally {
      setCancelingId(null)
    }
  }

  if (loading) {
    return (
      <div className="max-w-kb-container mx-auto px-6 py-16 text-center text-kb-text-muted">
        loading...
      </div>
    )
  }

  if (!account) {
    return (
      <div className="max-w-kb-container mx-auto px-6 py-16 text-center">
        <p className="text-[16px] text-kb-text-muted mb-4">계좌 정보를 찾을 수 없습니다.</p>
        <Link href="/inquiry/accounts" className="text-kb-blue hover:underline text-[14px]">
          계좌목록으로 돌아가기
        </Link>
      </div>
    )
  }

  const filteredTx = transactions.filter(tx => {
    if (txType === '입금' && tx.directionType !== 'IN') return false
    if (txType === '출금' && tx.directionType !== 'OUT') return false
    if (keyword && !(tx.transactionSummary || tx.transactionType || '').includes(keyword)) return false
    return true
  })

  return (
    <div className="max-w-kb-container mx-auto px-6 py-4 pb-16">
      {/* 브레드크럼 */}
      <div className="flex justify-end mb-4 text-[12px] text-kb-text-muted gap-1">
        <span>개인뱅킹</span><span>&gt;</span>
        <span>조회</span><span>&gt;</span>
        <Link href="/inquiry/accounts" className="hover:underline">계좌조회</Link>
        <span>&gt;</span>
        <span className="font-semibold text-kb-text">거래내역</span>
      </div>

      {/* 계좌 정보 헤더 */}
      <div className="border border-kb-border-dark rounded-xl p-6 mb-6 bg-kb-beige-light">
        <div className="flex items-start justify-between">
          <div>
            <p className="text-[13px] text-kb-text-muted mb-1">{account.name}</p>
            <p className="text-[18px] font-bold text-kb-text mb-1">{account.number}</p>
            <div className="flex gap-4 text-[12px] text-kb-text-muted">
              <span>신규일 {account.createdAt}</span>
              {account.maturityDate && <span>만기일 {account.maturityDate}</span>}
            </div>
          </div>
          <div className="text-right">
            <div className="flex items-center justify-end gap-2 mb-1">
              <span className="text-[12px] text-kb-text-muted">잔액보기</span>
              <button
                onClick={() => setBalanceVisible(v => !v)}
                className={`relative w-10 h-5 rounded-full transition-colors ${balanceVisible ? 'bg-kb-blue' : 'bg-gray-300'}`}
              >
                <span className={`absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${balanceVisible ? 'translate-x-5' : 'translate-x-0.5'}`} />
                <span className={`absolute text-[9px] font-bold text-white ${balanceVisible ? 'left-1.5 top-0.5' : 'right-1 top-0.5'}`}>
                  {balanceVisible ? 'ON' : 'OFF'}
                </span>
              </button>
            </div>
            <p className="text-[12px] text-kb-text-muted">출금가능잔액</p>
            <p className="text-[22px] font-bold text-kb-text">
              {balanceVisible ? formatNumber(account.availableBalance) : '●●●●●●●'}원
            </p>
            <p className="text-[12px] text-kb-text-muted mt-1">
              잔액 {balanceVisible ? formatNumber(account.balance) : '●●●●●●●'}원
            </p>
          </div>
        </div>

        {/* 계좌 액션 버튼 */}
        <div className="flex gap-2 mt-4 pt-4 border-t border-kb-border">
          {canTransferFrom(account) && (
            <Link href={`/transfer/account?from=${account.id}`} className="border border-kb-border px-5 py-1.5 text-[12px] text-kb-text-body hover:bg-white transition-colors">
              이체
            </Link>
          )}
          {account.type === '적금' && (
            <button
              onClick={() => { setFeedback(null); setPayOpen(true) }}
              className="bg-kb-yellow border border-kb-taupe px-5 py-1.5 text-[12px] font-bold text-kb-text hover:brightness-95 transition"
            >
              적금 납입
            </button>
          )}
          <button className="border border-kb-border px-5 py-1.5 text-[12px] text-kb-text-body hover:bg-white transition-colors">
            계좌관리
          </button>
          <button
            onClick={() => setAlertOpen(true)}
            className="border border-kb-border px-5 py-1.5 text-[12px] text-kb-text-body hover:bg-white transition-colors"
          >
            입금통보 신청
          </button>
        </div>
      </div>

      {/* 처리 결과 안내 */}
      {feedback && (
        <div
          className={`mb-4 rounded-lg border px-4 py-3 text-[13px] ${
            feedback.type === 'ok'
              ? 'border-kb-blue/30 bg-kb-blue/5 text-kb-blue'
              : 'border-kb-red/30 bg-kb-red/5 text-kb-red'
          }`}
        >
          {feedback.msg}
        </div>
      )}

      <h2 className="text-[18px] font-bold text-kb-text mb-4 pb-2 border-b-2 border-kb-text">거래내역 조회</h2>

      {/* 조회 조건 */}
      <div className="border border-kb-border-dark rounded-xl p-6 mb-4 space-y-3">
        {/* 기간 */}
        <div className="flex items-center gap-4">
          <span className="text-[13px] font-medium text-kb-text w-16 flex-shrink-0">조회기간</span>
          <div className="flex gap-1">
            {DATE_PRESETS.map(p => (
              <button
                key={p}
                onClick={() => setDatePreset(p)}
                className={`px-4 py-1.5 text-[12px] border rounded-lg transition-colors ${
                  datePreset === p
                    ? 'bg-kb-yellow border-kb-taupe font-bold text-kb-text'
                    : 'border-kb-border text-kb-text-muted hover:bg-kb-beige-light'
                }`}
              >
                {p}
              </button>
            ))}
          </div>
        </div>

        {/* 거래 종류 */}
        <div className="flex items-center gap-4">
          <span className="text-[13px] font-medium text-kb-text w-16 flex-shrink-0">거래종류</span>
          <div className="flex gap-1">
            {TX_TYPE_OPTS.map(t => (
              <button
                key={t}
                onClick={() => setTxType(t)}
                className={`px-4 py-1.5 text-[12px] border rounded-lg transition-colors ${
                  txType === t
                    ? 'bg-kb-yellow border-kb-taupe font-bold text-kb-text'
                    : 'border-kb-border text-kb-text-muted hover:bg-kb-beige-light'
                }`}
              >
                {t}
              </button>
            ))}
          </div>
        </div>

        {/* 키워드 검색 */}
        <div className="flex items-center gap-4">
          <span className="text-[13px] font-medium text-kb-text w-16 flex-shrink-0">내용검색</span>
          <div className="flex gap-2">
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="거래 내용을 입력하세요"
              className="border border-kb-border px-3 py-1.5 text-[13px] w-60 focus:outline-none focus:border-kb-taupe"
            />
            <button className="bg-kb-yellow px-5 py-1.5 text-[12px] font-bold text-kb-text hover:brightness-95">
              조회
            </button>
          </div>
        </div>
      </div>

      {/* 거래 건수 요약 */}
      <div className="flex items-center justify-between mb-2">
        <p className="text-[12px] text-kb-text-muted">
          총 <span className="font-bold text-kb-text">{filteredTx.length}</span>건
        </p>
        <div className="flex gap-3 text-[12px] text-kb-text-muted">
          <span>
            입금합계{' '}
            <span className="font-medium text-kb-blue">
              {formatNumber(filteredTx.filter(t => t.directionType === 'IN').reduce((s, t) => s + Number(t.amount), 0))}원
            </span>
          </span>
          <span>
            출금합계{' '}
            <span className="font-medium text-kb-red">
              {formatNumber(filteredTx.filter(t => t.directionType === 'OUT').reduce((s, t) => s + Number(t.amount), 0))}원
            </span>
          </span>
        </div>
      </div>

      {/* 거래내역 테이블 */}
      <table className="w-full border-collapse text-[13px]">
        <thead>
          <tr className="bg-kb-beige-light border-t-2 border-kb-text">
            <th className="border border-kb-border px-4 py-2 text-left font-semibold text-kb-text">날짜/시간</th>
            <th className="border border-kb-border px-4 py-2 text-left font-semibold text-kb-text">내용</th>
            <th className="border border-kb-border px-4 py-2 text-right font-semibold text-kb-text">출금금액</th>
            <th className="border border-kb-border px-4 py-2 text-right font-semibold text-kb-text">입금금액</th>
            <th className="border border-kb-border px-4 py-2 text-right font-semibold text-kb-text">잔액</th>
            <th className="border border-kb-border px-4 py-2 text-center font-semibold text-kb-text">거래점</th>
            <th className="border border-kb-border px-4 py-2 text-center font-semibold text-kb-text">관리</th>
          </tr>
        </thead>
        <tbody>
          {filteredTx.length === 0 ? (
            <tr>
              <td colSpan={7} className="border border-kb-border px-4 py-8 text-center text-kb-text-muted">
                조회된 거래내역이 없습니다.
              </td>
            </tr>
          ) : (
            filteredTx.map(tx => {
              const isIn = tx.directionType === 'IN'
              const amt = Number(tx.amount)
              const canceled = tx.status === 'CANCELED'
              return (
                <tr key={tx.transactionId} className={`transition-colors ${canceled ? 'bg-kb-beige-light/60 text-kb-text-muted' : 'hover:bg-kb-beige-light'}`}>
                  <td className="border border-kb-border px-4 py-2.5 text-kb-text-muted whitespace-nowrap">{tx.transactionAt}</td>
                  <td className="border border-kb-border px-4 py-2.5 text-kb-text-body">
                    <p className={canceled ? 'line-through' : ''}>{tx.transactionSummary || tx.transactionType}</p>
                    {tx.transactionMemo && <p className="text-[11px] text-kb-blue">[{tx.transactionMemo}]</p>}
                    {canceled && <p className="text-[11px] text-kb-red">취소됨</p>}
                  </td>
                  <td className="border border-kb-border px-4 py-2.5 text-right text-kb-red font-medium">
                    {!isIn ? formatNumber(amt) : '-'}
                  </td>
                  <td className="border border-kb-border px-4 py-2.5 text-right text-kb-blue font-medium">
                    {isIn ? formatNumber(amt) : '-'}
                  </td>
                  <td className="border border-kb-border px-4 py-2.5 text-right text-kb-text">
                    -
                  </td>
                  <td className="border border-kb-border px-4 py-2.5 text-center text-kb-text-muted">
                    -
                  </td>
                  <td className="border border-kb-border px-4 py-2.5 text-center">
                    {isCancelableTransaction(tx) ? (
                      <button
                        onClick={() => handleCancel(tx)}
                        disabled={cancelingId === tx.transactionId}
                        className="border border-kb-border px-3 py-1 text-[11px] text-kb-text-body hover:bg-white transition-colors disabled:opacity-50"
                      >
                        {cancelingId === tx.transactionId ? '처리중' : '취소'}
                      </button>
                    ) : (
                      <span className="text-[11px] text-kb-text-muted">-</span>
                    )}
                  </td>
                </tr>
              )
            })
          )}
        </tbody>
      </table>

      <div className="mt-4 flex justify-center">
        <Link href="/inquiry/accounts" className="border border-kb-border px-8 py-2 text-[13px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
          목록으로
        </Link>
      </div>

      {/* 적금 납입 모달 */}
      {payOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4" onClick={() => !paying && setPayOpen(false)}>
          <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl" onClick={e => e.stopPropagation()}>
            <h3 className="text-[18px] font-bold text-kb-text mb-1">적금 납입</h3>
            <p className="text-[12px] text-kb-text-muted mb-4">{account.name} · {account.number}</p>

            <div className="rounded-lg bg-kb-beige-light p-4 mb-4 space-y-1.5 text-[13px]">
              <div className="flex justify-between">
                <span className="text-kb-text-muted">납입 회차</span>
                <span className="font-medium text-kb-text">{nextSavingsPaymentRound(transactions)}회차</span>
              </div>
              {account.monthlyAmount ? (
                <div className="flex justify-between">
                  <span className="text-kb-text-muted">기존 납입액</span>
                  <span className="font-medium text-kb-text">{formatNumber(account.monthlyAmount)}원</span>
                </div>
              ) : null}
              <div className="flex justify-between">
                <span className="text-kb-text-muted">현재 잔액</span>
                <span className="font-medium text-kb-text">{formatNumber(account.balance)}원</span>
              </div>
            </div>

            <label className="block text-[13px] font-medium text-kb-text mb-1.5">납입 금액</label>
            <div className="flex items-center gap-2 mb-2">
              <input
                type="text"
                inputMode="numeric"
                value={payAmount}
                onChange={e => setPayAmount(e.target.value.replace(/[^0-9]/g, ''))}
                placeholder="금액을 입력하세요"
                className="flex-1 border border-kb-border px-3 py-2 text-[14px] text-right focus:outline-none focus:border-kb-taupe"
              />
              <span className="text-[13px] text-kb-text-muted">원</span>
            </div>
            <div className="flex gap-1.5 mb-5">
              {[100000, 300000, 500000].map(v => (
                <button
                  key={v}
                  onClick={() => setPayAmount(String((Number(payAmount.replace(/[^0-9]/g, '')) || 0) + v))}
                  className="flex-1 border border-kb-border rounded-lg py-1.5 text-[12px] text-kb-text-body hover:bg-kb-beige-light transition-colors"
                >
                  +{formatNumber(v)}
                </button>
              ))}
            </div>

            <div className="flex gap-2">
              <button
                onClick={() => setPayOpen(false)}
                disabled={paying}
                className="flex-1 border border-kb-border py-2.5 text-[14px] text-kb-text-body hover:bg-kb-beige-light transition-colors disabled:opacity-50"
              >
                취소
              </button>
              <button
                onClick={handlePay}
                disabled={paying}
                className="flex-1 bg-kb-yellow border border-kb-taupe py-2.5 text-[14px] font-bold text-kb-text hover:brightness-95 transition disabled:opacity-50"
              >
                {paying ? '처리중...' : '납입하기'}
              </button>
            </div>
          </div>
        </div>
      )}

      {alertOpen && account && (
        <DepositAlertModal accountNumber={account.number} onClose={() => setAlertOpen(false)} />
      )}
    </div>
  )
}
