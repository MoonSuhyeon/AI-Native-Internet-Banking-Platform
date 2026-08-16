'use client'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import LoanSidebar from '@/components/inquiry/LoanSidebar'
import CartModal from '@/components/products/CartModal'
import { loanProductApi, type PreferentialRatePolicy } from '@/lib/loan-api'

const CALC_TABS = ['원리금균등상환', '원금균등상환', '원금만기일시상환']
const DETAIL_TABS = ['상품안내', '금리 및 이율', '이용안내', '유의사항 및 기타', '다운로드']
const AMT_BTNS  = ['+100만', '+500만', '+1000만', '+5000만', '초기화']
const YEAR_BTNS = ['+1년', '+2년', '+5년', '+10년', '초기화']
const RATE_BTNS = ['+0.1%', '+1%', '+5%', '초기화']

function bpsToRate(bps: number) { return (bps / 100).toFixed(2) }
function formatAmount(amt: number) {
  if (amt >= 100_000_000) return `${amt / 100_000_000}억원`
  if (amt >= 10_000) return `${(amt / 10_000).toLocaleString('ko-KR')}만원`
  return `${amt.toLocaleString('ko-KR')}원`
}
function repayLabel(code: string) {
  const map: Record<string, string> = {
    INSTALLMENT: '원리금균등상환', PRINCIPAL_INSTALLMENT: '원금균등상환',
    BULLET: '만기일시상환', REVOLVING: '한도대출',
  }
  return map[code] ?? code
}

interface Product {
  prodId: number; prodName: string; loanTypeCd: string
  baseRateBps: number; minRateBps: number; maxRateBps: number
  minAmount: number; maxAmount: number
  minPeriodMo: number; maxPeriodMo: number
  repaymentMethodCd: string; targetCustomerCd: string
}

type CalcResult = { principal: number; interest: number; total: number }

interface Props {
  listHref: string
  listLabel: string
}

export default function LoanProductDetail({ listHref, listLabel }: Props) {
  const params = useParams()
  const prodId = params.id as string

  const [product, setProduct] = useState<Product | null>(null)
  const [loading, setLoading] = useState(true)
  const [error,   setError]   = useState('')
  const [prefRates, setPrefRates] = useState<PreferentialRatePolicy[]>([])

  const [calcTab, setCalcTab] = useState(0)
  const [principal, setPrincipal] = useState('')
  const [years, setYears] = useState('')
  const [rate, setRate] = useState('')
  const [calcResult, setCalcResult] = useState<CalcResult | null>(null)
  const [focusedInput, setFocusedInput] = useState<'principal' | 'years' | 'rate' | null>(null)
  const [detailTab, setDetailTab] = useState('상품안내')
  const [cartOpen, setCartOpen] = useState(false)
  const [consultOpen, setConsultOpen] = useState(false)

  useEffect(() => {
    const id = parseInt(prodId, 10)
    Promise.all([
      loanProductApi.get(id),
      loanProductApi.preferentialRates(id).catch(() => ({ data: { data: [] } })),
    ]).then(([prodRes, prefRes]) => {
      setProduct(prodRes.data.data)
      setPrefRates(prefRes.data?.data ?? [])
    }).catch(() => setError('상품 정보를 불러오지 못했습니다.'))
      .finally(() => setLoading(false))
  }, [prodId])

  function handleAmtBtn(b: string) {
    const map: Record<string, number> = { '+100만': 100, '+500만': 500, '+1000만': 1000, '+5000만': 5000 }
    if (b === '초기화') { setPrincipal(''); return }
    const cur = parseFloat(principal.replace(/,/g, '')) || 0
    setPrincipal((cur + (map[b] ?? 0)).toLocaleString('ko-KR'))
  }
  function handleYearBtn(b: string) {
    const map: Record<string, number> = { '+1년': 1, '+2년': 2, '+5년': 5, '+10년': 10 }
    if (b === '초기화') { setYears(''); return }
    setYears(y => String((parseInt(y) || 0) + (map[b] ?? 0)))
  }
  function handleRateBtn(b: string) {
    const map: Record<string, number> = { '+0.1%': 0.1, '+1%': 1, '+5%': 5 }
    if (b === '초기화') { setRate(''); return }
    setRate(r => String(Math.round(((parseFloat(r) || 0) + (map[b] ?? 0)) * 10) / 10))
  }
  function handleCalc() {
    const p = (parseFloat(principal.replace(/,/g, '')) || 0) * 10000
    const m = (parseInt(years) || 0) * 12
    const r = (parseFloat(rate) || 0) / 100 / 12
    if (!p || !m || !r) return
    if (calcTab === 0) {
      const total = Math.round(p * r / (1 - Math.pow(1 + r, -m)))
      const interest = Math.round(p * r)
      setCalcResult({ principal: total - interest, interest, total })
    } else if (calcTab === 1) {
      const mp = Math.round(p / m)
      const interest = Math.round(p * r)
      setCalcResult({ principal: mp, interest, total: mp + interest })
    } else {
      const interest = Math.round(p * r)
      setCalcResult({ principal: 0, interest, total: interest })
    }
  }
  function fmtN(n: number) { return n.toLocaleString('ko-KR') }

  if (loading) return <div className="py-20 text-center text-kb-text-muted text-[14px]">불러오는 중...</div>
  if (error || !product) return <div className="py-20 text-center text-kb-red text-[14px]">{error || '상품을 찾을 수 없습니다.'}</div>

  return (
    <>
    <main className="pb-16">
      <div className="max-w-kb-container mx-auto px-6 pt-6">
        <nav className="text-[12px] text-kb-text-muted mb-4 flex items-center gap-1">
          <Link href="/" className="hover:underline">개인뱅킹</Link><span>›</span>
          <Link href="/products/loan" className="hover:underline">대출</Link><span>›</span>
          <Link href={listHref} className="hover:underline">{listLabel}</Link><span>›</span>
          <span className="text-kb-text font-medium">{product.prodName}</span>
        </nav>

        <div className="flex gap-8">
          <LoanSidebar />
          <div className="flex-1 min-w-0">
            {/* 상품 헤더 */}
            <div className="border border-kb-primary-border p-6">
              <span className="inline-block text-[11px] font-bold px-2 py-0.5 text-white mb-3 bg-[#1A56DB]">
                인터넷뱅킹
              </span>
              <h1 className="text-[24px] font-bold text-kb-text mb-6">{product.prodName}</h1>
              <div className="flex gap-10 mb-6">
                {[
                  { label: '기간', value: `${product.minPeriodMo}~${product.maxPeriodMo}개월`, color: '#4A90D9' },
                  { label: '상환방법', value: repayLabel(product.repaymentMethodCd), color: '#4A90D9' },
                  { label: '최고한도', value: formatAmount(product.maxAmount), color: '#E05C5C' },
                ].map(item => (
                  <div key={item.label} className="flex items-center gap-2">
                    <div className="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0"
                      style={{ backgroundColor: item.color }}>
                      <span className="text-white text-[11px] font-bold">{item.label[0]}</span>
                    </div>
                    <div>
                      <p className="text-[11px] text-kb-text-muted">{item.label}</p>
                      <p className="text-[15px] font-bold text-kb-text">{item.value}</p>
                    </div>
                  </div>
                ))}
              </div>
              <div className="flex items-center gap-2 mb-3">
                <Link href={`/loans/apply?prodId=${product.prodId}`}
                  className="px-5 py-2.5 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 transition-colors whitespace-nowrap">
                  대출 신청하기
                </Link>
                <button onClick={() => setCartOpen(true)} className="px-4 py-2.5 text-[13px] border border-kb-primary-border text-kb-text-body hover:bg-kb-primary-bg transition-colors">장바구니</button>
                <button onClick={() => setConsultOpen(true)} className="px-4 py-2.5 text-[13px] border border-kb-primary-border text-kb-text-body hover:bg-kb-primary-bg transition-colors">상담신청</button>
              </div>
              <p className="text-[12px] text-kb-text-muted">※ 자세한 내용은 아래 상품안내를 참조하시기 바랍니다.</p>
            </div>

            {/* 대출 계산기 */}
            <div className="border border-t-0 border-kb-primary-border p-5 mb-3">
              <p className="text-[13px] font-bold text-kb-text mb-3">대출 계산기</p>
              <div className="flex border-b border-kb-primary-border mb-4">
                {CALC_TABS.map((tab, i) => (
                  <button key={tab} onClick={() => { setCalcTab(i); setCalcResult(null) }}
                    className={`px-5 py-2 text-[13px] border-b-2 -mb-px transition-colors ${
                      calcTab === i ? 'border-kb-primary text-kb-primary font-bold' : 'border-transparent text-kb-text-muted hover:text-kb-text'}`}>
                    {tab}
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-[13px] text-kb-text-muted font-bold">대출금액</span>
                <input type="text" value={principal} onChange={e => setPrincipal(e.target.value)} placeholder="금액"
                  onFocus={() => setFocusedInput('principal')} onBlur={() => setFocusedInput(null)}
                  className={`border px-3 py-1.5 w-24 outline-none text-right text-[13px] transition-colors ${focusedInput === 'principal' ? 'border-[#C09B3A] bg-[#C09B3A]/10' : 'border-kb-primary-border'}`} />
                <span className="text-[13px] text-kb-text">만원을</span>
                <span className="text-[13px] text-kb-text-muted font-bold ml-2">기간</span>
                <input type="text" value={years} onChange={e => setYears(e.target.value)} placeholder="기간"
                  onFocus={() => setFocusedInput('years')} onBlur={() => setFocusedInput(null)}
                  className={`border px-3 py-1.5 w-16 outline-none text-right text-[13px] transition-colors ${focusedInput === 'years' ? 'border-[#C09B3A] bg-[#C09B3A]/10' : 'border-kb-primary-border'}`} />
                <span className="text-[13px] text-kb-text">년 동안</span>
                <span className="text-[13px] text-kb-text-muted font-bold ml-2">이자</span>
                <input type="text" value={rate} onChange={e => setRate(e.target.value)} placeholder="금리"
                  onFocus={() => setFocusedInput('rate')} onBlur={() => setFocusedInput(null)}
                  className={`border px-3 py-1.5 w-16 outline-none text-right text-[13px] transition-colors ${focusedInput === 'rate' ? 'border-[#C09B3A] bg-[#C09B3A]/10' : 'border-kb-primary-border'}`} />
                <span className="text-[13px] text-kb-text">%로</span>
                <button onClick={handleCalc}
                  className="ml-auto text-white text-[12px] font-bold px-5 py-1.5" style={{ backgroundColor: '#5A504A' }}>
                  결과보기
                </button>
              </div>
              <div className="mt-2 min-h-[26px]" onMouseDown={e => e.preventDefault()}>
                {focusedInput === 'principal' && (
                  <div className="flex gap-1 flex-wrap">
                    {AMT_BTNS.map(b => <button key={b} onClick={() => handleAmtBtn(b)}
                      className="px-2.5 py-1 text-[11px] border border-kb-primary-border text-kb-text-body hover:bg-kb-primary-bg">{b}</button>)}
                  </div>
                )}
                {focusedInput === 'years' && (
                  <div className="flex gap-1 flex-wrap">
                    {YEAR_BTNS.map(b => <button key={b} onClick={() => handleYearBtn(b)}
                      className="px-2.5 py-1 text-[11px] border border-kb-primary-border text-kb-text-body hover:bg-kb-primary-bg">{b}</button>)}
                  </div>
                )}
                {focusedInput === 'rate' && (
                  <div className="flex gap-1 flex-wrap">
                    {RATE_BTNS.map(b => <button key={b} onClick={() => handleRateBtn(b)}
                      className="px-2.5 py-1 text-[11px] border border-kb-primary-border text-kb-text-body hover:bg-kb-primary-bg">{b}</button>)}
                  </div>
                )}
              </div>
              {calcResult && (
                <div className="mt-4 border border-kb-primary-border">
                  <table className="w-full text-[13px]">
                    <thead>
                      <tr className="bg-[#F5F0E8]">
                        {calcTab === 2
                          ? <><th className="py-2 px-4 text-left font-bold text-kb-text">월 이자</th><th className="py-2 px-4 text-left font-bold text-kb-text">만기 상환원금</th></>
                          : <><th className="py-2 px-4 text-left font-bold text-kb-text">월 상환원금</th><th className="py-2 px-4 text-left font-bold text-kb-text">월 이자</th><th className="py-2 px-4 text-left font-bold text-kb-text">월 납부금액</th></>}
                      </tr>
                    </thead>
                    <tbody>
                      <tr>
                        {calcTab === 2
                          ? <><td className="py-3 px-4 text-kb-text">{fmtN(calcResult.total)}원</td><td className="py-3 px-4 font-bold text-[#C05050]">{fmtN((parseFloat(principal.replace(/,/g,''))||0)*10000)}원</td></>
                          : <><td className="py-3 px-4 text-kb-text">{fmtN(calcResult.principal)}원</td><td className="py-3 px-4 text-kb-text">{fmtN(calcResult.interest)}원</td><td className="py-3 px-4 font-bold text-[#C05050]">{fmtN(calcResult.total)}원</td></>}
                      </tr>
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {/* 상세 탭 */}
            <div className="border border-kb-primary-border">
              <div className="flex border-b border-kb-primary-border">
                {DETAIL_TABS.map(tab => (
                  <button key={tab} onClick={() => setDetailTab(tab)}
                    className={`flex-1 py-3 text-[13px] transition-colors ${
                      detailTab === tab ? 'bg-kb-primary font-bold text-kb-text' : 'text-kb-text-muted hover:text-kb-text hover:bg-kb-primary-bg'}`}>
                    {tab}
                  </button>
                ))}
              </div>
              <div className="p-6">
                {detailTab === '상품안내' && (
                  <table className="w-full text-[13px]">
                    <tbody className="divide-y divide-kb-border">
                      <tr>
                        <td className="py-4 pr-6 font-bold text-kb-text w-40 align-top">대출금액</td>
                        <td className="py-4 text-kb-text-body">{formatAmount(product.minAmount)} ~ {formatAmount(product.maxAmount)}</td>
                      </tr>
                      <tr>
                        <td className="py-4 pr-6 font-bold text-kb-text align-top">대출기간</td>
                        <td className="py-4 text-kb-text-body">{product.minPeriodMo}개월 ~ {product.maxPeriodMo}개월</td>
                      </tr>
                      <tr>
                        <td className="py-4 pr-6 font-bold text-kb-text align-top">상환방법</td>
                        <td className="py-4 text-kb-text-body">{repayLabel(product.repaymentMethodCd)}</td>
                      </tr>
                    </tbody>
                  </table>
                )}
                {detailTab === '금리 및 이율' && (
                  <table className="w-full text-[13px]">
                    <tbody className="divide-y divide-kb-border">
                      <tr>
                        <td className="py-4 pr-6 font-bold text-kb-text w-40 align-top">기준금리</td>
                        <td className="py-4 text-kb-text-body">연 {bpsToRate(product.baseRateBps)}%</td>
                      </tr>
                      <tr>
                        <td className="py-4 pr-6 font-bold text-kb-text align-top">적용금리 범위</td>
                        <td className="py-4 text-kb-text-body">연 {bpsToRate(product.minRateBps)}% ~ {bpsToRate(product.maxRateBps)}%</td>
                      </tr>
                      {prefRates.length > 0 && (
                        <tr>
                          <td className="py-4 pr-6 font-bold text-kb-text align-top">우대금리</td>
                          <td className="py-4">
                            <ul className="space-y-2">
                              {prefRates.map(p => (
                                <li key={p.policyId} className="flex items-start gap-2 text-kb-text-body">
                                  <span className="inline-block mt-0.5 w-2 h-2 rounded-full bg-kb-primary flex-shrink-0" />
                                  <span>
                                    <span className="font-medium text-kb-text">{p.policyName}</span>
                                    <span className="text-[#1A56DB] font-bold ml-2">-{bpsToRate(p.preferentialRateBps)}%</span>
                                    {p.conditionCd && <span className="text-kb-text-muted ml-2">({p.conditionCd})</span>}
                                  </span>
                                </li>
                              ))}
                            </ul>
                          </td>
                        </tr>
                      )}
                      <tr>
                        <td className="py-4 pr-6 font-bold text-kb-text align-top">중도상환수수료</td>
                        <td className="py-4 text-kb-text-body">중도상환금액 × 수수료율(0.8%) × 잔존일수 ÷ 대출기간</td>
                      </tr>
                    </tbody>
                  </table>
                )}
                {(detailTab === '이용안내' || detailTab === '유의사항 및 기타') && (
                  <p className="text-[13px] text-kb-text-muted py-4">영업점 또는 고객센터(1588-9999)로 문의하시기 바랍니다.</p>
                )}
                {detailTab === '다운로드' && (
                  <p className="text-[13px] text-kb-text-muted py-4">다운로드 자료가 없습니다.</p>
                )}
              </div>
            </div>

            <div className="flex justify-center gap-3 mt-6">
              <Link href={listHref}
                className="px-10 py-2.5 border border-kb-primary-border text-[14px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">
                목록
              </Link>
            </div>
          </div>
        </div>
      </div>
    </main>

    {consultOpen && (
      <div className="fixed inset-0 z-50 flex items-center justify-center">
        <div className="absolute inset-0 bg-black/50" onClick={() => setConsultOpen(false)} />
        <div className="relative bg-white w-[400px] shadow-lg p-6">
          <p className="text-[15px] font-bold text-kb-text mb-4">상담신청</p>
          <p className="text-[14px] text-kb-text-body mb-2">☎ 전화상담: <span className="font-bold text-[#1A56DB]">1588-9999</span></p>
          <p className="text-[13px] text-kb-text-muted">09:00~16:00 (은행휴무일 제외)</p>
          <div className="flex justify-end mt-4">
            <button onClick={() => setConsultOpen(false)} className="text-[12px] text-kb-text-muted hover:text-kb-text">닫기 ×</button>
          </div>
        </div>
      </div>
    )}
    {cartOpen && <CartModal productName={product.prodName} onClose={() => setCartOpen(false)} />}
    </>
  )
}
