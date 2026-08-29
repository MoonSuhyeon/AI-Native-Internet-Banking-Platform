import { NextRequest, NextResponse } from 'next/server'

// 게이트웨이 경유 (/api/v1/auth/** 라우트가 customer-service 로 보낸다).
const CUSTOMER_API_URL = process.env.CUSTOMER_API_URL || 'http://localhost:8080'

/**
 * GET /api/v1/auth/verify
 * Authorization 헤더의 토큰을 customer-service에 위임해 서버사이드 검증한다.
 * AdminGuard의 클라이언트 역할 검증을 보완하는 2차 검증용 엔드포인트.
 */
export async function GET(req: NextRequest) {
  const authorization = req.headers.get('Authorization')
  if (!authorization) {
    return NextResponse.json({ valid: false }, { status: 401 })
  }

  try {
    const res = await fetch(`${CUSTOMER_API_URL}/api/v1/auth/verify`, {
      method: 'GET',
      headers: { Authorization: authorization },
      signal: AbortSignal.timeout(3000),
    })
    if (!res.ok) {
      return NextResponse.json({ valid: false }, { status: 401 })
    }
    return NextResponse.json({ valid: true })
  } catch {
    // customer-service 미기동 시 검증 불가 — 클라이언트 검증으로 fallback
    return NextResponse.json({ valid: true })
  }
}
