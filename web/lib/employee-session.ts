import { clearAuthStorage, readTokenClaims, readTokenRoles } from '@/lib/token'
import { BankRole, branchLabel } from '@/lib/admin-auth'

/**
 * 직원 로그인 뒤 세션을 저장한다.
 *
 * ## 왜 한 곳에 모았나
 *
 * 직원이 들어오는 문이 둘이다 — 고객과 같은 `/login`, 그리고 `/admin/login`.
 * 저장하는 값이 어긋나면 한쪽으로 들어온 사람만 관리자 화면이 열리는, 원인을
 * 찾기 어려운 상태가 된다. 실제로 그런 적이 있다: `/admin/login` 은 백엔드 JWT 가
 * 아니라 `mock.<base64>` 토큰을 스스로 만들었고, 그 토큰은 게이트웨이가 거절해
 * 화면만 열리고 모든 API 가 401 이었다.
 *
 * ## 여기 저장하는 역할로 인가하지 않는다
 *
 * 메뉴를 그릴지 말지를 정할 뿐이다. 화면이 부르는 API 는 매번 게이트웨이와 각
 * 서비스가 **토큰의 진짜 역할**로 다시 판정한다. 그래서 localStorage 를 손으로
 * 고쳐도 메뉴만 보이고 호출은 전부 거절된다.
 */

/** 직원이 아닐 때 던지는 오류. 화면이 문구를 그대로 보여준다. */
export class NotAnEmployeeError extends Error {
  constructor() {
    super('직원 계정이 아닙니다. 고객은 개인 로그인 화면을 이용해 주세요.')
    this.name = 'NotAnEmployeeError'
  }
}

/**
 * 토큰이 직원의 것인지.
 *
 * <p>고객 토큰에는 {@code ROLE_CUSTOMER} 하나만 담긴다(AuthEventService). 직원은
 * 자기 등급이 담기므로, 고객 역할 말고 다른 것이 하나라도 있으면 직원이다.
 */
export function isEmployeeToken(accessToken: string): boolean {
  return readTokenRoles(accessToken).some(r => r !== BankRole.CUSTOMER)
}

/**
 * 직원 세션을 저장하고 갈 곳을 돌려준다.
 *
 * @param accessToken 백엔드가 발급한 JWT
 * @param loginId 입력한 아이디. 화면에 이름 대신 쓴다
 * @param fallback 돌아갈 곳이 따로 없을 때 보낼 화면
 * @throws NotAnEmployeeError 고객 토큰이면
 */
export function persistEmployeeSession(
  accessToken: string,
  loginId: string,
  fallback = '/admin/fraud',
): string {
  const roles = readTokenRoles(accessToken)
  if (!roles.some(r => r !== BankRole.CUSTOMER)) {
    // 여기서 멈추지 않으면 고객이 관리자 화면에 들어간 것처럼 보이고, 그 화면이
    // 부르는 API 만 전부 403 이 된다 — 무엇이 잘못됐는지 알 수 없는 상태다.
    clearAuthStorage()
    throw new NotAnEmployeeError()
  }

  const claims = readTokenClaims(accessToken) ?? {}
  const branch = typeof claims.branch === 'string' ? claims.branch : undefined

  localStorage.setItem('accessToken', accessToken)
  localStorage.setItem('access_token', accessToken)
  localStorage.setItem('admin_roles', JSON.stringify(roles))
  localStorage.setItem('admin_user', JSON.stringify({
    loginId,
    name: loginId,
    branchCode: branch ?? '-',
    branchName: branchLabel(branch),
  }))

  return fallback
}
