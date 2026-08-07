import { test, expect, Page } from '@playwright/test'

/**
 * 모니터링 화면의 역할 게이팅.
 *
 * 사이드바에서 메뉴를 감추는 것은 표시일 뿐 접근 제어가 아니다. 주소를 직접 치면
 * 그대로 열린다. 그래서 화면 자체가 막는지를 확인한다 — 게이팅이 풀려도 화면은
 * 멀쩡히 뜨기 때문에 눈으로는 회귀를 알아채기 어렵다.
 *
 * Grafana 가 떠 있지 않아도 성립한다. 대시보드 로딩 여부가 아니라 권한 분기만 본다.
 */

const ALLOWED = ['ROLE_OPS', 'ROLE_HQ_RISK', 'ROLE_ADMIN']
const DENIED = ['ROLE_TELLER', 'ROLE_COMPLIANCE']

async function signInAs(page: Page, role: string) {
  await page.addInitScript((r) => {
    localStorage.setItem('admin_roles', JSON.stringify([r]))
    // AdminGuard 는 토큰 존재를 확인한 뒤 서버 검증을 시도한다.
    // 검증 서버가 없으면 클라이언트 판정으로 통과시키므로 값은 아무거나 된다.
    localStorage.setItem('accessToken', 'e2e-dummy-token')
  }, role)
}

test.describe('어드민 모니터링 화면 접근 제어', () => {
  for (const role of ALLOWED) {
    test(`${role} 은 대시보드를 연다`, async ({ page }) => {
      await signInAs(page, role)
      await page.goto('/admin/monitoring')

      await expect(page.getByRole('heading', { name: '모니터링 대시보드' })).toBeVisible()
      await expect(page.getByText('열람 권한이 없습니다')).toHaveCount(0)
      await expect(page.locator('aside a[href="/admin/monitoring"]')).toHaveCount(1)
    })
  }

  for (const role of DENIED) {
    test(`${role} 은 주소로 직접 들어와도 막힌다`, async ({ page }) => {
      await signInAs(page, role)
      await page.goto('/admin/monitoring')

      await expect(page.getByText('열람 권한이 없습니다')).toBeVisible()
      // 메뉴가 안 보이는 것만으로는 부족하다 — 대시보드가 실제로 없어야 한다.
      await expect(page.locator('iframe')).toHaveCount(0)
      await expect(page.locator('aside a[href="/admin/monitoring"]')).toHaveCount(0)
    })
  }
})
