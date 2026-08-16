'use client'
import { KB_PRIMARY } from '@/lib/theme'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import CartModal from '@/components/products/CartModal'
import ConsultModal from '@/components/layout/ConsultModal'
import RateModal from '@/components/products/RateModal'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import { fetchDepositProduct, getDepositProductIdBySlug } from '@/lib/deposit-api'

const DEPOSIT_SIDEBAR = [
  { label: '예금 상품/가입', href: '/products/deposit/list', active: true },
  { label: '판매중지상품', href: '#' },
  { label: '예금 조회/해지', href: '/products/deposit/inquiry/new' },
  { label: '예금 가이드', href: '#' },
]

const DETAIL_TABS = ['상품안내', '금리 및 이율', '유의사항', '약관·상품설명서']

type ProductInfo = {
  name: string
  label: string
  channel: string
  period: string
  minAmount: string
  rate: string
  rateDate: string
}

const PRODUCTS: Record<string, ProductInfo> = {
  'axful-regular': {
    name: 'AXful 정기예금',
    label: 'Digital AXful의 대표 정기예금 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '1~36개월',
    minAmount: '1백만원 이상',
    rate: '연 2.4%~2.9%',
    rateDate: '2026.05.25',
  },
  'axful-super': {
    name: 'AXful 수퍼정기예금(개인)',
    label: '가입 조건을 직접 설계하는 / 영업점',
    channel: '영업점',
    period: '1~36개월',
    minAmount: '1백만원 이상',
    rate: '연 2.2%~2.3%',
    rateDate: '2026.05.25',
  },
  'regular': {
    name: '일반정기예금',
    label: '목돈 모아 안정수익 / 영업점',
    channel: '영업점',
    period: '1~36개월',
    minAmount: '1백만원 이상',
    rate: '연 2.25%~2.25%',
    rateDate: '2026.05.25',
  },
  'axful-youth': {
    name: 'AXful 청년도약계좌',
    label: '청년의 자산형성을 응원합니다 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '60개월',
    minAmount: '월 1천원 이상',
    rate: '연 3.5%~6.0%',
    rateDate: '2026.05.25',
  },
  // ── 적금 ──────────────────────────────────────────────────────────────────
  'axful-free': {
    name: 'AXful 내맘대로적금',
    label: '누구나 쉽게 자유롭게 DIY / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '6~36개월',
    minAmount: '월 1만원 이상',
    rate: '연 2.95%~3.55%',
    rateDate: '2026.05.25',
  },
  'axful-dollar': {
    name: 'AXful 달러자적금',
    label: '달러 가치상승 응원하는 두배이율 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '6개월',
    minAmount: '월 1만원 이상',
    rate: '연 1%~7.2%',
    rateDate: '2026.05.25',
  },
  'axful-green': {
    name: 'AXful 맑은하늘적금',
    label: '맑은하늘 인증코드 금리도 Up / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '6~36개월',
    minAmount: '월 1만원 이상',
    rate: '연 2.85%~3.85%',
    rateDate: '2026.05.25',
  },
  'axful-soldier': {
    name: 'AXful 장병내일준비적금',
    label: '국군장병 미래대비 앞날준비 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '24개월',
    minAmount: '월 1만원 이상',
    rate: '연 5%~10.5%',
    rateDate: '2026.05.25',
  },
  'axful-star-savings': {
    name: 'AXful 특★한 적금',
    label: '고객 모두의 높은 수익을 위한 특별한 준비 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '1~12개월',
    minAmount: '월 1만원 이상',
    rate: '연 2%~6%',
    rateDate: '2026.05.25',
  },
  // ── 입출금자유 ────────────────────────────────────────────────────────────
  'axful-free-account': {
    name: 'AXful 자유입출금통장',
    label: '언제든 자유롭게 입출금 가능한 기본 통장 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '기간 제한 없음',
    minAmount: '제한 없음',
    rate: '연 0.1%',
    rateDate: '2026.05.25',
  },
  'axful-sok': {
    name: 'AXful 쏙머니통장',
    label: '쇼핑용 아껴 쏙머니가 쏙~ / 영업점',
    channel: '영업점',
    period: '기간 제한 없음',
    minAmount: '제한 없음',
    rate: '연 1.5%',
    rateDate: '2026.05.25',
  },
  'monimo-daily': {
    name: '모니모 AXful 매일이자 통장',
    label: '하루만 넣어도 이자가 쌓이는 / 영업점',
    channel: '영업점',
    period: '기간 제한 없음',
    minAmount: '제한 없음',
    rate: '연 2.5% (일 복리)',
    rateDate: '2026.05.25',
  },
  'axful-youth-account': {
    name: 'AXful 청년우대통장',
    label: '만 19~34세 청년을 위한 우대금리 제공 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '기간 제한 없음',
    minAmount: '제한 없음',
    rate: '연 0.1%~2.0%',
    rateDate: '2026.05.25',
  },
  // ── 주택청약 ──────────────────────────────────────────────────────────────
  'housing-savings': {
    name: '주택청약종합저축',
    label: '내 집 마련의 꿈을 응원합니다 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '24개월 기준',
    minAmount: '월 2만원 이상',
    rate: '연 3.1%',
    rateDate: '2026.05.25',
  },
  'youth-housing': {
    name: '청년 주택드림 청약통장',
    label: '청년의 내 집 마련을 응원합니다 / 인터넷뱅킹',
    channel: '인터넷뱅킹',
    period: '24개월 기준',
    minAmount: '월 2만원 이상',
    rate: '연 3.1%~4.5%',
    rateDate: '2026.05.25',
  },
}

type RateRow = { period: string; base: string; customer: string }

const PRODUCT_RATES: Record<string, RateRow[]> = {
  'axful-regular': [
    { period: '1개월 이상 ~ 3개월미만',  base: '1.80', customer: '2.45' },
    { period: '3개월 이상 ~ 6개월미만',  base: '2.00', customer: '2.75' },
    { period: '6개월 이상 ~ 9개월미만',  base: '2.10', customer: '2.85' },
    { period: '9개월 이상 ~ 12개월미만', base: '2.10', customer: '2.85' },
    { period: '12개월 이상 ~ 24개월미만',base: '2.15', customer: '2.90' },
    { period: '24개월 이상 ~ 36개월미만',base: '2.20', customer: '2.40' },
    { period: '36개월',                  base: '2.20', customer: '2.40' },
  ],
  'axful-super': [
    { period: '1개월 이상 ~ 6개월미만',  base: '1.90', customer: '2.20' },
    { period: '6개월 이상 ~ 12개월미만', base: '2.00', customer: '2.25' },
    { period: '12개월 이상 ~ 24개월미만',base: '2.10', customer: '2.30' },
    { period: '24개월 이상 ~ 36개월미만',base: '2.15', customer: '2.30' },
  ],
  'regular': [
    { period: '1개월 이상 ~ 6개월미만',  base: '1.85', customer: '2.25' },
    { period: '6개월 이상 ~ 12개월미만', base: '2.00', customer: '2.25' },
    { period: '12개월 이상 ~ 36개월미만',base: '2.10', customer: '2.25' },
  ],
  'axful-youth': [
    { period: '60개월 (기본)',            base: '3.50', customer: '4.50' },
    { period: '60개월 (소득요건 충족)',   base: '3.50', customer: '6.00' },
  ],
  'axful-free': [
    { period: '6개월 이상 ~ 12개월미만', base: '2.20', customer: '2.95' },
    { period: '12개월 이상 ~ 24개월미만',base: '2.50', customer: '3.25' },
    { period: '24개월 이상 ~ 36개월미만',base: '2.70', customer: '3.45' },
    { period: '36개월',                  base: '2.80', customer: '3.55' },
  ],
  'axful-dollar': [
    { period: '6개월',                   base: '1.00', customer: '7.20' },
  ],
  'axful-green': [
    { period: '6개월 이상 ~ 12개월미만', base: '2.10', customer: '2.85' },
    { period: '12개월 이상 ~ 24개월미만',base: '2.50', customer: '3.25' },
    { period: '24개월 이상 ~ 36개월미만',base: '2.80', customer: '3.55' },
    { period: '36개월',                  base: '3.10', customer: '3.85' },
  ],
  'axful-soldier': [
    { period: '24개월',                  base: '5.00', customer: '10.50' },
  ],
  'axful-star-savings': [
    { period: '1개월',                   base: '2.00', customer: '3.50' },
    { period: '3개월',                   base: '2.50', customer: '4.50' },
    { period: '6개월',                   base: '3.00', customer: '5.50' },
    { period: '12개월',                  base: '3.50', customer: '6.00' },
  ],
  'axful-youth-account': [
    { period: '기본',                    base: '0.10', customer: '2.00' },
  ],
  'housing-savings': [
    { period: '24개월 기준',             base: '2.80', customer: '3.10' },
  ],
  'youth-housing': [
    { period: '24개월 기준 (기본)',       base: '3.10', customer: '3.50' },
    { period: '24개월 기준 (요건 충족)',  base: '3.10', customer: '4.50' },
  ],
}

/* ── 일러스트 SVG ── */
function ClockPiggyIllust() {
  return (
    <svg viewBox="0 0 180 120" fill="none" className="w-44 h-28 mx-auto">
      {/* 시계 */}
      <circle cx="70" cy="60" r="42" fill="white" stroke="#5BC9A8" strokeWidth="6"/>
      <circle cx="70" cy="60" r="34" fill="white"/>
      <line x1="70" y1="60" x2="70" y2="32" stroke="#1A1A1A" strokeWidth="3" strokeLinecap="round"/>
      <line x1="70" y1="60" x2="88" y2="68" stroke="#1A1A1A" strokeWidth="3" strokeLinecap="round"/>
      <circle cx="70" cy="60" r="3" fill="#1A1A1A"/>
      <circle cx="70" cy="28" r="2" fill="#5BC9A8"/>
      <circle cx="70" cy="92" r="2" fill="#5BC9A8"/>
      <circle cx="38" cy="60" r="2" fill="#5BC9A8"/>
      <circle cx="102" cy="60" r="2" fill="#5BC9A8"/>
      {/* 돼지 저금통 */}
      <ellipse cx="135" cy="72" rx="26" ry="22" fill="#F5C842"/>
      <ellipse cx="150" cy="78" rx="10" ry="8" fill="#F0B830"/>
      <circle cx="125" cy="65" r="4" fill="#F0B830"/>
      <circle cx="126" cy="64" r="1.5" fill="#333"/>
      <path d="M130 84 Q135 90 140 84" stroke="#E8A020" strokeWidth="2" fill="none" strokeLinecap="round"/>
      <rect x="130" y="52" width="12" height="3" rx="1.5" fill="#333"/>
      <ellipse cx="158" cy="76" rx="5" ry="4" fill="#F0B830"/>
      <line x1="122" y1="90" x2="120" y2="100" stroke="#E8A020" strokeWidth="3" strokeLinecap="round"/>
      <line x1="130" y1="92" x2="129" y2="102" stroke="#E8A020" strokeWidth="3" strokeLinecap="round"/>
      <line x1="140" y1="92" x2="141" y2="102" stroke="#E8A020" strokeWidth="3" strokeLinecap="round"/>
      <line x1="148" y1="90" x2="150" y2="100" stroke="#E8A020" strokeWidth="3" strokeLinecap="round"/>
    </svg>
  )
}

function MoneyIllust() {
  return (
    <svg viewBox="0 0 160 100" fill="none" className="w-36 h-24 mx-auto">
      {[12, 8, 4, 0].map((offset, i) => (
        <g key={i} transform={`translate(${offset * 0.5}, ${-offset})`}>
          <rect x="20" y="45" width="120" height="48" rx="4"
            fill={i === 0 ? '#4CAF50' : '#5CB85C'}
            opacity={1 - i * 0.12}/>
          <rect x="26" y="50" width="108" height="38" rx="3"
            fill={i === 0 ? '#43A047' : '#4CAF50'}
            opacity={1 - i * 0.1}/>
          <text x="80" y="72" textAnchor="middle" fontSize="11" fontWeight="bold"
            fill="white" opacity={i === 0 ? 1 : 0.5}>10000</text>
          <circle cx="40" cy="69" r="10" fill="none" stroke="rgba(255,255,255,0.3)" strokeWidth="1.5"/>
          <circle cx="120" cy="69" r="10" fill="none" stroke="rgba(255,255,255,0.3)" strokeWidth="1.5"/>
        </g>
      ))}
    </svg>
  )
}

/* ── 스펙 테이블 행 ── */
function SpecRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <tr>
      <td className="border border-kb-border bg-kb-beige-light px-4 py-3 font-semibold text-kb-text text-[13px] w-[130px] align-top whitespace-nowrap">
        {label}
      </td>
      <td className="border border-kb-border px-4 py-3 text-[13px] text-kb-text-body leading-relaxed">
        {children}
      </td>
    </tr>
  )
}

export default function DepositDetailPage() {
  const params = useParams()
  const id = typeof params.id === 'string' ? params.id : 'axful-regular'
  const fallbackProduct = PRODUCTS[id] ?? PRODUCTS['axful-regular']
  const [apiProduct, setApiProduct] = useState<ProductInfo | null>(null)
  const product = apiProduct ?? fallbackProduct
  const [activeTab, setActiveTab] = useState('상품안내')
  const [showCart, setShowCart] = useState(false)
  const [showConsult, setShowConsult] = useState(false)
  const [showRate, setShowRate] = useState(false)
  const [calcAmount, setCalcAmount] = useState('')
  const [calcMonths, setCalcMonths] = useState('')
  const [calcRate, setCalcRate] = useState(product.rate.match(/[\d.]+/)?.[0] ?? '')
  const [calcResult, setCalcResult] = useState<string | null>(null)
  const isAxfulRegular = id === 'axful-regular'
  const rates = PRODUCT_RATES[id] ?? []

  // 적금 상품 ID 목록
  const SAVINGS_IDS       = new Set(['axful-free', 'axful-dollar', 'axful-green', 'axful-soldier', 'axful-star-savings'])
  const FREE_SAVINGS_IDS  = new Set(['axful-free', 'axful-dollar', 'axful-green', 'axful-star-savings'])
  const isSavings         = SAVINGS_IDS.has(id)
  const isFreeStyleSavings = FREE_SAVINGS_IDS.has(id)

  // 수시입출금 통장 상품 ID 목록
  const CHECKING_IDS = new Set([
    'axful-sok', 'monimo-daily', 'axful-moim', 'axful-star-account',
    'axful-wallet', 'axful-free-account', 'axful-youth-account',
  ])
  const isChecking = CHECKING_IDS.has(id)

  useEffect(() => {
    const productId = getDepositProductIdBySlug(id)
    if (!productId) return

    let cancelled = false
    async function loadProduct() {
      try {
        const data = await fetchDepositProduct(productId)
        if (cancelled) return

        const minMonth = data.minPeriodMonth
        const maxMonth = data.maxPeriodMonth
        const period =
          minMonth && maxMonth
            ? minMonth === maxMonth
              ? `${minMonth}개월`
              : `${minMonth}~${maxMonth}개월`
            : fallbackProduct.period
        const minAmount = data.minJoinAmount
          ? `${Number(data.minJoinAmount).toLocaleString('ko-KR')}원 이상`
          : fallbackProduct.minAmount
        const rate = data.bestRate != null
          ? `최고 연 ${Number(data.bestRate).toLocaleString('ko-KR')}%`
          : data.baseInterestRate != null
          ? `기본 연 ${Number(data.baseInterestRate).toLocaleString('ko-KR')}%`
          : fallbackProduct.rate

        setApiProduct({
          ...fallbackProduct,
          name: data.productName,
          label: `${data.description || fallbackProduct.label} / ${fallbackProduct.channel}`,
          period,
          minAmount,
          rate,
        })
      } catch {
        // API가 내려가 있으면 기존 정적 상품 정보로 계속 표시합니다.
      }
    }

    loadProduct()
    return () => {
      cancelled = true
    }
  }, [fallbackProduct, id])

  function handleCalc() {
    const a = parseFloat(calcAmount.replace(/,/g, ''))
    const m = parseFloat(calcMonths)
    const r = parseFloat(calcRate)
    if (!a || !m || !r) { alert('금액, 기간, 금리를 모두 입력해주세요.'); return }

    if (isSavings) {
      // 적금: 단리 적립식 — 월 저축금액 × 연이율/12 × n(n+1)/2
      const monthlyRate = r / 100 / 12
      const interest = Math.floor(a * monthlyRate * m * (m + 1) / 2)
      const principal = a * m
      const total = principal + interest
      setCalcResult(`만기 수령액: ${total.toLocaleString()}원 (원금 ${principal.toLocaleString()}원 + 이자 ${interest.toLocaleString()}원)`)
    } else {
      // 예금: 단리 거치식
      const interest = Math.floor(a * (r / 100) * (m / 12))
      const total = a + interest
      setCalcResult(`만기 수령액: ${total.toLocaleString()}원 (이자 ${interest.toLocaleString()}원)`)
    }
  }

  return (
    <>
    {showCart && <CartModal productName={product.name} onClose={() => setShowCart(false)} />}
    {showConsult && <ConsultModal onClose={() => setShowConsult(false)} />}
    {showRate && <RateModal productName={product.name} rates={rates} rateDate={product.rateDate} onClose={() => setShowRate(false)} />}
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        {/* 사이드바 */}
        <aside className="w-[180px] flex-shrink-0 border-r border-kb-border min-h-[700px] pt-6 pr-2">
          <h2 className="text-base font-bold text-kb-text mb-5 px-1">예금</h2>
          <nav>
            {DEPOSIT_SIDEBAR.map(item => (
              <Link key={item.label} href={item.href}
                className={`block px-2 py-2 text-sm ${
                  item.active ? 'font-semibold text-kb-text' : 'text-kb-text-muted hover:text-kb-text'
                }`}>
                {item.label}{item.active && <span className="ml-1 text-[10px]">˃</span>}
              </Link>
            ))}
          </nav>
          <div className="mt-6 space-y-2">
            <Link href="/cert"
              className="flex items-center gap-2 border border-kb-border rounded-lg px-3 py-2 text-sm text-kb-text-body hover:bg-kb-beige-light">
              🔒 인증센터
            </Link>
            <div className="border border-kb-border rounded-lg px-3 py-2 text-sm text-kb-text-body">
              <p className="text-[10px] text-kb-text-muted">신규상담</p>
              <p className="font-bold text-kb-text">1800-9500</p>
            </div>
          </div>
        </aside>

        {/* 본문 */}
        <main className="flex-1 pl-8 pt-4 pb-12">
          {/* 브레드크럼 */}
          <AutoBreadcrumb
            as="/products/deposit/list"
            className="flex justify-end items-center mb-2 text-[12px] text-kb-text-muted gap-1"
            trailing={<Link href="#" className="text-kb-blue hover:underline">도움말</Link>}
          />

          {/* 상품 카드 */}
          <div className="border border-kb-border rounded-xl p-5 mb-4">
            <p className="text-[12px] text-kb-text-muted mb-1">{product.label}</p>
            <h1 className="text-[22px] font-bold text-kb-text mb-4">{product.name}</h1>

            {/* 기간·금액·금리 뱃지 */}
            <div className="flex items-start gap-6 mb-5">
              {/* 기간 */}
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="3" y="4" width="18" height="17" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[11px] text-kb-text-muted">기간</p>
                  <p className="text-[15px] font-bold text-kb-text">{product.period}</p>
                </div>
              </div>
              {/* 금액 */}
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="9"/><line x1="12" y1="7" x2="12" y2="17"/><path d="M9 10h4.5a1.5 1.5 0 010 3H9v-3z"/><path d="M9 13h5a1.5 1.5 0 010 3H9v-3z"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[11px] text-kb-text-muted">금액</p>
                  <p className="text-[15px] font-bold text-kb-text">{product.minAmount}</p>
                </div>
              </div>
              {/* 금리 */}
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: '#FF6B35' }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M2.5 12c0-5.25 4.25-9.5 9.5-9.5 2.9 0 5.5 1.3 7.3 3.3"/><path d="M21.5 12c0 5.25-4.25 9.5-9.5 9.5-2.9 0-5.5-1.3-7.3-3.3"/><polyline points="17,2 19.8,5.5 16.5,7"/><polyline points="7,22 4.2,18.5 7.5,17"/><line x1="9" y1="15" x2="15" y2="9"/><circle cx="9.5" cy="9.5" r="0.8" fill="white"/><circle cx="14.5" cy="14.5" r="0.8" fill="white"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[11px] text-kb-text-muted">금리</p>
                  <p className="text-[15px] font-bold" style={{ color: '#FF6B35' }}>{product.rate}</p>
                  <p className="text-[10px] text-kb-text-muted">{product.rateDate} 기준, 세금공제전, 우대금리포함</p>
                </div>
              </div>
            </div>

            {/* 액션 버튼 4개 */}
            <div className="flex gap-2 mb-1">
              <Link href={`/products/deposit/join/${id}`}
                className="bg-kb-primary px-6 py-2 text-[13px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity">
                온라인가입
              </Link>
              <button onClick={() => setShowCart(true)}
                className="border border-kb-border rounded-xl px-6 py-2 text-[13px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                장바구니
              </button>
              <button onClick={() => setShowConsult(true)}
                className="border border-kb-border rounded-xl px-6 py-2 text-[13px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                상담신청
              </button>
              <Link href="/support/consultation/branch"
                className="border border-kb-border rounded-xl px-6 py-2 text-[13px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                영업점 방문예약
              </Link>
            </div>
            <p className="text-[12px] text-kb-text-muted text-right mt-2">
              ※ 자세한 내용은 아래 상품안내를 참조하시기 바랍니다.
            </p>
          </div>

          {/* 예금/적금 계산기 */}
          <div className="border border-kb-border rounded-xl px-5 py-4 mb-1">
            <p className="text-[13px] font-bold text-kb-text mb-3">{isSavings ? '적금 계산기' : '예금 계산기'}</p>
            {/* 시나리오 레이블 */}
            <div className="border border-kb-border rounded-lg px-4 py-2.5 mb-3 flex items-center gap-2 bg-white" style={{ width: '100%' }}>
              <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4 flex-shrink-0 text-kb-primary" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M3 10a7 7 0 0112.04-4.87"/><polyline points="19,2 15,5.5 18.5,9"/><path d="M17 10a7 7 0 01-12.04 4.87"/><polyline points="1,18 5,14.5 1.5,11"/>
              </svg>
              <span className="text-[13px] text-kb-text-body">
                {isFreeStyleSavings ? '자유롭게 납입할 때 (월 평균 납입 기준)' : isSavings ? '매월 일정금액을 저축할 때' : '열심히 모은 목돈을 예치할 때'}
              </span>
            </div>
            {/* 입력 행 */}
            <div className="flex items-center gap-2 flex-wrap">
              <input
                type="text"
                placeholder={isFreeStyleSavings ? '월 평균 납입액' : isSavings ? '월 저축금액' : '예치금액'}
                value={calcAmount}
                onChange={e => setCalcAmount(e.target.value)}
                className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] w-28 outline-none"
              />
              <span className="text-[13px] text-kb-text-body">{isSavings ? '원씩' : '원을'}</span>
              <input
                type="text"
                placeholder="기간"
                value={calcMonths}
                onChange={e => setCalcMonths(e.target.value)}
                className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] w-16 outline-none"
              />
              <span className="text-[13px] text-kb-text-body">개월 간</span>
              <input
                type="text"
                placeholder="금리"
                value={calcRate}
                onChange={e => setCalcRate(e.target.value)}
                className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] w-16 outline-none"
              />
              <span className="text-[13px] text-kb-text-body">%의 {isSavings ? '적금' : '예금'}상품에 저축하면?</span>
              <button
                onClick={handleCalc}
                className="bg-[#5C5C5C] text-white px-5 py-1.5 text-[13px] hover:opacity-90 transition-opacity ml-auto"
              >
                결과보기
              </button>
            </div>
            {calcResult && (
              <p className="mt-2 text-[13px] font-semibold" style={{ color: KB_PRIMARY }}>{calcResult}</p>
            )}
          </div>

          {/* SNS 공유 */}
          <div className="flex justify-end items-center gap-1.5 mb-4 py-2">
            {/* SNS 공유·추천메일 버튼이 있었다. 공유는 외부 SDK(카카오 등), 추천메일은
                메일 발송 백엔드가 필요한데 둘 다 없어 눌러도 아무 일이 없었다. */}
          </div>

          {/* 탭 */}
          <div className="flex border-b border-kb-border mb-0">
            {DETAIL_TABS.map(tab => (
              <button key={tab} onClick={() => setActiveTab(tab)}
                className={`px-8 py-3 text-[14px] border-b-2 -mb-px transition-colors ${
                  activeTab === tab
                    ? 'border-[#E8A020] font-bold text-kb-text bg-white'
                    : 'border-transparent text-kb-text-muted hover:text-kb-text'
                }`}>
                {tab}
              </button>
            ))}
          </div>

          {/* ── 상품안내 탭 ── */}
          {activeTab === '상품안내' && (
            <div>
              {isAxfulRegular ? (
                <>
                  {/* 비주얼 섹션 1 */}
                  <div className="py-12 text-center border-b border-kb-border">
                    <p className="text-[22px] font-bold text-kb-text mb-1 leading-snug">
                      목돈 불리는 예금,
                    </p>
                    <p className="text-[22px] font-bold text-kb-text mb-3 leading-snug">
                      만기되면{' '}
                      <span style={{ textDecoration: 'underline', textDecorationColor: '#F5C842', textDecorationThickness: '4px', textUnderlineOffset: '4px' }}>
                        자동으로 재예치!
                      </span>
                    </p>
                    <p className="text-[14px] text-kb-text-muted leading-relaxed mb-8">
                      처음 가입 시 자동재예치 여부를 설정하시면,<br />
                      만기 시 원금만 혹은 원금과 이자 모두 재예치 할 수 있어요.
                    </p>
                    <ClockPiggyIllust />
                  </div>

                  {/* 비주얼 섹션 2 */}
                  <div className="py-12 text-center border-b border-kb-border">
                    <p className="text-[22px] font-bold text-kb-text mb-3 leading-snug">
                      급할 땐 해지 하지 않아도{' '}
                      <span style={{ textDecoration: 'underline', textDecorationColor: '#F5C842', textDecorationThickness: '4px', textUnderlineOffset: '4px' }}>
                        분할 인출 가능
                      </span>
                    </p>
                    <p className="text-[14px] text-kb-text-muted leading-relaxed mb-8">
                      최대 2회까지 필요한 금액만 출금이 가능해요~<br />
                      (단, 가입일로부터 1개월 이상된 계좌만 가능,<br />
                      계좌별 잔액 100만원 이상 유지해야 가능)
                    </p>
                    <MoneyIllust />
                  </div>

                  {/* 스펙 테이블 */}
                  <div className="mt-8">
                    <table className="w-full border-collapse border-t-2 border-kb-text">
                      <tbody>
                        <SpecRow label="상품특징">
                          인터넷뱅킹, AXful뱅킹, 고객센터를 통해서만 가입가능한 Digital AXful 대표 정기예금으로,
                          자동 만기관리부터 분할인출까지 가능한 편리한 온라인 전용 정기예금입니다.
                        </SpecRow>
                        <SpecRow label="가입대상">개인 및 개인사업자</SpecRow>
                        <SpecRow label="계약기간">1개월 이상 36개월 이하(월단위)</SpecRow>
                        <SpecRow label="가입금액">1백만원 이상 (추가입금 불가)</SpecRow>
                        <SpecRow label="만기해지방법">
                          <span>최초 가입 시 아래의 구분 중 1개 방법 선택 가능</span>
                          <ul className="mt-1 space-y-1">
                            <li>- 자동해지 : 만기일 당일 상품 신규가입 시 출금계좌에 만기해지 금액 전액 입금</li>
                            <li>- 자동재예치(원금) : 만기(재예치)일 당일 고시한 고객적용이율을 적용하며, 적용이자율을 제외한 가입조건은 기존 가입조건과 동일하게 원금부분만 재예치, 이자 금액은 신규가입 시 출금계좌에 입금</li>
                            <li>- 자동재예치(원금+이자) : 만기(재예치)일 당일 고시한 고객적용이율을 적용하며, 적용이자율을 제외한 가입조건은 기존 가입조건과 동일조건으로 만기해지 금액 전액 재예치</li>
                            <li className="mt-1 text-kb-text-muted">-「오픈뱅킹」서비스를 통해 신규 가입한 경우, 자동해지(재예치)시 만기해지금액(이자금액)은 AXful Bank 출금계좌로 입금됩니다.</li>
                          </ul>
                        </SpecRow>
                        <SpecRow label="분할인출">
                          <ul className="space-y-1">
                            <li>- 대상계좌 : 가입일로부터 1개월이상 경과된 계좌</li>
                            <li>- 분할인출횟수 : 계좌별 3회(해지 포함)이내 가능</li>
                            <li>- 적용이율 : 신규 및 자동재예치 시 계약기간별 기본이율</li>
                            <li>- 인출금액 : 제한없음. 단, 분할인출 후 계좌별 잔액은 100만원 이상 유지되어야 함.</li>
                          </ul>
                        </SpecRow>
                      </tbody>
                    </table>
                  </div>
                </>
              ) : isChecking ? (
                /* 수시입출금 통장 — 기본 레이아웃 */
                <div className="py-8 space-y-6">
                  <table className="w-full border-collapse border-t-2 border-kb-text">
                    <tbody>
                      <SpecRow label="가입대상">실명의 개인</SpecRow>
                      <SpecRow label="가입기간">제한 없음</SpecRow>
                      <SpecRow label="가입금액">제한 없음</SpecRow>
                      <SpecRow label="이자지급방식">매월 이자지급식</SpecRow>
                      <SpecRow label="세금">이자소득세 15.4% (지방소득세 포함)</SpecRow>
                      <SpecRow label="적용이율">{product.rate}</SpecRow>
                    </tbody>
                  </table>
                </div>
              ) : (
                /* 기타 예금 상품 — 기본 레이아웃 */
                <div className="py-8 space-y-6">
                  <table className="w-full border-collapse border-t-2 border-kb-text">
                    <tbody>
                      <SpecRow label="가입대상">개인 및 개인사업자</SpecRow>
                      <SpecRow label="계약기간">{product.period}(월단위)</SpecRow>
                      <SpecRow label="가입금액">{product.minAmount} (추가입금 불가)</SpecRow>
                      <SpecRow label="이자지급방식">만기일시지급식</SpecRow>
                      <SpecRow label="세금">이자소득세 15.4% (지방소득세 포함)</SpecRow>
                    </tbody>
                  </table>
                </div>
              )}

              {/* 하단 버튼 */}
              <div className="flex justify-center gap-2 mt-10">
                <Link href={`/products/deposit/join/${id}`}
                  className="bg-kb-primary px-10 py-3 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity">
                  온라인가입
                </Link>
                <Link href="/products/deposit/list"
                  className="border border-kb-border rounded-xl px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                  목록
                </Link>
                <button
                  onClick={() => window.print()}
                  className="border border-kb-border rounded-xl px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                  인쇄
                </button>
              </div>
            </div>
          )}

          {/* ── 금리 및 이율 탭 ── */}
          {activeTab === '금리 및 이율' && (
            <div className="py-6 border-t-2 border-kb-text">
              <table className="w-full border-collapse text-[13px]">
                <tbody>
                  <tr>
                    <td className="bg-kb-beige-light border border-kb-border px-4 py-3 font-semibold text-kb-text w-[120px] whitespace-nowrap">
                      금리
                    </td>
                    <td className="border border-kb-border px-4 py-3">
                      <button
                        onClick={() => setShowRate(true)}
                        className="border border-kb-border rounded-lg px-4 py-1.5 text-[12px] text-kb-text-body hover:bg-kb-beige-light transition-colors"
                      >
                        자세히보기
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
              {/* 하단 버튼 */}
              <div className="flex justify-center gap-2 mt-8">
                <Link href={`/products/deposit/join/${id}`}
                  className="bg-kb-primary px-10 py-3 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity">
                  온라인가입
                </Link>
                <Link href="/products/deposit/list"
                  className="border border-kb-border rounded-xl px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                  목록
                </Link>
                <button onClick={() => window.print()}
                  className="border border-kb-border rounded-xl px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                  인쇄
                </button>
              </div>
            </div>
          )}

          {/* ── 유의사항 탭 ── */}
          {activeTab === '유의사항' && (
            <div className="py-4 text-[13px] text-kb-text-body space-y-0">

              {/* 거래방법 */}
              <section className="mb-5">
                <p className="font-bold text-kb-text mb-2 text-[14px]">거래방법</p>
                <table className="w-full border-collapse border-t border-kb-text">
                  <tbody>
                    <tr>
                      <td className="bg-kb-beige-light border border-kb-border px-4 py-2.5 font-semibold text-kb-text w-24 whitespace-nowrap">신규</td>
                      <td className="border border-kb-border px-4 py-2.5">AXful뱅킹, 인터넷뱅킹, 고객센터</td>
                    </tr>
                    <tr>
                      <td className="bg-kb-beige-light border border-kb-border px-4 py-2.5 font-semibold text-kb-text">해지</td>
                      <td className="border border-kb-border px-4 py-2.5">AXful뱅킹, 인터넷뱅킹, 영업점</td>
                    </tr>
                  </tbody>
                </table>
              </section>

              {/* 예금유의사항 */}
              <section className="mb-5">
                <p className="font-bold text-kb-text mb-2 text-[14px]">예금유의사항</p>
                <ul className="space-y-1.5">
                  {[
                    '이 금융상품을 가입하시기 전에 상품설명서 및 약관을 읽어보시기 바랍니다.',
                    '금융소비자는 해당 상품 또는 서비스에 대하여 설명받을 권리가 있습니다.',
                    '만기 전 해지할 경우 계약에서 정한 이율보다 낮은 중도해지이율이 적용됩니다.',
                    '계좌에 압류, 가압류, 질권 등이 등록될 경우 원금 및 이자 지급이 제한될 수 있습니다.',
                    '이 상품은 AXful Bank 수신상품부(P)에서 관리하는 상품입니다. 기타 상품에 대한 자세한 사항은 영업점 또는 고객센터(☎ 1588-9999)로 문의하시거나, 상품설명서를 참조하시기 바랍니다.',
                  ].map((item, i) => (
                    <li key={i} className="flex gap-2">
                      <span className="flex-shrink-0">·</span>{item}
                    </li>
                  ))}
                </ul>
              </section>

              {/* 기타안내 */}
              <section className="mb-5">
                <p className="font-bold text-kb-text mb-2 text-[14px]">기타안내</p>
                <ul className="space-y-2">
                  <li>
                    <p>* 이 예금은 자동해지/자동재예치(원금)/자동재예치(원금+이자) 중 1개의 만기해지방법을 필수로 선택하여야 하며, 아래의 사유에 해당할 경우 처리가 되지 않습니다.</p>
                    <ul className="ml-4 mt-1 space-y-0.5">
                      <li>① 자동해지/재예치 대상 계좌 및 출금계좌에 법적지급제한, 지급 및 해지제한이 되어 있는 경우</li>
                      <li>② 출금계좌가 해지된 경우</li>
                      <li>③ 재예치 가능기간(최장 10년)이 경과될 경우</li>
                    </ul>
                  </li>
                  <li>* 통장발행을 원하실 경우, 영업점에 방문하셔야 하며 수수료가 부과됩니다. 단, 수수료 면제는 AXful Bank 수수료 관련지침에서 정하는 바에 따릅니다.</li>
                </ul>
              </section>

              {/* 세제혜택 */}
              <section className="mb-5">
                <p className="font-bold text-kb-text mb-2 text-[14px]">세제혜택</p>
                <ul className="space-y-1">
                  <li>비과세종합저축으로 가입 가능</li>
                  <li className="text-kb-text-muted">※ 관련 세법이 개정될 경우 세율이 변경되거나 세금이 부과될 수 있음</li>
                  <li className="text-kb-text-muted">※ 계약기간 만료일 이후의 이자는 과세됨</li>
                </ul>
              </section>

              {/* 예금자보호여부 */}
              <section className="mb-5">
                <p className="font-bold text-kb-text mb-2 text-[14px]">예금자보호여부</p>
                <p className="font-semibold mb-1">예금보험공사 보호금융상품 1인당 최고 1억원</p>
                <p>이 예금은 예금자보호법에 따라 원금과 소정의 이자를 합하여 1인당 <span className="font-semibold">&quot;1억원까지&quot;</span>(본 은행의 여타 보호상품과 합산) 보호됩니다.</p>
              </section>

              {/* 준법감시인 */}
              <section className="mb-6 text-kb-text-muted text-[12px] space-y-0.5">
                <p>준법감시인 심의필 제2025-2226-4호(2025.06.02)</p>
                <p>본 공시내용의 유효기간 : 2025.06.09 ~ 2027.05.31 까지</p>
              </section>

              {/* 상품내용 변경 이력 */}
              <section>
                <p className="font-bold text-kb-text mb-3 text-[14px]">상품내용 변경에 관한 사항</p>

                {/* 2022.12.15 */}
                <div className="border border-kb-border rounded-xl overflow-hidden mb-3">
                  <div className="bg-kb-beige-light px-4 py-2 font-semibold text-kb-text border-b border-kb-border">
                    2022.12.15 변경 — 금리우대쿠폰 항목 추가
                  </div>
                  <div className="px-4 py-3 space-y-1">
                    <p>1. 변경내용</p>
                    <p className="ml-4">- 변경 전 : 해당없음</p>
                    <p className="ml-4">- 변경 후 : 금리우대쿠폰 추가</p>
                    <p>2. 적용대상 : 시행일 이후 신규 적용한 계좌부터 적용</p>
                  </div>
                </div>

                {/* 2019.04.17 */}
                <div className="border border-kb-border rounded-xl overflow-hidden mb-3">
                  <div className="bg-kb-beige-light px-4 py-2 font-semibold text-kb-text border-b border-kb-border">
                    2019.04.17 변경 — 판매채널 확대(콜센터 추가)
                  </div>
                  <div className="px-4 py-3 space-y-1">
                    <p className="text-kb-text-muted">변경 전</p>
                    <p className="ml-4">ㅇ 신규 : 인터넷뱅킹, AXful뱅킹</p>
                    <p className="ml-4">ㅇ 해지 : 영업점, 인터넷뱅킹, AXful뱅킹</p>
                    <p className="ml-4">ㅇ 만기해지방법 변경 : 영업점, 인터넷뱅킹, AXful뱅킹</p>
                    <p className="mt-2 text-kb-text-muted">변경 후</p>
                    <p className="ml-4">ㅇ 신규 : 인터넷뱅킹, AXful뱅킹, 콜센터</p>
                    <p className="ml-4">ㅇ 해지 : 영업점, 인터넷뱅킹, AXful뱅킹</p>
                    <p className="ml-4">ㅇ 만기해지방법 변경 : 영업점, 인터넷뱅킹, AXful뱅킹, 콜센터</p>
                    <p className="mt-2">- 적용대상 : 신규계좌 및 기존계좌</p>
                  </div>
                </div>

                {/* 2018.10.30 */}
                <div className="border border-kb-border rounded-xl overflow-hidden mb-3">
                  <div className="bg-kb-beige-light px-4 py-2 font-semibold text-kb-text border-b border-kb-border">
                    2018.10.30 변경 — 중도해지이율 변경
                  </div>
                  <div className="px-4 py-3 space-y-3">
                    <div>
                      <p className="text-kb-text-muted mb-1">변경 전 (2018.09.05 기준, 세금공제전, 단위:연%)</p>
                      <table className="w-full border-collapse text-[12px]">
                        <thead>
                          <tr className="bg-kb-beige-light">
                            <th className="border border-kb-border px-3 py-1.5 text-left font-semibold">예치기간</th>
                            <th className="border border-kb-border px-3 py-1.5 text-left font-semibold">이 율</th>
                          </tr>
                        </thead>
                        <tbody>
                          {[
                            { period: '1개월미만', rate: '0.1' },
                            { period: '1개월이상 ~ 3개월 미만', rate: '기본이율 × 50% × 경과월수/계약월수 (단, 최저금리 0.3)' },
                            { period: '3개월이상', rate: '기본이율 × 50% × 경과월수/계약월수 (단, 최저금리 0.5)' },
                          ].map((r, i) => (
                            <tr key={i}>
                              <td className="border border-kb-border px-3 py-1.5">{r.period}</td>
                              <td className="border border-kb-border px-3 py-1.5">{r.rate}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <div>
                      <p className="text-kb-text-muted mb-1">변경 후 (2018.10.30 기준, 세금공제전, 단위:연%)</p>
                      <table className="w-full border-collapse text-[12px]">
                        <thead>
                          <tr className="bg-kb-beige-light">
                            <th className="border border-kb-border px-3 py-1.5 text-left font-semibold">예치기간</th>
                            <th className="border border-kb-border px-3 py-1.5 text-left font-semibold">이 율</th>
                          </tr>
                        </thead>
                        <tbody>
                          {[
                            { period: '1개월미만', rate: '0.1' },
                            { period: '1개월이상 ~ 3개월미만', rate: '기본이율 × 50% × 경과월수/계약월수 (단, 최저금리 0.3)' },
                            { period: '3개월이상 ~ 6개월미만', rate: '기본이율 × 50% × 경과월수/계약월수 (단, 최저금리 0.5)' },
                            { period: '6개월이상 ~ 8개월미만', rate: '기본이율 × 60% × 경과월수/계약월수 (단, 최저금리 0.5)' },
                            { period: '8개월이상 ~ 10개월미만', rate: '기본이율 × 70% × 경과월수/계약월수 (단, 최저금리 0.5)' },
                            { period: '10개월이상 ~ 11개월미만', rate: '기본이율 × 80% × 경과월수/계약월수 (단, 최저금리 0.5)' },
                            { period: '11개월이상', rate: '기본이율 × 90% × 경과월수/계약월수 (단, 최저금리 0.5)' },
                          ].map((r, i) => (
                            <tr key={i}>
                              <td className="border border-kb-border px-3 py-1.5">{r.period}</td>
                              <td className="border border-kb-border px-3 py-1.5">{r.rate}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                      <p className="mt-2 text-kb-text-muted">- 적용대상 : 변경 시행일 이후 신규(재예치)분부터 적용</p>
                    </div>
                  </div>
                </div>
              </section>
            </div>
          )}

          {/* ── 약관·상품설명서 탭 ── */}
          {activeTab === '약관·상품설명서' && (
            <div className="py-5">
              <div className="bg-[#F5F5F5] border border-kb-border rounded-xl p-4">
                <div className="flex flex-wrap gap-2">
                  {[
                    '거치식예금약관',
                    `AXful Star 정기예금 특약`,
                    '예금거래기본약관',
                    `AXful Star 정기예금 상품설명서`,
                  ].map(doc => (
                    <button key={doc}
                      onClick={() => alert(`${doc} 파일을 다운로드합니다.`)}
                      className="flex items-center gap-2 border border-kb-border rounded-lg bg-white px-4 py-2 text-[13px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                      <span className="w-6 h-6 flex items-center justify-center border border-kb-border bg-white flex-shrink-0">
                        <svg viewBox="0 0 14 16" fill="none" className="w-3.5 h-4" stroke="currentColor" strokeWidth="1.5">
                          <rect x="1" y="1" width="10" height="14" rx="1"/>
                          <line x1="7" y1="5" x2="7" y2="11"/><polyline points="4,9 7,12 10,9"/>
                        </svg>
                      </span>
                      {doc}
                    </button>
                  ))}
                </div>
              </div>

              {/* 하단 버튼 */}
              <div className="flex justify-center gap-2 mt-8">
                <Link href={`/products/deposit/join/${id}`}
                  className="bg-kb-primary px-10 py-3 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity">
                  온라인가입
                </Link>
                <Link href="/products/deposit/list"
                  className="border border-kb-border rounded-xl px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                  목록
                </Link>
                <button onClick={() => window.print()}
                  className="border border-kb-border rounded-xl px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                  인쇄
                </button>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
    </>
  )
}
