'use client'
import { KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE } from '@/lib/theme'

import { useEffect, useState } from 'react'
import DepositSidebar from '@/components/products/DepositSidebar'
import {
  fetchDepositAccounts,
  fetchDepositContracts,
  fetchDepositProducts,
  getCurrentDepositCustomerId,
} from '@/lib/deposit-api'

const MOCK_ROWS = [
  { date: '2024.10.16', accountNo: '557315-2623671', type: 'AXful 정기예금', withdrawNo: '531089-04-274618', amount: '1,000,000' },
  { date: '2024.02.26', accountNo: '557315-2588965', type: 'AXful 정기예금', withdrawNo: '531089-04-274618', amount: '1,000,000' },
]

type NewHistoryRow = {
  date: string
  accountNo: string
  type: string
  withdrawNo: string
  amount: string
}

function dateText(value?: string) {
  return value ? value.replace(/-/g, '.') : '-'
}

export default function DepositNewHistoryPage() {
  const [rows, setRows] = useState<NewHistoryRow[]>(MOCK_ROWS)

  useEffect(() => {
    let cancelled = false
    async function loadRows() {
      try {
        const customerId = getCurrentDepositCustomerId()
        const [contracts, accounts, products] = await Promise.all([
          fetchDepositContracts(customerId),
          fetchDepositAccounts(customerId),
          fetchDepositProducts(),
        ])
        if (cancelled) return
        const accountByContractId = new Map(accounts.map(a => [a.contractId, a]))
        const productById = new Map(products.map(p => [p.productId, p]))
        const apiRows = contracts.map(contract => {
          const account = accountByContractId.get(contract.contractId)
          const product = productById.get(contract.productId)
          return {
            date: dateText(contract.startedAt),
            accountNo: account?.accountNumber || contract.contractNumber,
            type: product?.productName || '가입 상품',
            withdrawNo: contract.sourceAccountId ? String(contract.sourceAccountId) : '-',
            amount: Number(contract.joinAmount || 0).toLocaleString('ko-KR'),
          }
        })
        if (apiRows.length > 0) setRows(apiRows)
      } catch {
        setRows(MOCK_ROWS)
      }
    }
    loadRows()
    return () => { cancelled = true }
  }, [])

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <DepositSidebar />

        <main className="flex-1 pl-8 pt-6 pb-12">
          <h1 className="text-[22px] font-bold text-kb-text mb-5">신규결과/내역 조회</h1>

          <div className="rounded-xl px-5 py-4 mb-5 text-[12px] space-y-1.5"
            style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <p className="flex gap-1.5 text-kb-text-muted">
              <span className="flex-shrink-0">·</span>
              <span>예금 신규결과/내역입니다.</span>
            </p>
            <p className="flex gap-1.5 text-kb-text-muted">
              <span className="flex-shrink-0">·</span>
              <span>AXful Bank 계좌형 가입현황은 &apos;재청·저주신규조회&apos; 화면에서 확인 가능합니다.</span>
            </p>
          </div>

          <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <table className="w-full border-collapse text-[13px]">
              <thead>
                <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                  {['신규일자', '신규계좌번호', '신규계좌종류', '출금계좌번호', '신규금액'].map(h => (
                    <th key={h} className="px-4 py-3 text-center font-semibold text-[12px]"
                      style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, i) => (
                  <tr key={i} className="border-b hover:bg-kb-primary-surface transition-colors"
                    style={{ borderColor: KB_PRIMARY_BORDER }}>
                    <td className="px-4 py-3.5 text-center text-kb-text">{row.date}</td>
                    <td className="px-4 py-3.5 text-center font-medium" style={{ color: KB_PRIMARY }}>{row.accountNo}</td>
                    <td className="px-4 py-3.5 text-center text-kb-text">{row.type}</td>
                    <td className="px-4 py-3.5 text-center text-kb-text-muted">{row.withdrawNo}</td>
                    <td className="px-4 py-3.5 text-right font-semibold pr-5" style={{ color: KB_PRIMARY }}>{row.amount}원</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex justify-center mt-5">
            {/* 쪽이 하나뿐이라 표시로 둔다 */}
            <span
              className="w-8 h-8 text-[13px] font-bold text-white rounded-lg flex items-center justify-center"
              style={{ backgroundColor: KB_PRIMARY }}>
              1
            </span>
          </div>
        </main>
      </div>
    </div>
  )
}
