import { NextRequest, NextResponse } from 'next/server'

// 게이트웨이를 가리킨다. 8081(customer-service) 직통이면 JwtAuthenticationFilter 가
// 클라이언트발 신원 헤더를 지우고 검증된 클레임으로 덮어쓰는 단계를 통째로 건너뛴다.
const CUSTOMER_API_URL = process.env.CUSTOMER_API_URL || 'http://localhost:8088'

// 상담 서비스 계정(agent01·agent02·super01·admin01)은 consultation-service /auth/agent/login 에서 인증
// 이 mock은 customer-service 미기동 시 데모 전용 폴백이며 운영 배포 전 제거 필요
const MOCK_CUSTOMERS = [
  { loginId: 'user01', password: 'Test1234', customerId: 1, customerNo: 'CUST001', name: '김고객' },
  { loginId: 'user02', password: 'Test1234', customerId: 2, customerNo: 'CUST002', name: '이고객' },
  { loginId: 'user03', password: 'Test1234', customerId: 3, customerNo: 'CUST003', name: '박고객' },
]

export async function POST(req: NextRequest) {
  const body = await req.json()
  const { loginId, password } = body

  // 1차: customer-service 실제 API 호출 — 서명된 JWT 토큰 반환
  try {
    const upstream = await fetch(`${CUSTOMER_API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ loginId, password }),
      cache: 'no-store',
      signal: AbortSignal.timeout(3000),
    })
    const data = await upstream.json()
    return NextResponse.json(data, { status: upstream.status })
  } catch {
    // customer-service 미기동 시 mock 폴백
  }

  // 2차: 데모 환경 mock (customer-service 미기동 시에만)
  const user = MOCK_CUSTOMERS.find((u) => u.loginId === loginId && u.password === password)
  if (!user) {
    return NextResponse.json(
      { status: 'FAIL', message: '아이디 또는 비밀번호가 올바르지 않습니다.' },
      { status: 401 },
    )
  }

  const payload = { customerId: user.customerId, customerNo: user.customerNo, name: user.name }
  const accessToken = `mock.${Buffer.from(JSON.stringify(payload)).toString('base64')}.${Date.now()}`

  return NextResponse.json({
    status: 'SUCCESS',
    data: {
      accessToken,
      customerId: user.customerId,
      customerNo: user.customerNo,
      name: user.name,
    },
  })
}
