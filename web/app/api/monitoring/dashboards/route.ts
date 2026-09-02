import { NextRequest, NextResponse } from 'next/server'

// 서버에서 Grafana 를 부를 때 쓰는 주소. 브라우저용(NEXT_PUBLIC_GRAFANA_URL)과
// 다를 수 있다 — 컨테이너 안에서는 grafana:3000 이고 브라우저는 localhost:3000 이다.
const GRAFANA_URL =
  process.env.GRAFANA_URL || process.env.NEXT_PUBLIC_GRAFANA_URL || 'http://localhost:3000'

// 토큰 검증을 위임할 곳. 게이트웨이의 /api/v1/auth/** 가 customer-service 로 보낸다.
const CUSTOMER_API_URL = process.env.CUSTOMER_API_URL || 'http://localhost:8080'

/** 이 목록을 볼 수 있는 역할. 화면(MonitoringPage)의 게이팅과 같은 값이다. */
const ALLOWED_ROLES = ['ROLE_OPS', 'ROLE_HQ_RISK', 'ROLE_ADMIN']

/**
 * 검증이 끝난 토큰에서 역할을 읽는다.
 *
 * <p>서명을 여기서 보지 않는 것은 게으름이 아니라 배치다 — 검증은
 * customer-service 가 하고(아래 verify), 이 함수는 <b>검증을 통과한 뒤에만</b>
 * 불린다. 순서가 바뀌면 주장(claim)을 신원으로 쓰는 것이 된다.
 */
function readRoles(token: string): string[] {
  const parts = token.split('.')
  if (parts.length < 2) return []
  try {
    const json = Buffer.from(parts[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf-8')
    const roles = (JSON.parse(json) as { roles?: unknown }).roles
    return Array.isArray(roles) ? roles.filter((r): r is string => typeof r === 'string') : []
  } catch {
    return []
  }
}

/**
 * 어드민 모니터링 화면에 띄울 대시보드 목록.
 *
 * <p>브라우저에서 Grafana 를 직접 부르면 CORS 로 막힌다(Grafana 는
 * Access-Control-Allow-Origin 을 주지 않는다). 그래서 목록만 서버에서 가져온다.
 * 대시보드 본체는 iframe 이라 CORS 대상이 아니고 브라우저가 직접 불러도 된다.
 *
 * <p>목록을 코드에 박지 않는 이유: 대시보드를 추가·삭제할 때마다 화면이
 * 조용히 어긋난다. Grafana 가 가진 것을 그대로 보여주는 편이 안 틀린다.
 *
 * <p><b>인가.</b> 이 경로는 Caddy 가 게이트웨이를 거치지 않고 web 으로 바로
 * 보낸다. 그래서 게이트웨이의 JWT 검증이 닿지 않는데, 그동안 이 라우트에는
 * 검사가 한 줄도 없었다 — 화면에서 메뉴를 감춘 것이 전부라 주소만 알면 누구나
 * 대시보드 목록(제목·uid)을 받아 갈 수 있었다. 화면의 역할 게이팅과 같은
 * 조건을 서버에서도 본다.
 */
export async function GET(req: NextRequest) {
  const authorization = req.headers.get('Authorization')
  if (!authorization?.startsWith('Bearer ')) {
    return NextResponse.json({ error: '인증이 필요합니다' }, { status: 401 })
  }
  const token = authorization.slice('Bearer '.length)

  // 서명 검증은 토큰을 발급한 쪽에 맡긴다.
  //
  // 실패했을 때 통과시키지 않는다. 옆의 /api/v1/auth/verify 는 검증 서버가 없으면
  // 통과시키는데(화면이 아예 안 열리는 것을 피하려고), 여기서 같은 선택을 하면
  // customer-service 를 못 부르는 동안 인가가 통째로 사라진다.
  try {
    const verified = await fetch(`${CUSTOMER_API_URL}/api/v1/auth/verify`, {
      method: 'GET',
      headers: { Authorization: authorization },
      signal: AbortSignal.timeout(3000),
      cache: 'no-store',
    })
    if (!verified.ok) {
      return NextResponse.json({ error: '인증이 필요합니다' }, { status: 401 })
    }
  } catch {
    return NextResponse.json({ error: '인증 서버와 통신할 수 없습니다' }, { status: 503 })
  }

  const roles = readRoles(token)
  if (!roles.some((r) => ALLOWED_ROLES.includes(r))) {
    return NextResponse.json({ error: '열람 권한이 없습니다' }, { status: 403 })
  }

  try {
    const res = await fetch(`${GRAFANA_URL}/api/search?type=dash-db`, {
      cache: 'no-store',
    })
    if (!res.ok) {
      return NextResponse.json(
        { error: `Grafana 응답 ${res.status}` },
        { status: 502 },
      )
    }
    const list = await res.json()
    return NextResponse.json(list)
  } catch {
    // 대부분 모니터링 스택이 안 떠 있는 경우다. 화면에서 조치를 안내한다.
    return NextResponse.json(
      { error: 'Grafana 에 연결할 수 없습니다' },
      { status: 503 },
    )
  }
}
