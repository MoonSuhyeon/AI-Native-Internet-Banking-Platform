import axios from 'axios'

// 결제 호출도 게이트웨이를 거친다.
//
// 서비스 주소를 직접 가리키면 게이트웨이를 건너뛰게 되고, 그러면 검증된 신원
// 헤더(X-Customer-Id·X-Employee-Id·X-User-Role)가 붙지 않는다. 그 헤더 위에
// 서 있는 인가가 통째로 무의미해진다.
//
// 서비스별 NEXT_PUBLIC_*_API_URL 오버라이드를 없앤 것도 같은 이유다 —
// 남겨 두면 환경변수 한 줄로 우회로가 되살아난다.
const paymentApi = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8088',
  headers: { 'Content-Type': 'application/json' },
})

paymentApi.interceptors.request.use((config) => {
  if (typeof window === 'undefined') return config
  const token = localStorage.getItem('accessToken')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// ── 즉시이체 ──────────────────────────────────────────────────────────────

// MOCK_BANKS.code → 금융결제원 표준 3자리 기관코드. 자행(KB/AXful)은 EXTERNAL 경로를 타지 않으므로 제외.
export const PAYMENT_BANK_CODE_MAP: Record<string, string> = {
  IBK: '003', NH:  '011', IBD: '002', SH:  '007',
  SHB: '088', WR:  '020', KP:  '071', HN:  '081',
  CT:  '027', SC:  '023', KB2: '090', K:   '089',
  TS:  '092', KN:  '039', GJ:  '034', IM:  '031',
  BS:  '032', JB:  '037', JJ:  '035', SV:  '050',
  SF:  '064', SM:  '045', CU:  '048', DZ:  '055',
  BA:  '060', CCB: '067', ICB: '062', BOC: '063',
  HS:  '054', BN:  '061', JP:  '057',
  DAON: '088',
}

export type InstantTransferPayload = {
  senderAccountId: string
  receiverBankCode: string
  receiverAccountNo: string
  receiverHolderName: string
  transferAmount: number
  channel: string
  receiverMemo?: string
  senderMemo?: string
  receiverPassbookSenderDisplay?: string
}

export type TransferRequestHeaders = {
  userId: string
  authTokenId: string
  idempotencyKey: string
  channel: string
  requestId: string
}

export type InstantTransferResult = {
  paymentInstructionId: string
  transactionNo: string
  status: string
  completedAt: string | null
  failureCategory: string | null
}

// 요청 멱등키 — 이체 "의도"당 1회 생성해 재시도 시 동일 키를 재사용한다.
// (중복 제출 방지용 클라이언트 요청 키. 결제계 내부 deposit 호출 멱등키와는 별개.)
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `transfer-${crypto.randomUUID()}`
  }
  return `transfer-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

// auth_token_id 컬럼이 VARCHAR(20) — crypto.randomUUID()는 36자라 초과하므로 사용 금지.
export function newAuthToken(): string {
  return ('T' + Date.now().toString() + Math.random().toString(36).slice(2, 8)).slice(0, 20)
}

export async function createInstantTransfer(
  payload: InstantTransferPayload,
  headers: TransferRequestHeaders
): Promise<InstantTransferResult> {
  const { data } = await paymentApi.post<InstantTransferResult>(
    '/api/v1/payments',
    payload,
    {
      headers: {
        'X-User-Id': headers.userId,
        'X-Auth-Token-Id': headers.authTokenId,
        'X-Idempotency-Key': headers.idempotencyKey,
        'X-Channel': headers.channel,
        'X-Request-Id': headers.requestId,
      },
    }
  )
  return data
}

// ── 결제(운영자용) ─────────────────────────────────────────────────────────

export type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export type Payment = {
  piId: number
  customerId: string
  amount: number
  status: PaymentStatus
  createdAt: string
}

export async function cancelPayment(piId: number, reason?: string) {
  const { data } = await paymentApi.post(`/api/v1/payments/${piId}/operator-cancel`, { reason })
  return data
}
