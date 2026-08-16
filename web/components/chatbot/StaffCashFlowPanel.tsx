'use client'

import { FormEvent, useState } from 'react'
import { BarChart3, Loader2, Search } from 'lucide-react'
import { ChatbotFeatureExecuteResponse, executeChatbotFeature } from '@/lib/consultation-api'

function formatValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'number') return value.toLocaleString('ko-KR')
  return String(value)
}

export default function StaffCashFlowPanel() {
  const [customerNo, setCustomerNo] = useState('CUST001')
  const [staffId, setStaffId] = useState('1')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<ChatbotFeatureExecuteResponse | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLoading(true)
    setError('')
    try {
      const data = await executeChatbotFeature('STAFF_CASH_FLOW', {
        customer_no: customerNo.trim(),
        staff_id: staffId.trim(),
      })
      setResult(data)
    } catch {
      setResult(null)
      setError('고객 현금 흐름 조회에 실패했습니다. 상담 서비스와 직원 권한을 확인해주세요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="mb-5 rounded-lg border border-kb-border bg-white p-5 shadow-sm">
      <div className="mb-4 flex items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded bg-kb-chat-bg text-kb-chat">
            <BarChart3 className="h-5 w-5" />
          </span>
          <div>
            <h2 className="text-base font-bold text-gray-800">고객 현금 흐름 조회</h2>
            <p className="text-xs text-gray-500">상담 중 고객의 최근 입출금 흐름을 확인합니다.</p>
          </div>
        </div>
      </div>

      <form onSubmit={submit} className="grid grid-cols-1 gap-3 md:grid-cols-[1fr_1fr_auto]">
        <label className="text-xs font-medium text-gray-500">
          고객번호
          <input
            value={customerNo}
            onChange={(event) => setCustomerNo(event.target.value)}
            className="mt-1 h-10 w-full rounded border border-gray-300 px-3 text-sm text-gray-800 outline-none focus:border-kb-chat"
          />
        </label>
        <label className="text-xs font-medium text-gray-500">
          직원 ID
          <input
            value={staffId}
            onChange={(event) => setStaffId(event.target.value)}
            className="mt-1 h-10 w-full rounded border border-gray-300 px-3 text-sm text-gray-800 outline-none focus:border-kb-chat"
          />
        </label>
        <button
          type="submit"
          disabled={loading || !customerNo.trim() || !staffId.trim()}
          className="mt-5 flex h-10 items-center justify-center gap-2 rounded bg-kb-chat px-4 text-sm font-bold text-white transition hover:bg-kb-chat-dark disabled:bg-gray-300 md:mt-[19px]"
        >
          {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
          조회
        </button>
      </form>

      {error && <p className="mt-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

      {result && (
        <div className="mt-4">
          <div className="mb-2 flex items-center justify-between">
            <p className="text-sm font-bold text-gray-800">{result.message || '조회 결과'}</p>
            <span className="text-xs text-gray-500">{result.data.length}건</span>
          </div>
          <div className="overflow-hidden rounded border border-kb-border">
            <table className="w-full text-sm">
              <thead className="bg-kb-beige-light text-xs text-kb-text-muted">
                <tr>
                  <th className="px-3 py-2 text-left font-medium">거래ID</th>
                  <th className="px-3 py-2 text-left font-medium">계좌번호</th>
                  <th className="px-3 py-2 text-left font-medium">유형</th>
                  <th className="px-3 py-2 text-right font-medium">금액</th>
                  <th className="px-3 py-2 text-left font-medium">상태</th>
                  <th className="px-3 py-2 text-left font-medium">일시</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {result.data.length === 0 && (
                  <tr>
                    <td colSpan={6} className="px-3 py-8 text-center text-sm text-gray-500">
                      조회된 거래 내역이 없습니다.
                    </td>
                  </tr>
                )}
                {result.data.map((row, index) => (
                  <tr key={`${row.transaction_id ?? index}`} className="hover:bg-kb-beige-light">
                    <td className="px-3 py-2.5 text-xs text-blue-600">{formatValue(row.transaction_id)}</td>
                    <td className="px-3 py-2.5 text-xs text-gray-600">{formatValue(row.account_number)}</td>
                    <td className="px-3 py-2.5 text-gray-700">{formatValue(row.transaction_type)}</td>
                    <td className="px-3 py-2.5 text-right font-medium">{formatValue(row.amount)}</td>
                    <td className="px-3 py-2.5 text-gray-600">{formatValue(row.transaction_status)}</td>
                    <td className="px-3 py-2.5 text-xs text-gray-500">{formatValue(row.created_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  )
}
