'use client'
import { KB_MINT,KB_PRIMARY,KB_PRIMARY_BG,KB_PRIMARY_BORDER,KB_PRIMARY_SURFACE } from '@/lib/theme'

import { useState, useRef, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { NEWS_ITEMS } from '@/lib/news-data'

const NAV_ITEMS = [
  // ── 조회 ──
  { label: '계좌조회',              href: '/inquiry/accounts',                        group: '조회' },
  { label: '잔액조회',              href: '/inquiry/accounts',                        group: '조회' },
  { label: '전체계좌',              href: '/inquiry/accounts',                        group: '조회' },
  { label: '거래내역 조회',         href: '/inquiry/transactions',                    group: '조회' },
  { label: '거래내역',              href: '/inquiry/transactions',                    group: '조회' },
  { label: '입출금내역',            href: '/inquiry/transactions',                    group: '조회' },

  // ── 이체 ──
  { label: '계좌이체',              href: '/transfer/account',                        group: '이체' },
  { label: '이체',                  href: '/transfer/account',                        group: '이체' },
  { label: '송금',                  href: '/transfer/account',                        group: '이체' },
  { label: '타행이체',              href: '/transfer/other-bank',                     group: '이체' },
  { label: '타은행 이체',           href: '/transfer/other-bank',                     group: '이체' },
  { label: '타행 계좌 등록',        href: '/transfer/other-bank/register',            group: '이체' },
  { label: '자동이체 변경',         href: '/transfer/auto-service/change',            group: '이체' },
  { label: '자동이체',              href: '/transfer/auto-service/change',            group: '이체' },
  { label: '이체결과 조회',         href: '/transfer/result',                         group: '이체' },
  { label: '이체내역 조회',         href: '/transfer/inquiry',                        group: '이체' },

  // ── 예금 ──
  { label: '예금 상품/가입',        href: '/products/deposit/list',                   group: '예금' },
  { label: '예금',                  href: '/products/deposit/list',                   group: '예금' },
  { label: '정기예금',              href: '/products/deposit/list',                   group: '예금' },
  { label: '적금',                  href: '/products/deposit/list',                   group: '예금' },
  { label: '예금 신규가입',         href: '/products/deposit/list',                   group: '예금' },
  { label: '신규결과/내역 조회',    href: '/products/deposit/inquiry/new',            group: '예금' },
  { label: '예금해지',              href: '/products/deposit/inquiry/terminate',      group: '예금' },
  { label: '해지',                  href: '/products/deposit/inquiry/terminate',      group: '예금' },
  { label: '해지결과/내역 조회',    href: '/products/deposit/inquiry/terminate-result', group: '예금' },

  // ── 대출 ──
  { label: '대출 상품/신청',         href: '/products/loan/credit',                    group: '대출' },
  { label: '대출',                  href: '/products/loan/credit',                    group: '대출' },
  { label: '신용대출',              href: '/products/loan/credit',                    group: '대출' },
  { label: '자동차대출',            href: '/products/loan/auto',                      group: '대출' },
  { label: '자동차 구입자금',       href: '/products/loan/auto',                      group: '대출' },
  { label: '전세대출',              href: '/products/loan/jeonse',                    group: '대출' },
  { label: '전세',                  href: '/products/loan/jeonse',                    group: '대출' },
  { label: '주택담보대출',          href: '/products/loan/mortgage',                  group: '대출' },
  { label: '주담대',                href: '/products/loan/mortgage',                  group: '대출' },
  { label: '아파트담보대출',        href: '/products/loan/mortgage',                  group: '대출' },
  { label: '단체대출',              href: '/products/loan/group',                     group: '대출' },
  { label: '주택금융공사 대출',     href: '/products/loan/khfc',                      group: '대출' },
  { label: '보금자리론',            href: '/products/loan/khfc',                      group: '대출' },
  { label: '서민금융',              href: '/products/loan/khfc',                      group: '대출' },
  { label: '대출 신청',             href: '/loans/apply',                             group: '대출' },
  { label: '대출진행현황',          href: '/products/loan/status',                    group: '대출' },
  { label: '대출현황',              href: '/products/loan/status',                    group: '대출' },
  { label: '신용평가',              href: '/products/loan/credit-eval/biz-plan',      group: '대출' },
  { label: '대출 안내',             href: '/products/loan/guide/rate',                group: '대출' },
  { label: '대출 관리',             href: '/products/loan/manage/overview',           group: '대출' },

  // ── 뱅킹관리 ──
  { label: '첫 방문 고객을 위한 안내', href: '/banking/first-visit',                 group: '뱅킹관리' },
  { label: '인터넷뱅킹 가입',       href: '/banking/first-visit',                     group: '뱅킹관리' },
  { label: '이체한도 변경',         href: '/banking/transfer-limit',                  group: '뱅킹관리' },
  { label: '이체한도',              href: '/banking/transfer-limit',                  group: '뱅킹관리' },

  // ── 인증서 ──
  { label: '인증센터',              href: '/cert',                                    group: '인증서' },
  { label: 'AXful 금융인증서 발급', href: '/cert/fin-cert-issue',                    group: '인증서' },
  { label: '금융인증서',            href: '/cert/fin-cert-issue',                    group: '인증서' },
  { label: '인증서 발급',           href: '/cert/fin-cert-issue',                    group: '인증서' },
  { label: '인증서 관리',           href: '/cert/cert-management',                   group: '인증서' },
  { label: '공동인증서 발급',       href: '/cert/joint-cert-issue',                  group: '인증서' },
  { label: '공동인증서',            href: '/cert/joint-cert-issue',                  group: '인증서' },
  { label: '공인인증서',            href: '/cert/joint-cert-issue',                  group: '인증서' },
  { label: '공동인증서 관리',       href: '/cert/joint-cert-management',             group: '인증서' },

  // ── 마이페이지 ──
  { label: '마이페이지',            href: '/mypage',                                  group: '마이페이지' },
  { label: 'My AXful',             href: '/mypage',                                  group: '마이페이지' },
  { label: '내 정보',              href: '/mypage',                                  group: '마이페이지' },
  { label: '설정',                  href: '/settings',                                group: '마이페이지' },
  { label: '비밀번호 변경',         href: '/settings',                                group: '마이페이지' },
  { label: '개인정보 변경',         href: '/settings',                                group: '마이페이지' },
  { label: '알림 설정',             href: '/settings',                                group: '마이페이지' },

  // ── 고객센터 ──
  { label: '영업점 상담',           href: '/support/consultation/branch',             group: '고객센터' },
  { label: '영업점',                href: '/support/consultation/branch',             group: '고객센터' },
  { label: '지점 찾기',             href: '/support/consultation/branch',             group: '고객센터' },
  { label: '라이브 채팅',           href: '/support/consultation/staff-chat',          group: '고객센터' },
  { label: 'AI 챗봇',               href: '/support/consultation/staff-chat',          group: '고객센터' },
  { label: '채팅 상담',             href: '/support/consultation/staff-chat',          group: '고객센터' },
  { label: '직원 채팅',             href: '/support/consultation/staff-chat',         group: '고객센터' },
  { label: '상담',                  href: '/support/consultation/staff-chat',         group: '고객센터' },
  { label: '온라인고객 신규가입',   href: '/support/customer-info/online-join',       group: '고객센터' },
  { label: '회원가입',              href: '/support/customer-info/online-join',       group: '고객센터' },
  { label: '신규가입',              href: '/support/customer-info/online-join',       group: '고객센터' },
  { label: '회원탈퇴',              href: '/support/customer-info/withdraw',          group: '고객센터' },
  { label: '탈퇴',                  href: '/support/customer-info/withdraw',          group: '고객센터' },
  { label: '새소식',                href: '/support/news',                            group: '고객센터' },
  { label: '공지사항',              href: '/support/news',                            group: '고객센터' },
  { label: '이벤트',                href: '/support/news',                            group: '고객센터' },
  { label: '인터넷뱅킹 이용안내',   href: '/support/internet-banking-guide',          group: '고객센터' },
  { label: '이용안내',              href: '/support/internet-banking-guide',          group: '고객센터' },
  { label: '이용수수료 안내',       href: '/support/fee-guide',                       group: '고객센터' },
  { label: '수수료',                href: '/support/fee-guide',                       group: '고객센터' },

  // ── 보안 ──
  { label: '보안프로그램 설치',     href: '/security-install',                        group: '보안' },
  { label: '보안 설치',             href: '/security-install',                        group: '보안' },
  { label: '보안 안내',             href: '/security-guide',                          group: '보안' },
  { label: '보안',                  href: '/security-guide',                          group: '보안' },
  { label: '보안 FAQ',              href: '/security-faq',                            group: '보안' },
  { label: 'OTP',                  href: '/security-guide',                          group: '보안' },
]

// 연말정산증명서(3건)를 뺐다 — /manage/certificates/year-end 화면이 없어
// 검색에서 고르면 404 였다. 발급 기능이 생기면 되살린다.
const SEARCH_ITEMS = [
  ...NAV_ITEMS,
  ...NEWS_ITEMS.map(n => ({ label: n.title, href: n.href, group: n.type })),
]

export default function SearchBar() {
  const router = useRouter()
  const [query, setQuery]     = useState('')
  const [open, setOpen]       = useState(false)
  const [focused, setFocused] = useState(-1)
  const inputRef    = useRef<HTMLInputElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  const results = query.trim().length > 0
    ? SEARCH_ITEMS.filter(item =>
        item.label.includes(query) || item.group.includes(query)
      ).slice(0, 8)
    : []

  function handleSelect(href: string) {
    setQuery('')
    setOpen(false)
    router.push(href)
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setFocused(f => Math.min(f + 1, results.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setFocused(f => Math.max(f - 1, -1))
    } else if (e.key === 'Enter') {
      const target = focused >= 0 ? results[focused] : results[0]
      if (target) handleSelect(target.href)
    } else if (e.key === 'Escape') {
      setOpen(false)
      inputRef.current?.blur()
    }
  }

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onClickOutside)
    return () => document.removeEventListener('mousedown', onClickOutside)
  }, [])

  return (
    <div className="border-t" style={{ borderColor: KB_PRIMARY_BORDER, backgroundColor: KB_PRIMARY_SURFACE }}>
      <div className="max-w-kb-container mx-auto px-6 py-1.5">
        <div ref={containerRef} className="relative w-72">

          {/* 인풋 */}
          <div
            className="flex items-center gap-2 bg-white rounded-full px-3 py-1 border transition-colors"
            style={{ borderColor: open || query ? KB_MINT : KB_PRIMARY_BORDER }}
          >
            <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4 flex-shrink-0"
              stroke="#5BC9A8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="9" cy="9" r="6"/><path d="M15 15l-3.5-3.5"/>
            </svg>
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={e => { setQuery(e.target.value); setOpen(true); setFocused(-1) }}
              onFocus={() => setOpen(true)}
              onKeyDown={handleKeyDown}
              placeholder="검색어를 입력하세요"
              className="flex-1 text-[14px] outline-none bg-transparent text-kb-text placeholder:text-kb-text-muted"
            />
            {query && (
              <button
                onClick={() => { setQuery(''); setOpen(false); inputRef.current?.focus() }}
                className="text-kb-text-muted hover:text-kb-text transition-colors"
              >
                <svg viewBox="0 0 16 16" fill="none" className="w-4 h-4"
                  stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <line x1="4" y1="4" x2="12" y2="12"/><line x1="12" y1="4" x2="4" y2="12"/>
                </svg>
              </button>
            )}
          </div>

          {/* 검색 결과 드롭다운 */}
          {open && results.length > 0 && (
            <div className="absolute top-full left-0 right-0 mt-1 bg-white border border-kb-border rounded-xl shadow-lg z-[200] overflow-hidden">
              {results.map((item, i) => (
                <button
                  key={`${item.href}-${i}`}
                  onClick={() => handleSelect(item.href)}
                  className={`w-full flex items-center justify-between px-4 py-3 text-left transition-colors
                    ${i === focused ? 'bg-kb-primary-bg' : 'hover:bg-kb-primary-surface'}
                    ${i > 0 ? 'border-t border-gray-50' : ''}`}
                >
                  <div className="flex items-center gap-3">
                    <svg viewBox="0 0 16 16" fill="none" className="w-3.5 h-3.5 flex-shrink-0 text-kb-text-muted"
                      stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="7" cy="7" r="5"/><path d="M12 12l-2.5-2.5"/>
                    </svg>
                    <span className="text-[14px] text-kb-text">{item.label}</span>
                  </div>
                  <span className="text-[12px] px-2 py-0.5 rounded-full flex-shrink-0"
                    style={{ backgroundColor: KB_PRIMARY_BG, color: KB_PRIMARY }}>
                    {item.group}
                  </span>
                </button>
              ))}
            </div>
          )}

          {/* 결과 없음 */}
          {open && query.trim().length > 0 && results.length === 0 && (
            <div className="absolute top-full left-0 right-0 mt-1 bg-white border border-kb-border rounded-xl shadow-lg z-[200] px-4 py-5 text-center">
              <p className="text-[14px] text-kb-text-muted">
                &ldquo;{query}&rdquo;에 대한 검색 결과가 없습니다.
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
