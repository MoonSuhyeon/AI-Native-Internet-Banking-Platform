/**
 * 저장된 액세스 토큰 읽기.
 *
 * **왜 검사가 필요한가.** 예전에 프론트 라우트 몇 개가 백엔드를 거치지 않고
 * `mock.<base64>.<시각>` 형태의 가짜 토큰을 내줬다. 그 라우트들은 지웠지만
 * **이미 브라우저에 저장된 토큰은 그대로 남는다.**
 *
 * 남아 있으면 최악의 상태가 된다.
 *
 * - 헤더는 토큰이 있으니 **로그인된 것으로 표시**한다
 * - 게이트웨이는 그 토큰을 거절하므로 **모든 API 가 401**
 * - 화면과 실제가 어긋난 채로 사용자는 "왜 안 되지" 만 겪는다
 *
 * `lib/api.ts` 주석이 이 상태를 "좀비 로그인" 이라고 부른다. 토큰 만료로 생기는
 * 쪽은 refresh 로 풀었지만, **애초에 유효하지 않은 토큰**이 들어온 경우는 막는
 * 곳이 없었다.
 *
 * 그래서 읽는 지점에서 한 번 본다. JWT 가 아니면 지우고 없는 것으로 취급한다 —
 * 로그인 화면으로 돌아가는 편이 "로그인은 됐는데 아무것도 안 되는" 것보다 낫다.
 */

const KEYS = ['accessToken', 'access_token'] as const

/**
 * JWT 모양인가.
 *
 * <p>서명을 검증하지는 않는다 — 그건 게이트웨이의 일이고 브라우저가 할 수 있는
 * 일도 아니다. 여기서 거르는 것은 <b>애초에 JWT 가 아닌 값</b>이다.
 *
 * <p>JWT 헤더는 `{"alg":...` 로 시작하는 JSON 의 base64url 이라 항상 `eyJ` 로
 * 시작하고, 점으로 세 부분이 나뉜다.
 */
export function looksLikeJwt(token: string | null | undefined): boolean {
  if (!token) return false
  const parts = token.split('.')
  return parts.length === 3 && parts[0].startsWith('eyJ')
}

/** 저장된 토큰. JWT 가 아니면 지우고 `null` 을 돌려준다. */
export function readAccessToken(): string | null {
  if (typeof window === 'undefined') return null

  for (const key of KEYS) {
    const value = localStorage.getItem(key)
    if (!value) continue
    if (looksLikeJwt(value)) return value

    // 유효하지 않은 토큰은 지운다. 남겨 두면 다음 렌더에서 또 로그인된 것처럼 보인다.
    clearAuthStorage()
    return null
  }
  return null
}

/** 로그아웃·무효 토큰 정리. 흩어져 있던 같은 목록을 한 곳으로 모았다. */
export function clearAuthStorage(): void {
  if (typeof window === 'undefined') return
  for (const key of [
    'accessToken', 'access_token', 'refreshToken',
    'sessionExpiry', 'user', 'customerId', 'customerNo',
    // 직원 로그인이 남기는 것. 여기서 지우지 않으면 로그아웃 뒤에도 AdminGuard 가
    // 직원으로 판정해, 토큰 없이 관리자 화면이 열렸다가 API 만 전부 401 이 된다.
    'admin_roles', 'admin_user',
  ]) {
    localStorage.removeItem(key)
  }
}


/**
 * JWT payload 의 클레임을 읽는다. **서명은 검증하지 않는다.**
 *
 * <p>브라우저는 서명을 검증할 수 없고 해서도 안 된다 — 그건 게이트웨이의 일이다.
 * 여기서 읽은 값으로 <b>인가를 판정하면 안 된다.</b> 쓰임은 하나다: 화면을 어디로
 * 보낼지, 어떤 메뉴를 그릴지 정하는 것. 실제 접근 통제는 매 요청마다 게이트웨이와
 * 각 서비스가 다시 한다.
 *
 * <p>그래서 이 값을 고쳐도 뚫리지 않는다. localStorage 의 역할 배열을 손으로
 * 바꾸면 메뉴는 보이지만, 그 화면이 부르는 API 는 토큰의 진짜 역할로 판정되어
 * 전부 거절된다.
 */
export function readTokenClaims(token: string | null | undefined): Record<string, unknown> | null {
  if (!looksLikeJwt(token)) return null
  try {
    // base64url → base64. atob 는 -/_ 를 모르고, 패딩이 없으면 던진다.
    const payload = token!.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = payload + '='.repeat((4 - (payload.length % 4)) % 4)
    // 한글 이름이 들어 있어 escape/decodeURIComponent 없이 바로 파싱하면 깨진다.
    const json = decodeURIComponent(
      atob(padded)
        .split('')
        .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join(''),
    )
    const parsed: unknown = JSON.parse(json)
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}

/**
 * 토큰이 말하는 역할 목록. 없거나 못 읽으면 빈 배열.
 *
 * <p>백엔드가 {@code roles} 클레임에 {@code ROLE_HQ_RISK} 같은 authority 를 담는다
 * (JwtProvider.CLAIM_ROLES). 고객은 {@code ROLE_CUSTOMER} 하나다.
 */
export function readTokenRoles(token: string | null | undefined): string[] {
  const claims = readTokenClaims(token)
  const roles = claims?.roles
  if (!Array.isArray(roles)) return []
  return roles.filter((r): r is string => typeof r === 'string')
}
