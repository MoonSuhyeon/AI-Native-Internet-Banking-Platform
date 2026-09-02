import { test, expect, Page } from '@playwright/test'
import { DEPLOYED } from '../lib/deployed-services'
import { fakeJwt } from './fake-token'

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
  // 토큰은 Node 에서 만들어 넘긴다. addInitScript 의 본문은 브라우저에서 돌아
  // import 한 함수를 볼 수 없다.
  const token = fakeJwt([role])
  await page.addInitScript(
    ({ r, t }) => {
      localStorage.setItem('admin_roles', JSON.stringify([r]))
      localStorage.setItem('accessToken', t)
    },
    { r: role, t: token },
  )

  // 토큰 검증을 고정한다.
  //
  // 예전에는 "검증 서버가 없으면 통과한다" 는 fallback 에 기대고 있었다. 그래서
  // 백엔드가 없는 CI 와 customer-service 가 떠 있는 개발 PC 의 결과가 갈렸다.
  // 여기서 보려는 것은 역할 게이팅이지 토큰 검증이 아니다.
  await page.route('**/api/v1/auth/verify', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{"valid":true}' }),
  )
}

test.describe('어드민 모니터링 화면 접근 제어', () => {
  for (const role of ALLOWED) {
    test(`${role} 은 대시보드를 연다`, async ({ page }) => {
      await signInAs(page, role)
      await page.goto('/admin/monitoring')

      await expect(page.getByRole('heading', { name: '모니터링 대시보드' })).toBeVisible()
      await expect(page.getByText('열람 권한이 없습니다')).toHaveCount(0)

      // 메뉴가 보이는지는 **배포 여부**가 정한다. 권한과는 다른 조건이다 —
      // 관측 스택을 내린 뒤로 사이드바는 권한이 있어도 이 항목을 그리지 않는다.
      // 그 사실을 모르고 1 을 기대하던 단언이 배포 결정 때문에 깨지고 있었다.
      await expect(page.locator('aside a[href="/admin/monitoring"]')).toHaveCount(
        DEPLOYED.monitoring ? 1 : 0,
      )
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
