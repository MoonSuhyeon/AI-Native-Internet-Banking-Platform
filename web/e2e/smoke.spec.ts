import { test, expect } from '@playwright/test'

/**
 * 하네스 작동 검증용 스모크 — 백엔드 없이 렌더되는 페이지로 E2E 파이프라인이 도는지 확인.
 * (도입에서 가장 중요한 단계: 하네스가 실제로 도는지 1개로 확인)
 */
test('PIN 로그인 페이지가 렌더된다', async ({ page }) => {
  await page.goto('/login/pin')
  await expect(page.getByRole('heading', { name: '간편비밀번호 로그인' })).toBeVisible()
  await expect(page.getByText('이 기기에 등록한 6자리 PIN으로 로그인합니다.')).toBeVisible()
})
