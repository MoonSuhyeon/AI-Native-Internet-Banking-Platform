'use client'

import { useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { adminLoanApi } from '@/lib/loan-api'

export default function AdminRatePolicyPage() {
  const [prodId, setProdId]   = useState('')
  const [policies, setPolicies] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy]       = useState(false)
  const [msg, setMsg]         = useState('')
  const [err, setErr]         = useState('')

  // new policy form
  const [policyName, setPolicyName]               = useState('')
  const [preferentialRateBps, setPreferentialRateBps] = useState('')
  const [conditionCd, setConditionCd]             = useState('')

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string)   { setErr(m); setTimeout(() => setErr(''), 4000) }

  async function search() {
    if (!prodId) return
    setLoading(true); setErr('')
    try {
      const { data: res } = await adminLoanApi.getPreferentialPolicies(parseInt(prodId))
      setPolicies(res.data ?? [])
    } catch { fail('우대금리 정책을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }

  async function add() {
    if (!prodId || !policyName || !preferentialRateBps || !conditionCd) return
    setBusy(true)
    try {
      await adminLoanApi.addPreferentialPolicy(parseInt(prodId), {
        policyName,
        preferentialRateBps: parseInt(preferentialRateBps),
        conditionCd,
      })
      notify('우대금리 정책이 등록되었습니다.')
      setPolicyName(''); setPreferentialRateBps(''); setConditionCd('')
      await search()
    } catch (e: any) { fail(e?.response?.data?.message ?? '등록 실패') }
    finally { setBusy(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">우대금리 정책</span>
        </div>
        <div className="px-6 py-5 max-w-3xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">우대금리 정책 관리</h1>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {/* 조회 */}
          <div className="bg-white border border-gray-200 rounded-lg p-5 mb-5">
            <h2 className="text-[13px] font-semibold text-gray-700 mb-3">정책 조회</h2>
            <div className="flex gap-3 items-end">
              <label className="text-[12px] text-gray-600">
                상품 ID
                <input type="number" value={prodId} onChange={e => setProdId(e.target.value)}
                  placeholder="prodId 입력"
                  className="ml-2 border border-gray-300 rounded px-3 py-1.5 text-[13px] w-36 focus:outline-none" />
              </label>
              <button onClick={search} disabled={loading || !prodId}
                className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                {loading ? '조회 중...' : '조회'}
              </button>
            </div>
          </div>

          {/* 목록 */}
          {policies.length > 0 && (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden mb-5">
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    {['policyId', '정책명', '우대폭', '적용조건'].map(h => (
                      <th key={h} className="px-4 py-3 text-left font-semibold text-gray-600">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {policies.map((p: any) => (
                    <tr key={p.policyId} className="hover:bg-gray-50">
                      <td className="px-4 py-3 text-gray-400 text-xs">{p.policyId}</td>
                      <td className="px-4 py-3 font-medium text-gray-800">{p.policyName}</td>
                      <td className="px-4 py-3 text-blue-700 font-bold">-{(p.preferentialRateBps / 100).toFixed(2)}%</td>
                      <td className="px-4 py-3 text-gray-500">{p.conditionCd ?? '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* 등록 폼 */}
          {prodId && (
            <div className="bg-white border border-gray-200 rounded-lg p-5">
              <h2 className="text-[13px] font-semibold text-gray-700 mb-4">새 정책 등록</h2>
              <div className="space-y-3">
                <div>
                  <label className="block text-[12px] text-gray-600 mb-1">정책명 *</label>
                  <input type="text" value={policyName} onChange={e => setPolicyName(e.target.value)}
                    placeholder="예: 급여이체 우대"
                    className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] focus:outline-none" />
                </div>
                <div>
                  <label className="block text-[12px] text-gray-600 mb-1">우대폭 (bps, 1% = 100) *</label>
                  <input type="number" value={preferentialRateBps} onChange={e => setPreferentialRateBps(e.target.value)}
                    placeholder="예: 30 → 0.30%p 인하"
                    className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] focus:outline-none" />
                </div>
                <div>
                  <label className="block text-[12px] text-gray-600 mb-1">적용 조건 코드 *</label>
                  <input type="text" value={conditionCd} onChange={e => setConditionCd(e.target.value)}
                    placeholder="예: SALARY_TRANSFER"
                    className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] focus:outline-none" />
                </div>
                <div className="flex justify-end">
                  <button onClick={add} disabled={busy || !policyName || !preferentialRateBps || !conditionCd}
                    className="px-6 py-2 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                    {busy ? '등록 중...' : '정책 등록'}
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
