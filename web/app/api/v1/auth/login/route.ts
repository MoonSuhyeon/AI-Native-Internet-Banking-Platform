import { NextRequest, NextResponse } from 'next/server'

/**
 * 로그인 프록시.
 *
 * **mock 폴백을 없앴다.** 예전에는 customer-service 응답이 3초 안에 안 오면
 * 하드코딩된 계정 목록(`user01`/`Test1234` 등)과 대조해 **가짜 토큰**
 * (`mock.<base64>.<시각>`)을 내줬다.
 *
 * 두 가지가 나빴다.
 *
 * 1. **인증하지 않는 인증 경로다.** 백엔드가 잠깐 느리기만 해도 하드코딩된
 *    비밀번호로 들어올 수 있었다. 실제 시드의 user01 비밀번호와 다른 값이라,
 *    "백엔드가 살아 있을 때는 안 되고 죽었을 때만 되는" 계정이었다.
 * 2. **가짜 토큰은 게이트웨이가 거절한다.** 화면만 로그인된 것처럼 보이고 이후
 *    모든 API 가 401 이다 — 원인을 찾기 가장 어려운 상태다.
 *
 * 백엔드가 없으면 로그인은 **실패해야 한다.** 그것이 정직한 동작이다.
 */
const GATEWAY_URL =
  process.env.CUSTOMER_API_URL ||
  process.env.NEXT_PUBLIC_API_URL ||
  'http://localhost:8080'

export async function POST(req: NextRequest) {
  const body = await req.text()

  try {
    const upstream = await fetch(`${GATEWAY_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
    })
    const text = await upstream.text()
    return new NextResponse(text, {
      status: upstream.status,
      headers: { 'Content-Type': upstream.headers.get('Content-Type') || 'application/json' },
    })
  } catch {
    return NextResponse.json(
      { code: 'CUSTOMER_SERVICE_UNAVAILABLE', message: '인증 서버와 통신할 수 없습니다.' },
      { status: 503 },
    )
  }
}
