'use client'

import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import { useEffect, useState } from 'react'
import { AdminUser } from '@/lib/admin-mock-data'
import { getAdminRoles, hasAnyRole, primaryRoleLabel } from '@/lib/admin-auth'
import { KB_GNB_BIZ_ACTIVE } from '@/lib/theme'

// 게이팅은 BankRole(JWT, admin_roles) 단일 어휘로 한다. 섹션/항목에 bankRoles 를 지정하고
// 항목에 없으면 섹션 값을 상속한다. (ROLE_ADMIN 은 hasAnyRole 에서 항상 통과)
type NavItem    = { label: string; href: string; bankRoles?: string[] }
// domain = 도메인(계) 라벨. 같은 domain 끼리 한 블록으로 묶어 소유 경계를 시각화한다.
type NavSection = { domain: string; section: string; dot: string; bankRoles: string[]; items: NavItem[] }

// CUSTOMER 제외 전 직원(BankRole). break-glass 긴급 접근 등 '전 직원' 범위 게이팅에 사용.
const EMPLOYEE_ROLES = [
  'ROLE_TELLER', 'ROLE_DEPUTY_MANAGER', 'ROLE_BRANCH_MANAGER', 'ROLE_HQ_REVIEWER',
  'ROLE_HQ_RISK', 'ROLE_COMPLIANCE', 'ROLE_OPS', 'ROLE_INTERNAL', 'ROLE_ADMIN',
]
// 고객 데이터 열람 직군 (본사 + 지점). 가입통계·감사로그는 항목별로 더 좁게 게이팅한다.
const CUSTOMER_VIEW = ['ROLE_COMPLIANCE', 'ROLE_HQ_REVIEWER', 'ROLE_HQ_RISK', 'ROLE_BRANCH_MANAGER', 'ROLE_DEPUTY_MANAGER', 'ROLE_TELLER']
const AUDIT_VIEW    = ['ROLE_COMPLIANCE', 'ROLE_HQ_REVIEWER', 'ROLE_BRANCH_MANAGER', 'ROLE_TELLER']
const HQ_DESK       = ['ROLE_COMPLIANCE', 'ROLE_HQ_REVIEWER', 'ROLE_HQ_RISK']

// 도메인(계) 블록 단위로 정렬한다. 같은 domain 끼리 연속 배치 → 렌더에서 도메인 헤더로 묶인다.
const NAV: NavSection[] = [
  // ───────── 고객·인증보안계 ─────────
  {
    domain: '고객·인증보안계',
    // 고객 운영 — 조회·회원 라이프사이클·가입 통계. (감사 로그는 여신계 감사 소관)
    section: '고객', dot: 'bg-blue-300',
    bankRoles: CUSTOMER_VIEW,
    items: [
      { label: '고객 조회',      href: '/admin/customers' },
      { label: '회원 목록',      href: '/admin/members' },
      { label: '회원 상태 관리', href: '/admin/member-status' },
      { label: '가입 대시보드',  href: '/admin/join-stats', bankRoles: ['ROLE_COMPLIANCE', 'ROLE_HQ_RISK'] },
    ],
  },
  {
    domain: '고객·인증보안계',
    // KYC·AML·제재·세무 심사.
    section: '준법·심사', dot: 'bg-orange-400',
    bankRoles: HQ_DESK,
    items: [
      { label: '제재대상 Hit 검토', href: '/admin/screening' },
      { label: 'EDD 심사·승인',    href: '/admin/edd' },
      { label: '중복고객 검토',     href: '/admin/duplicates' },
      { label: '대리인 검토',       href: '/admin/agent' },
      { label: '미성년 검토',       href: '/admin/minor' },
      { label: 'FATCA/CRS',        href: '/admin/fatca' },
    ],
  },
  {
    domain: '고객·인증보안계',
    // 이상거래 조사 에이전트(Python/LangGraph 사이드카) — 경쟁 가설 조사 + HITL 권고.
    section: '이상거래 조사', dot: 'bg-purple-400',
    bankRoles: HQ_DESK,
    items: [
      { label: '조사 에이전트', href: '/admin/fraud' },
    ],
  },

  // ───────── 수신계 ─────────
  {
    domain: '수신계',
    section: '상담', dot: 'bg-teal-400',
    bankRoles: AUDIT_VIEW,
    items: [
      { label: '고객 조회', href: '/admin/consultation/customer' },
      { label: '상담원 채팅', href: '/admin/consultation/chat' },
      { label: '상담 이력 조회', href: '/admin/consultation/history' },
      { label: '만족도/통계', href: '/admin/consultation/stats' },
      { label: '상담원 계정 관리', href: '/admin/consultation/agents', bankRoles: ['ROLE_ADMIN'] },
    ],
  },

  // ───────── 여신계 ─────────
  {
    domain: '여신계',
    // 대출 어드민은 심사·운영·결재 직군이 사용한다.
    section: '대출', dot: 'bg-green-400',
    bankRoles: ['ROLE_DEPUTY_MANAGER', 'ROLE_OPS', 'ROLE_BRANCH_MANAGER', 'ROLE_HQ_REVIEWER'],
    items: [
      { label: '계약 모니터링',      href: '/admin/loan/contracts' },
      { label: '본심사 목록',        href: '/admin/loan/review' },
      { label: '자동심사 시뮬레이터', href: '/admin/loan/auto-review-sim' },
      { label: '담보 관리',      href: '/admin/loan/collateral' },
      { label: '서류 관리',      href: '/admin/loan/documents' },
      { label: '우대금리 정책',  href: '/admin/loan/rate-policy' },
      { label: '상품 관리',      href: '/admin/loan/products' },
      { label: '영업일 캘린더',  href: '/admin/loan/calendar' },
      { label: '신용정보 보고서',href: '/admin/loan/credit-report' },
      { label: '알림 발송함',    href: '/admin/loan/notification' },
      { label: '본인인증 조회',  href: '/admin/loan/identity' },
    ],
  },
  {
    domain: '여신계',
    // RAG·자문·편향감사 관리 — 본사 심사·컴플라이언스 데스크(HQ_DESK).
    section: 'AI 심사지원', dot: 'bg-violet-400',
    bankRoles: HQ_DESK,
    items: [
      { label: 'RAG 문서관리',  href: '/admin/ai/rag-documents' },
      { label: '자문 규칙',     href: '/admin/ai/advisory-rules' },
      { label: '감사·리스크',   href: '/admin/ai/audit-risk' },
      { label: '서류 검토 큐',  href: '/admin/ai/doc-review' },
    ],
  },
  {
    domain: '여신계',
    // 감사 — 접근 감사로그 + AI 출력 감사. (감사 로그는 여신계 감사 소관)
    section: '감사', dot: 'bg-red-400',
    bankRoles: AUDIT_VIEW,
    items: [
      { label: '감사 로그',     href: '/admin/audit-log' },
      { label: '감사 대시보드', href: '/admin/audit',            bankRoles: ['ROLE_COMPLIANCE'] },
      { label: '격리 관리',     href: '/admin/audit/quarantine', bankRoles: ['ROLE_COMPLIANCE'] },
    ],
  },
  {
    domain: '여신계',
    // break-glass 는 고객 제외 전 직원이 사용 → 섹션은 직원 역할 합집합, 항목은 개별 게이팅.
    section: '대출 운영·감사', dot: 'bg-emerald-400',
    bankRoles: EMPLOYEE_ROLES,
    items: [
      { label: 'EOD 배치',     href: '/admin/loan/eod',         bankRoles: ['ROLE_OPS'] },
      { label: '대출 감사로그', href: '/admin/loan/audit',       bankRoles: ['ROLE_COMPLIANCE'] },
      { label: '긴급 접근',    href: '/admin/loan/break-glass', bankRoles: EMPLOYEE_ROLES },
    ],
  },

  // ───────── 전사 공통 ─────────
  {
    domain: '전사 공통',
    // 특정 계에 속하지 않는다 — 모든 서비스와 에이전트의 지표를 한자리에서 본다.
    // 운영(장애·지연)과 리스크(에이전트 판단 품질)가 같은 화면을 다른 이유로 본다.
    section: '모니터링', dot: 'bg-slate-400',
    bankRoles: ['ROLE_OPS', 'ROLE_HQ_RISK'],
    items: [
      { label: '대시보드', href: '/admin/monitoring' },
    ],
  },
]

export default function AdminSidebar() {
  const pathname = usePathname()
  const router = useRouter()
  const [user, setUser] = useState<AdminUser | null>(null)
  const [adminRoles, setAdminRoles] = useState<string[]>([])

  useEffect(() => {
    try {
      const s = localStorage.getItem('admin_user')
      if (s) setUser(JSON.parse(s))
    } catch {}
    setAdminRoles(getAdminRoles())
  }, [])

  function logout() {
    const keys = ['accessToken', 'access_token', 'refreshToken', 'customerId', 'admin_roles', 'admin_role', 'admin_user']
    keys.forEach((k) => localStorage.removeItem(k))
    router.push('/admin/login')
  }

  // BankRole(JWT) 단일 게이팅 — 섹션 표시 후, 항목은 자체 bankRoles 가 있으면 그걸로, 없으면 섹션 상속.
  const visibleNav = NAV
    .filter((g) => hasAnyRole(adminRoles, ...g.bankRoles))
    .map((g) => ({
      ...g,
      items: g.items.filter((item) => !item.bankRoles || hasAnyRole(adminRoles, ...item.bankRoles)),
    }))
    .filter((g) => g.items.length > 0)

  return (
    <aside className="w-52 flex-shrink-0 flex flex-col h-screen sticky top-0 overflow-hidden" style={{ backgroundColor: KB_GNB_BIZ_ACTIVE }}>

      {/* 로고 */}
      <div className="flex items-stretch gap-2.5 px-4 py-4" style={{ backgroundColor: '#122550', borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
        <div className="w-[3px] rounded-full bg-kb-yellow self-stretch" />
        <div className="flex flex-col leading-none gap-1">
          <span className="text-[15px] font-bold text-white tracking-wide">AXful Bank</span>
          <span className="text-[9px] font-medium tracking-[0.18em] uppercase" style={{ color: 'rgba(255,255,255,0.45)' }}>Admin Console</span>
        </div>
      </div>

      {/* 유저 정보 */}
      {user && (
        <div className="flex items-center gap-2.5 px-4 py-3" style={{ backgroundColor: '#152E58', borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
          <div className="w-7 h-7 rounded-full bg-kb-yellow-dark flex items-center justify-center flex-shrink-0">
            <span className="text-xs font-bold text-white">{user.name[0]}</span>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[12px] font-semibold text-white truncate">{user.name}</p>
            <span className="text-[10px] border px-1.5 py-px font-medium rounded-sm inline-block mt-0.5 bg-white/10 text-white/80 border-white/20">
              {primaryRoleLabel(adminRoles)}
            </span>
          </div>
          <button
            onClick={logout}
            title="로그아웃"
            className="text-[11px] transition-colors flex-shrink-0"
            style={{ color: 'rgba(255,255,255,0.35)' }}
            onMouseEnter={(e) => (e.currentTarget.style.color = '#F87171')}
            onMouseLeave={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.35)')}
          >✕</button>
        </div>
      )}

      {/* 대시보드 */}
      <div className="px-3 pt-3 pb-1">
        <Link
          href="/admin/dashboard"
          className={`flex items-center gap-2 px-3 py-2 rounded-kb text-[12px] font-semibold transition-colors
            ${pathname === '/admin/dashboard'
              ? 'bg-kb-yellow-dark text-white'
              : 'text-white/80 hover:bg-white/10 hover:text-white'
            }`}
        >
          <svg width="13" height="13" viewBox="0 0 16 16" fill="currentColor">
            <path d="M8 1L1 7h2v7h4v-4h2v4h4V7h2L8 1z"/>
          </svg>
          대시보드
        </Link>
      </div>

      {/* 섹션별 네비게이션 */}
      <nav className="flex-1 overflow-y-auto pb-4">
        {visibleNav.map((group, idx) => {
          const showDomain = idx === 0 || visibleNav[idx - 1].domain !== group.domain
          return (
          <div key={group.section}>
            {/* 도메인(계) 헤더 — 같은 도메인 첫 섹션에서만. 소유 경계 시각화 */}
            {showDomain && (
              <div className="px-4 pt-3 pb-1 mt-4 first:mt-2" style={{ borderTop: '1px solid rgba(255,255,255,0.1)' }}>
                <p className="text-[11px] font-extrabold tracking-wide" style={{ color: 'rgba(255,255,255,0.8)' }}>
                  {group.domain}
                </p>
              </div>
            )}
            <div className="mt-3">
            <div className="flex items-center gap-1.5 px-4 pb-1.5">
              <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${group.dot}`} />
              <p className="text-[10px] font-bold uppercase tracking-wider" style={{ color: 'rgba(255,255,255,0.4)' }}>
                {group.section}
              </p>
            </div>
            {group.items.map((item) => {
              const active = pathname === item.href || pathname.startsWith(item.href + '/')
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`block px-6 py-[7px] text-[12px] transition-colors border-l-2
                    ${active
                      ? 'border-kb-yellow text-white font-semibold'
                      : 'border-transparent text-white/60 hover:text-white hover:bg-white/8'
                    }`}
                  style={active ? { backgroundColor: 'rgba(91,201,168,0.15)' } : undefined}
                >
                  {item.label}
                </Link>
              )
            })}
            </div>
          </div>
          )
        })}

        {visibleNav.length === 0 && (
          <div className="mx-3 mt-4 px-3 py-3 rounded text-[11px] leading-relaxed" style={{ backgroundColor: 'rgba(239,68,68,0.15)', color: '#FCA5A5', border: '1px solid rgba(239,68,68,0.3)' }}>
            접근 가능한 메뉴가 없습니다.<br />
            임시 권한이 필요한 경우<br />
            관리자에게 문의하세요.
          </div>
        )}
      </nav>

      {/* 하단 로그아웃 */}
      <div className="px-4 py-3" style={{ borderTop: '1px solid rgba(255,255,255,0.08)' }}>
        <button
          onClick={logout}
          className="w-full text-left text-[12px] font-medium transition-colors"
          style={{ color: 'rgba(255,255,255,0.7)' }}
          onMouseEnter={(e) => (e.currentTarget.style.color = '#FCA5A5')}
          onMouseLeave={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.7)')}
        >
          ← 로그아웃
        </button>
      </div>
    </aside>
  )
}
