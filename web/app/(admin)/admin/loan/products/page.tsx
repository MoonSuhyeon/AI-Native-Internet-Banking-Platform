'use client'

import { useState, useEffect, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { loanProductApi } from '@/lib/loan-api'

const LOAN_TYPE_LABEL: Record<string, string> = {
  CREDIT: '신용대출',
  MORTGAGE: '담보대출',
  CHARTER: '전월세',
  AUTO: '자동차',
  GROUP: '집단중도금',
  KHFC: '주택도시기금',
}

const STATUS_CLS: Record<string, string> = {
  ACTIVE: 'bg-green-100 text-green-700 border-green-300',
  DRAFT: 'bg-yellow-100 text-yellow-700 border-yellow-300',
  DISCONTINUED: 'bg-gray-100 text-gray-500 border-gray-300',
}

function statusBadge(status: string) {
  const cls = STATUS_CLS[status] ?? 'bg-yellow-100 text-yellow-700 border-yellow-300'
  const label = status === 'ACTIVE' ? '판매중' : status === 'DRAFT' ? '준비중' : status === 'DISCONTINUED' ? '종료' : status
  return (
    <span className={`text-[11px] px-2 py-0.5 rounded border ${cls}`}>{label}</span>
  )
}

const EMPTY_FORM = {
  prodCd: '',
  prodName: '',
  loanTypeCd: 'CREDIT',
  rateTypeCd: 'FIXED',
  baseRateBps: '',
  minRateBps: '',
  maxRateBps: '',
  minAmount: '',
  maxAmount: '',
  minPeriodMo: '',
  maxPeriodMo: '',
  repaymentMethodCd: 'LEVEL',
  targetCustomerCd: 'INDIVIDUAL',
}

export default function AdminLoanProductsPage() {
  const [products, setProducts] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('')

  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ ...EMPTY_FORM })

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string) { setErr(m); setTimeout(() => setErr(''), 3000) }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const { data: res } = await loanProductApi.list({ size: 50 })
      setProducts(res.data?.items ?? [])
    } catch { fail('상품 목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const filtered = products.filter(p => {
    const nameMatch = p.prodName?.toLowerCase().includes(search.toLowerCase())
    const typeMatch = !typeFilter || p.loanTypeCd === typeFilter
    return nameMatch && typeMatch
  })

  async function handleDiscontinue(prodId: number) {
    const reasonCd = prompt('단종 사유 코드를 입력하세요. (예: SALES_END)', 'SALES_END')
    if (!reasonCd) return
    const today = new Date()
    const saleEndDate =
      `${today.getFullYear()}${String(today.getMonth() + 1).padStart(2, '0')}${String(today.getDate()).padStart(2, '0')}`
    setBusy(true)
    try {
      await loanProductApi.discontinue(prodId, { saleEndDate, reasonCd })
      notify('상품이 중단되었습니다.')
      await load()
    } catch (e: any) { fail(e?.response?.data?.message ?? '중단 실패') }
    finally { setBusy(false) }
  }

  async function handleCreate() {
    setBusy(true)
    try {
      await loanProductApi.create({
        prodCd: form.prodCd,
        prodName: form.prodName,
        loanTypeCd: form.loanTypeCd,
        rateTypeCd: form.rateTypeCd,
        baseRateBps: Number(form.baseRateBps),
        minRateBps: Number(form.minRateBps),
        maxRateBps: Number(form.maxRateBps),
        minAmount: Number(form.minAmount),
        maxAmount: Number(form.maxAmount),
        minPeriodMo: Number(form.minPeriodMo),
        maxPeriodMo: Number(form.maxPeriodMo),
        repaymentMethodCd: form.repaymentMethodCd,
        targetCustomerCd: form.targetCustomerCd,
      })
      notify('상품이 등록되었습니다.')
      setForm({ ...EMPTY_FORM })
      setShowForm(false)
      await load()
    } catch (e: any) { fail(e?.response?.data?.message ?? '등록 실패') }
    finally { setBusy(false) }
  }

  function setF(key: string, val: string) {
    setForm(prev => ({ ...prev, [key]: val }))
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">대출상품 관리</span>
        </div>
        <div className="px-6 py-5 max-w-5xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">대출상품 관리</h1>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {/* 검색 */}
          <div className="bg-white border border-gray-200 rounded-lg p-4 mb-5 flex gap-3 items-center">
            <input
              type="text"
              placeholder="상품명 검색"
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="border border-gray-300 rounded px-3 py-1.5 text-[13px] w-56 focus:outline-none"
            />
            <select
              value={typeFilter}
              onChange={e => setTypeFilter(e.target.value)}
              className="border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none"
            >
              <option value="">전체</option>
              {Object.entries(LOAN_TYPE_LABEL).map(([k, v]) => (
                <option key={k} value={k}>{v}</option>
              ))}
            </select>
          </div>

          {/* 테이블 */}
          <div className="bg-white border border-gray-200 rounded-lg overflow-hidden mb-5">
            {loading ? (
              <p className="py-10 text-center text-sm text-gray-400">불러오는 중...</p>
            ) : filtered.length === 0 ? (
              <p className="py-10 text-center text-sm text-gray-400">상품이 없습니다.</p>
            ) : (
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    {['prodId', '상품명', '유형', '기준금리', '한도(max)', '상태', '처리'].map(h => (
                      <th key={h} className="px-4 py-3 text-left text-xs text-gray-600 font-semibold">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {filtered.map((p: any) => (
                    <tr key={p.prodId} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 text-gray-400 text-xs">{p.prodId}</td>
                      <td className="px-4 py-3 font-medium text-gray-800">{p.prodName}</td>
                      <td className="px-4 py-3 text-gray-600">{LOAN_TYPE_LABEL[p.loanTypeCd] ?? p.loanTypeCd}</td>
                      <td className="px-4 py-3 text-gray-600">
                        {Number.isFinite(p.baseRateBps) ? `${(p.baseRateBps / 100).toFixed(2)}%` : '-'}
                      </td>
                      <td className="px-4 py-3 text-gray-600">
                        {p.maxAmount >= 100_000_000
                          ? `${(p.maxAmount / 100_000_000).toLocaleString('ko-KR')}억원`
                          : `${(p.maxAmount / 10_000).toLocaleString('ko-KR')}만원`}
                      </td>
                      <td className="px-4 py-3">{statusBadge(p.prodStatusCd)}</td>
                      <td className="px-4 py-3">
                        {p.prodStatusCd === 'ACTIVE' && (
                          <button
                            onClick={() => handleDiscontinue(p.prodId)}
                            disabled={busy}
                            className="px-3 py-1 text-[12px] border border-red-300 text-red-600 rounded hover:bg-red-50 disabled:opacity-50"
                          >
                            중단
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          {/* 상품 등록 */}
          <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
            <button
              onClick={() => setShowForm(v => !v)}
              className="w-full flex items-center justify-between px-5 py-3 text-[13px] font-semibold text-gray-700 hover:bg-gray-50"
            >
              <span>+ 상품 등록</span>
              <span className="text-gray-400">{showForm ? '▲' : '▼'}</span>
            </button>
            {showForm && (
              <div className="px-5 py-4 border-t border-gray-200">
                <div className="grid grid-cols-2 gap-4">
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">상품코드</span>
                    <input type="text" value={form.prodCd} onChange={e => setF('prodCd', e.target.value)}
                      placeholder="예: CREDIT_2026_01"
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">상품명</span>
                    <input type="text" value={form.prodName} onChange={e => setF('prodName', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">대출 유형</span>
                    <select value={form.loanTypeCd} onChange={e => setF('loanTypeCd', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none">
                      {Object.entries(LOAN_TYPE_LABEL).map(([k, v]) => (
                        <option key={k} value={k}>{v}</option>
                      ))}
                    </select>
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">금리유형</span>
                    <select value={form.rateTypeCd} onChange={e => setF('rateTypeCd', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none">
                      <option value="FIXED">고정금리(FIXED)</option>
                      <option value="VARIABLE">변동금리(VARIABLE)</option>
                      <option value="MIXED">혼합금리(MIXED)</option>
                    </select>
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">기준금리(bps)</span>
                    <input type="number" value={form.baseRateBps} onChange={e => setF('baseRateBps', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">최소금리(bps)</span>
                    <input type="number" value={form.minRateBps} onChange={e => setF('minRateBps', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">최대금리(bps)</span>
                    <input type="number" value={form.maxRateBps} onChange={e => setF('maxRateBps', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">최소한도(원)</span>
                    <input type="number" value={form.minAmount} onChange={e => setF('minAmount', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">최대한도(원)</span>
                    <input type="number" value={form.maxAmount} onChange={e => setF('maxAmount', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">최소기간(개월)</span>
                    <input type="number" value={form.minPeriodMo} onChange={e => setF('minPeriodMo', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">최대기간(개월)</span>
                    <input type="number" value={form.maxPeriodMo} onChange={e => setF('maxPeriodMo', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none" />
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">상환방식</span>
                    <select value={form.repaymentMethodCd} onChange={e => setF('repaymentMethodCd', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none">
                      <option value="LEVEL">원리금균등(LEVEL)</option>
                      <option value="BULLET">만기일시(BULLET)</option>
                      <option value="INTEREST_ONLY">이자만(INTEREST_ONLY)</option>
                    </select>
                  </label>
                  <label className="block">
                    <span className="text-[12px] text-gray-600 mb-1 block">고객 유형</span>
                    <select value={form.targetCustomerCd} onChange={e => setF('targetCustomerCd', e.target.value)}
                      className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none">
                      <option value="INDIVIDUAL">개인(INDIVIDUAL)</option>
                      <option value="CORPORATE">법인(CORPORATE)</option>
                    </select>
                  </label>
                </div>
                <div className="flex justify-end mt-4 gap-2">
                  <button onClick={() => { setShowForm(false); setForm({ ...EMPTY_FORM }) }}
                    className="px-4 py-2 text-[13px] border border-gray-300 rounded hover:bg-gray-50">
                    취소
                  </button>
                  <button onClick={handleCreate} disabled={busy || !form.prodCd || !form.prodName}
                    className="px-5 py-2 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                    {busy ? '등록 중...' : '등록'}
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
