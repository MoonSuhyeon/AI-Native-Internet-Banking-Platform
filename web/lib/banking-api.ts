import { api } from '@/lib/api'

/**
 * 뱅킹 편의기능 API 클라이언트.
 * 게이트웨이(@/lib/api)를 통해 customer-service 엔드포인트를 호출한다.
 * - 이체 즐겨찾기 (/banking/favorites) — 자주쓰는계좌 · 단축이체
 * - 입금통보 신청 (/notifications/deposit-alerts)
 *
 * 고객번호는 보내지 않는다. 게이트웨이가 토큰에서 꺼내 X-Customer-Id 로 넣는다 —
 * 화면이 고객번호를 실어 보내면 값을 바꿔 남의 것을 볼 수 있다.
 */

/* ── 이체 즐겨찾기 ── */

/** 자주쓰는계좌는 금액을 그때 정하고, 단축이체는 미리 정해 둔다. */
export type FavoriteType = 'FAVORITE_ACCOUNT' | 'QUICK_TRANSFER'

export type FavoriteTransfer = {
  favoriteId: number
  favoriteType: FavoriteType
  alias: string
  bankCode: string
  bankName: string
  accountNumber: string
  accountHolderName: string | null
  /** 단축이체에만 있다. */
  amount: number | null
  displayOrder: number
}

export type RegisterFavoriteRequest = {
  favoriteType: FavoriteType
  alias: string
  bankCode: string
  bankName: string
  accountNumber: string
  accountHolderName?: string
  amount?: number | null
}

export async function fetchFavorites(type: FavoriteType): Promise<FavoriteTransfer[]> {
  const { data } = await api.get('/api/v1/banking/favorites', { params: { type } })
  return data.data ?? []
}

export async function registerFavorite(req: RegisterFavoriteRequest): Promise<FavoriteTransfer> {
  const { data } = await api.post('/api/v1/banking/favorites', req)
  return data.data
}

export async function deleteFavorite(favoriteId: number): Promise<void> {
  await api.delete(`/api/v1/banking/favorites/${favoriteId}`)
}

/* ── 입금통보 신청 ── */

export type AlertChannel = 'PUSH' | 'SMS' | 'EMAIL'

export type DepositAlertSubscription = {
  subscriptionId: number
  accountNumber: string
  channel: AlertChannel
  contact: string
  minAmount: number
  active: boolean
}

export async function fetchDepositAlerts(): Promise<DepositAlertSubscription[]> {
  const { data } = await api.get('/api/v1/notifications/deposit-alerts')
  return data.data ?? []
}

export async function subscribeDepositAlert(req: {
  accountNumber: string
  channel: AlertChannel
  contact?: string
  minAmount: number
}): Promise<DepositAlertSubscription> {
  const { data } = await api.post('/api/v1/notifications/deposit-alerts', req)
  return data.data
}

export async function unsubscribeDepositAlert(subscriptionId: number): Promise<void> {
  await api.delete(`/api/v1/notifications/deposit-alerts/${subscriptionId}`)
}

/* ── 영업점 · 지점 상담 예약 ── */

export type Branch = {
  branchId: number
  branchCode: string
  branchName: string
  branchType: 'BRANCH' | 'CORPORATE' | 'PB'
  region: string
  address: string
  phone: string | null
  openTime: string
  closeTime: string
}

export type BranchReservation = {
  reservationId: number
  branchId: number
  branchName: string
  reservedAt: string
  topicCd: string
  memo: string | null
  contactPhone: string
  statusCd: 'RESERVED' | 'CANCELLED' | 'COMPLETED' | 'NO_SHOW'
}

/** 지점검색. 지점명·지역 어느 쪽으로 쳐도 찾는다. */
export async function searchBranches(keyword: string): Promise<Branch[]> {
  const { data } = await api.get('/api/v1/branches', { params: { keyword } })
  return data.data ?? []
}

export async function reserveBranchConsultation(req: {
  branchId: number
  /** ISO 8601. 서버가 지점 영업시간 안인지 확인한다. */
  reservedAt: string
  topicCd: string
  memo?: string
  contactPhone: string
}): Promise<BranchReservation> {
  const { data } = await api.post('/api/v1/branches/reservations', req)
  return data.data
}

export async function fetchMyBranchReservations(): Promise<BranchReservation[]> {
  const { data } = await api.get('/api/v1/branches/reservations')
  return data.data ?? []
}

export async function cancelBranchReservation(reservationId: number): Promise<void> {
  await api.delete(`/api/v1/branches/reservations/${reservationId}`)
}
