'use client'
import { KB_BORDER, KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_TEXT_LIGHT } from '@/lib/theme'

import Link from 'next/link'
import { useState, useEffect } from 'react'
import DatePickerButton from '@/components/ui/DatePickerButton'
import { formatNumber } from '@/lib/mock-data'
import { fetchDepositAccountViewModels, fetchTransactions, getCurrentDepositCustomerId, type DepositViewAccount } from '@/lib/deposit-api'
import TransferSidebar from '@/components/inquiry/TransferSidebar'

const TABS = ['즉시이체 결과조회', '예약이체 조회', '연락이체 조회', '지연이체 조회']

type ResultRow = { id: string; datetime: string; bank: string; account: string; receiver: string; amount: number; memo: string; status?: string }
type InquiryAccount = Pick<DepositViewAccount, 'id' | 'apiAccountId' | 'number' | 'name'>

export default function TransferInquiryPage() {
  const [activeTab, setActiveTab] = useState('즉시이체 결과조회')
  const [fromAccount, setFromAccount] = useState('')
  const [startDate, setStartDate] = useState('20260519')
  const [endDate, setEndDate] = useState('20260525')
  const [counterAccount, setCounterAccount] = useState('')
  const [useCounter, setUseCounter] = useState(false)
  const [searched, setSearched] = useState(false)
  const [page, setPage] = useState(1)
  const [checkedRows, setCheckedRows] = useState<Set<string>>(new Set())
  const [localResults, setLocalResults] = useState<ResultRow[]>([])
  const [accounts, setAccounts] = useState<InquiryAccount[]>([])
  const [apiResults, setApiResults] = useState<ResultRow[]>([])

  useEffect(() => {
    async function load() {
      try {
        const raw = localStorage.getItem('transferHistory')
        if (raw) setLocalResults(JSON.parse(raw))
      } catch {}
      try {
        const accs = await fetchDepositAccountViewModels(getCurrentDepositCustomerId())
        const mapped = accs.map(a => ({ id: a.id, apiAccountId: a.apiAccountId, number: a.number, name: a.name }))
        setAccounts(mapped)
        if (mapped.length > 0) setFromAccount(mapped[0].number)
        const txResults = await Promise.allSettled(
          mapped
            .filter(account => account.apiAccountId)
            .map(account => fetchTransactions({ accountId: account.apiAccountId }))
        )
        const txs = txResults.flatMap(result => result.status === 'fulfilled' ? result.value : [])
        const rows: ResultRow[] = txs
          .filter(t => t.transactionType === 'TRANSFER' && t.directionType === 'OUT')
          .map(t => ({
            id: String(t.transactionId),
            datetime: t.transactionAt?.replace('T', '\n').slice(0, 19) || '',
            bank: t.counterpartyBankName || 'AXful',
            account: t.counterpartyAccountNo || '-',
            receiver: t.counterpartyName || t.transactionSummary || '-',
            amount: Number(t.amount),
            memo: t.transactionMemo || '',
            status: t.status,
          }))
        setApiResults(rows)
      } catch {}
    }
    load()
  }, [])

  const displayResults: ResultRow[] = apiResults.length > 0 ? apiResults : localResults.length > 0 ? localResults : []

  // 쪽 나누기. 조회 결과가 클라이언트에 다 있어 서버 왕복 없이 자른다.
  // 예전에는 |< < > >| 버튼이 아무 일도 하지 않았고, 옆의 "현재 1-N건 / N건" 도
  // 늘 전체를 가리켜 몇 건이든 한 쪽처럼 보였다.
  const PAGE_SIZE = 10
  const totalPages = Math.max(1, Math.ceil(displayResults.length / PAGE_SIZE))
  const pageStart = (page - 1) * PAGE_SIZE
  const pageRows = displayResults.slice(pageStart, pageStart + PAGE_SIZE)

  function toggleRow(id: string) {
    setCheckedRows(prev => {
      const next = new Set(prev)
      if (next.has(id)) { next.delete(id) } else { next.add(id) }
      return next
    })
  }

  function toggleAll(checked: boolean) {
    setCheckedRows(checked ? new Set(displayResults.map(r => r.id)) : new Set())
  }

  const allChecked = checkedRows.size === displayResults.length

  const displayFrom = startDate.length === 8
    ? `${startDate.slice(0,4)}.${startDate.slice(4,6)}.${startDate.slice(6,8)}`
    : startDate
  const displayTo = endDate.length === 8
    ? `${endDate.slice(0,4)}.${endDate.slice(4,6)}.${endDate.slice(6,8)}`
    : endDate

  function applyPeriod(days: number) {
    const to = new Date(2026, 4, 25)
    const from = new Date(to)
    from.setDate(to.getDate() - days)
    const fmt = (d: Date) =>
      `${d.getFullYear()}${String(d.getMonth()+1).padStart(2,'0')}${String(d.getDate()).padStart(2,'0')}`
    setStartDate(fmt(from))
    setEndDate(fmt(to))
  }

  function applyMonth(month: number) {
    setStartDate(`2026${String(month).padStart(2,'0')}01`)
    setEndDate(`2026${String(month).padStart(2,'0')}31`)
  }

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <TransferSidebar />

        {/* ===== 본문 ===== */}
        <main className="flex-1 pl-8 pt-4 pb-12">
          {/* 브레드크럼 */}
          <div className="flex justify-end mb-2 text-[12px] text-kb-text-muted gap-1">
            <span>개인뱅킹</span><span>›</span>
            <span>이체</span><span>›</span>
            <span>이체결과 조회</span><span>›</span>
            <span>계좌이체결과 조회</span><span>›</span>
            <Link href="#" className="font-medium hover:underline" style={{ color: KB_PRIMARY }}>? 도움말</Link>
          </div>

          <h1 className="text-[22px] font-bold text-kb-text mb-5">계좌이체결과 조회</h1>

          {/* 탭 */}
          <div className="flex border-b mb-5" style={{ borderColor: KB_PRIMARY_BORDER }}>
            {TABS.map(tab => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className="px-5 py-2.5 text-[13px] border-b-2 -mb-px transition-colors"
                style={activeTab === tab
                  ? { borderColor: KB_PRIMARY, color: KB_PRIMARY, fontWeight: 700, backgroundColor: 'white' }
                  : { borderColor: 'transparent', backgroundColor: KB_PRIMARY_SURFACE, color: KB_TEXT_LIGHT }}
              >
                {tab}
              </button>
            ))}
          </div>

          {/* 조회 폼 */}
          <div className="rounded-xl overflow-hidden mb-5" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <table className="w-full text-[13px]">
              <tbody>
                {/* 출금계좌번호 */}
                <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <td className="px-4 py-3 font-semibold text-kb-text w-[140px] whitespace-nowrap" style={{ backgroundColor: KB_PRIMARY_BG }}>출금계좌번호</td>
                  <td className="px-4 py-3">
                    <select
                      value={fromAccount}
                      onChange={e => setFromAccount(e.target.value)}
                      className="border rounded-lg px-3 py-1.5 text-[13px] w-[280px] outline-none"
                      style={{ borderColor: KB_BORDER }}
                    >
                      {accounts.map(a => (
                        <option key={a.id} value={a.number}>{a.number}</option>
                      ))}
                    </select>
                  </td>
                </tr>

                {/* 조회기간 */}
                <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <td className="px-4 py-3 font-semibold text-kb-text whitespace-nowrap" style={{ backgroundColor: KB_PRIMARY_BG }}>조회기간</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1 mb-2 flex-wrap">
                      {[['당일',0],['1주일',7],['1개월',30],['3개월',90],['6개월',180]].map(([label, days]) => (
                        <button key={label as string}
                          onClick={() => applyPeriod(days as number)}
                          className="border rounded-lg px-3 py-1 text-[12px] font-medium transition-colors hover:bg-kb-primary-bg"
                          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                          {label}
                        </button>
                      ))}
                      <div className="w-px h-4 mx-1" style={{ backgroundColor: KB_PRIMARY_BORDER }} />
                      {[5, 4, 3].map(m => (
                        <button key={m}
                          onClick={() => applyMonth(m)}
                          className="border rounded-lg px-3 py-1 text-[12px] font-medium transition-colors hover:bg-kb-primary-bg"
                          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                          {m.toString().padStart(2,'0')}월
                        </button>
                      ))}
                    </div>
                    <div className="flex items-center gap-2">
                      <div className="flex items-center gap-1">
                        <input
                          type="text"
                          value={startDate}
                          onChange={e => setStartDate(e.target.value)}
                          maxLength={8}
                          className="border rounded-lg px-2 py-1.5 text-[13px] w-28 outline-none"
                          style={{ borderColor: KB_BORDER }}
                          placeholder="YYYYMMDD"
                        />
                        <DatePickerButton value={startDate} onChange={setStartDate} label="조회 시작일 선택" />
                      </div>
                      <span className="text-kb-text-muted">~</span>
                      <div className="flex items-center gap-1">
                        <input
                          type="text"
                          value={endDate}
                          onChange={e => setEndDate(e.target.value)}
                          maxLength={8}
                          className="border rounded-lg px-2 py-1.5 text-[13px] w-28 outline-none"
                          style={{ borderColor: KB_BORDER }}
                          placeholder="YYYYMMDD"
                        />
                        <DatePickerButton value={endDate} onChange={setEndDate} label="조회 종료일 선택" />
                      </div>
                    </div>
                  </td>
                </tr>

                {/* 상대 입금계좌 */}
                <tr>
                  <td className="px-4 py-3 font-semibold text-kb-text whitespace-nowrap" style={{ backgroundColor: KB_PRIMARY_BG }}>상대 입금계좌</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={useCounter}
                        onChange={e => setUseCounter(e.target.checked)}
                        className="w-4 h-4"
                      />
                      <input
                        type="text"
                        value={counterAccount}
                        onChange={e => setCounterAccount(e.target.value)}
                        disabled={!useCounter}
                        className="border rounded-lg px-3 py-1.5 text-[13px] w-44 outline-none"
                        style={{ borderColor: KB_BORDER, backgroundColor: useCounter ? 'white' : KB_PRIMARY_SURFACE }}
                      />
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
            <div className="flex justify-center py-4" style={{ borderTop: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <button
                onClick={() => { setSearched(true); setPage(1) }}
                className="px-24 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity"
                style={{ backgroundColor: KB_PRIMARY }}
              >
                조회
              </button>
            </div>
          </div>

          {/* 결과 영역 */}
          {searched && (
            <>
              <div className="rounded-xl px-4 py-3 mb-2 text-[13px]" style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_SURFACE }}>
                <span className="text-kb-text-muted mr-1">*</span>
                <span className="text-kb-text-muted">계좌번호 : </span>
                <Link href="#" className="underline font-medium" style={{ color: KB_PRIMARY }}>{fromAccount}</Link>
              </div>

              <div className="text-right text-[12px] text-kb-text-muted mb-1">
                조회기간 : {displayFrom} ~ {displayTo}
              </div>

              <div className="overflow-x-auto mb-1 rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <table className="w-full border-collapse text-[13px]">
                  <thead>
                    <tr style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `2px solid ${KB_PRIMARY_BORDER}` }}>
                      <th className="px-2 py-2 text-center w-8" style={{ borderBottom: `2px solid ${KB_PRIMARY}` }}>
                        <input type="checkbox" checked={allChecked} onChange={e => toggleAll(e.target.checked)} className="w-4 h-4" />
                      </th>
                      {['이체일시', '입금은행', '입금계좌번호', '받는분', '이체금액', '출금통장표시내용', '이체유무'].map(h => (
                        <th key={h} className="px-3 py-2 text-center font-semibold text-[12px] whitespace-nowrap"
                          style={{ borderBottom: `2px solid ${KB_PRIMARY}`, color: KB_PRIMARY }}>
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {displayResults.length === 0 ? (
                      <tr>
                        <td colSpan={8} className="px-3 py-8 text-center text-[13px] text-kb-text-muted">
                          조회된 이체 내역이 없습니다.
                        </td>
                      </tr>
                    ) : pageRows.map(row => (
                      <tr key={row.id} className="hover:bg-kb-primary-surface transition-colors" style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                        <td className="px-2 py-3 text-center">
                          <input type="checkbox" checked={checkedRows.has(row.id)} onChange={() => toggleRow(row.id)} className="w-4 h-4" />
                        </td>
                        <td className="px-3 py-3 text-center whitespace-pre-line text-[12px]">{row.datetime}</td>
                        <td className="px-3 py-3 text-center">{row.bank}</td>
                        <td className="px-3 py-3 text-center">{row.account}</td>
                        <td className="px-3 py-3 text-center">{row.receiver}</td>
                        <td className="px-3 py-3 text-right pr-4 font-semibold" style={{ color: KB_PRIMARY }}>{formatNumber(row.amount)}</td>
                        <td className="px-3 py-3 text-center text-kb-text-muted">{row.memo}</td>
                        <td className="px-3 py-3 text-center">
                          {row.status === 'CANCELED'
                            ? <span className="font-medium text-kb-red">취소</span>
                            : <span className="font-medium" style={{ color: KB_PRIMARY }}>정상</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* 페이지네이션 */}
              <div className="flex items-center justify-between mb-5">
                <div className="flex items-center gap-1">
                  {([
                    ['|<', 1,               page === 1],
                    ['<',  page - 1,        page === 1],
                    ['>',  page + 1,        page === totalPages],
                    ['>|', totalPages,      page === totalPages],
                  ] as const).map(([label, target, disabled]) => (
                    <button key={label}
                      onClick={() => setPage(target)}
                      disabled={disabled}
                      className="border rounded-lg px-2 py-1 text-[12px] text-kb-text-muted hover:bg-kb-primary-bg transition-colors disabled:opacity-40"
                      style={{ borderColor: KB_PRIMARY_BORDER }}>
                      {label}
                    </button>
                  ))}
                  <span className="ml-2 text-[12px] text-kb-text-muted">
                    현재 {displayResults.length === 0 ? 0 : pageStart + 1}-{pageStart + pageRows.length}건
                    {' / '}{displayResults.length}건
                  </span>
                </div>
              </div>

              {/* 하단 버튼 */}
              <div className="flex justify-center gap-2">
                <button className="border rounded-xl px-5 py-2 text-[13px] font-medium hover:bg-kb-primary-bg transition-colors flex items-center gap-1"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                  <svg viewBox="0 0 16 16" fill="none" className="w-3.5 h-3.5" stroke="currentColor" strokeWidth="1.5">
                    <rect x="2" y="2" width="12" height="12" rx="1"/><line x1="8" y1="5" x2="8" y2="11"/><line x1="5" y1="8" x2="11" y2="8"/>
                  </svg>
                  저장
                </button>
                <button className="border rounded-xl px-5 py-2 text-[13px] font-medium hover:bg-kb-primary-bg transition-colors"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                  이체확인증 건별 출력
                </button>
                <button className="border rounded-xl px-5 py-2 text-[13px] font-medium hover:bg-kb-primary-bg transition-colors flex items-center gap-1"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                  이체확인증 일괄 출력
                  <svg viewBox="0 0 12 12" fill="none" className="w-3 h-3" stroke="currentColor" strokeWidth="1.5">
                    <path d="M2 10L10 2M10 2H5M10 2v5"/>
                  </svg>
                </button>
                <button className="border rounded-xl px-5 py-2 text-[13px] font-medium hover:bg-kb-primary-bg transition-colors"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                  이체결과 전송
                </button>
              </div>
            </>
          )}
        </main>
      </div>
    </div>
  )
}
