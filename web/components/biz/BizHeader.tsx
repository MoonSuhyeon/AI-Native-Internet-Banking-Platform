'use client'

import Link from 'next/link'
import { useState, useRef, useEffect } from 'react'
import { usePathname } from 'next/navigation'

/** 기업 사이트에 실제로 있는 화면. 없는 것을 넣으면 다시 죽은 링크가 된다. */
const BIZ_SERVICES = [
  { label: '기업 로그인',            href: '/biz/login' },
  { label: '금융인증서 발급',        href: '/biz/cert/fin-cert-issue' },
  { label: '금융인증서 관리',        href: '/biz/cert/fin-cert-management' },
  { label: '공동인증서 발급',        href: '/biz/cert/joint-cert-issue' },
  { label: '공동인증서 관리',        href: '/biz/cert/joint-cert-management' },
]

type MenuItem = { label: string; arrow?: boolean; href?: string; active?: boolean }
type MenuColumn = { header?: string; items: MenuItem[] }

const BIZ_GNB_MENUS: {
  id: string
  label: string
  columns: MenuColumn[]
}[] = [
  { id: 'mypage', label: '마이페이지', columns: [] },

  { id: 'transfer', label: '조회/이체', columns: [
    { items: [
      { label: '조회', arrow: true, active: true },
      { label: '이체', arrow: true },
    ]},
    { items: [
      { label: '계좌조회', arrow: true, active: true },
      { label: '거래내역조회', arrow: true },
      { label: '증명서/확인증 발급', arrow: true },
      { label: '로그인 없이 조회', arrow: true },
      { label: '기타 조회', arrow: true },
    ]},
    { items: [
      { label: '전체계좌조회' },
      { label: '통합계좌조회(CMS)' },
      { label: 'AXful금융그룹조회' },
      { label: '계좌통합관리서비스(어카운트인포)' },
    ]},
  ]},

  { id: 'banking', label: '뱅킹업무', columns: [
    { items: [
      { label: '자금관리', arrow: true, active: true },
      { label: '공과금', arrow: true },
      { label: '예금/신탁', arrow: true },
      { label: '대출', arrow: true },
      { label: '펀드', arrow: true },
      { label: '보험/공제', arrow: true },
      { label: '전자세금계산서 발행', arrow: true },
    ]},
    { items: [
      { label: '조회', arrow: true, active: true },
      { label: '나의 사업장 조회', arrow: true },
      { label: '자금관리서비스 안내/가입' },
      { label: '자금관리서비스 변경관리', arrow: true },
      { label: '원화 가상계좌', arrow: true },
      { label: '외화 가상계좌', arrow: true },
      { label: 'Star CMS Global' },
      { label: '설치프로그램안내' },
    ]},
    { items: [
      { label: '통합계좌조회' },
      { label: '통합계좌 거래내역조회' },
      { label: '매입/매출 조회' },
      { label: '카드사용내역 조회' },
      { label: '카드한도조회' },
      { label: '바로ERP 일괄조회' },
      { label: '글로벌계좌발신 전문조회' },
    ]},
  ]},

  { id: 'products', label: '금융상품', columns: [
    { items: [
      { label: '금융상품 HOME', active: true },
      { label: '예금', arrow: true },
      { label: '외화예금', arrow: true },
      { label: '대출', arrow: true },
      { label: '입출금', arrow: true },
      { label: '펀드', arrow: true },
      { label: '신탁', arrow: true },
      { label: '보험/공제', arrow: true },
      { label: '쿠폰함' },
    ]},
  ]},

  { id: 'b2b', label: 'B2B', columns: [
    { items: [
      { label: '통합서비스', arrow: true, active: true },
      { label: '세금계산서 관리', arrow: true },
      { label: '구매기업', arrow: true },
      { label: '판매기업', arrow: true },
      { label: '전자어음 및 기타상품', arrow: true },
    ]},
    { items: [
      { label: 'My B2B', arrow: true, active: true },
      { label: '약정현황조회' },
      { label: '판매기업 통합조회', arrow: true },
      { label: '서비스신규약정', arrow: true },
      { label: '매출채권통지서비스', arrow: true },
      { label: 'B2B제증명서 발급', arrow: true },
      { label: 'B2B통합조회(금융결제원)', arrow: true },
      { label: 'B2B상품 안내', arrow: true },
      { label: '중계기업(e-MP)', arrow: true },
    ]},
    { items: [
      { label: '판매기업' },
      { label: '구매기업' },
    ]},
  ]},

  { id: 'fx', label: '외환', columns: [
    { items: [
      { label: '외환HOME', active: true },
      { label: '마이외환', arrow: true },
      { label: '환율/금리', arrow: true },
      { label: '외화송금', arrow: true },
      { label: '국내외화이체/외화예금입출금', arrow: true },
      { label: '외화현찰환전', arrow: true },
      { label: '수출입', arrow: true },
      { label: '해외투자/자본거래 신고', arrow: true },
      { label: '외국인직접투자/자본거래 신고', arrow: true },
      { label: 'FX/파생상품', arrow: true },
    ]},
  ]},

  { id: 'esg', label: '경영지원/ESG', columns: [
    { items: [
      { label: '정책자금 맞춤추천', arrow: true, active: true },
      { label: 'AXful ESG 자가진단 서비스', arrow: true },
      { label: 'AXful 탄소관리시스템' },
      { label: '사장님 필수 콘텐츠', arrow: true },
    ]},
    { items: [
      { label: '정책자금 맞춤추천 HOME', active: true },
      { label: '신청가능 정책자금' },
      { label: '전체 정책자금' },
      { label: '나의 관심정책자금' },
    ]},
  ]},

  { id: 'bizservice', label: '기업서비스', columns: [
    { items: [
      { label: '서비스이용', arrow: true, active: true },
      { label: '서비스안내', arrow: true },
      { label: '부가서비스', arrow: true },
      { label: '기업고객 우대제도', arrow: true },
    ]},
    { items: [
      { label: '부가세 매입자 납부특례 결제', arrow: true, active: true },
      { label: '팸뱅킹서비스', arrow: true },
      { label: '연계서비스', arrow: true },
      { label: 'ID통합 관리(CB)', arrow: true },
      { label: '가맹금 예치제도', arrow: true },
    ]},
    { items: [
      { label: '금(Gold)거래 결제' },
      { label: '구리/철/비철금속 거래 결제' },
      { label: '용역 거래 결제' },
      { label: '결제내역 조회' },
      { label: '전용계좌 전환등록' },
    ]},
  ]},

  { id: 'mgmt', label: '뱅킹관리', columns: [
    { items: [
      { label: '결제/권한관리', arrow: true, active: true },
      { label: '인터넷뱅킹 관리', arrow: true },
      { label: '자금관리', arrow: true },
      { label: '통지/편의서비스 신청', arrow: true },
      { label: '이용안내', arrow: true },
    ]},
    { items: [
      { label: '결재관리', arrow: true, active: true },
      { label: '사용자 관리', arrow: true },
    ]},
    { items: [
      { label: '결재진행 내역' },
      { label: '결재완료 내역' },
      { label: '권한설정 승인' },
    ]},
  ]},
]

export default function BizHeader() {
  const [activeMenu, setActiveMenu] = useState<string | null>(null)
  const [allServicesOpen, setAllServicesOpen] = useState(false)
  const currentMenu = BIZ_GNB_MENUS.find((m) => m.id === activeMenu)
  const pathname = usePathname()
  const isLoginPage = pathname === '/biz/login'

  const gnbContainerRef = useRef<HTMLDivElement>(null)
  const liRefs = useRef<(HTMLLIElement | null)[]>([])
  const [col1Width, setCol1Width] = useState(180)

  useEffect(() => {
    if (!activeMenu) return
    const refId = ['banking', 'products', 'b2b', 'fx', 'esg', 'bizservice', 'mgmt'].includes(activeMenu) ? 'transfer' : activeMenu
    const idx = BIZ_GNB_MENUS.findIndex((m) => m.id === refId)
    const li = liRefs.current[idx]
    const container = gnbContainerRef.current
    if (!li || !container) return
    const liRect = li.getBoundingClientRect()
    const containerRect = container.getBoundingClientRect()
    setCol1Width(liRect.right - containerRect.left - 48)
  }, [activeMenu])

  return (
    <header>
      {/* 상단 공통 바 */}
      <div className="border-b border-kb-border bg-white">
        <div className="max-w-kb-container mx-auto px-6 flex items-center h-[70px]">
          <Link href="/" className="flex items-center gap-3">
            <div className="w-[3px] self-stretch bg-kb-yellow" />
            <div className="flex flex-col leading-none">
              <span className="text-[22px] font-bold text-kb-text tracking-[0.05em]">AXful Bank</span>
            </div>
          </Link>
          <nav className="flex items-center ml-auto">
            {['개인', '기업'].map((item, i) => (
              <span key={item} className="flex items-center">
                <Link href={item === '개인' ? '/' : '/biz/login'}
                  className={`text-[15px] px-2 hover:text-kb-text transition-colors
                    ${item === '기업' ? 'text-kb-text font-semibold' : 'text-kb-text-muted'}`}>
                  {item}
                </Link>
                {i === 0 && <span className="text-kb-border text-[15px]">|</span>}
              </span>
            ))}
            <span className="text-kb-border text-[15px] mx-1">|</span>
            {['자산관리', '부동산', '퇴직연금', '카드'].map((item) => (
              <Link key={item} href="#"
                className="text-[15px] text-kb-text-muted px-2 hover:text-kb-text transition-colors">
                {item}
              </Link>
            ))}
            <span className="text-kb-border text-[15px] mx-1">|</span>
            {/* 전체서비스 — 기업 사이트에 실제로 있는 화면만 넣는다.
                예전에는 ▾ 만 있고 열릴 메뉴가 없었다. */}
            <div className="relative">
              <button onClick={() => setAllServicesOpen(v => !v)}
                aria-expanded={allServicesOpen}
                className="text-[15px] text-kb-text-muted px-2 hover:text-kb-text flex items-center gap-0.5">
                전체서비스 <span className="text-[10px]">{allServicesOpen ? '▴' : '▾'}</span>
              </button>
              {allServicesOpen && (
                <div className="absolute right-0 top-full mt-2 w-[220px] bg-white border border-kb-border rounded-lg shadow-lg py-1.5 z-50">
                  {BIZ_SERVICES.map(item => (
                    <Link key={item.href} href={item.href}
                      onClick={() => setAllServicesOpen(false)}
                      className="block px-4 py-2 text-[13px] text-kb-text-body hover:bg-kb-beige-light transition-colors">
                      {item.label}
                    </Link>
                  ))}
                </div>
              )}
            </div>
            {/* GLOBAL 은 언어 전환인데 이 레포에 i18n 이 없다. 메뉴를 만들면
                고를 언어가 없어 또 하나의 빈 약속이 된다. ▾ 를 떼고 표시로 둔다. */}
            <span className="text-[15px] text-kb-text-muted px-2">GLOBAL</span>
          </nav>
          <div className="flex items-center gap-2 ml-3">
            <button className="text-kb-text-muted hover:text-kb-text">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
              </svg>
            </button>
            <button className="text-kb-text-muted hover:text-kb-text ml-1">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/>
              </svg>
            </button>
          </div>
        </div>
      </div>

      {/* 기업 타이틀 + 버튼 */}
      {!isLoginPage && (
        <div className="bg-white border-b border-kb-border">
          <div className="max-w-kb-container mx-auto px-6 flex items-center justify-between h-[60px]">
            <span className="text-[28px] font-bold text-kb-text pl-8">기업</span>
            <div className="flex items-center gap-2">
              <Link href="/biz/login"
                className="px-3 py-1 border border-kb-border-dark text-sm text-kb-text-body hover:bg-kb-beige-light transition-colors rounded-lg">
                로그인
              </Link>
              <Link href="/cert-biz" className="px-3 py-1 border border-kb-border-dark text-sm text-kb-text-body hover:bg-kb-beige-light transition-colors rounded-lg">
                인증센터
              </Link>
            </div>
          </div>
        </div>
      )}

      {/* GNB */}
      {!isLoginPage && <nav
        className="bg-kb-gnb-biz relative"
        onMouseLeave={() => setActiveMenu(null)}
      >
        <div ref={gnbContainerRef} className="max-w-kb-container mx-auto px-6">
          <ul className="flex items-stretch w-full" style={{ height: '60px' }}>
            {BIZ_GNB_MENUS.map((menu, idx) => (
              <li key={menu.id} ref={el => { liRefs.current[idx] = el }}
                className="flex grow"
                onMouseEnter={() => setActiveMenu(menu.id)}>
                <Link
                  href="#"
                  onClick={() => setActiveMenu(null)}
                  className={`flex items-center justify-center w-full px-5
                    text-[19px] font-semibold transition-colors duration-kb whitespace-nowrap
                    ${activeMenu === menu.id
                      ? 'bg-kb-gnb-biz-active text-white font-bold'
                      : 'text-kb-text hover:bg-kb-gnb-biz-hover'
                    }`}>
                  {menu.label}
                </Link>
              </li>
            ))}
          </ul>
        </div>

        {/* 드롭다운 */}
        {activeMenu && currentMenu && currentMenu.columns.length > 0 && (
          <div className="absolute top-full left-0 right-0 bg-white border-b border-kb-border shadow-md z-50">
            <div className="max-w-kb-container mx-auto px-6 py-6">
              <div className="flex gap-6">
                {currentMenu.columns.map((col, ci) => (
                  <div key={ci}
                    className={`flex flex-col ${ci > 0 ? 'border-l border-kb-border' : ''}`}
                    style={{ width: col.items.some(i => i.arrow) ? (ci === 0 ? `${col1Width}px` : `${col1Width + 16}px`) : undefined, minWidth: col.items.some(i => i.arrow) ? undefined : '160px', paddingLeft: ci > 0 ? '16px' : undefined }}>
                    {col.header && (
                      <p className="text-sm text-kb-text-muted mb-4">{col.header}</p>
                    )}
                    <ul className="space-y-1" style={{ width: '100%' }}>
                      {col.items.map((item) => (
                        <li key={item.label}
                          className="rounded hover:bg-[#f0f4fb] transition-colors duration-100"
                          style={{ width: '100%', backgroundColor: item.active ? '#f0f4fb' : undefined }}>
                          <Link href={item.href ?? '#'}
                            className={`text-base flex items-center gap-0.5 whitespace-nowrap px-4 py-2 text-kb-text-body hover:text-kb-text ${item.active ? 'font-bold' : ''}`}
                            style={{ display: 'block', width: '100%' }}>
                            <span className="flex items-center gap-0.5">
                              {item.label}
                              {item.arrow && <span className="text-kb-text-muted text-xs">›</span>}
                            </span>
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </nav>}
    </header>
  )
}
