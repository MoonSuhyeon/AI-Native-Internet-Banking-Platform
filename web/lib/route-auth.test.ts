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
