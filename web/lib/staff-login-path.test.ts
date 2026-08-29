import { describe, it, expect, vi, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

/**
 * 직원이 로그인할 수 있는 문이 실제로 열려 있는지 고정한다.
 *
 * ## 왜 필요한가
 *
 * 배포된 데모에서 **어떤 계정으로도 조사 화면에 들어갈 수 없는** 상태가 있었다.
 * 각 조각은 저마다 정상으로 보였고, 이어 붙였을 때만 막다른 길이 됐다.
 *
 * 1. `AdminGuard` 가 `localStorage.admin_roles` 로 직원인지 판정한다
 * 2. 그 값을 쓰는 곳은 상담 서비스 로그인 두 곳뿐이다
 * 3. 상담 서비스는 데모 스택에 없다 → 값이 채워질 수 없다
 * 4. 가드는 `/admin/login` 으로 되돌린다 → 그것도 상담 서비스다
 *
 * 게다가 그 화면은 백엔드 JWT 가 아니라 `mock.<base64>` 토큰을 스스로 만드는데,
 * `readAccessToken` 이 지우는 형태라 상담 서비스를 올려도 열리지 않았다.
 *
 * 화면은 멀쩡히 그려지고 로그인 폼도 뜬다. **눌러 보기 전에는 드러나지 않는다.**
 */

const WEB_ROOT = resolve(__dirname, '..')

function read(...parts: string[]): string {
  return readFileSync(join(WEB_ROOT, ...parts), 'utf-8')
}

describe('직원 로그인 동선', () => {
  it('AdminGuard 는 동작하는 로그인 화면으로 보낸다', () => {
    const guard = read('components', 'admin', 'AdminGuard.tsx')

    expect(
      guard.includes("router.replace('/admin/login')"),
      '/admin/login 은 상담 서비스의 상담원 로그인이라 은행 직원은 들어갈 수 없다.\n' +
        '거기로 되돌리면 로그인 → 실패 → 다시 로그인의 막다른 길이 된다.',
    ).toBe(false)

    expect(
      guard.includes("'/login?returnUrl='"),
      '가드가 되돌릴 곳은 /login 이고, 원래 가려던 화면을 returnUrl 로 이어야 한다',
    ).toBe(true)
  })

  it('로그인 화면이 직원의 403 을 실패로 취급하지 않는다', () => {
    const login = read('app', '(personal)', 'login', 'page.tsx')

    // 게이트웨이가 /api/v1/customers/me 를 고객 전용으로 막으므로(#72),
    // 직원 로그인에서 403 은 정상이다. 이것을 오류로 처리하면 직원은 로그인할 수 없다.
    expect(
      login.includes('persistEmployeeState'),
      '403 을 "직원이다" 로 해석해 역할을 저장하는 경로가 있어야 한다',
    ).toBe(true)

    // 문구가 아니라 **던지는지**를 본다. 왜 그렇게 고쳤는지 설명하는 주석은
    // 옛 문구를 인용하므로, 단순 포함 검사로는 주석까지 걸린다.
    expect(
      login.includes("throw new Error('고객 계정이 아닙니다"),
      '직원 로그인을 오류로 던지고 있다 — 403 은 직원이라는 신호이지 실패가 아니다',
    ).toBe(false)
  })

  it('헤더의 관리자 링크가 상담 서비스로 가지 않는다', () => {
    const header = read('components', 'layout', 'Header.tsx')
    expect(
      header.includes('href="/admin/login"'),
      '관리자 진입점이 상담 서비스 로그인을 가리킨다',
    ).toBe(false)
  })
})

/**
 * 배포되지 않은 서비스의 메뉴가 화면에 남지 않는지 본다.
 *
 * <p>없는 기능이 안 보이는 것은 문제가 아니다. 문제는 <b>메뉴가 보이는데 눌러도
 * 깨지는 것</b>이다 — 처음 온 사람은 서비스 전체가 고장 났다고 판단한다.
 */
describe('배포되지 않은 서비스의 메뉴', () => {
  it('헤더가 배포 목록을 보고 거른다', () => {
    const header = read('components', 'layout', 'Header.tsx')
    expect(header).toContain('DEPLOYED')
    expect(
      header.includes('GNB_MENUS.map((menu) => {'),
      '거르지 않은 원본 배열을 그대로 그리고 있다',
    ).toBe(false)
  })

  it('챗봇은 상담 서비스가 있을 때만 마운트된다', () => {
    const chrome = read('components', 'layout', 'GlobalChrome.tsx')
    expect(
      chrome.includes('DEPLOYED.consultation && <ChatbotWidget />'),
      '상담 서비스 없이 챗봇을 띄우면 열리기만 하고 답이 오지 않는다',
    ).toBe(true)
  })

  it('메뉴 정의 자체는 지우지 않는다', () => {
    // 빵부스러기(AutoBreadcrumb)·PageLayout 이 같은 배열로 경로를 해석하고,
    // LoanNavConsistencyTest 가 이 정의를 읽는다. 지우면 그 검사가 무력해진다.
    const header = read('components', 'layout', 'Header.tsx')
    expect(header).toContain("href: '/products/loan/credit'")
  })
})

/**
 * 토큰에서 역할을 읽는 부분. 여기서 읽은 값으로 **인가하지 않는다** — 화면을 어디로
 * 보낼지만 정한다. 실제 통제는 게이트웨이와 각 서비스가 매 요청마다 다시 한다.
 */
describe('JWT 역할 읽기', () => {
  afterEach(() => vi.resetModules())

  function makeJwt(payload: object): string {
    const b64 = (o: object) =>
      Buffer.from(JSON.stringify(o), 'utf-8').toString('base64')
        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    return `${b64({ alg: 'HS256' })}.${b64(payload)}.sig`
  }

  it('roles 클레임을 읽는다', async () => {
    const { readTokenRoles } = await import('./token')
    expect(readTokenRoles(makeJwt({ roles: ['ROLE_HQ_RISK'] }))).toEqual(['ROLE_HQ_RISK'])
  })

  it('한글이 든 클레임도 깨지지 않는다', async () => {
    const { readTokenClaims } = await import('./token')
    const claims = readTokenClaims(makeJwt({ roles: [], name: '김리스크' }))
    expect(claims?.name).toBe('김리스크')
  })

  it('JWT 가 아니면 빈 배열이다', async () => {
    const { readTokenRoles } = await import('./token')
    // /admin/login 이 만들던 형태. 게이트웨이가 거절하므로 역할이 있다고 믿으면 안 된다.
    expect(readTokenRoles('mock.eyJyb2xlcyI6WyJST0xFX0FETUlOIl19.123')).toEqual([])
    expect(readTokenRoles(null)).toEqual([])
  })
})
