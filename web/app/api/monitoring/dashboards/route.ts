import { NextResponse } from 'next/server'

// 서버에서 Grafana 를 부를 때 쓰는 주소. 브라우저용(NEXT_PUBLIC_GRAFANA_URL)과
// 다를 수 있다 — 컨테이너 안에서는 grafana:3000 이고 브라우저는 localhost:3000 이다.
const GRAFANA_URL =
  process.env.GRAFANA_URL || process.env.NEXT_PUBLIC_GRAFANA_URL || 'http://localhost:3000'

/**
 * 어드민 모니터링 화면에 띄울 대시보드 목록.
 *
 * <p>브라우저에서 Grafana 를 직접 부르면 CORS 로 막힌다(Grafana 는
 * Access-Control-Allow-Origin 을 주지 않는다). 그래서 목록만 서버에서 가져온다.
 * 대시보드 본체는 iframe 이라 CORS 대상이 아니고 브라우저가 직접 불러도 된다.
 *
 * <p>목록을 코드에 박지 않는 이유: 대시보드를 추가·삭제할 때마다 화면이
 * 조용히 어긋난다. Grafana 가 가진 것을 그대로 보여주는 편이 안 틀린다.
 */
export async function GET() {
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
