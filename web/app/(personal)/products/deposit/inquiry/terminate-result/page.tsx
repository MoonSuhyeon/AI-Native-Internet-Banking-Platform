'use client'
import { KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE } from '@/lib/theme'

import { useState } from 'react'
import DepositSidebar from '@/components/products/DepositSidebar'
import {
  fetchDepositAccounts,
  fetchDepositContracts,
  fetchDepositProducts,
  getCurrentDepositCustomerId,
} from '@/lib/deposit-api'

const PERIOD_BTNS = ['당일', '1개월', '6개월', '1년']

export default function TerminateResultPage() {
  const [startYear,  setStartYear]  = useState('2026')
  const [startMonth, setStartMonth] = useState('05')
  const [startDay,   setStartDay]   = useState('25')
  const [endYear,    setEndYear]    = useState('2026')
  const [endMonth,   setEndMonth]   = useState('05')
  const [endDay,     setEndDay]     = useState('25')
  const [searched,   setSearched]   = useState(false)
  const [rows, setRows] = useState<Array<{
    accountNo: string
    terminatedAt: string
    type: string
    amount: string
  }>>([])

  const YEARS  = ['2026', '2025', '2024', '2023', '2022']
  const MONTHS = Array.from({ length: 12 }, (_, i) => String(i + 1).padStart(2, '0'))
  const DAYS   = Array.from({ length: 31 }, (_, i) => String(i + 1).padStart(2, '0'))

  async function handleSearch() {
    setSearched(true)
    try {
      const customerId = getCurrentDepositCustomerId()
      const [contracts, accounts, products] = await Promise.all([
        fetchDepositContracts(customerId),
        fetchDepositAccounts(customerId),
        fetchDepositProducts(),
      ])
      const accountByContractId = new Map(accounts.map(a => [a.contractId, a]))
      const productById = new Map(products.map(p => [p.productId, p]))
      setRows(contracts
        .filter(c => c.contractStatus === 'TERMINATED')
        .map(contract => {
          const account = accountByContractId.get(contract.contractId)
          const product = productById.get(contract.productId)
          return {
            accountNo: account?.accountNumber || contract.contractNumber,
            terminatedAt: contract.terminatedAt?.replace(/-/g, '.') || '-',
            type: product?.productName || '해지 상품',
            amount: Number(contract.joinAmount || 0).toLocaleString('ko-KR'),
          }
        }))
    } catch {
      setRows([])
    }
  }

  const selectCls = "border rounded-lg px-2 py-1.5 text-[12px] outline-none bg-white"
  const selectStyle = { borderColor: '#D1D5DB' }

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <DepositSidebar />

        <main className="flex-1 pl-8 pt-6 pb-12">
          <h1 className="text-[22px] font-bold text-kb-text mb-5">해지결과/내역 조회</h1>

          <div className="rounded-xl px-5 py-4 mb-5 text-[12px] space-y-1.5"
            style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <p className="flex gap-1.5 text-kb-text-muted">
              <span className="flex-shrink-0">·</span>
              <span>조회시작일은 당일 기준으로 5년 이내에서 선택 가능합니다. 단, 1회 조회 시 최대기간은 1년입니다.</span>
            </p>
            <p className="flex gap-1.5 text-kb-text-muted">
              <span className="flex-shrink-0">·</span>
              <span>예시) 포함일 : 2015.8.20 → 조회시작일 : 2010.8.20부터 선택 가능</span>
            </p>
          </div>

          {/* 필터 */}
          <div className="rounded-xl px-5 py-5 mb-5" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <div className="flex items-center gap-3 flex-wrap mb-4">
              <span className="text-[13px] font-semibold text-kb-text">조회기간</span>
              <div className="flex gap-1.5">
                {PERIOD_BTNS.map((b, i) => (
                  <button key={i}
                    onClick={() => setSearched(false)}
                    className="border rounded-lg px-3 py-1 text-[12px] font-medium transition-colors hover:bg-kb-primary-bg"
                    style={{ borderColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                    {b}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex items-center gap-2 flex-wrap">
              <div className="flex items-center gap-1 text-[13px]">
                <select value={startYear}  onChange={e => setStartYear(e.target.value)}  className={selectCls} style={selectStyle}>{YEARS.map(y  => <option key={y}>{y}</option>)}</select>
                <span className="text-kb-text-muted">년</span>
                <select value={startMonth} onChange={e => setStartMonth(e.target.value)} className={`${selectCls} w-14`} style={selectStyle}>{MONTHS.map(m => <option key={m}>{m}</option>)}</select>
                <span className="text-kb-text-muted">월</span>
                <select value={startDay}   onChange={e => setStartDay(e.target.value)}   className={`${selectCls} w-14`} style={selectStyle}>{DAYS.map(d   => <option key={d}>{d}</option>)}</select>
                <span className="text-kb-text-muted">일</span>
              </div>
              <span className="text-kb-text-muted mx-1">~</span>
              <div className="flex items-center gap-1 text-[13px]">
                <select value={endYear}  onChange={e => setEndYear(e.target.value)}  className={selectCls} style={selectStyle}>{YEARS.map(y  => <option key={y}>{y}</option>)}</select>
                <span className="text-kb-text-muted">년</span>
                <select value={endMonth} onChange={e => setEndMonth(e.target.value)} className={`${selectCls} w-14`} style={selectStyle}>{MONTHS.map(m => <option key={m}>{m}</option>)}</select>
                <span className="text-kb-text-muted">월</span>
                <select value={endDay}   onChange={e => setEndDay(e.target.value)}   className={`${selectCls} w-14`} style={selectStyle}>{DAYS.map(d   => <option key={d}>{d}</option>)}</select>
                <span className="text-kb-text-muted">일</span>
              </div>
            </div>

            <div className="flex justify-center mt-4">
              <button
                onClick={handleSearch}
                className="px-12 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity"
                style={{ backgroundColor: KB_PRIMARY }}>
                조회
              </button>
            </div>
          </div>

          {/* 결과 테이블 */}
          <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <table className="w-full border-collapse text-[13px]">
              <thead>
                <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                  {['해지계좌번호', '해지일자', '해지계좌종류', '해지금액', '바로가기'].map(h => (
                    <th key={h} className="px-4 py-3 text-center font-semibold text-[12px]"
                      style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.length > 0 ? rows.map((row, i) => (
                  <tr key={i} className="border-b hover:bg-kb-primary-surface transition-colors"
                    style={{ borderColor: KB_PRIMARY_BORDER }}>
                    <td className="px-4 py-3.5 text-center font-medium" style={{ color: KB_PRIMARY }}>{row.accountNo}</td>
                    <td className="px-4 py-3.5 text-center text-kb-text">{row.terminatedAt}</td>
                    <td className="px-4 py-3.5 text-center text-kb-text">{row.type}</td>
                    <td className="px-4 py-3.5 text-right font-semibold pr-5" style={{ color: KB_PRIMARY }}>{row.amount}원</td>
                    <td className="px-4 py-3.5 text-center">
                      <button className="px-4 py-1 text-[12px] font-medium rounded-lg border transition-colors hover:bg-kb-primary-bg"
                        style={{ borderColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>조회</button>
                    </td>
                  </tr>
                )) : (
                  <tr>
                    <td colSpan={5} className="px-4 py-10 text-center text-[13px] text-kb-text-muted">
                      {searched ? '조회하실 내역이 없습니다.' : '조회 버튼을 눌러 해지 내역을 확인하세요.'}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </main>
      </div>
    </div>
  )
}
