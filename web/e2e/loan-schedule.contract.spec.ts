import { test, expect } from '@playwright/test'

/**
 * 상환 스케줄 화면 ↔ loan-service 계약 검증 (실제 백엔드 필요).
 *
 * **왜 이 테스트가 있는가.** 이 화면은 프론트가 기대하는 응답 필드명이 백엔드와 전부
 * 어긋난 채로 3개월 넘게 방치돼 있었다.
 *
 *   프론트          백엔드(RepaymentScheduleResponse)
 *   seq             installmentNo
 *   scheduledDt     dueDate
 *   principalAmt    scheduledPrincipal
 *   paidYn          rschStatusCd
 *
 * 응답을 가공 없이 담으므로 전 필드가 undefined 였고 `s.principalAmt.toLocaleString()`
 * 에서 터진다. 그런데 아무도 못 잡았다. 이유가 이 테스트의 존재 이유다.
 *
 *   1. `api.get<any>` 로 받아 그대로 담는다 → TypeScript 인터페이스는 선언일 뿐
 *      검증이 아니다. 무엇을 적어도 컴파일은 통과한다.
 *   2. 기존 E2E 는 백엔드 없이 돈다(playwright.config 의 webServer 는 next dev 뿐).
 *      표가 `{schedules.length > 0 && ...}` 뒤에 있어, 데이터가 없으면 렌더링 자체가
 *      일어나지 않고 테스트는 초록으로 통과한다.
 *
 * 그래서 **진짜 응답으로 표를 그려보는 것** 말고는 이 부류를 잡을 방법이 없다.
 *
 * 실행 전제: infra/docker/docker-compose.e2e.yml 로 loan-service 가 8083 에 떠 있어야 한다.
 * 데이터는 Flyway 시드(V39__seed_repayment_lifecycle)가 넣는다 —
 * 고객 1006 / 계약 9201 / 12회차(PAID 3, OVERDUE 1, DUE 8).
 */

const LOAN_API = process.env.NEXT_PUBLIC_LOAN_API_URL ?? 'http://localhost:8083'

/**
 * 스케줄 표는 이자/월부금입금 화면(InterestPaymentForm)에 있다.
 * URL 의 slug 는 계약 ID 가 아니라 섹션 이름이다 — 잘못 주면 아무것도 렌더링되지 않는다.
 */
const SCHEDULE_PAGE = '/products/loan/manage/payment'

/** 시드가 보장하는 값. 바뀌면 V39 를 함께 고쳐야 한다. */
const SEEDED = {
  customerId: '1006',
  cntrId: 9201,
  installments: 12,
}

test.describe('상환 스케줄 — 실제 백엔드', () => {
  test.beforeEach(async ({ page }) => {
    // 화면은 localStorage 의 customerId 로 계약 목록을 부르고 첫 계약을 고른다.
    //
    // 인증은 admin_user 경로를 쓴다. lib/admin-loan-auth 가 그 값을 읽어
    // loan-service 가 요구하는 X-User-Id / X-User-Role 헤더를 만든다.
    // 두 가지를 지켜야 한다(둘 다 어기면 403 → 계약 목록이 비어 화면이 안 그려진다):
    //   - admin_user 는 JSON 이어야 한다. 문자열이면 JSON.parse 가 실패해 빈 헤더가 된다.
    //   - accessToken 을 함께 두면 안 된다. JWT 가 있으면 게이트웨이 헤더를 주입하지 않는데,
    //     가짜 토큰은 loan-service 가 거부한다.
    await page.addInitScript((customerId) => {
      localStorage.setItem('customerId', customerId)
      localStorage.setItem('admin_user', JSON.stringify({ role: 'ROLE_HQ_RISK', name: 'e2e' }))
      localStorage.setItem('admin_role', 'ROLE_HQ_RISK')
    }, SEEDED.customerId)
  })

  test('백엔드가 살아 있고 시드 계약의 스케줄을 준다', async ({ request }) => {
    const res = await request.get(
      `${LOAN_API}/api/loan-contracts/${SEEDED.cntrId}/repayment-schedules`,
      { headers: { 'X-User-Id': '9104', 'X-User-Role': 'ROLE_OPS' } }
    )
    expect(res.ok()).toBeTruthy()

    const body = await res.json()
    const items = body?.data?.items
    expect(Array.isArray(items)).toBeTruthy()
    expect(items).toHaveLength(SEEDED.installments)

    // 프론트 RepaymentSchedule 인터페이스가 기대하는 필드가 실제로 오는지 —
    // 여기서 어긋나면 화면은 undefined 를 그리게 된다.
    const first = items[0]
    for (const field of [
      'rschId', 'installmentNo', 'dueDate',
      'scheduledPrincipal', 'scheduledInterest', 'scheduledTotal',
      'rschStatusCd',
    ]) {
      expect(first, `응답에 ${field} 가 없다 — 프론트 인터페이스와 어긋났다`).toHaveProperty(field)
    }
    expect(typeof first.scheduledPrincipal).toBe('number')
    expect(typeof first.installmentNo).toBe('number')
  })

  test('스케줄 표가 실제 응답으로 렌더된다', async ({ page }) => {
    await page.goto(SCHEDULE_PAGE)

    // 계약이 로드돼 조회 버튼이 활성화될 때까지 기다린다.
    // 내비게이션에도 '조회' 버튼이 있어 이름으로는 둘이 잡힌다 — 훅으로 가려낸다.
    const search = page.getByTestId('contract-search')
    await expect(search).toBeEnabled({ timeout: 15_000 })
    await search.click()

    const rows = page.locator('tbody tr')
    await expect(rows).toHaveCount(SEEDED.installments, { timeout: 15_000 })

    // 금액 칸이 숫자로 채워졌는지 — 필드명이 어긋나면 undefined 라 여기서 터지거나 빈다.
    const firstRow = rows.first()
    await expect(firstRow.locator('td').nth(0)).toHaveText('1')       // installmentNo
    await expect(firstRow.locator('td').nth(1)).toHaveText('2025-04-10') // dueDate 포맷
    await expect(firstRow.locator('td').nth(2)).toContainText('1,000,000원')
    await expect(firstRow.locator('td').nth(4)).toContainText('1,047,000원')
  })

  test('회차 상태가 rschStatusCd 대로 표시된다', async ({ page }) => {
    await page.goto(SCHEDULE_PAGE)
    // 내비게이션에도 '조회' 버튼이 있어 이름으로는 둘이 잡힌다 — 훅으로 가려낸다.
    const search = page.getByTestId('contract-search')
    await expect(search).toBeEnabled({ timeout: 15_000 })
    await search.click()
    await expect(page.locator('tbody tr')).toHaveCount(SEEDED.installments, { timeout: 15_000 })

    // 시드: 1~3회차 PAID, 4회차 OVERDUE, 5~12회차 DUE.
    // 예전에는 존재하지 않는 paidYn 을 'Y' 와 비교해 전부 "미납"으로 보였다.
    //
    // 표 안으로 범위를 좁힌다 — '연체' 같은 낱말은 내비게이션에도 있어
    // 페이지 전체에서 세면 다른 것까지 잡힌다.
    const body = page.locator('tbody')
    await expect(body.getByText('납입완료', { exact: true })).toHaveCount(3)
    await expect(body.getByText('연체', { exact: true })).toHaveCount(1)
    await expect(body.getByText('납입예정', { exact: true })).toHaveCount(8)

    // 이미 낸 회차에는 납입 버튼이 없어야 한다(PAID 3건 제외).
    await expect(body.getByRole('button', { name: '납입' })).toHaveCount(9)
  })
})
