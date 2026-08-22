/**
 * 로그인 뒤 돌아갈 곳.
 *
 * **왜 필요한가.** 로그인 가드가 목적지를 버렸다. 홈에서 "AI 대출 심사" 를 누르면
 * `/loans/apply` 로 갔다가 `AuthGuard` 가 `router.replace('/login')` 으로 되돌리는데,
 * **어디로 가려던 참이었는지는 아무 데도 남지 않았다.** 로그인 화면은 성공하면
 * 무조건 `window.location.href = '/'` 였다.
 *
 * 그래서 사용자가 겪는 것은 이렇다 — 버튼을 누른다, 로그인하라고 한다, 로그인한다,
 * **누른 적 없는 홈이 뜬다.** 누른 것과 나온 것이 이어지지 않으니 "버튼이 안 먹는다"
 * 로 보인다. 실제로 각 단계는 다 성공했다.
 *
 * 여기서 목적지를 `returnUrl` 로 실어 보내고, 로그인 성공 시 그리로 되돌린다.
 *
 * **왜 검사하는가.** `returnUrl` 은 주소창에 있으니 누구나 고칠 수 있다. 검사 없이
 * 그대로 이동하면 `?returnUrl=https://악성.example` 같은 링크가 **우리 로그인
 * 화면을 거쳐 남의 사이트로 보내는** 통로가 된다(open redirect). 은행 도메인에서
 * 출발했으니 피싱 쪽에서 값이 크다. 그래서 **이 사이트 안의 경로만** 허용한다.
 */

/** 로그인 뒤 되돌아갈 수 없는 곳. 되돌리면 제자리를 맴돈다. */
const NEVER_RETURN_TO = ['/login', '/logout']

/**
 * 이 사이트 안의 경로인가.
 *
 * <p>`/` 로 시작하는지만 보면 부족하다. `//evil.example` 은 프로토콜 상대 URL 이라
 * 브라우저가 <b>바깥 호스트</b>로 읽고, `/\evil.example` 도 같게 취급하는 브라우저가
 * 있다. 둘 다 `/` 로 시작한다.
 */
function isInternalPath(value: string): boolean {
  if (!value.startsWith('/')) return false
  if (value.startsWith('//') || value.startsWith('/\\')) return false
  return !NEVER_RETURN_TO.some((p) => value === p || value.startsWith(p + '/') || value.startsWith(p + '?'))
}

/** 가드가 보낼 로그인 주소. 지금 있던 곳을 실어 보낸다. */
export function loginUrlFor(pathname: string, search = ''): string {
  const target = pathname + search
  if (!isInternalPath(target)) return '/login'
  return '/login?returnUrl=' + encodeURIComponent(target)
}

/**
 * 로그인 성공 뒤 갈 곳. 실어 온 것이 없거나 수상하면 홈.
 *
 * <p>`useSearchParams` 를 쓰지 않는다 — 로그인 처리는 컴포넌트 밖 함수들에도
 * 흩어져 있어서 훅을 부를 수 없는 자리가 있다.
 */
export function postLoginTarget(): string {
  if (typeof window === 'undefined') return '/'
  const raw = new URLSearchParams(window.location.search).get('returnUrl')
  if (!raw) return '/'
  const decoded = (() => {
    try { return decodeURIComponent(raw) } catch { return '' }
  })()
  return isInternalPath(decoded) ? decoded : '/'
}

/** 다른 로그인 화면(`/login/pin` 등)으로 넘어갈 때 목적지를 잃지 않게 한다. */
export function withReturnUrl(path: string): string {
  if (typeof window === 'undefined') return path
  const target = postLoginTarget()
  if (target === '/') return path
  return path + (path.includes('?') ? '&' : '?') + 'returnUrl=' + encodeURIComponent(target)
}
