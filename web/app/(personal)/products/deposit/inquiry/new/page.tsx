'use client'
import { KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE } from '@/lib/theme'

import { useEffect, useState } from 'react'
import DepositSidebar from '@/components/products/DepositSidebar'
import { readAccessToken } from '@/lib/token'
import {
  fetchDepositAccounts,
  fetchDepositContracts,
  fetchDepositProducts,
  getCurrentDepositCustomerId,
} from '@/lib/deposit-api'

/**
 * 여기에는 예시 데이터를 두지 않는다.
 *
 * <p>예전에는 가입 기록 두 줄이 하드코딩돼 있었고, 그것이 초기값이자 조회 실패 시의
 * 폴백이었다. 그래서 <b>로그인하지 않은 사람에게도</b> 남의 예금 가입 기록처럼 보이는
 * 표가 떴다. 실제 고객 데이터는 아니었지만(게이트웨이가 미인증 호출을 막는다),
 * 은행 화면에서 그 표는 유출로 읽힌다.
 *
 * <p>조회가 안 되면 안 되는 대로 보여준다. 빈 화면이 가짜 기록보다 낫다.
 */
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

type LoadState = 'loading' | 'ready' | 'signed-out'

export default function DepositNewHistoryPage() {
  const [rows, setRows] = useState<NewHistoryRow[]>([])
  const [state, setState] = useState<LoadState>('loading')

  useEffect(() => {
    let cancelled = false
    async function loadRows() {
      // 토큰이 없으면 부르지 않는다. 불러 봐야 게이트웨이가 401 로 막고,
      // 그 실패를 "내역 없음" 으로 보여주면 로그인하면 보인다는 것을 알 수 없다.
      if (!readAccessToken()) {
        if (!cancelled) setState('signed-out')
        return
      }
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
        setRows(apiRows)
        setState('ready')
      } catch {
        // 조회 실패도 '내역 없음' 으로 보여준다. 지어낸 기록을 채우지 않는다.
        setRows([])
        setState('ready')
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
              <span>AXful Bank 계좌형 가입현황은 &apos;계좌조회&apos; 화면에서 확인할 수 있습니다.</span>
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
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={5} className="px-4 py-10 text-center text-[13px] text-kb-text-muted">
                      {state === 'loading' && '조회 중입니다.'}
                      {state === 'signed-out' && '로그인 후 조회할 수 있습니다.'}
                      {state === 'ready' && '조회된 신규 내역이 없습니다.'}
                    </td>
                  </tr>
                )}
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
