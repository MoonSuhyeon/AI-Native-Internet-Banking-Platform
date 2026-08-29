import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

/**
 * API 폴백 주소가 게이트웨이를 가리키는지 고정한다.
 *
 * ## 왜 필요한가
 *
 * 한동안 프런트의 폴백이 전부 `localhost:8088` 이었다. payment-service 가 8080 을
 * 물고 있던 시절의 우회였는데, 그 서비스가 core-banking 으로 병합되면서 8080 은
 * 게이트웨이로 돌아왔고 8088 은 **review-ai-gateway** 가 가져갔다. 즉 폴백이 다른
 * 서비스를 가리키게 됐다.
 *
 * 그런데 아무도 몰랐다. `web/.env.local` 이 `NEXT_PUBLIC_API_URL` 을 8080 으로 덮어
 * 주고 있었기 때문이다. 그 파일이 없는 곳 — 새로 받은 저장소·CI·컨테이너 빌드 —
 * 에서만 조용히 엉뚱한 데로 갔다. 로컬에서 멀쩡하니 드러날 자리가 없었다.
 *
 * 포트는 앞으로도 옮겨 다닌다. 옮길 때마다 열한 곳을 손으로 맞추는 대신 여기서 센다.
 *
 * ## 정본은 `.env.sample`
 *
 * `.env` 는 커밋되지 않으므로(gitignore) CI 에서 읽을 수 없다. 커밋된 `.env.sample`
 * 의 `GATEWAY_APP_PORT` 를 기준으로 삼는다.
 */

const REPO_ROOT = resolve(__dirname, '..', '..')
const WEB_ROOT = resolve(__dirname, '..')

/** 폴백 주소를 두는 곳. 게이트웨이를 거치는 클라이언트가 여기 모여 있다. */
const SCAN_DIRS = [join(WEB_ROOT, 'lib'), join(WEB_ROOT, 'app', 'api')]

/**
 * 게이트웨이가 아닌 주소를 일부러 두는 곳.
 *
 * 이유 없이 늘어나면 이 검사가 무의미해진다. 새로 넣을 때는 **왜 게이트웨이를
 * 거치지 않아도 되는지**를 함께 적는다. 둘 다 은행 API 가 아니거나 다른 은행이다.
 */
const ALLOWED_NON_GATEWAY: Record<string, string> = {
  // 그라파나는 우리 API 가 아니라 관측 도구다. 게이트웨이 라우팅 대상이 아니고,
  // 브라우저에서 직접 부르면 CORS 로 막혀 서버에서 목록만 가져온다.
  'app/api/monitoring/dashboards/route.ts': 'Grafana — 은행 API 가 아니다',

  // 다온은행(core-banking-b)은 **타행**이다. 우리 게이트웨이가 인증할 대상이 아니라
  // 타행이체 시연에서 상대편으로 부르는 곳이다.
  'app/api/other-bank/[...path]/route.ts': '타행(다온은행) — 우리 게이트웨이 밖',
}

const LOCALHOST_URL = /['"`]http:\/\/localhost:(\d{4,5})['"`]/g

function gatewayPort(): string {
  const env = readFileSync(join(REPO_ROOT, '.env.sample'), 'utf-8')
  const m = env.match(/^GATEWAY_APP_PORT=(\d+)/m)
  expect(m, '.env.sample 에 GATEWAY_APP_PORT 가 없다').not.toBeNull()
  return m![1]
}

function sourceFiles(dir: string): string[] {
  let out: string[] = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      out = out.concat(sourceFiles(full))
    } else if (/\.tsx?$/.test(name) && !/\.(test|spec)\.tsx?$/.test(name)) {
      out.push(full)
    }
  }
  return out
}

describe('API 폴백 주소', () => {
  it('게이트웨이 포트만 가리킨다', () => {
    const expected = gatewayPort()
    const offenders: string[] = []

    for (const dir of SCAN_DIRS) {
      for (const file of sourceFiles(dir)) {
        const rel = relative(WEB_ROOT, file).replace(/\\/g, '/')
        if (rel in ALLOWED_NON_GATEWAY) continue

        // matchAll 의 반환을 그대로 순회하면 tsconfig 의 target 에 걸린다.
        // 배열로 받아 두면 설정과 무관하게 돈다.
        const src = readFileSync(file, 'utf-8')
        const matches = Array.from(src.matchAll(LOCALHOST_URL))
        for (const match of matches) {
          if (match[1] !== expected) {
            offenders.push(`${rel} → :${match[1]}`)
          }
        }
      }
    }

    expect(
      offenders,
      `게이트웨이(:${expected}) 가 아닌 주소를 폴백으로 둔 곳이 있다.\n` +
        '게이트웨이를 건너뛰면 검증된 신원 헤더가 붙지 않고, .env.local 이 있는 로컬\n' +
        '에서는 드러나지 않는다. 일부러 그런 것이면 ALLOWED_NON_GATEWAY 에 이유와 함께 적을 것.\n' +
        offenders.join('\n'),
    ).toEqual([])
  })

  it('실제로 검사할 파일을 찾는다', () => {
    // 경로가 바뀌어 아무것도 안 읽으면 위 테스트는 언제나 통과한다.
    // 조용히 무력해지는 것을 막는다.
    const files = SCAN_DIRS.flatMap(sourceFiles)
    expect(files.length).toBeGreaterThan(5)
    expect(files.some(f => f.endsWith('api.ts'))).toBe(true)
  })
})
