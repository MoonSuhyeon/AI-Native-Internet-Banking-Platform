import { defineConfig, devices } from '@playwright/test'

// E2E 하네스. 로컬은 켜둔 next dev(3001)를 재사용, CI는 webServer가 새로 띄운다.
// web 은 `next dev -p 3001` 이므로 baseURL 도 3001. ([[port_3001_collision]] 주의)
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:3001',
    trace: 'on-first-retry',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3001',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  // 두 갈래로 나눈다.
  //   default  — 백엔드 없이 도는 화면 검증. PR 마다 돈다.
  //   contract — 실제 loan-service 를 상대로 응답 계약을 검증한다(*.contract.spec.ts).
  //              infra/docker/docker-compose.e2e.yml 이 떠 있어야 하므로 별도 잡에서만 돈다.
  //              섞으면 백엔드 없는 CI 에서 계약 테스트가 항상 실패한다.
  projects: [
    {
      name: 'chromium',
      testIgnore: /\.contract\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'contract',
      testMatch: /\.contract\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
