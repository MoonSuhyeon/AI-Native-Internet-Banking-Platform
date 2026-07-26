import { test, expect } from '@playwright/test'

/**
 * 계좌이체 진입 플로우 E2E — 실제 백엔드/로그인 없이 프론트 흐름만 검증한다.
 *  - accessToken 을 주입해 인증 가드를 통과시키고,
 *  - /api/v1/** 호출은 route 로 목킹해 계좌 목록 등 로드 API를 대체한다.
 * 다단계(account → confirm → result) 데이터 전달은 클라이언트 상태에 의존하므로,
 * 여기서는 진입 페이지 렌더 + 금액 입력 상호작용까지 검증한다(이후 단계는 확장 지점).
 */
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'e2e-fake-token')
  })
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
})

test('계좌이체 페이지가 렌더되고 금액 입력이 동작한다', async ({ page }) => {
  await page.goto('/transfer/account')

  await expect(page.getByRole('heading', { name: '계좌이체' })).toBeVisible()

  // 금액 입력 필드 상호작용 (숫자만 허용되는 입력)
  // 금액 입력 시 천단위 콤마로 포맷된다.
  const amount = page.getByPlaceholder('0').first()
  await amount.fill('10000')
  await expect(amount).toHaveValue('10,000')
})
