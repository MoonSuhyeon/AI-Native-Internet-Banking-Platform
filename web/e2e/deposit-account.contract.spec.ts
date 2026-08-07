import { test, expect } from '@playwright/test'

/**
 * 수신계 계좌 화면 ↔ core-banking 계약 검증 (실제 백엔드 필요).
 *
 * 상환 스케줄 화면과 같은 이유로 둔다 — 프론트가 기대하는 응답 모양과 백엔드가 실제로
 * 주는 모양이 어긋나도 백엔드 없는 E2E 는 잡지 못한다. 게다가 이 화면은 API 실패 시
 * MOCK_ROWS 로 조용히 대체되므로, 백엔드가 없거나 응답이 어긋나도 화면은 멀쩡해 보인다.
 * 그래서 "목이 아니라 진짜 데이터가 그려졌는가"를 확인한다.
 *
 * 실행 전제: infra/docker/docker-compose.e2e.yml 로 core-banking 이 8082 에 떠 있어야 한다.
 * 데이터는 Flyway 시드가 넣는다.
 */

const DEPOSIT_API = process.env.NEXT_PUBLIC_DEPOSIT_API_URL ?? 'http://localhost:8082/api'

/** 시드가 보장하는 값. deposit 시드 마이그레이션이 바뀌면 함께 고쳐야 한다. */
const SEEDED = {
  customerId: '9001',
  accountNumber: '001-2000-0000001',
}

test.describe('수신 계좌 — 실제 백엔드', () => {
  test('계좌 응답이 프론트가 기대하는 필드를 준다', async ({ request }) => {
    const res = await request.get(`${DEPOSIT_API}/accounts`, {
      params: { customerId: SEEDED.customerId },
      headers: { 'X-Customer-Id': SEEDED.customerId },
    })
    expect(res.ok()).toBeTruthy()

    const accounts = await res.json()
    expect(Array.isArray(accounts)).toBeTruthy()
    expect(accounts.length).toBeGreaterThan(0)

    // lib/deposit-api.ts 의 DepositAccount 가 읽는 필드들.
    const first = accounts[0]
    for (const field of ['accountId', 'accountNumber', 'customerId', 'contractId', 'accountType', 'balance']) {
      expect(first, `응답에 ${field} 가 없다 — 프론트 타입과 어긋났다`).toHaveProperty(field)
    }
  })

  test('잔액이 정수로 온다 — 금액 컬럼 BIGINT 전환의 끝단 확인', async ({ request }) => {
    const res = await request.get(`${DEPOSIT_API}/accounts`, {
      params: { customerId: SEEDED.customerId },
      headers: { 'X-Customer-Id': SEEDED.customerId },
    })
    const accounts = await res.json()
    const balance = accounts[0].balance

    // NUMERIC(18,2) 시절에는 소수부가 붙어 "50000000.00" 같은 문자열로 왔다.
    // BIGINT 로 바꾼 뒤에는 JSON 숫자이고 정수다. 화면은 toLocaleString 으로 그리므로
    // 문자열이 섞이면 자릿수 표기가 깨진다.
    expect(typeof balance).toBe('number')
    expect(Number.isInteger(balance)).toBeTruthy()
  })

  test('계좌 목록 화면이 목이 아니라 실제 계좌를 그린다', async ({ page }) => {
    await page.addInitScript((customerId) => {
      localStorage.setItem('customerId', customerId)
    }, SEEDED.customerId)

    await page.goto('/products/deposit/inquiry/new')

    // 이 화면은 API 실패 시 MOCK_ROWS 를 그대로 둔다. 시드 계좌번호가 보이면
    // 실제 응답이 화면까지 도달한 것이다.
    await expect(page.getByText(SEEDED.accountNumber)).toBeVisible({ timeout: 15_000 })
  })
})
