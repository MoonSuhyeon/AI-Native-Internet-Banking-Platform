import axios from 'axios'
import type { Account } from '@/lib/mock-data'

// 게이트웨이를 거친다. 수신 경로(/api/accounts·/api/contracts 등)는 게이트웨이가
// 라우팅하므로 서비스 주소를 직접 가리킬 이유가 없다 — 직접 가리키면 검증된 신원
// 헤더가 붙지 않는다.
function depositBaseUrl() {
  const gateway = (process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8088').replace(/\/$/, '')
  return `${gateway}/api`
}

const depositApi = axios.create({
  baseURL: depositBaseUrl(),
  headers: {
    'Content-Type': 'application/json',
  },
})

depositApi.interceptors.request.use((config) => {
  if (typeof window === 'undefined') return config

  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export type DepositProductType = 'DEPOSIT' | 'SAVINGS' | 'SUBSCRIPTION'
export type SavingType = 'REGULAR' | 'FREE'

export type DepositProductTargetGroup = {
  targetGroupId: number
  targetGroupName: string
  minAge?: number | null
  maxAge?: number | null
}

export type DepositProduct = {
  productId: number
  productType: DepositProductType
  productName: string
  description?: string
  baseInterestRate?: number | string | null
  bestRate?: number | string | null
  minJoinAmount?: number | string
  maxJoinAmount?: number | string
  minPeriodMonth?: number
  maxPeriodMonth?: number
  productStatus?: string
  savingType?: SavingType
  targetGroups?: DepositProductTargetGroup[]
}

export type DepositInterestRate = {
  rateId: number
  productId: number
  rateType: 'BASE' | 'PREFERENTIAL'
  minimumContractPeriod?: number | null
  maximumContractPeriod?: number | null
  minimumJoinAmount?: number | string | null
  maximumJoinAmount?: number | string | null
  rate: number | string
  conditionDescription?: string | null
  effectiveStartDate?: string
  effectiveEndDate?: string | null
  isActive?: boolean
}

export type DepositContract = {
  contractId: number
  contractNumber: string
  customerId: string
  productId: number
  joinAmount: number | string
  contractPeriodMonth: number
  maturityAt?: string
  startedAt?: string
  autoTransferDay?: number
  contractStatus?: string
  terminatedAt?: string
  sourceAccountId?: number
}

export type DepositAccount = {
  accountId: number
  accountNumber: string
  customerId: string
  contractId: number
  accountType: DepositProductType
  savingType?: SavingType
  accountAlias?: string
  balance: number | string
  totalPaidAmount?: number | string
  isWithdrawable?: boolean
  withdrawable?: boolean
  openedAt?: string
  maturityAt?: string
  accountStatus?: string
}

export type DepositViewAccount = Account & {
  apiAccountId?: number
  contractId?: number
  rawAccountType?: DepositProductType
  isWithdrawable?: boolean
  accountStatus?: string
  savingType?: SavingType
}

export type DepositRecommendProduct = {
  product_id?: number
  productId?: number
  product_name?: string
  productName?: string
  product_type?: string
  productType?: string
  base_interest_rate?: number
  baseInterestRate?: number
  bestRate?: number
  minJoinAmount?: number
  maxJoinAmount?: number
  minPeriodMonth?: number
  maxPeriodMonth?: number
  reason?: string
}

export type DepositRecommendResponse = {
  customerId: string
  periodMonth?: number
  analysisPeriodMonth?: number
  cashFlow?: {
    totalInflow?: number
    totalOutflow?: number
    netCashFlow?: number
    estimatedSavingsAmount?: number
  }
  products?: DepositRecommendProduct[]
  recommendations?: DepositRecommendProduct[]
}

export type CreateDepositContractInput = {
  slug: string
  productName: string
  amount: number
  periodMonth: number
  accountPassword: string
  isSavings: boolean
  isHousing: boolean
  isChecking: boolean
  isRegularSavings: boolean
  autoTransferEnabled: boolean
  autoTransferDay?: number
  taxExempt?: boolean
}

// 하드코딩 ID는 환경마다 시퀀스가 달라 오동작 유발 — resolveProductId에서 항상 API 조회
const PRODUCT_ID_BY_SLUG: Record<string, number> = {}

// PRODUCT_NAME_TO_SLUG 하드코딩 제거 — DB 상품명 변경 시 조용히 깨지는 문제 방지.
// slug는 productId 기반(product-{id})으로 생성하고, 상세 페이지 라우팅은 productId를 직접 사용한다.
const SLUG_BY_PRODUCT_ID = Object.fromEntries(
  Object.entries(PRODUCT_ID_BY_SLUG).map(([slug, productId]) => [productId, slug])
) as Record<number, string>

const CHECKING_PRODUCT_SLUGS = new Set([
  'axful-moim',
  'axful-star-account',
  'axful-wallet',
  'axful-free-account',
  'axful-youth-account',
  'axful-sok',
  'monimo-daily',
  // join/[id]/page.tsx CHECKING_IDS와 동기화
  'axful-living',
  'axful-gs',
  'election',
])

const SAVING_TYPE_BY_SLUG: Record<string, SavingType> = {
  'axful-free': 'FREE',
  'axful-dollar': 'FREE',
  'axful-green': 'FREE',
  'axful-star-savings': 'FREE',
  'axful-soldier': 'REGULAR',
  'axful-work': 'REGULAR',
  'axful-dream': 'REGULAR',
  'axful-together': 'REGULAR',
}

const LEGACY_CUSTOMER_ID_MAP: Record<string, string> = {
  CUST001: '1',
}

export function normalizeDepositCustomerId(customerId?: string | number | null) {
  const value = String(customerId ?? '').trim()
  if (!value) return '1'
  return LEGACY_CUSTOMER_ID_MAP[value] ?? value
}

function headers(customerId: string) {
  return { 'X-Customer-Id': normalizeDepositCustomerId(customerId) }
}

function toNumber(value: number | string | undefined, fallback: number | undefined = 0) {
  if (value === undefined || value === null || value === '') return fallback
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function toDateText(value?: string) {
  if (!value) return undefined
  return value.replace(/-/g, '.')
}

export function getCurrentDepositCustomerId() {
  if (typeof window === 'undefined') return '1'

  const direct = localStorage.getItem('customerId')
  if (direct) return normalizeDepositCustomerId(direct)

  try {
    const user = JSON.parse(localStorage.getItem('user') || '{}')
    return normalizeDepositCustomerId(user.customerId || user.customer_id || user.id)
  } catch {
    return '1'
  }
}

export async function fetchDepositProducts(params?: {
  productType?: DepositProductType
  productStatus?: string
}) {
  const { data } = await depositApi.get<DepositProduct[]>('/products', { params })
  return data
}

export async function fetchDepositProduct(productId: number) {
  const { data } = await depositApi.get<DepositProduct>(`/products/${productId}`)
  return data
}

export async function fetchDepositInterestRates(productId: number) {
  const { data } = await depositApi.get<DepositInterestRate[]>(`/products/${productId}/interest-rates`)
  return data
}

export function getDepositProductIdBySlug(slug: string) {
  return PRODUCT_ID_BY_SLUG[slug]
}

export function getDepositSlugByProductId(productId: number) {
  // 환경마다 ID 시퀀스가 다르므로 하드코딩 맵 대신 productId 기반 slug를 사용한다.
  // 상세 페이지 라우팅이 필요하면 slug 대신 productId를 직접 쿼리파라미터로 전달할 것.
  return SLUG_BY_PRODUCT_ID[productId] ?? `product-${productId}`
}

export function toDepositProductCard(product: DepositProduct) {
  const minMonth = product.minPeriodMonth
  const maxMonth = product.maxPeriodMonth
  const period =
    minMonth && maxMonth
      ? minMonth === maxMonth
        ? `${minMonth}개월`
        : `${minMonth}~${maxMonth}개월`
      : undefined

  return {
    id: getDepositSlugByProductId(product.productId),
    name: product.productName,
    channel: '인터넷·스타뱅킹',
    desc: product.description,
    period,
    rate:
      product.bestRate != null
        ? `최고 연 ${Number(product.bestRate).toLocaleString('ko-KR')}%`
        : product.baseInterestRate != null
        ? `기본 연 ${Number(product.baseInterestRate).toLocaleString('ko-KR')}%`
        : undefined,
    canApply: product.productStatus ? product.productStatus === 'SELLING' : true,
  }
}

export async function fetchDepositContracts(customerId: string) {
  const normalizedCustomerId = normalizeDepositCustomerId(customerId)
  const { data } = await depositApi.get<DepositContract[]>('/contracts', {
    params: { customerId: normalizedCustomerId },
    headers: headers(normalizedCustomerId),
  })
  return data
}

export async function fetchDepositAccounts(customerId: string) {
  const normalizedCustomerId = normalizeDepositCustomerId(customerId)
  const { data } = await depositApi.get<DepositAccount[]>('/accounts', {
    params: { customerId: normalizedCustomerId },
    headers: headers(normalizedCustomerId),
  })
  return data
}

export async function terminateDepositContract(contractId: number, reason = 'ONLINE_TERMINATION', targetAccountId?: number) {
  const { data } = await depositApi.patch<DepositContract>(`/contracts/${contractId}/terminate`, {
    terminationReason: reason,
    targetAccountId: targetAccountId ?? null,
  })
  return data
}

export async function fetchDepositRecommendAgent(customerId: string, periodMonth = 3, birthYear?: number) {
  const normalizedCustomerId = normalizeDepositCustomerId(customerId)
  const { data } = await depositApi.get<DepositRecommendResponse>('/products/recommend-agent', {
    params: { customerId: normalizedCustomerId, periodMonth, ...(birthYear != null && { birthYear }) },
    headers: headers(normalizedCustomerId),
  })
  return data
}

async function resolveProductId(slug: string, productName: string) {
  if (PRODUCT_ID_BY_SLUG[slug]) return PRODUCT_ID_BY_SLUG[slug]

  const products = await fetchDepositProducts()
  const found = products.find((product) => product.productName === productName)
  if (!found) {
    throw new Error(`가입할 상품을 찾을 수 없습니다: ${productName}`)
  }
  return found.productId
}

export async function createDepositContract(customerId: string, input: CreateDepositContractInput) {
  const normalizedCustomerId = normalizeDepositCustomerId(customerId)
  const productId = await resolveProductId(input.slug, input.productName)
  const joinAmount = input.amount > 0 ? input.amount : 1
  const contractPeriodMonth = input.periodMonth > 0 ? input.periodMonth : 1

  const payload = {
    customerId: normalizedCustomerId,
    productId,
    joinAmount,
    contractPeriodMonth,
    joinChannel: 'WEB',
    totalPreferentialRate: 0,
    taxBenefitType: input.taxExempt ? 'NON_TAXABLE' : 'GENERAL',
    isAutoRenewal: false,
    autoTransferEnabled: input.autoTransferEnabled,
    autoTransferDay: input.autoTransferDay,
    savingType: input.isSavings ? SAVING_TYPE_BY_SLUG[input.slug] : undefined,
    accountPassword: input.accountPassword,
  }

  const { data } = await depositApi.post<DepositContract>('/contracts', payload, {
    headers: headers(normalizedCustomerId),
  })
  return data
}

function accountTypeLabel(account: DepositAccount, product?: DepositProduct): Account['type'] {
  if (account.accountType === 'SAVINGS') return '적금'
  if (account.accountType === 'SUBSCRIPTION') return '청약'
  if (product && CHECKING_PRODUCT_SLUGS.has(getDepositSlugByProductId(product.productId))) return '입출금'
  if (product?.productName?.includes('통장')) return '입출금'
  if (account.accountAlias?.includes('통장')) return '입출금'
  if (account.isWithdrawable ?? account.withdrawable) return '입출금'
  return '예금'
}

function fallbackName(account: DepositAccount) {
  if (account.accountType === 'SAVINGS') return '가입 적금'
  if (account.accountType === 'SUBSCRIPTION') return '가입 청약'
  if (account.isWithdrawable ?? account.withdrawable) return '가입 통장'
  return '가입 예금'
}

export type DepositTransaction = {
  transactionId: number
  accountNumber?: string
  transactionType: string
  directionType: 'IN' | 'OUT'
  amount: number | string
  balanceAfter?: number | string
  availableBalanceAfter?: number | string
  status: string
  transactionAt: string
  transactionSummary?: string
  /** 통장표시내용. 거래 시점에 함께 보낸 문구라 바꿀 수 없다. */
  transactionMemo?: string
  /** 고객 개인 메모. 나중에 붙이고 고칠 수 있다. */
  customerMemo?: string | null
  counterpartyAccountNo?: string
  counterpartyBankName?: string
  counterpartyName?: string
}

export type ExecuteDepositTransferInput = {
  fromAccountId: number
  toAccountId?: number
  toAccountNo: string
  amount: number
  transferType?: 'INTERNAL' | 'EXTERNAL' | 'AUTO' | 'SCHEDULED'
  counterpartyBankCode?: string
  counterpartyBankName?: string
  counterpartyName?: string
  transactionMemo?: string
  /**
   * 자금이동 승인 토큰(step-up). 인증서 PIN 확인 후 받는다.
   * 아직 선택값이다 — 서버가 required 로 바뀌기 전까지는 없어도 처리된다.
   */
  approvalToken?: string
}

export async function executeDepositTransfer(
  customerId: string,
  input: ExecuteDepositTransferInput
): Promise<DepositTransaction> {
  const { data } = await depositApi.post<DepositTransaction>(
    '/transactions/transfer',
    {
      fromAccountId: input.fromAccountId,
      toAccountId: input.toAccountId,
      toAccountNo: input.toAccountNo,
      amount: input.amount,
      transferType: input.transferType ?? (input.toAccountId ? 'INTERNAL' : 'EXTERNAL'),
      counterpartyBankCode: input.counterpartyBankCode,
      counterpartyBankName: input.counterpartyBankName,
      counterpartyName: input.counterpartyName,
      channelType: 'INTERNET',
      transactionMemo: input.transactionMemo ?? '인터넷 이체',
      approvalToken: input.approvalToken,
    },
    { headers: headers(customerId) }
  )
  return data
}

export async function fetchTransactions(params: { customerId?: string; accountId?: number }): Promise<DepositTransaction[]> {
  const { data } = await depositApi.get<DepositTransaction[] | { content?: DepositTransaction[] }>('/transactions', { params })
  if (Array.isArray(data)) return data
  return Array.isArray(data.content) ? data.content : []
}

export async function fetchTransaction(transactionId: number): Promise<DepositTransaction> {
  const { data } = await depositApi.get<DepositTransaction>(`/transactions/${transactionId}`)
  return data
}

export type TransactionChannel = 'BRANCH' | 'ATM' | 'INTERNET' | 'MOBILE' | 'SYSTEM'

export type SavingsPaymentInput = {
  accountId: number
  contractId: number
  amount: number
  paymentRound: number
  channelType?: TransactionChannel
}

// 적금 납입 — POST /transactions/savings-payment
export async function paySavings(input: SavingsPaymentInput): Promise<DepositTransaction> {
  const { data } = await depositApi.post<DepositTransaction>('/transactions/savings-payment', {
    accountId: input.accountId,
    contractId: input.contractId,
    amount: input.amount,
    paymentRound: input.paymentRound,
    channelType: input.channelType ?? 'INTERNET',
  })
  return data
}

// 거래 취소 — PATCH /transactions/{id}/cancel (백엔드: 출금/이체 거래만 취소 가능)
export async function cancelTransaction(transactionId: number, cancelReason = 'CUSTOMER_REQUEST'): Promise<DepositTransaction> {
  const { data } = await depositApi.patch<DepositTransaction>(`/transactions/${transactionId}/cancel`, { cancelReason })
  return data
}

// 출금/이체 거래이면서 아직 취소되지 않은 건만 취소 가능
export function isCancelableTransaction(tx: DepositTransaction): boolean {
  const cancelable = tx.transactionType === 'WITHDRAW' || tx.transactionType === 'TRANSFER'
  return cancelable && tx.status !== 'CANCELED'
}

// 다음 적금 납입 회차 = 기존 적금납입 거래 수 + 1
export function nextSavingsPaymentRound(transactions: DepositTransaction[]): number {
  const paid = transactions.filter((tx) => tx.transactionType === 'SAVINGS_PAYMENT' && tx.status !== 'CANCELED').length
  return paid + 1
}

export async function fetchDepositAccountViewModels(customerId: string): Promise<DepositViewAccount[]> {
  const accounts = await fetchDepositAccounts(customerId)
  const [contractsResult, productsResult] = await Promise.allSettled([
    fetchDepositContracts(customerId),
    fetchDepositProducts(),
  ])

  const contracts = contractsResult.status === 'fulfilled' ? contractsResult.value : []
  const products = productsResult.status === 'fulfilled' ? productsResult.value : []
  const contractById = new Map(contracts.map((contract) => [contract.contractId, contract]))
  const productById = new Map(products.map((product) => [product.productId, product]))

  // localStorage에 저장된 type 정보 활용 (입출금/적금/예금 구분)
  const localTypeMap = new Map<number, Account['type']>()
  const localNameMap = new Map<number, string>()
  if (typeof window !== 'undefined') {
    try {
      const raw = localStorage.getItem('joinedAccounts')
      if (raw) {
        const parsed = JSON.parse(raw) as DepositViewAccount[]
        parsed.forEach(a => {
          if (a.apiAccountId) {
            localTypeMap.set(a.apiAccountId, a.type as Account['type'])
            if (a.name) localNameMap.set(a.apiAccountId, a.name)
          }
        })
      }
    } catch {}
  }

  return accounts
    .filter((account) => account.accountStatus !== 'CLOSED')
    .map((account) => {
      const contract = contractById.get(account.contractId)
      const product = contract ? productById.get(contract.productId) : undefined
      const balance = toNumber(account.balance)
      const resolvedType = localTypeMap.get(account.accountId) ?? accountTypeLabel(account, product)
      const resolvedName = account.accountAlias || localNameMap.get(account.accountId) || product?.productName || fallbackName(account)

      return {
        id: `deposit-${account.accountId}`,
        apiAccountId: account.accountId,
        contractId: account.contractId,
        rawAccountType: account.accountType,
        isWithdrawable: account.isWithdrawable ?? account.withdrawable,
        accountStatus: account.accountStatus,
        savingType: account.savingType ?? (account.accountType === 'SAVINGS' ? 'REGULAR' : undefined),
        number: account.accountNumber,
        type: resolvedType,
        name: resolvedName,
        balance,
        availableBalance: balance,
        createdAt: toDateText(account.openedAt) || '',
        maturityDate: toDateText(account.maturityAt),
        monthlyAmount:
          account.accountType === 'SAVINGS'
            ? toNumber(account.totalPaidAmount || contract?.joinAmount, undefined)
            : undefined,
      }
    })
}

// ── 거래 증빙 ────────────────────────────────────────────────────────────────
//
// 화면에 "거래영수증"·"이체확인증 건별/일괄 출력" 버튼이 있었지만 부를 곳이 없었다.
// 발급 대상은 보내지 않는다 — 게이트웨이가 검증한 고객번호로 서버가 정한다.

export type CertificateType = 'RECEIPT' | 'TRANSFER_CONFIRMATION'

export type TransactionCertificate = {
  certificateNo: string
  certificateType: CertificateType
  issuedAt: string
  transactionNumber: string
  transactionAt: string
  transactionType: string | null
  amount: number
  feeAmount: number
  balanceAfter: number
  transactionMemo: string | null
  /** 이체확인증에만 있다. 영수증은 상대가 없어도 성립한다. */
  counterpartyBankName: string | null
  counterpartyAccountNo: string | null
  counterpartyName: string | null
}

/** 단건 발급. 재발급하면 새 번호가 붙고 이력이 쌓인다. */
export async function issueTransactionCertificate(
  transactionId: number,
  type: CertificateType,
): Promise<TransactionCertificate> {
  const { data } = await depositApi.post<TransactionCertificate>(
    `/transactions/${transactionId}/certificates`, null, { params: { type } },
  )
  return data
}

/** 일괄 발급. 한 건이라도 발급할 수 없으면 전부 실패한다. */
export async function issueTransactionCertificatesBatch(
  transactionIds: number[],
  type: CertificateType,
): Promise<TransactionCertificate[]> {
  const { data } = await depositApi.post<TransactionCertificate[]>(
    '/transactions/certificates/batch', transactionIds, { params: { type } },
  )
  return data
}

/**
 * 개인 메모 수정.
 *
 * 통장표시내용(transactionMemo)은 바꾸지 않는다 — 이체할 때 함께 보낸 문구라
 * 사후에 고치면 실제로 보낸 내용과 기록이 달라진다. 내용을 비우면 메모가 지워진다.
 */
export async function updateTransactionMemo(
  transactionId: number,
  memo: string,
): Promise<DepositTransaction> {
  const { data } = await depositApi.patch<DepositTransaction>(
    `/transactions/${transactionId}/memo`, { memo },
  )
  return data
}

/* ── 적금 납입현황 ── */

export type SavingsPaymentRound = {
  paymentRound: number
  scheduledDate: string
  scheduledAmount: number
  status: 'PENDING' | 'PAID' | 'OVERDUE' | 'FAILED' | 'SUSPENDED'
  paidAt: string | null
  actualAmount: number | null
  autoTransfer: boolean
}

export type SavingsPaymentStatus = {
  accountId: number
  contractId: number
  contractNumber: string
  monthlyAmount: number
  totalRounds: number
  paidRounds: number
  /** 예정일이 지났는데 아직 안 낸 회차. 당일은 세지 않는다. */
  missedRounds: number
  totalPaidAmount: number
  startedAt: string
  maturityAt: string | null
  nextScheduledDate: string | null
  rounds: SavingsPaymentRound[]
}

/**
 * 적금 납입현황.
 *
 * 계좌 아이디로 부른다 — 고객이 아는 것은 계좌이지 계약이 아니다. 서버가 소유자를
 * 확인하므로 고객번호를 실어 보내지 않는다.
 */
export async function fetchSavingsPaymentStatus(accountId: number): Promise<SavingsPaymentStatus> {
  const { data } = await depositApi.get<SavingsPaymentStatus>(`/payment-schedules/accounts/${accountId}`)
  return data
}
