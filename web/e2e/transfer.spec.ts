import { test, expect } from '@playwright/test'
import { fakeJwt } from './fake-token'

/**
 * 계좌이체 진입 플로우 E2E — 실제 백엔드/로그인 없이 프론트 흐름만 검증한다.
 *  - accessToken 을 주입해 인증 가드를 통과시키고,
 *  - /api/v1/** 호출은 route 로 목킹해 계좌 목록 등 로드 API를 대체한다.
 * 다단계(account → confirm → result) 데이터 전달은 클라이언트 상태에 의존하므로,
 * 여기서는 진입 페이지 렌더 + 금액 입력 상호작용까지 검증한다(이후 단계는 확장 지점).
 */
test.beforeEach(async ({ page }) => {
  // JWT 모양이어야 한다 — 아니면 readAccessToken() 이 저장소를 비워 가드가
  // 로그인으로 보낸다. 자세한 이유는 fake-token.ts 에 적어 두었다.
  const token = fakeJwt()
  await page.addInitScript((t) => {
    localStorage.setItem('accessToken', t)
  }, token)
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

/**
 * 홈 "시연 2 · 위험 안내 받기" 로 들어온 경우.
 *
 * <p>위험 안내는 신호 둘(반복 이체 + 고액)이 쌓여야 뜬다. 조건을 모르면 이 화면을
 * 평범한 이체 폼으로 보고 지나가므로, 화면이 조건을 알리고 받는 곳·금액을 미리
 * 채운다. <b>받는 곳은 탭까지 옮겨야 보인다</b> — 기본 탭은 내 계좌 목록에서 고르는
 * select 라 값만 넣으면 화면에 아무것도 나타나지 않는다. 그 함정을 여기서 막는다.
 */
test('?demo=risk 로 들어오면 조건 안내와 함께 받는 곳·금액이 채워진다', async ({ page }) => {
  await page.goto('/transfer/account?demo=risk')

  await expect(page.getByText('소액을 여러 번 이체한 뒤 1천만원 이상')).toBeVisible()
  await expect(page.getByPlaceholder('AXful Bank 계좌번호 입력')).toHaveValue('001-2000-0000017')
  await expect(page.getByPlaceholder('0').first()).toHaveValue('12,000,000')
})
