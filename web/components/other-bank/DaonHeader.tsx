'use client'

import Link from 'next/link'
import { useState, useRef, useEffect } from 'react'
import { usePathname } from 'next/navigation'
import { KB_GNB_BIZ_ACTIVE } from '@/lib/theme'

type MenuItem = { label: string; arrow?: boolean; href?: string; active?: boolean }
type MenuColumn = { header?: string; items: MenuItem[] }

const DAON_GNB_MENUS: {
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
      { label: '계좌조회', arrow: true, active: true, href: '/other-bank/accounts' },
      { label: '거래내역조회', arrow: true, href: '/other-bank/accounts' },
      { label: '증명서/확인증 발급', arrow: true },
      { label: '로그인 없이 조회', arrow: true },
      { label: '기타 조회', arrow: true },
    ]},
    { items: [
      { label: '전체계좌조회', href: '/other-bank/accounts' },
      { label: '잔액·거래내역조회' },
      { label: '다온금융그룹조회' },
      { label: '계좌통합관리서비스(어카운트인포)' },
    ]},
  ]},

  { id: 'deposit', label: '예금/신탁', columns: [
    { items: [
      { label: '예금/적금', arrow: true, active: true },
      { label: '입출금', arrow: true },
      { label: '신탁', arrow: true },
      { label: '펀드', arrow: true },
      { label: '보험', arrow: true },
      { label: '퇴직연금(IRP)', arrow: true },
    ]},
    { items: [
      { label: '조회', arrow: true, active: true },
      { label: '예적금 신규가입' },
      { label: '예적금 해지' },
      { label: '자동이체 관리', arrow: true },
      { label: '만기·재예치 안내', arrow: true },
      { label: '비과세·세금우대 안내' },
    ]},
    { items: [
      { label: '내 예적금 한눈에 보기' },
      { label: '목표저축 현황' },
      { label: '펀드 수익률 조회' },
      { label: '연금 가입현황' },
    ]},
  ]},

  { id: 'loan', label: '대출', columns: [
    { items: [
      { label: '대출조회', arrow: true, active: true },
      { label: '신용대출', arrow: true },
      { label: '담보대출', arrow: true },
      { label: '전세자금대출', arrow: true },
      { label: '비상금대출', arrow: true },
    ]},
    { items: [
      { label: '내 대출 현황', arrow: true, active: true },
      { label: '대출 한도조회' },
      { label: '대출이자 납입', arrow: true },
      { label: '중도상환', arrow: true },
      { label: '대출연장·재약정', arrow: true },
      { label: '상환내역 조회' },
    ]},
    { items: [
      { label: '맞춤대출 추천' },
      { label: '대출금리 안내' },
      { label: '대출서류 안내' },
    ]},
  ]},

  { id: 'products', label: '금융상품', columns: [
    { items: [
      { label: '금융상품 HOME', active: true },
      { label: '예금', arrow: true },
      { label: '적금', arrow: true },
      { label: '입출금', arrow: true },
      { label: '대출', arrow: true },
      { label: '펀드', arrow: true },
      { label: '신탁', arrow: true },
      { label: '보험', arrow: true },
      { label: '카드' },
      { label: '쿠폰함' },
    ]},
  ]},

  { id: 'fx', label: '외환', columns: [
    { items: [
      { label: '외환HOME', active: true },
      { label: '마이외환', arrow: true },
      { label: '환율조회', arrow: true },
      { label: '외화송금', arrow: true },
      { label: '국내외화이체/외화예금입출금', arrow: true },
      { label: '외화현찰환전', arrow: true },
      { label: '환전주머니', arrow: true },
      { label: '해외주식 투자', arrow: true },
      { label: '여행자보험', arrow: true },
    ]},
  ]},

  { id: 'asset', label: '자산관리', columns: [
    { items: [
      { label: '내 자산 한눈에', arrow: true, active: true },
      { label: '마이데이터', arrow: true },
      { label: '소비·지출 분석', arrow: true },
      { label: '목표저축', arrow: true },
    ]},
    { items: [
      { label: '자산현황 HOME', active: true },
      { label: '월별 지출 리포트' },
      { label: '카드사용 내역' },
      { label: '나의 관심상품' },
    ]},
  ]},

  { id: 'mgmt', label: '뱅킹관리', columns: [
    { items: [
      { label: '인터넷뱅킹 관리', arrow: true, active: true },
      { label: '이체한도 관리', arrow: true },
      { label: '보안서비스', arrow: true },
      { label: '알림·통지서비스 신청', arrow: true },
      { label: '이용안내', arrow: true },
    ]},
    { items: [
      { label: '내 정보 관리', arrow: true, active: true },
      { label: '비밀번호·이체비밀번호 변경' },
    ]},
    { items: [
      { label: '로그인 기록 조회' },
      { label: '자동이체 관리' },
      { label: '이용중인 서비스' },
    ]},
  ]},
]

export default function DaonHeader() {
  const [activeMenu, setActiveMenu] = useState<string | null>(null)
  const currentMenu = DAON_GNB_MENUS.find((m) => m.id === activeMenu)
  const pathname = usePathname()
  const isLoginPage = pathname === '/other-bank/login'

  const gnbContainerRef = useRef<HTMLDivElement>(null)
  const liRefs = useRef<(HTMLLIElement | null)[]>([])
  const [col1Width, setCol1Width] = useState(180)

  useEffect(() => {
    if (!activeMenu) return
    const refId = ['deposit', 'loan', 'products', 'fx', 'asset', 'mgmt'].includes(activeMenu) ? 'transfer' : activeMenu
    const idx = DAON_GNB_MENUS.findIndex((m) => m.id === refId)
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
          <Link href="/other-bank" className="flex items-center gap-3">
            <div className="w-[4px] self-stretch" style={{ backgroundColor: KB_GNB_BIZ_ACTIVE }} />
            <div className="flex flex-col leading-none gap-1.5">
              <span className="text-[22px] font-bold tracking-[0.1em]" style={{ color: KB_GNB_BIZ_ACTIVE }}>다온은행</span>
              <span className="text-[14px] font-medium text-kb-text-body tracking-[0.18em]">DAON Bank</span>
            </div>
          </Link>
          <nav className="flex items-center ml-auto">
            {['개인', '기업'].map((item, i) => (
              <span key={item} className="flex items-center">
                <Link href="/other-bank"
                  className={`text-[15px] px-2 hover:text-kb-text transition-colors
                    ${item === '개인' ? 'text-kb-text font-semibold' : 'text-kb-text-muted'}`}>
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
            <button className="text-[15px] text-kb-text-muted px-2 hover:text-kb-text flex items-center gap-0.5">
              전체서비스 <span className="text-[10px]">▾</span>
            </button>
            <button className="text-[15px] text-kb-text-muted px-2 hover:text-kb-text flex items-center gap-0.5">
              GLOBAL <span className="text-[10px]">▾</span>
            </button>
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

      {/* 개인 타이틀 + 버튼 */}
      {!isLoginPage && (
        <div className="bg-white border-b border-kb-border">
          <div className="max-w-kb-container mx-auto px-6 flex items-center justify-between h-[60px]">
            <span className="text-[28px] font-bold text-kb-text pl-8">개인</span>
            <div className="flex items-center gap-2">
              <Link href="/other-bank/login"
                className="px-3 py-1 border border-kb-border-dark text-sm text-kb-text-body hover:bg-kb-beige-light transition-colors rounded-lg">
                로그인
              </Link>
              <Link href="#" className="px-3 py-1 border border-kb-border-dark text-sm text-kb-text-body hover:bg-kb-beige-light transition-colors rounded-lg">
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
            {DAON_GNB_MENUS.map((menu, idx) => (
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
