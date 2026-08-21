import { readAccessToken } from '@/lib/token'
/**
 * 어드민 목업 세션 → loan-service 게이트웨이 헤더 변환 유틸
 *
 * 어드민 로그인은 customer-service JWT를 발급하지 않고 localStorage에
 * admin_role / admin_user 만 저장하는 목업 방식이다.
 * loan-service는 게이트웨이를 통해 주입되는 X-User-Id / X-User-Role 헤더로
 * 인증하므로, 로컬 개발 환경에서는 이 유틸이 해당 헤더를 생성한다.
 *
 * 매핑 기준 (customer-service/application.yml employee-directory 참조):
 *   admin mock role       → customer_id  loan role
 *   ROLE_HQ_REVIEW        → 9103         ROLE_HQ_REVIEWER
 *   ROLE_HQ_AUDIT         → 9105         ROLE_COMPLIANCE
 *   ROLE_HQ_RISK          → 9104         ROLE_OPS
 *   ROLE_HQ_MARKETING     → 9104         ROLE_OPS
 *   ROLE_PRIMARY_OWNER    → 9002         ROLE_DEPUTY_MANAGER
 *   ROLE_BRANCH_STAFF     → 9002         ROLE_DEPUTY_MANAGER
 *   ROLE_OTHER_BRANCH     → 9001         ROLE_BRANCH_MANAGER
 *   (fallback)            → 9104         ROLE_OPS
 */

const ADMIN_ROLE_MAP: Record<string, { userId: number; loanRole: string }> = {
  ROLE_HQ_REVIEW:     { userId: 9103, loanRole: 'ROLE_HQ_REVIEWER' },
  ROLE_HQ_AUDIT:      { userId: 9105, loanRole: 'ROLE_COMPLIANCE' },
  ROLE_HQ_RISK:       { userId: 9104, loanRole: 'ROLE_OPS' },
  ROLE_HQ_MARKETING:  { userId: 9104, loanRole: 'ROLE_OPS' },
  ROLE_PRIMARY_OWNER: { userId: 9002, loanRole: 'ROLE_DEPUTY_MANAGER' },
  ROLE_BRANCH_STAFF:  { userId: 9002, loanRole: 'ROLE_DEPUTY_MANAGER' },
  ROLE_OTHER_BRANCH:  { userId: 9001, loanRole: 'ROLE_BRANCH_MANAGER' },
}

const FALLBACK = { userId: 9104, loanRole: 'ROLE_OPS' }

/**
 * localStorage의 admin_user 세션에서 loan-service 게이트웨이 헤더를 생성.
 * accessToken이 있으면 헤더를 주입하지 않음 (JWT 인증 우선).
 *
 * @returns 헤더 객체 또는 빈 객체
 */
export function getAdminGatewayHeaders(): Record<string, string> {
  if (typeof window === 'undefined') return {}

  // JWT가 있으면 게이트웨이 헤더 불필요 (JwtFallbackAuthFilter 가 처리)
  if (readAccessToken()) return {}

  const adminUserJson = localStorage.getItem('admin_user')
  if (!adminUserJson) return {}

  try {
    const adminUser = JSON.parse(adminUserJson) as { role?: string }
    const mapping = ADMIN_ROLE_MAP[adminUser.role ?? ''] ?? FALLBACK
    return {
      'X-User-Id':   String(mapping.userId),
      'X-User-Role': mapping.loanRole,
    }
  } catch {
    return {}
  }
}

/**
 * 현재 어드민 세션의 loan 역할(ROLE_*)을 반환한다.
 * 어드민 화면 버튼 노출 제어(UX)용 — 서버 인가는 loan-service SecurityConfig가 담당한다.
 *
 * 역할은 로그인 시 JWT에서 추출해 admin_roles(BankRole[])에 저장된다. admin_user에는
 * 역할 필드가 없으므로(표시용 신원만 보관) admin_roles를 단일 출처로 읽는다.
 * loan 관련 역할이 없으면(또는 SSR) 빈 문자열을 반환해 버튼을 숨긴다(최소권한 기본값).
 */
const LOAN_ROLE_PRIORITY = [
  'ROLE_OPS', 'ROLE_DEPUTY_MANAGER', 'ROLE_BRANCH_MANAGER', 'ROLE_HQ_REVIEWER', 'ROLE_COMPLIANCE',
]
export function getAdminLoanRole(): string {
  if (typeof window === 'undefined') return ''

  try {
    const raw = localStorage.getItem('admin_roles')
    const roles: string[] = raw ? JSON.parse(raw) : []
    if (!Array.isArray(roles)) return ''
    return LOAN_ROLE_PRIORITY.find((r) => roles.includes(r)) ?? ''
  } catch {
    return ''
  }
}
