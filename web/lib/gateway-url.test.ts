import { describe, it, expect, vi, afterEach } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve, sep } from 'node:path'

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

/**
 * 폴백이 **배포에서** 무엇이 되는지 고정한다.
 *
 * 위 검사는 포트가 맞는지만 봤다. 그래서 다음이 통과했다: 운영 이미지는
 * `ARG NEXT_PUBLIC_API_URL=""` 로 빌드되는데, 빈 문자열이 falsy 라
 * `"" || 'http://localhost:8080'` 이 되어 방문자 브라우저가 **자기 PC 의 8080** 을
 * 불렀다. 포트는 8080 으로 "맞았고", 사이트만 통째로 죽었다.
 *
 * 화면은 정상으로 뜨고 API 만 실패하므로 눈으로는 원인이 보이지 않는다. 여기서 센다.
 */
describe('배포 번들의 게이트웨이 주소', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  async function resolveBase(env: Record<string, string>) {
    for (const [k, v] of Object.entries(env)) vi.stubEnv(k, v)
    vi.resetModules()
    return (await import('./gateway-url')).GATEWAY_BASE_URL
  }

  it('운영 빌드에서 주소가 비면 같은 오리진 상대경로가 된다', async () => {
    // Caddy 가 같은 도메인에서 /api 를 게이트웨이로 넘긴다. 상대경로면 도메인이
    // 무엇이든 맞물리므로 이미지를 도메인마다 다시 만들 필요가 없다.
    expect(
      await resolveBase({ NEXT_PUBLIC_API_URL: '', NODE_ENV: 'production' }),
      '운영 빌드가 localhost 로 떨어지면 배포된 사이트의 모든 API 호출이 방문자 PC 로 간다',
    ).toBe('')
  })

  it('개발 빌드에서는 로컬 게이트웨이를 가리킨다', async () => {
    // 로컬은 3000(Next)과 8080(게이트웨이)이 따로 뜨고 그 사이에 프록시가 없다.
    // 여기까지 상대경로로 만들면 개발이 죽는다 — 그래서 두 경우를 가른다.
    expect(await resolveBase({ NEXT_PUBLIC_API_URL: '', NODE_ENV: 'development' }))
      .toBe('http://localhost:8080')
  })

  it('명시된 주소가 있으면 그것이 이긴다', async () => {
    expect(await resolveBase({
      NEXT_PUBLIC_API_URL: 'https://example.test',
      NODE_ENV: 'production',
    })).toBe('https://example.test')
  })
})

/**
 * 폴백을 다시 흩뜨리지 못하게 한다.
 *
 * 아홉 곳에 같은 `|| 'http://localhost:8080'` 이 복사돼 있었고, 그래서 한 곳을
 * 고쳐도 나머지 여덟이 남았다. 브라우저 클라이언트는 gateway-url 만 쓰게 묶는다.
 */
describe('게이트웨이 주소를 정하는 곳', () => {
  it('브라우저 클라이언트는 lib/gateway-url 만 본다', () => {
    const offenders: string[] = []
    for (const file of sourceFiles(join(WEB_ROOT, 'lib'))) {
      const rel = relative(WEB_ROOT, file).split(sep).join('/')
      if (rel === 'lib/gateway-url.ts') continue
      if (readFileSync(file, 'utf-8').includes('process.env.NEXT_PUBLIC_API_URL')) {
        offenders.push(rel)
      }
    }
    expect(
      offenders,
      'NEXT_PUBLIC_API_URL 을 직접 읽는 곳이 생겼다.\n' +
        '@/lib/gateway-url 의 GATEWAY_BASE_URL 을 쓸 것 — 직접 읽으면 운영 빌드의\n' +
        '빈 문자열 처리가 그 파일에서만 다시 빠진다.\n' +
        offenders.join('\n'),
    ).toEqual([])
  })
})
