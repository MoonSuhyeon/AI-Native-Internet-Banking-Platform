import { NextRequest, NextResponse } from 'next/server'

// 다온은행(payment-service-b) 호출용 BFF 프록시.
// 브라우저는 same-origin /api/other-bank 로만 요청하고, 실제 백엔드 주소는
// 서버 전용 env PAYMENT_B_API_URL 로 관리한다(배포 HTTPS 환경의 Mixed Content 회피).
const PAYMENT_B_API_URL = process.env.PAYMENT_B_API_URL || 'http://localhost:8180'

type RouteContext = { params: { path: string[] } }

/**
 * 이 프록시가 넘겨도 되는 것 — 메서드와 경로가 모두 맞아야 한다.
 *
 * <p><b>왜 목록이 필요한가.</b> 이 라우트는 `[...path]` 라 무엇이든 그대로
 * 이어 붙여 보냈다. 인증도 붙이지 않는다 — 게이트웨이를 거치지 않으니 JWT 검증도
 * 신원 헤더 주입도 없다. 그런데 상대인 다온은행(core-banking-b)은 우리 원장과
 * <b>같은 이미지</b>다(BANK_CODE 만 다르다). 즉 이 라우트는 원장 서비스 한 대의
 * 임의 엔드포인트로 가는 무인증 통로였다.
 *
 * <p>지금 그것을 막고 있는 것은 배포 스택에 다온은행이 없다는 사실뿐인데,
 * 그것은 통제가 아니라 우연이다. 올리는 순간 열린다.
 *
 * <p>그래서 <b>화면이 실제로 쓰는 것만</b> 남긴다. 타행 입금 조회 하나다
 * (`web/lib/other-bank-api.ts`). 늘릴 때는 여기에 한 줄을 더하는 것이
 * 곧 "무엇을 열었는가" 의 기록이 된다.
 */
const ALLOWED: ReadonlyArray<{ method: string; path: string }> = [
  { method: 'GET', path: 'api/v1/payments/inbound' },
]

function isAllowed(method: string, path: string): boolean {
  return ALLOWED.some((a) => a.method === method && a.path === path)
}

async function proxy(request: NextRequest, context: RouteContext) {
  const path = context.params.path.join('/')
  const search = request.nextUrl.search

  // 목록에 없으면 여기서 끝낸다. 404 로 답하는 것은 이 프록시에 그런 경로가
  // 없다는 뜻이고, 뒤에 무엇이 있는지는 알려 주지 않는다.
  if (!isAllowed(request.method, path)) {
    return NextResponse.json({ detail: '지원하지 않는 경로입니다.' }, { status: 404 })
  }
  const targetUrl = `${PAYMENT_B_API_URL}/${path}${search}`
  const body = request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.text()

  try {
    const response = await fetch(targetUrl, {
      method: request.method,
      headers: {
        'Content-Type': request.headers.get('content-type') || 'application/json',
      },
      body,
      cache: 'no-store',
    })
    const responseBody = await response.text()
    return new NextResponse(responseBody, {
      status: response.status,
      headers: { 'Content-Type': response.headers.get('content-type') || 'application/json' },
    })
  } catch {
    return NextResponse.json({ detail: '다온은행 서비스에 연결할 수 없습니다.' }, { status: 502 })
  }
}

export async function GET(request: NextRequest, context: RouteContext) { return proxy(request, context) }
export async function POST(request: NextRequest, context: RouteContext) { return proxy(request, context) }
