'use client'
import { KB_GOLD, KB_MINT } from '@/lib/theme'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import { fetchDepositProducts, DepositProduct } from '@/lib/deposit-api'
import { DEPLOYED } from '@/lib/deployed-services'

type Slide = {
  badge: string; category: string; sub: string; title: string
  field1Label: string; field1: string; field2Label: string; field2: string
  rate: string; rateNote: string; href: string
}

function productToSlide(p: DepositProduct, category: string, badge: string, tab: string): Slide {
  const rate = p.bestRate != null
    ? `최고 연 ${p.bestRate}%`
    : p.baseInterestRate != null
      ? `기본 연 ${p.baseInterestRate}%`
      : '-'
  const period = p.minPeriodMonth != null
    ? (p.maxPeriodMonth && p.maxPeriodMonth !== p.minPeriodMonth
        ? `${p.minPeriodMonth}~${p.maxPeriodMonth}개월`
        : `${p.minPeriodMonth}개월`)
    : '제한없음'
  const minAmt = p.minJoinAmount != null
    ? `${Number(p.minJoinAmount).toLocaleString('ko-KR')}원 이상`
    : '제한없음'
  return {
    badge, category,
    sub: p.description || badge + ' 상품',
    title: p.productName,
    field1Label: '기간', field1: period,
    field2Label: '금액', field2: minAmt,
    rate, rateNote: new Date().toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' }).replace(/\. /g, '.').replace(/\.$/, '') + ' 기준, 세금공제전',
    href: `/products/deposit/list?tab=${tab}`,
  }
}

const FALLBACK_SLIDES: Slide[] = [
  { badge: '예금', category: '예금', sub: 'Digital AXful의 대표 정기예금', title: 'AXful 정기예금', field1Label: '기간', field1: '1~36개월', field2Label: '금액', field2: '제한없음', rate: '연 2.4%', rateNote: '세금공제전', href: '/products/deposit/list?tab=예금' },
  { badge: '적금', category: '적금', sub: '누구나 쉽게 우대받는 DIY', title: 'AXful 내맘대로적금', field1Label: '기간', field1: '6~36개월', field2Label: '금액', field2: '1만원 이상', rate: '연 2.95%', rateNote: '세금공제전, 우대금리포함', href: '/products/deposit/list?tab=자유적금' },
  { badge: '입출금자유', category: '입출금자유', sub: '입금과 출금을 내 마음대로', title: 'AXful 입출금자유 통장', field1Label: '기간', field1: '제한없음', field2Label: '금액', field2: '제한없음', rate: '연 2%', rateNote: '세금공제전', href: '/products/deposit/list?tab=입출금자유' },
  { badge: '주택청약', category: '주택청약', sub: '내 집 마련의 꿈을 위한', title: 'AXful 주택청약종합저축', field1Label: '기간', field1: '24개월 기준', field2Label: '금액', field2: '제한없음', rate: '연 3.1%', rateNote: '세금공제전', href: '/products/deposit/list?tab=주택청약' },
]

const DEPOSIT_CATEGORIES = [
  { no: '01', desc: '열심히 모은 종자돈을 더 크게',  label: '예금 상품',    href: '/products/deposit/list?tab=예금',      key: '예금' },
  { no: '02', desc: '당신의 노력과 꿈을 모아모아',   label: '적금 상품',    href: '/products/deposit/list?tab=자유적금',   key: '적금' },
  { no: '03', desc: '입금과 출금을 내 마음대로',     label: '입출금자유 상품', href: '/products/deposit/list?tab=입출금자유', key: '입출금자유' },
  { no: '04', desc: '내 집 마련의 꿈을 위한',       label: '주택청약 상품', href: '/products/deposit/list?tab=주택청약',  key: '주택청약' },
]

const LOAN_SLIDES = [
  {
    badge: '신용', category: '신용',
    sub: '근로소득이 발생되는 국민 누구나',
    title: 'AXful Smart신용대출',
    field1Label: '기간', field1: '최대 10년',
    field2Label: '한도', field2: '3.5억원 이내',
    rate: '연 4.5% ~ 7.8%', rateNote: '2026.05.25 기준, 우대금리포함',
    href: '/products/loan/credit',
  },
  {
    badge: '담보', category: '담보',
    sub: '내 집을 담보로 유리한 조건의 대출',
    title: 'AXful 아파트담보대출',
    field1Label: '기간', field1: '최대 30년',
    field2Label: '한도', field2: '10억원 이내',
    rate: '연 3.2% ~ 5.1%', rateNote: '2026.05.25 기준, 우대금리포함',
    href: '/products/loan/mortgage',
  },
  {
    badge: '전월세', category: '전월세',
    sub: '안전한 전세 생활을 위한 자금 지원',
    title: 'AXful 전세자금대출',
    field1Label: '기간', field1: '최대 2년',
    field2Label: '한도', field2: '2억원 이내',
    rate: '연 2.8% ~ 4.2%', rateNote: '2026.05.25 기준, 우대금리포함',
    href: '/products/loan/jeonse',
  },
  {
    badge: '기타', category: '기타',
    sub: '내 차 마련을 위한 합리적 금리',
    title: 'AXful 자동차대출',
    field1Label: '기간', field1: '최대 7년',
    field2Label: '한도', field2: '5천만원 이내',
    rate: '연 5.1% ~ 8.3%', rateNote: '2026.05.25 기준, 우대금리포함',
    href: '/products/loan/auto',
  },
  {
    badge: '기타', category: '기타',
    sub: '주거 안정을 위한 정책금융 대출',
    title: 'AXful 주택도시기금대출',
    field1Label: '기간', field1: '최대 30년',
    field2Label: '한도', field2: '한도 내',
    rate: '연 1.5% ~ 3.3%', rateNote: '2026.05.25 기준, 정책금리',
    href: '/products/loan/khfc',
  },
]

const LOAN_CATEGORIES = [
  { no: '01', desc: '빠르고 간편하게 바로 지금',    label: '신용대출',    href: '/products/loan/credit',   key: '신용' },
  { no: '02', desc: '부동산을 활용한 합리적 금리',   label: '담보대출',    href: '/products/loan/mortgage', key: '담보' },
  { no: '03', desc: '내 집 마련의 첫걸음',          label: '전월세 대출', href: '/products/loan/jeonse',   key: '전월세' },
  // '기타 대출' 이 /products/loan/other 를 가리켰는데 그 페이지가 없어 404 였다.
  // 아래 셋은 페이지가 있는데 어디서도 링크되지 않았다. (products/loan/page.tsx 와 동일)
  { no: '04', desc: '차량 구입 자금을 나누어',    label: '자동차대출',            href: '/products/loan/auto',  key: '자동차' },
  { no: '05', desc: '입주 전 중도금과 이주 비용', label: '집단중도금·이주비대출', href: '/products/loan/group', key: '집단' },
  { no: '06', desc: '주택도시기금을 재원으로',    label: '주택도시기금대출',      href: '/products/loan/khfc',  key: '기금' },
]

type Tab = '수신' | '여신'

/**
 * 보여 줄 탭.
 *
 * <p>여신은 loan-service 가 배포될 때만 낸다. 슬라이드와 카테고리 정의는 남겨 두므로
 * (LOAN_SLIDES · LOAN_CATEGORIES) 서비스를 다시 올리면 `DEPLOYED.loan` 한 줄로 돌아온다.
 *
 * <p>탭이 하나만 남으면 전환할 데가 없어 머리말처럼 읽힌다 — 눌러도 아무 일이 없는
 * 탭을 두는 것보다는 낫다.
 */
const TABS: Tab[] = DEPLOYED.loan ? ['수신', '여신'] : ['수신']

export default function ProductShowcase() {
  const [activeTab, setActiveTab] = useState<Tab>('수신')
  const [depositSlide, setDepositSlide] = useState(0)
  const [loanSlide, setLoanSlide] = useState(0)
  const [depositSlides, setDepositSlides] = useState<Slide[]>(FALLBACK_SLIDES)

  useEffect(() => {
    fetchDepositProducts({ productStatus: 'SELLING' }).then(products => {
      const TYPE_MAP: { type: string; category: string; badge: string; tab: string }[] = [
        { type: 'DEPOSIT',      category: '예금',      badge: '예금',      tab: '예금' },
        { type: 'SAVINGS',      category: '적금',      badge: '적금',      tab: '자유적금' },
        { type: 'DEPOSIT',      category: '입출금자유', badge: '입출금자유', tab: '입출금자유' },
        { type: 'SUBSCRIPTION', category: '주택청약',  badge: '주택청약',  tab: '주택청약' },
      ]
      const slides: Slide[] = []
      TYPE_MAP.forEach(({ type, category, badge, tab }) => {
        const isChecking = category === '입출금자유'
        const filtered = products.filter(p =>
          p.productType === type &&
          (isChecking
            ? (p.productName?.includes('통장') || p.productName?.includes('입출금'))
            : !p.productName?.includes('통장'))
        )
        const pick = filtered[0]
        if (pick) slides.push(productToSlide(pick, category, badge, tab))
      })
      if (slides.length > 0) setDepositSlides(slides)
    }).catch(() => {})
  }, [])

  const isDeposit = activeTab === '수신'
  const slides = isDeposit ? depositSlides : LOAN_SLIDES
  const categories = isDeposit ? DEPOSIT_CATEGORIES : LOAN_CATEGORIES
  const slide = isDeposit ? depositSlide : loanSlide
  const setSlide = isDeposit ? setDepositSlide : setLoanSlide
  const current = slides[slide]


  return (
    <section className="py-6" style={{ backgroundColor: '#F5F6F8' }}>
      <div className="max-w-kb-container mx-auto px-8">

        {/* 헤더 */}
        <div className="mb-3">
          <p className="text-[11px] font-semibold tracking-widest uppercase mb-0.5" style={{ color: KB_MINT }}>Featured Products</p>
          <h2 className="text-[22px] font-bold text-kb-text">AXful Bank 대표상품</h2>
        </div>

        {/* 카드 — 탭 + 캐러셀 통합 */}
        <div className="bg-white rounded-lg overflow-hidden">

          {/* 탭 바 */}
          <div className="flex border-b border-kb-border px-4">
            {TABS.map(tab => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className="px-6 py-3 text-[14px] font-bold transition-colors"
                style={activeTab === tab
                  ? { color: '#2D5A3D', borderBottom: '2px solid #2D5A3D', marginBottom: '-1px' }
                  : { color: '#999', borderBottom: '2px solid transparent', marginBottom: '-1px' }
                }>
                {tab}
              </button>
            ))}
          </div>

        {/* 히어로 캐러셀 */}
        <div className="flex">

          {/* 좌: 슬라이드 배너 */}
          <div className="px-12 pt-9 pb-7 relative min-h-[300px] flex flex-col justify-between"
            style={{ width: 'calc(66.667% - 8px)', flexShrink: 0 }}>
            <div>
              <span className="inline-block text-white text-[12px] font-bold px-3 py-0.5 rounded-sm mb-3"
                style={{ backgroundColor: '#2D5A3D' }}>
                {current.badge}
              </span>
              <p className="text-[13px] text-kb-text-muted mb-2">{current.sub}</p>
              <h3 className="text-[34px] font-bold text-kb-text leading-tight mb-5">{current.title}</h3>
              <hr className="border-t-4 border-kb-border mb-5" />

              <div className="flex gap-14 mb-5">
                {/* field1 */}
                <div className="flex items-center gap-3">
                  <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0"
                    style={{ backgroundColor: KB_GOLD }}>
                    <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="1.8">
                      <rect x="3" y="4" width="18" height="17" rx="2"/>
                      <line x1="3" y1="9" x2="21" y2="9"/>
                      <line x1="8" y1="2" x2="8" y2="6"/>
                      <line x1="16" y1="2" x2="16" y2="6"/>
                    </svg>
                  </div>
                  <div>
                    <p className="text-[12px] text-kb-text-muted">{current.field1Label}</p>
                    <p className="text-[19px] font-bold text-kb-text">{current.field1}</p>
                  </div>
                </div>
                {/* field2 */}
                <div className="flex items-center gap-3">
                  <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0"
                    style={{ backgroundColor: KB_GOLD }}>
                    <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="1.8">
                      <circle cx="12" cy="12" r="9"/>
                      <text x="12" y="16" textAnchor="middle" fontSize="11" fill="white" stroke="none" fontWeight="bold">₩</text>
                    </svg>
                  </div>
                  <div>
                    <p className="text-[12px] text-kb-text-muted">{current.field2Label}</p>
                    <p className="text-[19px] font-bold text-kb-text">{current.field2}</p>
                  </div>
                </div>
              </div>

              {/* 금리 */}
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0"
                  style={{ backgroundColor: KB_GOLD }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="2">
                    <circle cx="12" cy="12" r="9"/>
                    <line x1="12" y1="7" x2="12" y2="17"/>
                    <line x1="7" y1="12" x2="17" y2="12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[22px] font-bold text-kb-text leading-none">{current.rate}</p>
                  <p className="text-[11px] text-kb-text-muted mt-1">{current.rateNote}</p>
                </div>
              </div>
            </div>

            {/* 캐러셀 컨트롤 + 바로가기 */}
            <div className="flex items-center justify-between mt-5">
              <div className="flex items-center gap-2">
                <button onClick={() => setSlide(s => (s - 1 + slides.length) % slides.length)}
                  className="text-kb-text-muted hover:text-kb-text text-xl leading-none">‹</button>
                <div className="flex gap-1.5">
                  {slides.map((_, i) => (
                    <button key={i} onClick={() => setSlide(i)}
                      className={`w-2 h-2 rounded-full transition-colors ${
                        i === slide ? 'bg-kb-text' : 'bg-kb-border'
                      }`} />
                  ))}
                </div>
                <button onClick={() => setSlide(s => (s + 1) % slides.length)}
                  className="text-kb-text-muted hover:text-kb-text text-xl leading-none">›</button>
                <span className="ml-2 text-[11px] text-kb-text-muted border border-kb-border px-1.5 py-0.5">II</span>
              </div>
              <Link href={current.href} className="text-[14px] font-bold text-kb-text underline hover:opacity-70">
                바로가기
              </Link>
            </div>
          </div>

          {/* 우: 카테고리 4개 */}
          <div className="flex-1 bg-[#F2F0E8] flex flex-col gap-2 p-3">
            {categories.map(cat => {
              const isActive = cat.key === current.category
              return (
                <button key={cat.no}
                  onClick={() => {
                    const idx = slides.findIndex(s => s.category === cat.key)
                    if (idx !== -1) setSlide(idx)
                  }}
                  className={`flex items-center justify-between px-5 py-4 flex-1 transition-colors text-left ${
                    isActive ? 'bg-[#2D5A3D]' : 'bg-white hover:bg-[#2D5A3D]/10'
                  }`}>
                  <div className="flex items-start gap-4">
                    <span className={`text-[22px] font-bold leading-none mt-0.5 ${
                      isActive ? 'text-white/70' : 'text-kb-gold'
                    }`}>{cat.no}</span>
                    <div>
                      <p className={`text-[11px] leading-relaxed ${isActive ? 'text-white/80' : 'text-kb-text-muted'}`}>
                        {cat.desc}
                      </p>
                      <p className={`text-[14px] font-bold ${isActive ? 'text-white' : 'text-kb-text'}`}>
                        {cat.label}
                      </p>
                    </div>
                  </div>
                  {isActive && <span className="text-white text-lg">›</span>}
                </button>
              )
            })}
          </div>
        </div>

          <p className="text-[12px] text-kb-text-muted px-4 py-2 text-right border-t border-kb-border">
            ⊙ {new Date().toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' }).replace(/\. /g, '.').replace('.', '')} 기준 · 세금공제전 · 우대금리포함
          </p>
        </div>
      </div>
    </section>
  )
}
