'use client'
import { KB_PRIMARY,KB_PRIMARY_BORDER } from '@/lib/theme'
import type { Loan } from '@/lib/generated'

import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { Suspense, useState, useEffect } from 'react'
import LoanSidebar from '@/components/inquiry/LoanSidebar'
import { loanProductApi, bpsToRate, formatAmount } from '@/lib/loan-api'

const FILTER_TABS = [
  { label: '신용대출',              href: '/products/loan/credit',   loanTypeCd: 'CREDIT' },
  { label: '담보대출',              href: '/products/loan/mortgage',  loanTypeCd: 'MORTGAGE' },
  { label: '전월세/반환보증',        href: '/products/loan/jeonse',    loanTypeCd: 'CHARTER' },
  { label: '자동차대출',            href: '/products/loan/auto',      loanTypeCd: 'AUTO' },
  { label: '집단중도금/이주비대출',   href: '/products/loan/group',     loanTypeCd: 'GROUP' },
  { label: '주택도시기금대출',       href: '/products/loan/khfc',      loanTypeCd: 'KHFC' },
]

const PRODUCT_TYPES = ['전체', '직장인', '전문직', '사업자', '연금수급자']
const JOIN_METHODS  = ['전체', '인터넷뱅킹', '영업점', '스마트대출']

// 목록 응답에는 minRateBps·maxRateBps 가 없다. 예전에는 그 필드를 적어 두고
// `?? baseRateBps` 로 우회했는데, 타입을 맞추면 우회가 필요 없다.
type Product = Loan['LoanProductListItem']

interface Props {
  loanTypeCd: string
  pageTitle: string
  activeHref: string
}

/**
 * useSearchParams 를 쓰는 부분을 Suspense 로 감싼다.
 *
 * Next 14 는 이 훅이 정적 렌더링을 막는다고 보고 빌드를 세운다 — 경계가 없으면
 * 배포 빌드에서 터진다. 타입 검사만으로는 드러나지 않는다.
 */
export default function LoanListPage(props: Props) {
  return <Suspense><LoanListPageInner {...props} /></Suspense>
}

function LoanListPageInner({ loanTypeCd, pageTitle, activeHref }: Props) {
  const [productType, setProductType] = useState('전체')
  const [joinMethod,  setJoinMethod]  = useState('전체')
  // 대출 랜딩의 검색창이 ?q= 로 검색어를 들고 온다. 초기값으로 받지 않으면
  // 검색해서 왔는데 전체 목록이 나온다.
  const searchParams = useSearchParams()
  const [searchName,  setSearchName]  = useState(searchParams.get('q') ?? '')
  const [sortBy,      setSortBy]      = useState('판매순')
  const [page,        setPage]        = useState(0)
  const [products,    setProducts]    = useState<Product[]>([])
  const [totalPages,  setTotalPages]  = useState(1)
  const [loading,     setLoading]     = useState(true)
  const [error,       setError]       = useState('')

  async function fetchProducts() {
    setLoading(true)
    setError('')
    try {
      const { data: res } = await loanProductApi.list({
        loanTypeCd,
        prodStatusCd: 'ACTIVE',
        page,
        size: 10,
      })
      const d = res.data
      setProducts(d.items ?? [])
      setTotalPages(Math.max(1, Math.ceil((d.totalCount ?? 0) / (d.size || 10))))
    } catch {
      setError('상품 목록을 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchProducts() }, [page])

  const displayed = products.filter(p => !searchName || p.prodName.includes(searchName))

  return (
    <main className="pb-16">
      <div className="max-w-kb-container mx-auto px-6 pt-6">
        <nav className="text-[12px] text-kb-text-muted mb-4 flex items-center gap-1">
          <Link href="/" className="hover:underline">개인뱅킹</Link>
          <span>›</span>
          <Link href="/products/deposit/list" className="hover:underline">금융상품</Link>
          <span>›</span>
          <Link href="/products/loan" className="hover:underline">대출</Link>
          <span>›</span>
          <span className="text-kb-text font-medium">{pageTitle}</span>
        </nav>

        <div className="flex gap-8">
          <LoanSidebar />

          <div className="flex-1 min-w-0">
            <h1 className="text-[26px] font-bold text-kb-text mb-5">{pageTitle}</h1>

            {/* 카테고리 탭 */}
            <div className="flex border-b mb-6" style={{ borderColor: KB_PRIMARY_BORDER }}>
              {FILTER_TABS.map(tab => (
                <Link key={tab.href} href={tab.href}
                  className={`px-5 py-3 text-[14px] font-medium whitespace-nowrap transition-colors border-b-2 -mb-px
                    ${tab.href === activeHref
                      ? 'border-kb-primary text-kb-primary font-bold'
                      : 'border-transparent text-kb-text-muted hover:text-kb-text'}`}>
                  {tab.label}
                </Link>
              ))}
            </div>

            {/* 검색 폼 */}
            <div className="border border-kb-primary-border p-5 mb-6">
              <div className="flex items-center gap-4 mb-4">
                <label className="w-20 text-[13px] font-medium text-kb-text flex-shrink-0">상품명</label>
                <input type="text" value={searchName} onChange={e => setSearchName(e.target.value)}
                  placeholder="상품명을 입력하세요"
                  className="flex-1 border border-kb-primary-border px-3 py-2 text-[13px] focus:outline-none focus:border-kb-text" />
              </div>
              <div className="flex items-center gap-4 mb-4">
                <span className="w-20 text-[13px] font-medium text-kb-text flex-shrink-0">상품유형</span>
                <div className="flex gap-5">
                  {PRODUCT_TYPES.map(type => (
                    <label key={type} className="flex items-center gap-1.5 cursor-pointer">
                      <input type="radio" name="productType" value={type} checked={productType === type}
                        onChange={() => setProductType(type)} className="accentColor: KB_PRIMARY" />
                      <span className="text-[13px] text-kb-text-body">{type}</span>
                    </label>
                  ))}
                </div>
              </div>
              <div className="flex items-center gap-4 mb-5">
                <span className="w-20 text-[13px] font-medium text-kb-text flex-shrink-0">가입방법</span>
                <div className="flex gap-5 flex-wrap">
                  {JOIN_METHODS.map(method => (
                    <label key={method} className="flex items-center gap-1.5 cursor-pointer">
                      <input type="radio" name="joinMethod" value={method} checked={joinMethod === method}
                        onChange={() => setJoinMethod(method)} className="accentColor: KB_PRIMARY" />
                      <span className="text-[13px] text-kb-text-body">{method}</span>
                    </label>
                  ))}
                </div>
              </div>
              <div className="flex justify-center">
                <button onClick={() => { setPage(0); fetchProducts() }}
                  className="px-12 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity" style={{ backgroundColor: KB_PRIMARY }}>
                  조회
                </button>
              </div>
            </div>

            {/* 결과 헤더 */}
            <div className="flex items-center justify-between mb-3">
              <p className="text-[13px] text-kb-text">
                상품목록 <span className="font-bold">{displayed.length}</span>건
              </p>
              <select value={sortBy} onChange={e => setSortBy(e.target.value)}
                className="border border-kb-primary-border text-[13px] px-2 py-1.5 focus:outline-none">
                <option>판매순</option>
                <option>금리순</option>
                <option>한도순</option>
              </select>
            </div>

            {loading && <p className="py-12 text-center text-[13px] text-kb-text-muted">불러오는 중...</p>}
            {error   && <p className="py-12 text-center text-[13px] text-kb-red">{error}</p>}

            {!loading && !error && (
              <>
                <div className="border-t-2 border-kb-primary divide-y divide-kb-border">
                  {displayed.length === 0 && (
                    <p className="py-12 text-center text-[13px] text-kb-text-muted">조회된 상품이 없습니다.</p>
                  )}
                  {displayed.map(product => (
                    <div key={product.prodId} className="py-5 flex items-center gap-5">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-1.5">
                          <span className="text-[11px] font-bold px-2 py-0.5 bg-kb-primary text-white">
                            인터넷뱅킹
                          </span>
                          <Link href={`${activeHref}/${product.prodId}`}
                            className="text-[16px] font-bold text-kb-text hover:underline">
                            {product.prodName}
                          </Link>
                        </div>
                        <div className="flex items-center gap-4 text-[13px]">
                          <span className="text-kb-text-muted">
                            최고 <span className="font-bold text-kb-text">{formatAmount(product.maxAmount)}</span>
                          </span>
                          <span className="text-kb-text-muted">
                            연 {bpsToRate(product.baseRateBps)}%
                          </span>
                          <span className="text-kb-text-muted">{product.minPeriodMo}~{product.maxPeriodMo}개월</span>
                        </div>
                      </div>
                      <div className="flex items-center gap-2 flex-shrink-0">
                        <Link href={`${activeHref}/${product.prodId}`}
                          className="px-5 py-2 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 transition-colors whitespace-nowrap">
                          상세보기
                        </Link>
                      </div>
                    </div>
                  ))}
                </div>

                {totalPages > 1 && (
                  <div className="flex justify-center items-center gap-1 mt-8">
                    <button onClick={() => setPage(p => Math.max(0, p - 1))}
                      className="w-8 h-8 flex items-center justify-center border border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg">‹</button>
                    {Array.from({ length: totalPages }, (_, i) => i).map(n => (
                      <button key={n} onClick={() => setPage(n)}
                        className={`w-8 h-8 flex items-center justify-center text-[13px] border transition-colors
                          ${page === n ? 'border-kb-primary bg-kb-primary text-white font-bold' : 'border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'}`}>
                        {n + 1}
                      </button>
                    ))}
                    <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                      className="w-8 h-8 flex items-center justify-center border border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg">›</button>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </main>
  )
}
