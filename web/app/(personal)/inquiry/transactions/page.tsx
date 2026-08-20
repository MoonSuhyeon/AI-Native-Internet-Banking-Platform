'use client'
import { KB_BORDER, KB_DANGER, KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_TEXT_BODY, KB_TEXT_LIGHT, KB_TEXT_MUTED } from '@/lib/theme'

import Link from 'next/link'
import { Fragment, useState, useEffect } from 'react'
import InquirySidebar from '@/components/inquiry/InquirySidebar'
import { formatNumber } from '@/lib/mock-data'
import { fetchDepositAccountViewModels, getCurrentDepositCustomerId, fetchTransactions,
         issueTransactionCertificate,
         DepositViewAccount, DepositTransaction, type TransactionCertificate } from '@/lib/deposit-api'
import CertificateModal from '@/components/transactions/CertificateModal'

const PERIOD_BUTTONS = ['당일', '1주일', '1개월', '3개월', '6개월', '1년']
const YEARS  = ['2026', '2025', '2024']
const MONTHS = ['01','02','03','04','05','06','07','08','09','10','11','12']

export default function TransactionsPage() {
  const [selectedAccount, setSelectedAccount] = useState('')
  const [calAccount, setCalAccount]     = useState('')
  const [year, setYear]   = useState('2026')
  const [month, setMonth] = useState('05')
  const [startDate, setStartDate] = useState('20260521')
  const [endDate, setEndDate]     = useState('20260521')
  const [activeTab, setActiveTab] = useState('간편조회')
  const [searched, setSearched]   = useState(false)
  const [calSearched, setCalSearched] = useState(false)
  const [expandedTxId, setExpandedTxId] = useState<number | null>(null)
  const [receipt, setReceipt] = useState<TransactionCertificate[] | null>(null)
  const [issuingTxId, setIssuingTxId] = useState<number | null>(null)
  const [receiptError, setReceiptError] = useState('')

  // 영수증은 이체가 아닌 거래에도 발급된다 — "내 계좌에서 무슨 일이 있었나" 라서
  // 상대가 없어도 문서가 성립한다.
  async function issueReceipt(transactionId: number) {
    setIssuingTxId(transactionId)
    setReceiptError('')
    try {
      setReceipt([await issueTransactionCertificate(transactionId, 'RECEIPT')])
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } }
      setReceiptError(err.response?.data?.message ?? '영수증 발급에 실패했습니다.')
    } finally {
      setIssuingTxId(null)
    }
  }
  const [txType, setTxType]     = useState<'전체' | '입금' | '출금'>('전체')
  const [sortOrder, setSortOrder] = useState<'최근' | '과거'>('최근')
  const [memoFilter, setMemoFilter] = useState('')
  const [minAmount, setMinAmount] = useState('')
  const [maxAmount, setMaxAmount] = useState('')
  const [senderFilter, setSenderFilter] = useState('')
  const [accounts, setAccounts] = useState<DepositViewAccount[]>([])
  const [allTransactions, setAllTransactions] = useState<DepositTransaction[]>([])
  const [loadingAccounts, setLoadingAccounts] = useState(true)

  useEffect(() => {
    const customerId = getCurrentDepositCustomerId()
    async function loadData() {
      let loadedAccounts: DepositViewAccount[] = []
      try {
        const accs = await fetchDepositAccountViewModels(customerId)
        loadedAccounts = accs
        setAccounts(accs)
        setSelectedAccount((current) => current || accs[0]?.id || '')
        setCalAccount((current) => current || accs[0]?.id || '')
      } catch { setAccounts([]) }
      finally { setLoadingAccounts(false) }
      try {
        const txGroups = await Promise.all(
          loadedAccounts
            .map(account => account.apiAccountId)
            .filter((accountId): accountId is number => accountId != null)
            .map(accountId => fetchTransactions({ accountId })),
        )
        setAllTransactions(txGroups.flat())
      } catch { setAllTransactions([]) }
    }
    loadData()
  }, [])

  const txs = selectedAccount
    ? allTransactions.filter(t => {
        const account = accounts.find(a => a.id === selectedAccount)
        if (!account) return false
        const txAccountId = (t as DepositTransaction & { accountId?: number }).accountId
        return account.number === t.accountNumber || account.apiAccountId === txAccountId
      })
    : []

  const filteredTxs = txs.filter(t => {
    if (txType !== '전체' && ((txType === '입금') !== (t.directionType === 'IN'))) return false
    if (senderFilter && !(t.counterpartyName ?? '').includes(senderFilter)) return false
    const amt = Number(t.amount)
    if (minAmount && amt < Number(minAmount)) return false
    if (maxAmount && amt > Number(maxAmount)) return false
    return true
  })
  const sortedTxs = [...filteredTxs].sort((a, b) =>
    sortOrder === '최근'
      ? b.transactionAt.localeCompare(a.transactionAt)
      : a.transactionAt.localeCompare(b.transactionAt)
  )

  const totalDeposit  = filteredTxs.filter(t => t.directionType === 'IN').reduce((s, t) => s + Number(t.amount), 0)
  const totalWithdraw = filteredTxs.filter(t => t.directionType === 'OUT').reduce((s, t) => s + Number(t.amount), 0)
  const selectedAcc   = accounts.find(a => a.id === selectedAccount)

  function setPeriod(p: string) {
    const today = new Date()
    const fmt = (d: Date) =>
      `${d.getFullYear()}${String(d.getMonth()+1).padStart(2,'0')}${String(d.getDate()).padStart(2,'0')}`
    const end = fmt(today)
    const s = new Date(today)
    if      (p === '당일')  { setStartDate(end); setEndDate(end); return }
    else if (p === '1주일') s.setDate(s.getDate()-7)
    else if (p === '1개월') s.setMonth(s.getMonth()-1)
    else if (p === '3개월') s.setMonth(s.getMonth()-3)
    else if (p === '6개월') s.setMonth(s.getMonth()-6)
    else if (p === '1년')   s.setFullYear(s.getFullYear()-1)
    setStartDate(fmt(s)); setEndDate(end)
  }

  const calTxs = (() => {
    if (!calSearched || !calAccount) return []
    const prefix = `${year}-${month}`
    const account = accounts.find(a => a.id === calAccount)
    return allTransactions.filter(t => {
      const txAccountId = (t as DepositTransaction & { accountId?: number }).accountId
      return (account?.number === t.accountNumber || account?.apiAccountId === txAccountId) && t.transactionAt.startsWith(prefix)
    })
  })()

  function buildCalendar() {
    const y = parseInt(year), m = parseInt(month)
    const firstDay = new Date(y, m-1, 1).getDay()
    const daysInMonth = new Date(y, m, 0).getDate()
    return { firstDay, daysInMonth }
  }
  const { firstDay, daysInMonth } = buildCalendar()

  function txsOnDay(day: number) {
    const d = String(day).padStart(2,'0')
    return calTxs.filter(t => t.transactionAt.startsWith(`${year}-${month}-${d}`))
  }

  const inputCls = "border rounded-lg px-3 py-1.5 text-[13px] outline-none focus:ring-1 transition-all"
  const inputStyle = { borderColor: KB_BORDER }

  const periodBtn = (p: string) => (
    <button key={p} onClick={() => setPeriod(p)}
      className="border rounded-lg px-4 py-1.5 text-[12px] font-medium transition-colors hover:bg-kb-primary-bg"
      style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
      {p}
    </button>
  )

  const labelCell = "px-4 py-3 text-[13px] font-semibold text-kb-text whitespace-nowrap w-[130px]"
  const valueCell = "px-4 py-3"

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <InquirySidebar />

        <main className="flex-1 pl-8 pt-6 pb-12">
          <h1 className="text-[22px] font-bold text-kb-text mb-5">거래내역 조회</h1>

          {/* 탭 */}
          <div className="flex border-b mb-5" style={{ borderColor: KB_PRIMARY_BORDER }}>
            {['간편조회', '상세조회', '달력으로 보기'].map(tab => (
              <button key={tab} onClick={() => setActiveTab(tab)}
                className="px-6 py-2.5 text-[14px] font-medium border-b-2 -mb-px transition-colors"
                style={activeTab === tab
                  ? { borderColor: KB_PRIMARY, color: KB_PRIMARY, fontWeight: 700 }
                  : { borderColor: 'transparent', color: KB_TEXT_LIGHT }}>
                {tab}
              </button>
            ))}
          </div>

          {/* ── 달력으로 보기 ── */}
          {activeTab === '달력으로 보기' && (
            <div>
              <div className="rounded-xl overflow-hidden mb-5" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <table className="w-full text-[13px]">
                  <tbody>
                    <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                      <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>계좌번호</td>
                      <td className={valueCell}>
                        <select value={calAccount} onChange={e => { setCalAccount(e.target.value); setCalSearched(false) }}
                          className={inputCls + " w-[340px]"} style={inputStyle}>
                          <option value="">－선택－</option>
                          {loadingAccounts ? <option disabled>loading...</option>
                            : accounts.map(a => <option key={a.id} value={a.id}>{a.number} : {a.name}</option>)}
                        </select>
                      </td>
                    </tr>
                    <tr>
                      <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>조회기간</td>
                      <td className={valueCell}>
                        <div className="flex items-center gap-2">
                          <select value={year} onChange={e => { setYear(e.target.value); setCalSearched(false) }}
                            className={inputCls} style={inputStyle}>
                            {YEARS.map(y => <option key={y}>{y}</option>)}
                          </select>
                          <span className="text-[13px] text-kb-text-muted">년</span>
                          <select value={month} onChange={e => { setMonth(e.target.value); setCalSearched(false) }}
                            className={inputCls} style={inputStyle}>
                            {MONTHS.map(m => <option key={m}>{m}</option>)}
                          </select>
                          <span className="text-[13px] text-kb-text-muted">월</span>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div className="flex justify-center mb-6">
                <button onClick={() => setCalSearched(true)}
                  className="px-14 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  조회
                </button>
              </div>

              {calSearched && (
                <div>
                  <p className="text-[14px] font-bold text-kb-text mb-3">{year}년 {month}월</p>
                  <table className="w-full border-collapse text-[12px] mb-6">
                    <thead>
                      <tr>
                        {['일','월','화','수','목','금','토'].map((d, i) => (
                          <th key={d} className="py-2 text-center font-semibold text-[12px]"
                            style={{
                              backgroundColor: KB_PRIMARY_BG,
                              border: `1px solid ${KB_PRIMARY_BORDER}`,
                              color: i === 0 ? KB_DANGER : i === 6 ? '#3B82F6' : KB_TEXT_BODY,
                            }}>
                            {d}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {Array.from({ length: Math.ceil((firstDay + daysInMonth) / 7) }).map((_, weekIdx) => (
                        <tr key={weekIdx}>
                          {Array.from({ length: 7 }).map((_, dayIdx) => {
                            const day = weekIdx * 7 + dayIdx - firstDay + 1
                            const valid = day >= 1 && day <= daysInMonth
                            const dayTxs = valid ? txsOnDay(day) : []
                            return (
                              <td key={dayIdx} className="align-top p-1.5 h-20 text-[11px]"
                                style={{
                                  border: `1px solid ${KB_PRIMARY_BORDER}`,
                                  color: dayIdx === 0 ? KB_DANGER : dayIdx === 6 ? '#3B82F6' : KB_TEXT_BODY,
                                }}>
                                {valid && (
                                  <>
                                    <p className="font-semibold mb-0.5">{day}</p>
                                    {dayTxs.filter(t => t.directionType === 'IN').map(t => (
                                      <p key={t.transactionId} className="truncate" style={{ color: KB_PRIMARY }}>{formatNumber(Number(t.amount))}</p>
                                    ))}
                                    {dayTxs.filter(t => t.directionType === 'OUT').map(t => (
                                      <p key={t.transactionId} className="truncate text-kb-danger">{formatNumber(Number(t.amount))}</p>
                                    ))}
                                  </>
                                )}
                              </td>
                            )
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {calTxs.length === 0 && (
                    <p className="text-center text-[13px] text-kb-text-muted py-6">해당 월 거래 내역이 없습니다.</p>
                  )}
                </div>
              )}
            </div>
          )}

          {/* ── 간편조회 / 상세조회 ── */}
          {activeTab !== '달력으로 보기' && (
            <div>
              <div className="rounded-xl overflow-hidden mb-5" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <table className="w-full text-[13px]">
                  <tbody>
                    <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                      <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>계좌번호</td>
                      <td className={valueCell}>
                        <select value={selectedAccount}
                          onChange={e => { setSelectedAccount(e.target.value); setSearched(false) }}
                          className={inputCls + " w-[340px]"} style={inputStyle}>
                          <option value="">－선택－</option>
                          {loadingAccounts ? <option disabled>loading...</option>
                            : accounts.map(a => <option key={a.id} value={a.id}>{a.number} : {a.name}</option>)}
                        </select>
                      </td>
                    </tr>

                    {activeTab === '상세조회' && (
                      <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                        <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>월별조회</td>
                        <td className={valueCell}>
                          <div className="flex items-center gap-2">
                            <select value={year} onChange={e => setYear(e.target.value)} className={inputCls} style={inputStyle}>
                              {YEARS.map(y => <option key={y}>{y}</option>)}
                            </select>
                            <span className="text-kb-text-muted">년</span>
                            <select value={month} onChange={e => setMonth(e.target.value)} className={inputCls} style={inputStyle}>
                              {MONTHS.map(m => <option key={m}>{m}</option>)}
                            </select>
                            <span className="text-kb-text-muted">월</span>
                          </div>
                        </td>
                      </tr>
                    )}

                    <tr style={activeTab === '상세조회' ? { borderBottom: '1px solid #E2F5EF' } : {}}>
                      <td className={labelCell + " align-top pt-4"} style={{ backgroundColor: KB_PRIMARY_BG }}>조회기간</td>
                      <td className={valueCell}>
                        <div className="flex flex-wrap gap-1.5 mb-2">
                          {PERIOD_BUTTONS.map(p => periodBtn(p))}
                        </div>
                        <div className="flex items-center gap-2">
                          <input type="text" value={startDate} onChange={e => setStartDate(e.target.value)}
                            className={inputCls + " w-28"} style={inputStyle} />
                          <span className="text-kb-text-muted">~</span>
                          <input type="text" value={endDate} onChange={e => setEndDate(e.target.value)}
                            className={inputCls + " w-28"} style={inputStyle} />
                        </div>
                      </td>
                    </tr>

                    {activeTab === '상세조회' && (
                      <>
                        <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                          <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>조회내용</td>
                          <td className={valueCell}>
                            <div className="flex items-center gap-5">
                              {(['전체', '입금내용만', '출금내용만'] as const).map(v => (
                                <label key={v} className="flex items-center gap-1.5 text-[13px] cursor-pointer">
                                  <input type="radio" name="txType"
                                    checked={txType === (v === '전체' ? '전체' : v === '입금내용만' ? '입금' : '출금')}
                                    onChange={() => setTxType(v === '전체' ? '전체' : v === '입금내용만' ? '입금' : '출금')}
                                    style={{ accentColor: KB_PRIMARY }} />
                                  {v}
                                </label>
                              ))}
                            </div>
                          </td>
                        </tr>
                        <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                          <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>조회결과 순서</td>
                          <td className={valueCell}>
                            <div className="flex items-center gap-5">
                              {[['최근', '최근 거래내역이 위로'], ['과거', '과거 거래내역이 위로']].map(([val, label]) => (
                                <label key={val} className="flex items-center gap-1.5 text-[13px] cursor-pointer">
                                  <input type="radio" name="sortOrder"
                                    checked={sortOrder === val}
                                    onChange={() => setSortOrder(val as '최근' | '과거')}
                                    style={{ accentColor: KB_PRIMARY }} />
                                  {label}
                                </label>
                              ))}
                            </div>
                          </td>
                        </tr>
                        <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                          <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>메모</td>
                          <td className={valueCell}>
                            <select value={memoFilter} onChange={e => setMemoFilter(e.target.value)}
                              className={inputCls + " w-36"} style={inputStyle}>
                              <option value="">전체</option>
                              <option value="있음">메모 있음</option>
                              <option value="없음">메모 없음</option>
                            </select>
                          </td>
                        </tr>
                        <tr>
                          <td className={labelCell + " align-top pt-4"} style={{ backgroundColor: KB_PRIMARY_BG }}>추가검색</td>
                          <td className={valueCell + " space-y-2"}>
                            <div className="flex items-center gap-2 text-[13px]">
                              <span className="text-kb-text-muted w-24">금액 범위</span>
                              <input type="text" value={minAmount} onChange={e => setMinAmount(e.target.value)}
                                placeholder="최소" className={inputCls + " w-24"} style={inputStyle} />
                              <span className="text-kb-text-muted">원 ~</span>
                              <input type="text" value={maxAmount} onChange={e => setMaxAmount(e.target.value)}
                                placeholder="최대" className={inputCls + " w-24"} style={inputStyle} />
                              <span className="text-kb-text-muted">원</span>
                            </div>
                            <div className="flex items-center gap-2 text-[13px]">
                              <span className="text-kb-text-muted w-24">보낸분/받는분</span>
                              <input type="text" value={senderFilter} onChange={e => setSenderFilter(e.target.value)}
                                className={inputCls + " w-48"} style={inputStyle} />
                            </div>
                          </td>
                        </tr>
                      </>
                    )}
                  </tbody>
                </table>
              </div>

              <div className="flex justify-center mb-6">
                <button onClick={() => setSearched(true)}
                  className="px-14 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  조회
                </button>
              </div>

              {/* 조회 결과 */}
              {searched && (
                <div>
                  {/* 선택 계좌 카드 */}
                  {selectedAcc && (
                    <div className="rounded-xl p-5 mb-4" style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_SURFACE }}>
                      <div className="flex items-center justify-between">
                        <div>
                          <div className="flex items-center gap-2 mb-1">
                            <span className="text-[15px] font-bold text-kb-text">{selectedAcc.number}</span>
                            {selectedAcc.badge && (
                              <span className="text-[11px] px-2 py-0.5 rounded-full" style={{ backgroundColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                                {selectedAcc.badge}
                              </span>
                            )}
                          </div>
                          <p className="text-[12px] text-kb-text-muted mb-1">{selectedAcc.name}</p>
                          <p className="text-[13px] text-kb-text">
                            잔액 <span className="font-bold text-[16px]" style={{ color: KB_PRIMARY }}>{formatNumber(selectedAcc.balance)}원</span>
                            <span className="text-kb-text-muted ml-2 text-[12px]">(출금가능 {formatNumber(selectedAcc.availableBalance)}원)</span>
                          </p>
                        </div>
                        <Link href="/transfer/account"
                          className="px-5 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                          style={{ backgroundColor: KB_PRIMARY }}>
                          이체
                        </Link>
                      </div>
                    </div>
                  )}

                  {/* 합계 */}
                  <div className="flex justify-between text-[13px] text-kb-text-muted mb-3 px-1">
                    <span>총 입금 ({filteredTxs.filter(t=>t.directionType==='IN').length}건) <span className="font-semibold" style={{ color: KB_PRIMARY }}>{formatNumber(totalDeposit)}원</span></span>
                    <span>총 출금 ({filteredTxs.filter(t=>t.directionType==='OUT').length}건) <span className="font-semibold text-kb-danger">{formatNumber(totalWithdraw)}원</span></span>
                  </div>

                  {/* 거래 테이블 */}
                  <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                    <table className="w-full border-collapse text-[12px]">
                      <thead>
                        <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                          {['거래일시', '적요', '보낸분/받는분', '출금액(원)', '입금액(원)', '잔액(원)', '메모'].map(h => (
                            <th key={h} className="px-3 py-2.5 text-center font-semibold text-[12px]"
                              style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>
                              {h}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {sortedTxs.length === 0 ? (
                          <tr>
                            <td colSpan={7} className="py-10 text-center text-[13px] text-kb-text-muted">
                              조회 내역이 없습니다.
                            </td>
                          </tr>
                        ) : sortedTxs.map(tx => {
                          const isExpanded = expandedTxId === tx.transactionId
                          const isIn = tx.directionType === 'IN'
                          const amt = Number(tx.amount)
                          return (
                            <Fragment key={tx.transactionId}>
                              <tr
                                onClick={() => setExpandedTxId(isExpanded ? null : tx.transactionId)}
                                className="cursor-pointer transition-colors"
                                style={isExpanded
                                  ? { backgroundColor: KB_PRIMARY_BG }
                                  : undefined}
                                onMouseEnter={e => { if (!isExpanded) (e.currentTarget as HTMLElement).style.backgroundColor = KB_PRIMARY_SURFACE }}
                                onMouseLeave={e => { if (!isExpanded) (e.currentTarget as HTMLElement).style.backgroundColor = '' }}
                              >
                                <td className="px-3 py-2.5 text-center" style={{ borderBottom: '1px solid #F0F0F0' }}>{tx.transactionAt?.slice(0,16).replace('T',' ')}</td>
                                <td className="px-3 py-2.5" style={{ borderBottom: '1px solid #F0F0F0' }}>{tx.transactionSummary || tx.transactionType}</td>
                                <td className="px-3 py-2.5 text-center" style={{ borderBottom: '1px solid #F0F0F0' }}>{tx.counterpartyName ?? ''}</td>
                                <td className="px-3 py-2.5 text-right font-semibold" style={{ borderBottom: '1px solid #F0F0F0', color: KB_DANGER }}>
                                  {!isIn ? formatNumber(amt) : ''}
                                </td>
                                <td className="px-3 py-2.5 text-right font-semibold" style={{ borderBottom: '1px solid #F0F0F0', color: KB_PRIMARY }}>
                                  {isIn ? formatNumber(amt) : ''}
                                </td>
                                <td className="px-3 py-2.5 text-right" style={{ borderBottom: '1px solid #F0F0F0', color: KB_TEXT_MUTED }}>-</td>
                                <td className="px-3 py-2.5 text-center" style={{ borderBottom: '1px solid #F0F0F0', color: KB_TEXT_MUTED }}>{tx.transactionMemo ?? ''}</td>
                              </tr>
                              {isExpanded && (
                                <tr>
                                  <td colSpan={7} style={{ backgroundColor: KB_PRIMARY_SURFACE, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                                    <div className="flex gap-10 px-6 py-4 text-[12px]">
                                      <div className="space-y-1.5">
                                        {[
                                          ['거래일시', tx.transactionAt?.slice(0,16).replace('T',' ')],
                                          ['거래구분', isIn ? '입금' : '출금'],
                                          ['거래금액', `${formatNumber(amt)}원`],
                                        ].map(([k, v]) => (
                                          <div key={k} className="flex gap-3">
                                            <span className="text-kb-text-muted w-20">{k}</span>
                                            <span className="font-medium" style={{ color: k === '거래구분' ? (isIn ? KB_PRIMARY : KB_DANGER) : KB_TEXT_BODY }}>{v}</span>
                                          </div>
                                        ))}
                                      </div>
                                      <div className="space-y-1.5">
                                        {[
                                          ['적요', tx.transactionSummary || tx.transactionType],
                                          tx.transactionMemo ? ['메모', tx.transactionMemo] : null,
                                          ['거래유형', tx.transactionType],
                                        ].filter(Boolean).map(row => (
                                          <div key={row![0]} className="flex gap-3">
                                            <span className="text-kb-text-muted w-20">{row![0]}</span>
                                            <span className="text-kb-text">{row![1]}</span>
                                          </div>
                                        ))}
                                      </div>
                                      <div className="ml-auto flex items-start gap-2 pt-1">
                                        <button
                                          onClick={() => issueReceipt(tx.transactionId)}
                                          disabled={issuingTxId !== null}
                                          className="border rounded-lg px-3 py-1 text-[12px] transition-colors hover:bg-kb-primary-bg disabled:opacity-40"
                                          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                                          {issuingTxId === tx.transactionId ? '발급 중…' : '거래영수증'}
                                        </button>
                                        <button className="border rounded-lg px-3 py-1 text-[12px] transition-colors hover:bg-kb-primary-bg"
                                          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                                          메모 수정
                                        </button>
                                      </div>
                                    </div>
                                  </td>
                                </tr>
                              )}
                            </Fragment>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </div>
          )}
        </main>
      </div>
      {receiptError && (
        <p className="text-center text-[12px] text-kb-danger py-2">{receiptError}</p>
      )}
      {receipt && (
        <CertificateModal certificates={receipt} onClose={() => setReceipt(null)} />
      )}
    </div>
  )
}
