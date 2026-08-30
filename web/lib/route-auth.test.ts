import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 드롭다운에서 갈 수 있는 화면의 로그인 요구 여부를 고정한다.
 *
 * ## 왜 필요한가
 *
 * `AuthGuard` 의 공개 목록이 접두사라 `/products` 와 `/support` 를 통째로 열었다.
 * 그 아래에는 상품 안내만 있는 것이 아니라 **예금해지·회원탈퇴·인터넷뱅킹 해지와
 * 가입·해지 내역 조회**가 함께 있었고, 그래서 로그인하지 않아도 전부 열렸다.
 *
 * 조회 API 는 게이트웨이가 막으므로 값은 비어 있었다. 그러나 화면이 열린다는 것
 * 자체가 문제다 — 무엇을 할 수 있고 어떤 항목을 다루는지가 그대로 보인다.
 * 게다가 그 빈 화면을 예시 데이터로 덮으면 남의 기록처럼 보인다(실제로 그랬다).
 *
 * ## 무엇을 보는가
 *
 * 판정을 그대로 재현해 경로별 기대와 맞춘다. 목록이 둘(공개·비공개)이고 더 좁은
 * 쪽이 이기는 구조라, 새 경로를 넣을 때 어느 쪽에 넣어야 하는지 헷갈리기 쉽다.
 */

const GUARD = resolve(__dirname, '..', 'components', 'layout', 'AuthGuard.tsx')

/** 가드 소스에서 목록을 읽는다. 목록이 코드에 있으므로 그것을 정본으로 본다. */
function prefixes(constName: string): string[] {
  const src = readFileSync(GUARD, 'utf-8')
  const block = src.split(`const ${constName} = [`)[1]
  expect(block, `${constName} 을 찾지 못했다 — 가드 구조가 바뀌었다`).toBeTruthy()
  const body = block.split(']')[0]
  return Array.from(body.matchAll(/'([^']+)'/g)).map(m => m[1])
}

function isPublic(pathname: string): boolean {
  const privates = prefixes('PRIVATE_PREFIXES')
  const publics = prefixes('PUBLIC_PREFIXES')
  if (privates.some(p => pathname.startsWith(p))) return false
  return pathname === '/' || publics.some(p => pathname.startsWith(p))
}

/** 본인의 계좌·계약·계정 상태를 보여주거나 바꾸는 화면. */
const NEEDS_LOGIN = [
  ['/inquiry/accounts', '계좌조회'],
  ['/inquiry/transactions', '거래내역 조회'],
  ['/transfer/account', '계좌이체'],
  ['/transfer/inquiry', '계좌이체결과 조회'],
  ['/products/deposit/inquiry/new', '신규결과/내역 조회'],
  ['/products/deposit/inquiry/terminate', '예금해지'],
  ['/products/deposit/inquiry/terminate-result', '해지결과/내역 조회'],
  ['/products/deposit/join/1', '예금 가입'],
  ['/support/customer-info/withdraw', '회원탈퇴'],
  ['/support/customer-info/internet-banking-cancel', '인터넷뱅킹 해지'],
]

/** 상품 설명·약관·FAQ·가입 전 절차. 로그인 전에 보여야 한다. */
const OPEN_TO_ALL = [
  ['/', '홈'],
  ['/login', '로그인'],
  ['/products/deposit/list', '예금 상품 목록'],
  ['/support/customer-info/online-join', '온라인고객 신규가입'],
  ['/support/customer-info/id-password', 'ID조회·사용자암호 설정'],
  ['/support/faq', 'FAQ'],
]

describe('드롭다운 화면의 로그인 요구', () => {
  it('본인 데이터를 다루는 화면은 로그인이 필요하다', () => {
    const leaked = NEEDS_LOGIN.filter(([path]) => isPublic(path))
      .map(([path, label]) => `${label} (${path})`)

    expect(
      leaked,
      '로그인 없이 열리는 화면이다. 조회 API 가 막혀 값은 비어도,\n' +
        '무엇을 할 수 있고 어떤 항목을 다루는지가 그대로 보인다.\n' +
        'AuthGuard 의 PRIVATE_PREFIXES 에 넣을 것.\n',
    ).toEqual([])
  })

  it('안내·가입 전 화면은 로그인 없이 열린다', () => {
    const blocked = OPEN_TO_ALL.filter(([path]) => !isPublic(path))
      .map(([path, label]) => `${label} (${path})`)

    expect(
      blocked,
      '로그인을 요구하면 안 되는 화면이다. 계정 찾기는 로그인하지 못하는\n' +
        '사람이 쓰고, 상품 안내와 신규가입은 계정이 없는 사람이 본다.\n',
    ).toEqual([])
  })

  it('목록을 실제로 읽는다', () => {
    // 가드 구조가 바뀌어 목록을 못 읽으면 위 검사가 조용히 무력해진다.
    expect(prefixes('PUBLIC_PREFIXES').length).toBeGreaterThan(3)
    expect(prefixes('PRIVATE_PREFIXES').length).toBeGreaterThan(3)
  })
})


/**
 * 로그인 뒤 막다른 길로 보내지 않는지 본다.
 *
 * <p>홈 배너가 조사 화면을 가리키므로, 고객이 그 버튼을 누르면 returnUrl 에
 * {@code /admin/...} 이 담긴 채 로그인한다. 그대로 보내면 AdminGuard 가 되돌리고,
 * 화면에서는 <b>로그인이 안 되는 것과 구분되지 않는다.</b>
 */
describe('로그인 뒤 목적지', () => {
  it('고객은 직원 화면으로 보내지 않는다', () => {
    const login = readFileSync(
      resolve(__dirname, '..', 'app', '(personal)', 'login', 'page.tsx'), 'utf-8')

    expect(
      login.includes('customerTarget'),
      '고객 목적지를 따로 고르는 곳이 없다. returnUrl 이 /admin 이면 홈으로 보낼 것',
    ).toBe(true)

    expect(
      login.includes("target.startsWith('/admin')"),
      '/admin 으로 가려던 고객을 걸러내지 않는다 — 로그인 → 되돌림 → 로그인이 반복된다',
    ).toBe(true)
  })
})


/**
 * 토큰 검증이 <b>불가능한</b> 경우와 <b>거절된</b> 경우를 가르는지 본다.
 *
 * <p>AdminGuard 가 부르는 /api/v1/auth/verify 는 Next 라우트 핸들러로만 있다.
 * 운영에서는 Caddy 가 /api/* 를 전부 게이트웨이로 보내므로 백엔드에 없는 경로가 되어
 * 404 가 난다. 그것을 무효 토큰으로 취급하면 <b>직원이 로그인 직후 튕겨 나간다</b> —
 * 화면에서는 로그인이 안 되는 것과 구분되지 않는다.
 */
describe('관리자 가드의 토큰 검증', () => {
  it('401·403 만 무효로 본다', () => {
    const guard = readFileSync(
      resolve(__dirname, '..', 'components', 'admin', 'AdminGuard.tsx'), 'utf-8')

    expect(
      guard.includes('res.status === 401 || res.status === 403'),
      '응답 실패를 뭉뚱그려 로그아웃시키고 있다. 404·5xx 는 검증을 못 한 것이지 토큰이 틀린 것이 아니다',
    ).toBe(true)

    expect(
      guard.includes('if (!res.ok) {'),
      '!res.ok 로 판정하면 404 에도 로그아웃된다',
    ).toBe(false)
  })
})


/**
 * 홈에서 조사 화면까지 이어지는지 본다.
 *
 * <p>히어로 버튼 → (미로그인이면) 관리자 로그인 → 다시 조사 화면. 중간에 returnUrl
 * 이 끊기면 로그인에 성공해도 누른 적 없는 화면이 뜨고, 사용자에게는 버튼이 안 먹는
 * 것으로 보인다.
 */
describe('홈에서 조사 화면으로', () => {
  it('히어로 버튼이 조사 화면을 바로 가리킨다', () => {
    const hero = readFileSync(
      resolve(__dirname, '..', 'components', 'home', 'HeroCarousel.tsx'), 'utf-8')

    expect(hero).toContain("href: '/admin/fraud'")

    // 로그인 주소를 박아 두면 이미 로그인한 사람까지 로그인 화면을 거치고,
    // 로그인 경로가 바뀔 때 여기도 함께 고쳐야 한다.
    expect(
      hero.includes('/login?returnUrl='),
      '히어로가 로그인 주소를 직접 가리키고 있다 — 가드가 알아서 보내게 둘 것',
    ).toBe(false)
  })

  it('가드가 되돌릴 때 원래 화면을 기억한다', () => {
    const guard = readFileSync(
      resolve(__dirname, '..', 'components', 'admin', 'AdminGuard.tsx'), 'utf-8')
    expect(guard).toContain("'/admin/login?returnUrl='")
  })
})


/**
 * 세션이 끊겼을 때 <b>어느 로그인 화면으로</b> 보내는지 본다.
 *
 * <p>인터셉터가 401 을 만나면 무조건 고객 로그인(/login)으로 보냈다. 관리자 화면에서
 * 끊긴 직원까지 인증서·간편비밀번호 탭이 있는 고객 화면으로 넘어가, 자기 계정이
 * 통하지 않는 자리에서 아이디를 넣게 됐다.
 */
describe('세션이 끊겼을 때 돌아갈 로그인 화면', () => {
  it('관리자 화면은 관리자 로그인으로 보낸다', async () => {
    const { loginScreenFor } = await import('./return-url')
    expect(loginScreenFor('/admin/fraud')).toBe('/admin/login')
    expect(loginScreenFor('/inquiry/accounts')).toBe('/login')
  })

  it('인터셉터가 로그인 화면을 박아 두지 않는다', () => {
    const api = readFileSync(resolve(__dirname, 'api.ts'), 'utf-8')
    expect(
      api.includes('window.location.href = "/login"'),
      '고객 로그인 화면이 박혀 있다 — 관리자 화면에서 끊긴 직원도 그리로 간다',
    ).toBe(false)
    expect(api).toContain('loginScreenFor(window.location.pathname)')
  })

  it('직원 로그인 실패를 세션 만료로 취급하지 않는다', () => {
    const api = readFileSync(resolve(__dirname, 'api.ts'), 'utf-8')
    // 자격이 틀린 401 을 갱신 대상으로 보면, 로그인 화면에 있는 사람을 다시
    // 로그인 화면으로 보내 입력한 것이 사라진다.
    expect(api).toContain('"/auth/employee/login"')
  })
})
