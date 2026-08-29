import { NextRequest, NextResponse } from 'next/server'

// 게이트웨이. 예전 기본값 8086 은 아무도 듣지 않는 포트였다 — goal-agent 는
// compose 에 없고 호스트(:8000)에서 돌기 때문이다. 지출 분석이 조용히 실패하고 있었다.
const AI_API_URL =
  process.env.NEXT_PUBLIC_AI_API_URL || 'http://localhost:8080'

/** Authorization: Bearer 토큰에서 customer_id를 서버사이드로 추출.
 *  토큰 포맷:
 *    - 실 JWT : header.payload.sig  (payload에 customer_id 또는 sub)
 *    - 목 토큰: mock.<base64json>.<ts>
 *  서명 검증은 API Gateway(Java)에서 담당하므로 여기서는 페이로드 디코딩만 수행.
 */
function extractCustomerId(authHeader: string | null): string | null {
  if (!authHeader?.startsWith('Bearer ')) return null
  const parts = authHeader.slice(7).split('.')
  if (parts.length < 2) return null
  try {
    const payload = JSON.parse(Buffer.from(parts[1], 'base64').toString('utf-8'))
    const id = payload.customer_id ?? payload.sub ?? null
    return id != null ? String(id) : null
  } catch {
    return null
  }
}

export async function POST(req: NextRequest) {
  try {
    const customerId = extractCustomerId(req.headers.get('Authorization'))
    if (!customerId) {
      return NextResponse.json({ error: '인증 정보가 없습니다.' }, { status: 401 })
    }

    const body = await req.json()

    const upstream = await fetch(`${AI_API_URL}/api/v1/agent/spending/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // customer_id는 서버에서 토큰으로 확정 — 클라이언트 값 무시
      body: JSON.stringify({ ...body, customer_id: customerId }),
      cache: 'no-store',
    })

    const data = await upstream.json()
    return NextResponse.json(data, { status: upstream.status })
  } catch {
    return NextResponse.json(
      { error: '지출 분석 서버와 통신할 수 없습니다.' },
      { status: 503 },
    )
  }
}
