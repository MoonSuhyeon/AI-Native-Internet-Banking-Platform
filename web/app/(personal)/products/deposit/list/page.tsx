'use client'
import { KB_BORDER, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_TEXT_LIGHT } from '@/lib/theme'

import Link from 'next/link'
import { useState, useEffect, Suspense } from 'react'
import { useSearchParams } from 'next/navigation'
import CartModal from '@/components/products/CartModal'
import CompareModal from '@/components/products/CompareModal'
import DepositSidebar from '@/components/products/DepositSidebar'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import { fetchDepositProducts, toDepositProductCard } from '@/lib/deposit-api'

type Product = {
  id: string
  name: string
  channel: string
  desc?: string
  period?: string
  rate?: string
  isNew?: boolean
  canApply?: boolean
}

const DEPOSIT_PRODUCTS: Product[] = [
  {
    id: 'axful-regular',
    name: 'AXful 정기예금',
    channel: '인터넷뱅킹',
    desc: 'Digital AXful의 대표 정기예금',
    period: '1~36개월',
    rate: '연 2.4%~2.9%',
    canApply: true,
  },
  {
    id: 'axful-super',
    name: 'AXful 수퍼정기예금(개인)',
    channel: '영업점',
    desc: '가입 조건을 직접 설계하는',
    period: '36개월 기준',
    rate: '연 2.2%~2.3%',
    canApply: true,
  },
  {
    id: 'regular',
    name: '일반정기예금',
    channel: '영업점',
    desc: '목돈 모아 안정수익 마음든든',
    period: '36개월 기준',
    rate: '연 2.25%~2.25%',
    canApply: true,
  },
  {
    id: 'axful-youth',
    name: 'AXful 청년도약계좌',
    channel: '인터넷뱅킹',
    desc: '청년의 자산형성을 응원합니다',
    period: '60개월',
    rate: '연 3.5%~6.0%',
    canApply: true,
  },
]

// 자유적금 (saving_type = FREE)
const FREE_SAVINGS_PRODUCTS: Product[] = [
  {
    id: 'axful-free',
    name: 'AXful 내맘대로적금',
    channel: '인터넷뱅킹',
    desc: '누구나 쉽게 자유롭게 DIY',
    period: '36개월 기준',
    rate: '연 2.95%~3.55%',
    canApply: true,
  },
  {
    id: 'axful-dollar',
    name: 'AXful 달러자적금',
    channel: '인터넷뱅킹',
    desc: '달러 가치상승 응원하는 두배이율',
    period: '6개월 기준',
    rate: '연 1%~7.2%',
    isNew: true,
    canApply: true,
  },
  {
    id: 'axful-green',
    name: 'AXful 맑은하늘적금',
    channel: '인터넷뱅킹',
    desc: '맑은하늘 인증코드 금리도 Up',
    period: '36개월 기준',
    rate: '연 2.85%~3.85%',
    canApply: true,
  },
  {
    id: 'axful-star-savings',
    name: 'AXful 특★한 적금',
    channel: '인터넷뱅킹',
    desc: '고객 모두의 높은 수익을 위한 특별한 준비',
    period: '1개월 기준',
    rate: '연 2%~6%',
    canApply: true,
  },
]

// 정기적금 (saving_type = REGULAR)
const REGULAR_SAVINGS_PRODUCTS: Product[] = [
  {
    id: 'axful-soldier',
    name: 'AXful 장병내일준비적금',
    channel: '인터넷뱅킹',
    desc: '국군장병 미래대비 앞날준비',
    period: '24개월 기준',
    rate: '연 5%~10.5%',
    canApply: true,
  },
  {
    id: 'axful-work',
    name: 'AXful 직장인우대적금',
    channel: '인터넷뱅킹',
    desc: '급여이체 고객 우대금리 제공',
    period: '12~36개월',
    rate: '연 3.2%~4.5%',
    canApply: true,
  },
  {
    id: 'axful-dream',
    name: 'AXful 꿈적금',
    channel: '인터넷뱅킹',
    desc: '목표금액 설정으로 꿈을 향해 꾸준히',
    period: '12~36개월',
    rate: '연 3.0%~4.2%',
    canApply: true,
  },
  {
    id: 'axful-together',
    name: 'AXful 함께적금',
    channel: '인터넷뱅킹',
    desc: '가족·연인과 함께 모으는 공동 적금',
    period: '6~24개월',
    rate: '연 2.8%~4.0%',
    isNew: true,
    canApply: true,
  },
]

const CHECKING_PRODUCTS: Product[] = [
  {
    id: 'axful-sok',
    name: 'AXful 쏙머니통장',
    channel: '영업점',
    desc: '쇼핑용 아껴 쏙머니가 쏙~',
    isNew: true,
    canApply: true,
  },
  {
    id: 'election',
    name: '당선통장',
    channel: '영업점',
    desc: '각종 공직선거 입후보자 및 입후보예정자 선거자금 관리 통장',
    isNew: true,
  },
  {
    id: 'axful-living',
    name: 'AXful 생계비계좌',
    channel: '인터넷뱅킹',
    desc: '생계 유지에 필요한 자금을 최대 250만원까지 보호하는 압류방지 전용통장',
    isNew: true,
  },
  {
    id: 'axful-gs',
    name: 'AXful GS Pay통장',
    channel: '인터넷뱅킹',
    desc: 'GS25와의 만남으로 더 풍성해진 혜택',
    isNew: true,
  },
  {
    id: 'monimo-daily',
    name: '모니모 AXful 매일이자 통장',
    channel: '영업점',
    desc: '하루만 넣어도 이자가 쌓이는',
    canApply: true,
  },
  {
    id: 'axful-moim',
    name: 'AXful 모임금고',
    channel: '인터넷뱅킹',
    desc: '고인 여유자금을 연 2.0%(최대 1천만원)로 불리는',
  },
  {
    id: 'axful-star-account',
    name: 'AXful 스타통장',
    channel: '인터넷뱅킹',
    desc: 'Digital AXful의 대표 통장',
  },
  {
    id: 'axful-wallet',
    name: 'AXful 지갑통장',
    channel: '인터넷뱅킹',
    desc: '일상의 모든 지출을 한 곳에서 관리',
    isNew: true,
  },
  {
    id: 'axful-free-account',
    name: 'AXful 자유입출금통장',
    channel: '인터넷뱅킹',
    desc: '언제든 자유롭게 입출금 가능한 기본 통장',
    canApply: true,
  },
  {
    id: 'axful-youth-account',
    name: 'AXful 청년우대통장',
    channel: '인터넷뱅킹',
    desc: '만 19~34세 청년을 위한 우대금리 제공',
    canApply: true,
  },
]

const HOUSING_PRODUCTS: Product[] = [
  {
    id: 'housing-savings',
    name: '주택청약종합저축',
    channel: '인터넷뱅킹',
    period: '24개월 기준',
    rate: '연 3.1%',
    canApply: true,
  },
  {
    id: 'youth-housing',
    name: '청년 주택드림 청약통장',
    channel: '인터넷뱅킹',
    period: '24개월 기준',
    rate: '연 3.1%~4.5%',
    canApply: true,
  },
]

type Tab = '예금' | '정기적금' | '자유적금' | '입출금자유' | '주택청약'
const TABS: Tab[] = ['예금', '정기적금', '자유적금', '입출금자유', '주택청약']

const TAB_ALIASES: Record<string, Tab> = {
  deposit: '예금',
  'regular-savings': '정기적금',
  regular: '정기적금',
  'free-savings': '자유적금',
  free: '자유적금',
  checking: '입출금자유',
  account: '입출금자유',
  demand: '입출금자유',
  subscription: '주택청약',
  housing: '주택청약',
}

function resolveTabParam(raw: string | null): Tab | null {
  if (!raw) return null
  const decoded = decodeURIComponent(raw).trim()
  if ((TABS as readonly string[]).includes(decoded)) return decoded as Tab
  return TAB_ALIASES[decoded.toLowerCase()] ?? null
}

const DEPOSIT_PRODUCT_TYPES = ['전체', '정기예금', '지수연동예금', '시장성예금']
const JOIN_METHODS = ['전체', '인터넷뱅킹', 'AXful Next', '영업점']
const JOIN_PERIODS = ['전체', '3개월 미만', '3-6개월 미만', '6-12개월 미만', '12-24개월 미만', '24개월 이상']

function DepositListPageInner() {
  const searchParams = useSearchParams()
  const [tab, setTab] = useState<Tab>('예금')
  const [apiProductsMap, setApiProductsMap] = useState<Partial<Record<Tab, Product[]>>>({})

  // URL ?tab= 파라미터로 초기 탭 설정 (클라이언트 전용)
  useEffect(() => {
    const nextTab = resolveTabParam(searchParams.get('tab'))
    if (nextTab) setTab(nextTab)
  }, [searchParams])

  useEffect(() => {
    let cancelled = false
    async function loadProducts() {
      try {
        const products = await fetchDepositProducts({ productStatus: 'SELLING' })
        if (cancelled) return

        const next: Partial<Record<Tab, Product[]>> = {
          '예금': [],
          '정기적금': [],
          '자유적금': [],
          '입출금자유': [],
          '주택청약': [],
        }

        products.forEach(product => {
          const card = toDepositProductCard(product)
          if (product.productType === 'SUBSCRIPTION') {
            next['주택청약']?.push(card)
          } else if (product.productType === 'SAVINGS') {
            if (product.savingType === 'REGULAR') {
              next['정기적금']?.push(card)
            } else {
              next['자유적금']?.push(card)
            }
          } else if (product.productName.includes('통장')) {
            next['입출금자유']?.push(card)
          } else {
            next['예금']?.push(card)
          }
        })

        setApiProductsMap(next)
      } catch {
        setApiProductsMap({})
      }
    }

    loadProducts()
    return () => {
      cancelled = true
    }
  }, [])
  const [productType, setProductType] = useState('전체')
  const [joinMethod, setJoinMethod] = useState('전체')
  const [joinPeriod, setJoinPeriod] = useState('전체')
  const [searchName, setSearchName] = useState('')
  const [cartProduct, setCartProduct] = useState<string | null>(null)
  // 비교하기 — 챗봇에는 비교 기능이 있었지만, 상품을 눈앞에 두고 고르는 화면에서는
  // 쓸 수 없었다. 셋까지만 받는다 — 그 이상은 표가 가로로 넘쳐 읽기 어려워진다.
  const [compareIds, setCompareIds] = useState<string[]>([])
  const [compareOpen, setCompareOpen] = useState(false)

  const productsMap: Record<Tab, Product[]> = {
    '예금': DEPOSIT_PRODUCTS,
    '정기적금': REGULAR_SAVINGS_PRODUCTS,
    '자유적금': FREE_SAVINGS_PRODUCTS,
    '입출금자유': CHECKING_PRODUCTS,
    '주택청약': HOUSING_PRODUCTS,
  }
  const products = (apiProductsMap[tab]?.length ? apiProductsMap[tab] : productsMap[tab])
    .filter(product => !searchName.trim() || product.name.includes(searchName.trim()))

  const showPeriodFilter = tab === '예금' || tab === '정기적금' || tab === '자유적금'
  const showProductTypeFilter = tab === '예금'
  const showHousingNote = tab === '주택청약'

  const MAX_COMPARE = 3
  const compareProducts = compareIds
    .map(id => products.find(p => p.id === id))
    .filter((p): p is Product => Boolean(p))

  function toggleCompare(id: string) {
    setCompareIds(prev => prev.includes(id)
      ? prev.filter(x => x !== id)
      : prev.length >= MAX_COMPARE ? prev : [...prev, id])
  }

  function handleTabChange(t: Tab) {
    setTab(t)
    setCompareIds([])
    setProductType('전체')
    setJoinMethod('전체')
    setJoinPeriod('전체')
    setSearchName('')
  }

  return (
    <>
    {cartProduct && (
      <CartModal productName={cartProduct} onClose={() => setCartProduct(null)} />
    )}
    {compareOpen && compareProducts.length > 0 && (
      <CompareModal
        products={compareProducts}
        onRemove={id => setCompareIds(prev => prev.filter(x => x !== id))}
        onClose={() => setCompareOpen(false)}
      />
    )}
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <DepositSidebar />

        {/* 본문 */}
        <main className="flex-1 pl-8 pt-4 pb-12">
          {/* 브레드크럼 */}
          <AutoBreadcrumb
            className="flex justify-end items-center mb-2 text-[12px] text-kb-text-muted gap-1"
            trailing={<Link href="#" className="font-medium hover:underline" style={{ color: KB_PRIMARY }}>도움말</Link>}
          />

          <h1 className="text-[22px] font-bold text-kb-text mb-5">예금 상품/가입</h1>

          {/* 탭 */}
          <div className="flex border-b mb-5" style={{ borderColor: KB_PRIMARY_BORDER }}>
            {TABS.map(t => (
              <button
                key={t}
                onClick={() => handleTabChange(t)}
                className="px-8 py-3 text-[14px] font-medium transition-colors border-b-2 -mb-px"
                style={tab === t
                  ? { borderColor: KB_PRIMARY, color: KB_PRIMARY, fontWeight: 700, backgroundColor: 'white' }
                  : { borderColor: 'transparent', color: KB_TEXT_LIGHT, backgroundColor: KB_PRIMARY_SURFACE }}
              >
                {t}
              </button>
            ))}
          </div>

          {/* 필터 */}
          <div className="rounded-xl p-5 mb-5" style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_SURFACE }}>
            <div className="grid grid-cols-[100px_1fr] gap-y-3 text-[13px] items-center">
              <span className="font-semibold text-kb-text">• 상품명</span>
              <input
                type="text"
                value={searchName}
                onChange={e => setSearchName(e.target.value)}
                className="border rounded-lg px-3 py-1.5 text-[13px] w-64 outline-none bg-white"
                style={{ borderColor: KB_BORDER }}
              />

              {showProductTypeFilter && (
                <>
                  <span className="font-semibold text-kb-text">• 상품유형</span>
                  <div className="flex items-center gap-5">
                    {DEPOSIT_PRODUCT_TYPES.map(v => (
                      <label key={v} className="flex items-center gap-1.5 cursor-pointer text-kb-text-body">
                        <input type="radio" name="productType" checked={productType === v}
                          onChange={() => setProductType(v)} style={{ accentColor: KB_PRIMARY }} />
                        {v}
                      </label>
                    ))}
                  </div>
                </>
              )}

              <span className="font-semibold text-kb-text">• 가입방법</span>
              <div className="flex items-center gap-5">
                {JOIN_METHODS.map(v => (
                  <label key={v} className="flex items-center gap-1.5 cursor-pointer text-kb-text-body">
                    <input type="radio" name="joinMethod" checked={joinMethod === v}
                      onChange={() => setJoinMethod(v)} style={{ accentColor: KB_PRIMARY }} />
                    {v}
                  </label>
                ))}
              </div>

              {showPeriodFilter && (
                <>
                  <span className="font-semibold text-kb-text">• 가입기간</span>
                  <div className="flex items-center gap-4 flex-wrap">
                    {JOIN_PERIODS.map(v => (
                      <label key={v} className="flex items-center gap-1.5 cursor-pointer text-kb-text-body">
                        <input type="radio" name="joinPeriod" checked={joinPeriod === v}
                          onChange={() => setJoinPeriod(v)} style={{ accentColor: KB_PRIMARY }} />
                        {v}
                      </label>
                    ))}
                  </div>
                </>
              )}
            </div>
            <div className="flex justify-center mt-4">
              <button className="px-16 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity"
                style={{ backgroundColor: KB_PRIMARY }}>
                조회
              </button>
            </div>
          </div>

          {/* 목록 헤더 */}
          <div className="flex justify-between items-center mb-2">
            <p className="text-[13px] text-kb-text">
              상품목록 <span className="font-bold" style={{ color: KB_PRIMARY }}>{products.length}</span>건
            </p>
            <select className="border rounded-lg px-2 py-1 text-[12px] outline-none" style={{ borderColor: KB_PRIMARY_BORDER }}>
              <option>금리순</option>
              <option>기간순</option>
              <option>상품명순</option>
            </select>
          </div>

          {/* 상품 목록 */}
          <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            {products.map((product, idx) => (
              <div key={product.id}
                className="py-5 px-5 hover:bg-kb-primary-surface transition-colors"
                style={{ borderBottom: idx < products.length - 1 ? `1px solid ${KB_PRIMARY_BORDER}` : 'none' }}>
                <div className="flex items-center justify-between gap-4">
                  <div className="flex-1">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-[11px] rounded px-1.5 py-0.5 text-kb-text-muted"
                        style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_BG }}>
                        {product.channel}
                      </span>
                      {product.isNew && (
                        <span className="text-[11px] rounded px-1.5 py-0.5 font-bold text-white"
                          style={{ backgroundColor: KB_PRIMARY }}>NEW</span>
                      )}
                    </div>
                    <Link href={`/products/deposit/${product.id}`}
                      className="text-[16px] font-bold text-kb-text hover:underline">
                      {product.name}
                    </Link>
                    {product.desc && (
                      <p className="text-[13px] text-kb-text-muted mt-0.5">{product.desc}</p>
                    )}
                    {(product.period || product.rate) && (
                      <p className="text-[13px] mt-1">
                        {product.period && <span className="text-kb-text-muted">{product.period}, </span>}
                        {product.rate && <span className="font-bold" style={{ color: KB_PRIMARY }}>{product.rate}</span>}
                      </p>
                    )}
                  </div>
                  <div className="flex items-center gap-2 flex-shrink-0">
                    <button
                      onClick={() => setCartProduct(product.name)}
                      className="border rounded-lg px-3 py-1.5 text-[13px] hover:bg-kb-primary-bg transition-colors"
                      style={{ borderColor: KB_PRIMARY_BORDER }}
                    >
                      🛒
                    </button>
                    <button
                      onClick={() => toggleCompare(product.id)}
                      disabled={!compareIds.includes(product.id) && compareIds.length >= MAX_COMPARE}
                      title={compareIds.length >= MAX_COMPARE && !compareIds.includes(product.id)
                        ? `한 번에 ${MAX_COMPARE}개까지 비교할 수 있습니다.` : undefined}
                      className="border rounded-lg px-4 py-1.5 text-[13px] font-medium hover:bg-kb-primary-bg transition-colors disabled:opacity-40"
                      style={compareIds.includes(product.id)
                        ? { borderColor: KB_PRIMARY, backgroundColor: KB_PRIMARY_BG, color: KB_PRIMARY, fontWeight: 700 }
                        : { borderColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                      {compareIds.includes(product.id) ? '비교 담김' : '비교하기'}
                    </button>
                    {product.canApply && (
                      <Link href={`/products/deposit/join/${product.id}`}
                        className="rounded-xl px-5 py-1.5 text-[13px] font-bold text-white hover:opacity-85 transition-opacity"
                        style={{ backgroundColor: KB_PRIMARY }}>
                        가입하기
                      </Link>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>

          {/* 주택청약 주석 */}
          {showHousingNote && (
            <p className="text-[12px] text-kb-text-muted text-right mt-4">
              * 2026.05.25 기준, 세금공제전, 우대금리포함
            </p>
          )}

          {/* 페이지네이션 */}
          <div className="flex justify-center mt-8 gap-1">
            {/* 목록을 한 번에 다 보여주므로 쪽이 하나뿐이다. 누를 것이 없어 표시로 둔다. */}
            <span className="w-8 h-8 text-[13px] rounded-lg font-bold text-white flex items-center justify-center"
              style={{ backgroundColor: KB_PRIMARY }}>1</span>
          </div>
        </main>
      </div>

      {/* 비교함. 담기만 하고 열 곳이 없으면 담은 것이 어디 갔는지 알 수 없다. */}
      {compareIds.length > 0 && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[150] flex items-center gap-3
                        rounded-xl px-5 py-3 shadow-xl bg-white"
          style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
          <span className="text-[13px] text-kb-text">
            비교함 <b style={{ color: KB_PRIMARY }}>{compareIds.length}</b>/{MAX_COMPARE}
          </span>
          <button onClick={() => setCompareOpen(true)}
            className="rounded-lg px-4 py-1.5 text-[13px] font-bold text-white hover:opacity-85 transition-opacity"
            style={{ backgroundColor: KB_PRIMARY }}>
            비교하기
          </button>
          <button onClick={() => { setCompareIds([]); setCompareOpen(false) }}
            className="text-[13px]" style={{ color: KB_TEXT_LIGHT }}>
            비우기
          </button>
        </div>
      )}
    </div>
    </>
  )
}

export default function DepositListPage() {
  return <Suspense><DepositListPageInner /></Suspense>
}
