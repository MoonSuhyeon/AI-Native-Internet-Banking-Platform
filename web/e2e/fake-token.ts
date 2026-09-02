/**
 * E2E 가 인증 가드를 통과하는 데 쓰는 토큰.
 *
 * ## 왜 아무 문자열이나 쓰면 안 되나
 *
 * `readAccessToken()`(web/lib/token.ts)은 JWT 모양이 아닌 값을 만나면 그 값만
 * 버리는 것이 아니라 **저장소를 통째로 비운다**(`clearAuthStorage`). 다음 렌더에서
 * 또 로그인된 것처럼 보이지 않게 하려는 것이다.
 *
 * 그래서 `'e2e-fake-token'` 같은 값을 심으면, 방금 함께 심은 `admin_roles` 까지
 * 지워진 채로 가드가 돌아 로그인 화면으로 튕긴다. 화면에 아무것도 없으니 테스트는
 * "요소를 못 찾음" 으로 실패하는데, 정작 실패 이유는 검증하려던 것과 아무 상관이
 * 없다. 실제로 어드민 모니터링 5건과 이체 1건이 이 이유로 깨져 있었다.
 *
 * ## 무엇이 아닌가
 *
 * 서명은 가짜다. 게이트웨이나 백엔드를 상대로는 통하지 않는다 — 백엔드 없이 도는
 * 화면 검증에서만 쓴다. 계약 테스트(`*.contract.spec.ts`)에는 넣지 말 것.
 */
function b64url(o: unknown): string {
  return btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** JWT 모양(`eyJ...` 세 토막)을 갖춘 가짜 토큰. roles 는 화면 게이팅이 읽는다. */
export function fakeJwt(roles: string[] = []): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url({ sub: 'e2e', roles })}.sig`
}
