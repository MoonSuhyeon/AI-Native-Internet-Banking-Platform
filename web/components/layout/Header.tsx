'use client'
import { KB_MINT,KB_PRIMARY,KB_PRIMARY_BORDER,KB_PRIMARY_SURFACE } from '@/lib/theme'

import Link from 'next/link'
import SearchBar from '@/components/home/SearchBar'
import { usePathname } from 'next/navigation'
import { useState, useEffect } from 'react'
import { api } from '@/lib/api'

// ============================================================
// GNB 메뉴 데이터
// megaMenu: 드롭다운 패널용 (카테고리 + 소분류 링크)
// ============================================================
export const GNB_MENUS = [
  {
    id: 'inquiry',
    label: '조회',
    href: '/inquiry/accounts',
    megaMenu: [
      {
        title: '계좌조회',
        href: '/inquiry/accounts',
        items: [
          { label: 'AXful Bank 계좌조회', href: '/inquiry/accounts' },
        ],
      },
      {
        title: '거래내역 조회',
        href: '/inquiry/transactions',
        items: [
          { label: '거래내역 조회', href: '/inquiry/transactions' },
        ],
      },
    ],
  },
  {
    id: 'transfer',
    label: '이체',
    href: '/transfer/account',
    megaMenu: [
      {
        title: '계좌이체',
        href: '/transfer/account',
        items: [
          { label: '계좌이체', href: '/transfer/account' },
        ],
      },
      {
        title: '이체결과 조회',
        href: '/transfer/inquiry',
        items: [
          { label: '계좌이체결과 조회', href: '/transfer/inquiry' },
        ],
      },
    ],
  },
  {
    id: 'products',
    label: '금융상품',
    href: '/products/deposit/list',
    megaMenu: [
      {
        title: '예금',
        href: '/products/deposit/list',
        items: [
          { label: '예금 상품/가입',    href: '/products/deposit/list' },
          { label: '신규결과/내역 조회', href: '/products/deposit/inquiry/new' },
          { label: '예금해지',          href: '/products/deposit/inquiry/terminate' },
          { label: '해지결과/내역 조회', href: '/products/deposit/inquiry/terminate-result' },
        ],
      },
      {
        title: '대출',
        href: '/products/loan/credit',
        items: [
          { label: '대출 상품/신청', href: '/products/loan/credit' },
          { label: '대출진행현황',  href: '/products/loan/status' },
          { label: '대출관리',      href: '/products/loan/manage/rate' },
          { label: '대출 가이드',    href: '/products/loan/guide/rate' },
          { label: '여신심사 자료제출', href: '/products/loan/credit-eval/biz-plan' },
        ],
      },
    ],
  },
  {
    id: 'manage',
    label: '뱅킹관리',
    href: '/banking/first-visit',
    megaMenu: [
      {
        title: '고객정보관리',
        href: '/support/customer-info/online-join',
        items: [
          { label: '온라인고객 신규가입', href: '/support/customer-info/online-join' },
          { label: '개인정보 수정',       href: '/settings' },
          { label: '회원탈퇴',           href: '/support/customer-info/withdraw' },
        ],
      },
      {
        title: '계좌관리',
        href: '/banking/withdrawal-account',
        items: [
          { label: '타행 출금계좌 등록/삭제', href: '/banking/withdrawal-account' },
          { label: '이체한도 조회/변경',          href: '/banking/transfer-limit' },
        ],
      },
      {
        title: '인터넷 뱅킹관리',
        href: '/support/customer-info/id-password',
        items: [
          { label: 'ID조회/사용자암호 설정', href: '/support/customer-info/id-password' },
          { label: '인터넷뱅킹 해지',        href: '/support/customer-info/internet-banking-cancel' },
        ],
      },
      {
        title: '이용안내',
        href: '/banking/first-visit',
        items: [
          { label: '첫 방문 고객을 위한 안내', href: '/banking/first-visit' },
          { label: '인터넷뱅킹 FAQ',          href: '/support/faq' },
          { label: '인터넷뱅킹 이용안내',      href: '/support/internet-banking-guide' },
          { label: '이용수수료 안내',          href: '/support/fee-guide' },
        ],
      },
    ],
  },
]


interface StoredUser { name: string; email: string; customer_id: number }

const SESSION_SECONDS = 10 * 60 // 10분
const SESSION_MS = SESSION_SECONDS * 1000

function getStoredToken() {
  return localStorage.getItem('accessToken') || localStorage.getItem('access_token')
}

function renewLocalSession() {
  const expiry = Date.now() + SESSION_MS
  localStorage.setItem('sessionExpiry', String(expiry))
  return expiry
}

function formatTime(sec: number) {
  const m = String(Math.floor(sec / 60)).padStart(2, '0')
  const s = String(sec % 60).padStart(2, '0')
  return `${m}:${s}`
}

export default function Header() {
  const pathname = usePathname()
  const [activeMenu, setActiveMenu] = useState<string | null>(null)
  // 로그인 여부는 토큰으로 판단한다(홈과 동일 기준). user 프로필은 인사말 표시에만 쓰며,
  // me 조회가 실패해 user 키가 비어 있어도 토큰만 있으면 로그인 UI를 보여준다.
  const [authed, setAuthed] = useState(false)
  const [user, setUser] = useState<StoredUser | null>(null)
  const [remaining, setRemaining] = useState(SESSION_SECONDS)
  const [extending, setExtending] = useState(false)
  const [extendError, setExtendError] = useState(false)
  const isLoggedIn = user !== null

  useEffect(() => {
    // sessionExpiry 체크: 만료됐으면 로그아웃, 없으면 토큰 있을 때 세션 복원
    const expiry = localStorage.getItem('sessionExpiry')
    const token = localStorage.getItem('accessToken') || localStorage.getItem('access_token')
    if (expiry && parseInt(expiry, 10) <= Date.now()) {
      // 명시적으로 만료된 세션만 강제 로그아웃
      localStorage.removeItem('accessToken')
      localStorage.removeItem('access_token')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('sessionExpiry')
      localStorage.removeItem('user')
      localStorage.removeItem('customerId')
      setAuthed(false)
      setUser(null)
      return
    }
    if (!expiry && token) {
      // sessionExpiry 없지만 토큰 있으면 30분 세션 복원 (로그인 중 페이지 이동 대응)
      localStorage.setItem('sessionExpiry', String(Date.now() + 30 * 60 * 1000))
    }
    setAuthed(!!(localStorage.getItem('accessToken') || localStorage.getItem('access_token')))
    if (pathname.startsWith('/logout')) {
      setUser(null)
      return
    }


    try {
      const stored = localStorage.getItem('user')
      if (stored) {
        const parsed = JSON.parse(stored)
        setUser(prev => {
          if (prev?.customer_id === parsed?.customer_id) return prev
          return parsed
        })
        return
      }
      setUser(null)
    } catch {}
  }, [pathname])

  // 카운트다운 + 자동 로그아웃
  useEffect(() => {
    if (!authed && !isLoggedIn) return

    const stored = localStorage.getItem('sessionExpiry')
    const parsedExpiry = stored ? parseInt(stored, 10) : NaN
    const hasValidToken = Boolean(getStoredToken())
    const expiry = !Number.isFinite(parsedExpiry) || parsedExpiry <= Date.now()
      ? hasValidToken
        ? renewLocalSession()
        : Date.now()
      : parsedExpiry

    const seconds = Math.max(0, Math.round((expiry - Date.now()) / 1000))
    setRemaining(seconds)

    const tick = setInterval(() => {
      const storedExpiry = localStorage.getItem('sessionExpiry')
      if (!storedExpiry) {
        if (getStoredToken()) {
          const renewed = renewLocalSession()
          setRemaining(Math.max(0, Math.round((renewed - Date.now()) / 1000)))
          return
        }
        clearInterval(tick)
        setUser(null)
        return
      }
      const remaining = Math.max(0, Math.round((parseInt(storedExpiry) - Date.now()) / 1000))
      setRemaining(remaining)
      if (remaining <= 0) {
        if (getStoredToken()) {
          const renewed = renewLocalSession()
          setRemaining(Math.max(0, Math.round((renewed - Date.now()) / 1000)))
          return
        }
        clearInterval(tick)
        localStorage.removeItem('access_token')
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        localStorage.removeItem('sessionExpiry')
        localStorage.removeItem('user')
        localStorage.removeItem('customerId')
        setUser(null)
      }
    }, 1000)

    return () => {
      clearInterval(tick)
    }
  }, [authed, isLoggedIn, pathname])


  function handleLogout() {
    localStorage.removeItem('access_token')
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('sessionExpiry')
    localStorage.removeItem('user')
    localStorage.removeItem('customerId')
    sessionStorage.clear()
    setUser(null)
    window.location.href = '/logout?reason=manual'
  }

  async function handleExtend() {
    if (extending) return
    const refreshToken = localStorage.getItem('refreshToken')
    if (!refreshToken) {
      if (getStoredToken()) {
        const newExpiry = renewLocalSession()
        setRemaining(Math.max(0, Math.round((newExpiry - Date.now()) / 1000)))
        return
      }
      setUser(null)
      return
    }
    setExtending(true)
    setExtendError(false)
    try {
      const { data } = await api.post('/api/v1/auth/refresh', { refreshToken })
      localStorage.setItem('accessToken', data.data.accessToken)
      localStorage.setItem('access_token', data.data.accessToken)
      if (data.data.refreshToken) {
        localStorage.setItem('refreshToken', data.data.refreshToken)
      }
      const newExpiry = Date.now() + SESSION_SECONDS * 1000
      localStorage.setItem('sessionExpiry', String(newExpiry))
      setRemaining(SESSION_SECONDS)
    } catch {
      if (localStorage.getItem('accessToken') || localStorage.getItem('access_token')) {
        const newExpiry = Date.now() + SESSION_SECONDS * 1000
        localStorage.setItem('sessionExpiry', String(newExpiry))
        setRemaining(SESSION_SECONDS)
        setExtendError(false)
      } else {
        setExtendError(true)
      }
    } finally {
      setExtending(false)
    }
  }

  const currentMenu = GNB_MENUS.find((m) => m.id === activeMenu)
  const isLoginPage = pathname === '/login'
  const hideGnb = pathname.startsWith('/cert') || pathname.startsWith('/cert-biz') || isLoginPage || pathname.startsWith('/support')

  return (
    <header className="w-full bg-white relative z-50 shadow-sm">
      {/* ===== 1. 로고 + 사용자 영역 통합 ===== */}
      <div className="max-w-kb-container mx-auto px-6 flex items-center justify-between h-[60px]">
        {/* 로고 */}
        <Link href="/" className="flex items-center gap-3">
          <div className="w-[6px] h-[26px] rounded-full" style={{ backgroundColor: KB_MINT }} />
          <span className="text-[26px] font-bold tracking-[0.02em]" style={{ color: KB_PRIMARY }}>AXful Bank</span>
        </Link>

        {/* 우측: 사용자 영역 */}
        {!isLoginPage && (
          <div className="flex items-center gap-2 text-[14px]">
            {/* SearchBar 는 만들어져 있었지만 어디에도 마운트돼 있지 않았다 —
                컴포넌트도 테스트도 멀쩡한데 화면에서 닿을 길이 없는 상태였다.
                메뉴가 100개 가까이 되는 사이트에서 검색이 없으면 못 찾는다. */}
            <div className="hidden lg:block w-[220px]">
              <SearchBar />
            </div>
            <Link href="/admin/login"
              className="px-3 py-1 text-[12px] font-semibold rounded-full border border-gray-300 text-gray-400 transition-colors hover:bg-gray-50">
              관리자
            </Link>
            {authed ? (
              <>
                <span className="text-kb-text-muted font-medium">{user?.name ?? '고객'}님</span>
                <span className="text-kb-border">|</span>
                <Link href="/mypage" className="text-kb-text-muted hover:text-kb-text transition-colors">My AXful</Link>
                <span className="text-kb-border">|</span>
                <span className="text-kb-text-muted">🔒 {formatTime(remaining)}</span>
                {extendError && (
                  <span className="text-[12px] text-red-500">연장 실패 — 재시도하거나 로그아웃하세요</span>
                )}
                <button onClick={handleExtend}
                  disabled={extending}
                  className="px-3 py-1 text-[13px] font-semibold rounded-full border transition-colors hover:bg-kb-primary-bg disabled:opacity-50"
                  style={{ borderColor: extendError ? '#EF4444' : KB_MINT, color: extendError ? '#EF4444' : KB_PRIMARY }}>
                  {extending ? '연장 중...' : extendError ? '재시도' : '연장'}
                </button>
                <button onClick={handleLogout}
                  className="px-3 py-1 text-[13px] font-semibold rounded-full border border-gray-200 text-kb-text-muted hover:bg-gray-50 transition-colors">
                  로그아웃
                </button>
                <Link href="/cert"
                  className="px-3 py-1 text-[13px] font-semibold rounded-full text-white transition-opacity hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  인증센터
                </Link>
              </>
            ) : (
              <>
                <Link href="/login"
                  className="px-4 py-1 text-[13px] font-semibold rounded-full border transition-colors hover:bg-kb-primary-bg"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                  로그인
                </Link>
                <Link href="/cert"
                  className="px-4 py-1 text-[13px] font-semibold rounded-full text-white transition-opacity hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  인증센터
                </Link>
              </>
            )}
          </div>
        )}
      </div>

      {/* ===== 2. GNB + 메가메뉴 ===== */}
      {!hideGnb && <nav
        className="relative border-t"
        style={{ backgroundColor: KB_PRIMARY_SURFACE, borderColor: KB_PRIMARY_BORDER }}
        onMouseLeave={() => setActiveMenu(null)}
      >
        <div className="max-w-kb-container mx-auto px-6">
          <ul className="flex items-stretch w-full" style={{ height: '48px' }}>
            {GNB_MENUS.map((menu) => {
              const isActive = pathname === menu.href || pathname.startsWith(menu.href + '/')
              const isOpen = activeMenu === menu.id
              return (
                <li
                  key={menu.id}
                  className="flex grow"
                  onMouseEnter={() => setActiveMenu(menu.id)}
                >
                  <Link
                    href={menu.href}
                    onClick={() => setActiveMenu(null)}
                    className={`
                      flex items-center justify-center w-full px-5
                      text-[16px] font-semibold transition-colors duration-150 whitespace-nowrap relative
                      ${isActive
                        ? 'text-kb-primary font-bold'
                        : isOpen
                        ? 'text-kb-primary bg-kb-primary-bg'
                        : 'text-kb-text-body hover:text-kb-primary hover:bg-kb-primary-bg'}
                    `}
                  >
                    {menu.label}
                    {isActive && (
                      <span className="absolute bottom-0 left-1/4 right-1/4 h-[2px] rounded-full" style={{ backgroundColor: KB_MINT }} />
                    )}
                  </Link>
                </li>
              )
            })}
          </ul>
        </div>

        {/* ===== 메가메뉴 드롭다운 ===== */}
        {activeMenu && currentMenu && (
          <div className="absolute top-full left-0 right-0 bg-white border-b border-kb-border shadow-md z-50">
            <div className="max-w-kb-container mx-auto px-6 py-6">
              <div className={`grid ${currentMenu.megaMenu.length >= 4 ? 'grid-cols-4' : 'grid-cols-2'}`}>
                {currentMenu.megaMenu.map((category, ci) => (
                  <div key={category.title}
                    className={ci > 0 ? 'border-l border-kb-border pl-6 text-center' : 'text-center'}>
                    <Link
                      href={category.items[0]?.href ?? category.href}
                      onClick={() => setActiveMenu(null)}
                      className="block text-[17px] font-bold text-kb-text mb-1 hover:text-kb-taupe"
                    >
                      {category.title}
                    </Link>
                    {category.items.length > 0 && (
                      <ul className="space-y-0.5">
                        {category.items.map((item) => (
                          <li key={item.href} className="rounded hover:bg-[#e8f7f2] transition-colors duration-100">
                            <Link
                              href={item.href}
                              onClick={() => setActiveMenu(null)}
                              className="block text-[15px] py-2 text-kb-text-body hover:text-kb-text"
                            >
                              {item.label}
                            </Link>
                          </li>
                        ))}
                      </ul>
                    )}
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
