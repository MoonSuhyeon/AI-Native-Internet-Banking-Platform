'use client'

import {
  type FormEvent,
  type PointerEvent as ReactPointerEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { createPortal } from 'react-dom'
import Link from 'next/link'
import { api, fetchMyCertificates, issueTransferApproval } from '@/lib/api'
import { ArrowLeftRight, Bot, Home, LogOut, MessageCircle, PackageSearch, Paperclip, Phone, Send, Sparkles, X } from 'lucide-react'
import {
  ChatbotButton,
  ChatbotFeatureExecuteResponse,
  analyzeFile,
  uploadDocument,
  executeChatbotFeature,
  executeChatbotTransfer,
  sendChatbotMessage,
  startChatbotConsultation,
  sendChatMessage as sendAgentChatMessage,
  getChatMessages as getAgentChatMessages,
  endChat as endAgentChat,
  requestAgentChat,
  getChatConsultation,
  type ChatMessage as AgentChatMessage,
} from '@/lib/consultation-api'
import {
  getCurrentDepositCustomerId,
  fetchDepositAccountViewModels,
  fetchDepositProducts,
  fetchDepositInterestRates,
  fetchDepositRecommendAgent,
  terminateDepositContract,
  type DepositProduct,
  type DepositProductType,
  type DepositRecommendProduct,
  type DepositInterestRate,
} from '@/lib/deposit-api'
import ConsultModal from '@/components/layout/ConsultModal'
import { KB_CHAT } from '@/lib/theme'
import { readAccessToken } from '@/lib/token'

type ChatMessage = {
  id: string
  role: 'bot' | 'user' | 'system'
  text: string
  buttons?: ChatbotButton[]
  data?: Record<string, unknown>[]
  compareData?: { accumulate: Record<string, unknown>[]; lumpSum: Record<string, unknown>[] }
  link?: { text: string; href: string }
  featureCode?: string
  loginForm?: boolean
}

type TransferStep = 'form' | 'confirm' | 'verify' | 'processing' | 'done' | 'error'

type MyAccount = { account_id: number; account_number: string; balance: number; account_alias: string | null }

type TransferState = {
  step: TransferStep
  fromAccountId: number
  fromAccountNumber: string
  fromBalance: number
  toAccountNumber: string
  toBank: string
  toTab: 'direct' | 'my_accounts'
  myAccounts: MyAccount[]
  amount: string
  memo: string
  resultMessage: string
  balanceAfter: number | null
  // 보안카드는 백엔드 구현이 없어 화면도 없앴다(에러 코드만 정의돼 있던 상태).
  // 인증서 PIN 경로만 남는다.
  verifySubStep: 'cert-info' | 'cert-pin'
  /** 인증서가 없어 막힌 경우 — 발급 동선을 함께 보여준다. */
  needsCertificate?: boolean
  certPin: string
}

type ProductSearchStep = 'period' | 'amount' | 'type' | 'rate' | 'purpose' | 'done'
type ProductSearchState = {
  step: ProductSearchStep
  period: string
  amount: string
  productType: 'DEPOSIT' | 'SAVINGS' | 'SUBSCRIPTION' | null
  purpose: 'lump_sum' | 'monthly' | null
  minRate: string
}

type TerminateStep = 'method' | 'verify-cert-info' | 'verify-cert-pin' | 'done' | 'error'
type TerminateState = {
  step: TerminateStep
  accountId: number
  accountNumber: string
  productName: string
  balance: number
  contractId: number | null
  method: 'cash' | 'own' | 'other' | null
  targetAccountId: string
  otherBank: string
  otherAccount: string
  checkingAccounts: MyAccount[]
  certPin: string
}

type ExpandedRow = {
  key: string
  title: string
  row: Record<string, unknown>
}

const DEFAULT_CUSTOMER_NO = ''

function canShowTransferButton(row: Record<string, unknown>) {
  const values = [
    row.product_type,
    row.account_type,
    row.deposit_product_type,
    row.raw_account_type,
    row.saving_type,
  ].map((value) => String(value ?? '').toUpperCase())

  const labels = [
    row.product_type,
    row.account_type,
    row.product_name,
    row.account_alias,
  ].map((value) => String(value ?? ''))

  const blockedTypes = new Set(['DEPOSIT', 'SAVINGS', 'SUBSCRIPTION', 'REGULAR', 'FREE'])
  if (values.some((value) => blockedTypes.has(value))) return false
  if (labels.some((value) => /예금|적금|청약/.test(value))) return false

  return row.is_withdrawable === true || values.some((value) => value === 'DEMAND' || value === 'CHECKING')
}

const TEXT = {
  deposit: '\uC608\uAE08',
  savings: '\uC801\uAE08',
  subscription: '\uCCAD\uC57D',
  saving: '\uC800\uCD95',
  recommend: '\uC0C1\uD488 \uCD94\uCC9C',
  cashflow: '\uCD5C\uADFC \uD604\uAE08\uD750\uB984',
  myProducts: '\uB0B4 \uC0C1\uD488',
  detail: '\uC0C1\uC138',
  won: '\uC6D0',
  count: '\uAC74',
  item: '\uD56D\uBAA9',
  // 예전 값이 유니코드 이스케이프로 적힌 오타였다(= AX + 풀댜크).
  // 'AX풀뱅크' 같은 한글 음차는 오타가 아니라 정당한 표기라 손대지 않는다 —
  // 여기만 '댜크' 로 깨져 있었다.
  title: 'AXful Bank \uC0C1\uB2F4 \uCC57\uBD07',
  subtitle: '\uD604\uAE08 \uD750\uB984 \uBD84\uC11D\uACFC \uC0C1\uD488 \uCD94\uCC9C',
  openChat: '\uCC57\uBD07 \uC0C1\uB2F4 \uC5F4\uAE30',
  closeChat: '\uCC57\uBD07 \uC0C1\uB2F4 \uB2EB\uAE30',
  closeDetail: '\uC0C1\uC138 \uB2EB\uAE30',
  inputPlaceholder: '\uAD81\uAE08\uD55C \uB0B4\uC6A9\uC744 \uC785\uB825\uD558\uC138\uC694',
  sendMessage: '\uBA54\uC2DC\uC9C0 \uBCF4\uB0B4\uAE30',
  loading: '\uC751\uB2F5\uC744 \uC900\uBE44\uD558\uACE0 \uC788\uC2B5\uB2C8\uB2E4.',
  prev: '\uC774\uC804',
  next: '\uB2E4\uC74C',
}

const DATA_PAGE_SIZE = 10

const PRODUCT_CHOICES = [
  { label: TEXT.deposit,      query: '\uC608\uAE08 \uC0C1\uD488 \uC54C\uB824\uC918' },
  { label: TEXT.savings,      query: '\uC801\uAE08 \uC0C1\uD488 \uC54C\uB824\uC918' },
  { label: TEXT.subscription, query: '\uCCAD\uC57D \uC0C1\uD488 \uC54C\uB824\uC918' },
]

const FIELD_LABELS: Record<string, string> = {
  transaction_id: '\uAC70\uB798\uBC88\uD638',
  transaction_number: '\uAC70\uB798\uAD00\uB9AC\uBC88\uD638',
  account_number: '\uACC4\uC88C\uBC88\uD638',
  customer_no: '\uACE0\uAC1D\uBC88\uD638',
  transaction_type: '\uAC70\uB798\uAD6C\uBD84',
  amount: '\uAC70\uB798\uAE08\uC561',
  transaction_status: '\uAC70\uB798\uC0C1\uD0DC',
  created_at: '\uAC70\uB798\uC77C\uC2DC',
  total_balance: '\uCD1D \uC794\uC561',
  monthly_surplus: '\uC6D4\uD3C9\uADE0 \uC789\uC5EC\uC790\uAE08',
  monthly_tx_count: '\uC6D4\uD3C9\uADE0 \uAC70\uB798\uAC74\uC218',
  has_data: '\uD604\uAE08\uD750\uB984 \uB370\uC774\uD130',
  product_count: '\uCD94\uCC9C \uAC00\uB2A5 \uC0C1\uD488 \uC218',
  rank: '\uC21C\uC704',
  product_name: '\uC0C1\uD488\uBA85',
  product_type: '\uC0C1\uD488\uAD6C\uBD84',
  base_interest_rate: '\uAE30\uBCF8\uAE08\uB9AC',
  min_join_amount: '\uCD5C\uC18C \uAC00\uC785\uAE08\uC561',
  max_join_amount: '\uCD5C\uB300 \uAC00\uC785\uAE08\uC561',
  min_period_month: '\uCD5C\uC18C \uAE30\uAC04',
  max_period_month: '\uCD5C\uB300 \uAE30\uAC04',
  target_groups: '\uAC00\uC785 \uB300\uC0C1',
  is_early_termination_allowed: '\uC911\uB3C4\uD574\uC9C0',
  is_tax_benefit_available: '\uC138\uAE08 \uD61C\uD0DD',
  is_auto_renewal_available: '\uC790\uB3D9 \uAC31\uC2E0',
  product_desc: '\uC0C1\uD488 \uC124\uBA85',
  pref_condition: '\uC6B0\uB300\uAE08\uB9AC \uC870\uAC74',
}

const VALUE_LABELS: Record<string, string> = {
  DEPOSIT: '\uC785\uAE08',
  WITHDRAWAL: '\uCD9C\uAE08',
  TRANSFER: '\uC774\uCCB4',
  SAVINGS: '\uC801\uAE08',
  SUBSCRIPTION: '\uCCAD\uC57D',
  SUCCESS: '\uC131\uACF5',
  COMPLETED: '\uC644\uB8CC',
  PENDING: '\uCC98\uB9AC\uC911',
  FAILED: '\uC2E4\uD328',
}

function messageId(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function formatFieldLabel(key: string) {
  return FIELD_LABELS[key] || key
}

function formatDisplayValue(key: string, value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'boolean') return value ? '\uC788\uC74C' : '\uC5C6\uC74C'
  if (typeof value === 'number') {
    const formatted = Math.round(value).toLocaleString('ko-KR')
    if (key.includes('amount') || key.includes('balance') || key.includes('surplus')) return `${formatted}${TEXT.won}`
    if (key.includes('count')) return `${formatted}${TEXT.count}`
    if (key.includes('period_month')) return `${formatted}\uAC1C\uC6D4`
    if (key === 'base_interest_rate') return `${value}%`
    if (key === 'rank') return `${formatted}\uC21C\uC704`
    return formatted
  }

  const text = String(value)
  if (VALUE_LABELS[text]) return VALUE_LABELS[text]
  if (key === 'created_at') return text.replace('T', ' ').slice(0, 16)
  if (key === 'base_interest_rate') return `${text}%`
  if (/^-?\d+(\.\d+)?$/.test(text) && (key.includes('amount') || key.includes('balance') || key.includes('surplus'))) {
    return `${Number(text).toLocaleString('ko-KR')}${TEXT.won}`
  }
  if (/^-?\d+(\.\d+)?$/.test(text) && key.includes('period_month')) return `${Number(text).toLocaleString('ko-KR')}\uAC1C\uC6D4`
  return text
}

function dataTitle(row: Record<string, unknown>, index: number) {
  return (
    row.deposit_product_name ||
    row.product_name ||
    row.account_number ||
    row.transaction_number ||
    row.transaction_id ||
    `${TEXT.item} ${index + 1}`
  )
}

function rowSummary(row: Record<string, unknown>, index: number) {
  if (row.row_type === 'recommended_product') {
    return {
      title: `${formatDisplayValue('rank', row.rank)} · ${formatDisplayValue('product_name', row.product_name)}`,
      meta: `${formatDisplayValue('product_type', row.product_type)} · ${formatFieldLabel('base_interest_rate')} ${formatDisplayValue('base_interest_rate', row.base_interest_rate)}`,
    }
  }

  if (row.row_type === 'cash_flow_summary') {
    return {
      title: '\uD604\uAE08\uD750\uB984 \uBD84\uC11D \uC694\uC57D',
      meta: `${formatFieldLabel('monthly_surplus')} ${formatDisplayValue('monthly_surplus', row.monthly_surplus)}`,
    }
  }

  if ('product_name' in row && 'base_interest_rate' in row) {
    const period = row.min_period_month && row.max_period_month
      ? `${row.min_period_month}~${row.max_period_month}개월`
      : row.min_period_month
        ? `${row.min_period_month}개월 이상`
        : '기간 무관'
    return {
      title: String(row.product_name || '상품'),
      meta: `${formatDisplayValue('base_interest_rate', row.base_interest_rate)} · ${period} · ${row.target_groups || '개인고객'}`,
    }
  }

  if ('account_number' in row && 'balance' in row && 'product_name' in row) {
    const balance = Number(row.balance ?? 0).toLocaleString('ko-KR')
    const type = String(row.product_type ?? '')
    return {
      title: String(row.product_name || row.account_number || ''),
      meta: `${type} · 잔액 ${balance}원`,
    }
  }

  if ('transaction_id' in row || 'transaction_type' in row) {
    return {
      title: `${formatDisplayValue('transaction_type', row.transaction_type)} ${formatDisplayValue('amount', row.amount)}`,
      meta: `${formatDisplayValue('created_at', row.created_at)} · ${formatDisplayValue('transaction_status', row.transaction_status)}`,
    }
  }

  return {
    title: String(dataTitle(row, index)),
    meta: Object.entries(row)
      .slice(0, 2)
      .map(([key, value]) => `${formatFieldLabel(key)} ${formatDisplayValue(key, value)}`)
      .join(' · '),
  }
}

function addFeatureResult(result: ChatbotFeatureExecuteResponse & { compareData?: { accumulate: Record<string, unknown>[]; lumpSum: Record<string, unknown>[] } }): ChatMessage {
  const needLogin = result.requires_auth && !readAccessToken()
  return {
    id: messageId(result.feature_code),
    role: 'bot',
    text: result.message || '\uC694\uCCAD\uD558\uC2E0 \uB0B4\uC6A9\uC744 \uD655\uC778\uD588\uC2B5\uB2C8\uB2E4.',
    data: result.data,
    compareData: result.compareData,
    featureCode: result.feature_code,
    ...(needLogin ? { loginForm: true } : {}),
  }
}

export default function ChatbotWidget() {
  const [mounted, setMounted] = useState(false)
  const [open, setOpen] = useState(false)
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [showConsult, setShowConsult] = useState(false)
  const [loading, setLoading] = useState(false)
  const [input, setInput] = useState('')
  const [customerNo, setCustomerNo] = useState(DEFAULT_CUSTOMER_NO)
  const [chatbotConsultationId, setChatbotConsultationId] = useState<number | null>(null)
  const [expandedRow, setExpandedRow] = useState<ExpandedRow | null>(null)
  const [dataPages, setDataPages] = useState<Record<string, number>>({})
  const [feedback, setFeedback] = useState<Record<string, 'like' | 'dislike'>>({})
  const [panelOffset, setPanelOffset] = useState({ x: 0, y: 0 })
  const [transferState, setTransferState] = useState<TransferState | null>(null)
  const [terminateState, setTerminateState] = useState<TerminateState | null>(null)
  const [productSearchState, setProductSearchState] = useState<ProductSearchState | null>(null)
  const [lastRecommendCtx, setLastRecommendCtx] = useState<string>('')
  const lastRecommendProductsRef = useRef<Record<string, unknown>[]>([])
  const lastTopProductRef = useRef<Record<string, unknown> | null>(null)
  const lastCompareAnalysisRef = useRef<string | null>(null)
  const lastCompareNamesRef = useRef<[string, string] | null>(null)
  const [showFileMenu, setShowFileMenu] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [pendingFileAction, setPendingFileAction] = useState<'CASH_FLOW' | 'TERMS' | 'PRODUCT' | 'ENROLLMENT' | null>(null)
  const pendingLoginActionRef = useRef<string | null>(null)
  const dragRef = useRef<{ startX: number; startY: number; originX: number; originY: number } | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: 'welcome',
      role: 'bot',
      text: '\uC548\uB155\uD558\uC138\uC694. \uC6D0\uD558\uC2DC\uB294 \uC0C1\uD488\uAD70\uC744 \uACE0\uB974\uAC70\uB098 \uD604\uAE08 \uD750\uB984 \uAE30\uBC18 \uC0C1\uD488 \uCD94\uCC9C\uC744 \uBC1B\uC544\uBCF4\uC138\uC694.',
    },
  ])

  // ── 상담원 모드 state ──────────────────────────────────────────────────────
  // 상담원 모드 state 는 패널과 함께 제거했다 — 설정되는 곳이 없어 전부 초기값에 고정돼 있었다.

  const hasStarted = chatbotConsultationId !== null

  // ── 고객 채팅(상담사 연결) state ──────────────────────────────────────────
  const [customerChatId, setCustomerChatIdState] = useState<number | null>(null)
  const [customerChatStatus, setCustomerChatStatusState] = useState<'WAITING' | 'CONNECTED' | 'ENDED' | null>(null)

  const setCustomerChatId = (id: number | null) => {
    setCustomerChatIdState(id)
    if (id == null) sessionStorage.removeItem('customerChatId')
    else sessionStorage.setItem('customerChatId', String(id))
  }
  const setCustomerChatStatus = (s: 'WAITING' | 'CONNECTED' | 'ENDED' | null) => {
    setCustomerChatStatusState(s)
    if (s == null) sessionStorage.removeItem('customerChatStatus')
    else sessionStorage.setItem('customerChatStatus', s)
  }
  const [customerChatMessages, setCustomerChatMessages] = useState<AgentChatMessage[]>([])
  const [customerChatInput, setCustomerChatInput] = useState('')
  const [customerChatSending, setCustomerChatSending] = useState(false)
  const [satisfactionScore, setSatisfactionScore] = useState<number | null>(null)
  const [satisfactionSubmitted, setSatisfactionSubmitted] = useState(false)
  const customerChatBottomRef = useRef<HTMLDivElement>(null)

  // ── 상담원 모드 함수 ──────────────────────────────────────────────────────






  // sessionStorage에서 고객 채팅 상태 복원
  useEffect(() => {
    const savedId = sessionStorage.getItem('customerChatId')
    const savedStatus = sessionStorage.getItem('customerChatStatus')
    if (savedId && savedStatus && savedStatus !== 'ENDED') {
      setCustomerChatIdState(Number(savedId))
      setCustomerChatStatusState(savedStatus as 'WAITING' | 'CONNECTED')
    }
  }, [])

  // 새 채팅 시작 시 만족도 상태 초기화
  useEffect(() => {
    setSatisfactionSubmitted(false)
    setSatisfactionScore(null)
  }, [customerChatId])

  // 고객 채팅 폴링: WAITING → CONNECTED 감지 + 메시지 갱신
  useEffect(() => {
    if (!customerChatId || customerChatStatus === 'ENDED') return
    const poll = async () => {
      try {
        const chat = await getChatConsultation(customerChatId)
        setCustomerChatStatus(chat.status)
        if (chat.status === 'CONNECTED' || chat.status === 'ENDED') {
          setCustomerChatMessages(await getAgentChatMessages(customerChatId))
        }
      } catch { /* ignore */ }
    }
    poll()
    const id = setInterval(poll, 3000)
    return () => clearInterval(id)
  }, [customerChatId, customerChatStatus])

  useEffect(() => {
    customerChatBottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [customerChatMessages])

  async function handleCustomerChatSend() {
    if (!customerChatInput.trim() || !customerChatId || customerChatStatus !== 'CONNECTED') return
    setCustomerChatSending(true)
    try {
      const msg = await sendAgentChatMessage(customerChatId, customerChatInput.trim(), 'USER')
      setCustomerChatMessages(prev => [...prev, msg])
      setCustomerChatInput('')
    } catch { /* ignore */ } finally { setCustomerChatSending(false) }
  }

  const quickActions = useMemo(
    () => [
      ...PRODUCT_CHOICES.map((choice) => ({ type: 'product_guide' as const, ...choice })),
      { type: 'my_products' as const, label: TEXT.myProducts, message: '\uB0B4 \uC0C1\uD488 \uBCF4\uC5EC\uC918' },
      { type: 'recommend' as const, label: TEXT.recommend, message: '' },
      { type: 'consult' as const, label: '\uC0C1\uB2F4\uC6D0 \uC5F0\uACB0', message: '' },
    ],
    [],
  )

  useEffect(() => {
    setMounted(true)
  }, [])

  useEffect(() => {
    if (!open) return
    // Header와 동일한 기준: localStorage 'user' 키 존재 여부로 로그인 상태 판단
    const userRaw = localStorage.getItem('user')
    const token = readAccessToken()
    if (!userRaw || !token) {
      setIsLoggedIn(false)
      setCustomerNo('')
      return
    }
    try {
      JSON.parse(userRaw)
      const cid = getCurrentDepositCustomerId()
      setIsLoggedIn(true)
      if (cid) setCustomerNo(cid)
    } catch {
      setIsLoggedIn(false)
      setCustomerNo('')
    }
  }, [open])

  useEffect(() => {
    function handlePointerMove(event: globalThis.PointerEvent) {
      const drag = dragRef.current
      if (!drag) return
      setPanelOffset({
        x: drag.originX + event.clientX - drag.startX,
        y: drag.originY + event.clientY - drag.startY,
      })
    }

    function handlePointerUp() {
      dragRef.current = null
    }

    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', handlePointerUp)
    return () => {
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', handlePointerUp)
    }
  }, [])

  function pushMessages(next: ChatMessage[]) {
    setMessages((current) => [...current, ...next])
    window.setTimeout(() => {
      scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
    }, 50)
  }

  function startPanelDrag(event: ReactPointerEvent<HTMLElement>) {
    if (event.button !== 0) return
    dragRef.current = {
      startX: event.clientX,
      startY: event.clientY,
      originX: panelOffset.x,
      originY: panelOffset.y,
    }
    event.currentTarget.setPointerCapture?.(event.pointerId)
  }

  function setMessagePage(messageIdValue: string, page: number) {
    setExpandedRow(null)
    setDataPages((current) => ({ ...current, [messageIdValue]: page }))
  }

  async function ensureStarted(silent = false) {
    if (chatbotConsultationId) return chatbotConsultationId
    const started = await startChatbotConsultation(customerNo.trim() || DEFAULT_CUSTOMER_NO)
    setChatbotConsultationId(started.chatbot_consultation_id)
    if (!silent) {
      pushMessages([
        {
          id: messageId('start'),
          role: 'bot',
          text: started.message,
          buttons: started.buttons,
        },
      ])
    }
    return started.chatbot_consultation_id
  }

  function saveRecommendContext(result: ChatbotFeatureExecuteResponse) {
    if (!result.data?.length) return
    const products = result.data.filter(r => r.row_type === 'recommended_product')
    if (!products.length) return
    lastRecommendProductsRef.current = products
    lastTopProductRef.current = products[0]
    const ctx = products.map((p, i) => {
      const name = String(p.product_name ?? '')
      const rate = p.base_interest_rate ?? '-'
      const reason = p.reason ? ` 추천이유:${String(p.reason)}` : ''
      const period = p.min_period_month != null ? ` 기간:${p.min_period_month}~${p.max_period_month}개월` : ''
      return `${i + 1}위:${name}(금리${rate}%${period}${reason})`
    }).join(' / ')
    setLastRecommendCtx(ctx)
  }

  function productTypeLabel(type?: string) {
    if (type === 'SAVINGS') return '적금'
    if (type === 'SUBSCRIPTION') return '청약'
    return '예금'
  }

  function getProductId(product: DepositProduct | DepositRecommendProduct) {
    return Number(
      ('productId' in product ? product.productId : undefined) ??
      ('product_id' in product ? product.product_id : undefined) ??
      0,
    )
  }

  function summarizePreferentialRates(rates: DepositInterestRate[]) {
    const preferential = rates
      .filter((rate) => rate.rateType === 'PREFERENTIAL' && rate.isActive !== false)
      .sort((a, b) => Number(b.rate ?? 0) - Number(a.rate ?? 0))
    const total = preferential.reduce((sum, rate) => sum + Number(rate.rate ?? 0), 0)
    const conditions = preferential
      .map((rate) => rate.conditionDescription)
      .filter((condition): condition is string => Boolean(condition && condition.trim()))
    return {
      prefRate: total > 0 ? Number(total.toFixed(2)) : undefined,
      prefCondition: conditions.length > 0 ? conditions.join(' / ') : undefined,
    }
  }

  async function enrichWithPreferentialRates(rows: Record<string, unknown>[]) {
    const enriched = await Promise.all(rows.map(async (row) => {
      const productId = Number(row.product_id ?? 0)
      if (!productId) return row
      try {
        const summary = summarizePreferentialRates(await fetchDepositInterestRates(productId))
        return {
          ...row,
          pref_rate: summary.prefRate,
          pref_condition: summary.prefCondition,
        }
      } catch {
        return row
      }
    }))
    return enriched
  }

  function parseKoreanAmount(value: string): number {
    const v = value.replace(/,/g, '').trim()
    const manMatch = v.match(/(\d+(?:\.\d+)?)\s*만/)
    if (manMatch) return Math.round(parseFloat(manMatch[1]) * 10_000)
    const num = parseFloat(v.replace(/[^0-9.]/g, ''))
    return isNaN(num) ? 0 : num
  }

  function inferProductType(text: string): DepositProductType | undefined {
    if (text.includes('청약')) return 'SUBSCRIPTION'
    if (text.includes('적금')) return 'SAVINGS'
    if (text.includes('예금')) return 'DEPOSIT'
    return undefined
  }

  function productToRow(
    product: DepositProduct | DepositRecommendProduct,
    index: number,
    reason?: string,
  ): Record<string, unknown> {
    const productType = String(
      ('productType' in product ? product.productType : undefined) ??
      ('product_type' in product ? product.product_type : undefined) ??
      'DEPOSIT',
    )

    return {
      row_type: 'recommended_product',
      rank: index + 1,
      product_id: getProductId(product),
      product_name: ('productName' in product ? product.productName : undefined) ?? ('product_name' in product ? product.product_name : undefined),
      product_type: productTypeLabel(productType),
      base_interest_rate: ('bestRate' in product ? product.bestRate : undefined) ??
        ('baseInterestRate' in product ? product.baseInterestRate : undefined) ??
        ('base_interest_rate' in product ? product.base_interest_rate : undefined),
      min_join_amount: ('minJoinAmount' in product ? product.minJoinAmount : undefined),
      max_join_amount: ('maxJoinAmount' in product ? product.maxJoinAmount : undefined),
      min_period_month: ('minPeriodMonth' in product ? product.minPeriodMonth : undefined),
      max_period_month: ('maxPeriodMonth' in product ? product.maxPeriodMonth : undefined),
      product_desc: 'description' in product ? product.description : undefined,
      reason: reason ?? ('reason' in product ? product.reason : undefined),
    }
  }

  function buildFeatureResult(featureCode: string, message: string, rows: Record<string, unknown>[]): ChatbotFeatureExecuteResponse {
    return {
      feature_code: featureCode,
      status: rows.length || message ? 'OK' : 'EMPTY',
      message,
      data: rows,
      requires_auth: false,
      requires_staff_auth: false,
    }
  }

  async function executeDepositProductGuide(query: string) {
    const productType = inferProductType(query)
    const products = await fetchDepositProducts(productType ? { productType } : undefined)
    const rows = products
      .filter((p) => !p.productStatus || p.productStatus === 'SELLING')
      .sort((a, b) => Number(b.bestRate ?? b.baseInterestRate ?? 0) - Number(a.bestRate ?? a.baseInterestRate ?? 0))
      .map((p, i) => productToRow(p, i))
    const label = productType ? productTypeLabel(productType) : '예금/적금/청약'
    return buildFeatureResult('PRODUCT_GUIDE', `${label} 상품을 조회했습니다.`, await enrichWithPreferentialRates(rows))
  }




  async function answerCashflowRecommend(customerId: string, _userText: string): Promise<string> {
    try {
      let birthYear: number | undefined
      try {
        const meRes = await api.get<{ data: { birthDate?: string } }>('/api/v1/customers/me')
        const birthDate = meRes.data?.data?.birthDate
        if (birthDate) {
          birthYear = parseInt(birthDate.replace(/-/g, '').slice(0, 4), 10)
        }
      } catch { /* 나이 미확인 시 필터 생략 */ }
      const result = await fetchDepositRecommendAgent(customerId, 3, birthYear)
      const products = result.recommendations ?? result.products ?? []
      const rows = products.slice(0, 3).map((product, index) =>
        productToRow(product, index, product.reason ?? '최근 현금흐름 기반 추천 상품입니다.'),
      )
      const enrichedRows = await enrichWithPreferentialRates(rows)
      saveRecommendContext(buildFeatureResult('CASH_FLOW_RECOMMEND', '최근 현금흐름 기반 추천 상품입니다.', enrichedRows))
      const firstDepositOrSavings = products.find((product) => {
        const type = product.productType ?? product.product_type
        return type === 'DEPOSIT' || type === 'SAVINGS'
      })
      const productType = firstDepositOrSavings?.productType ?? firstDepositOrSavings?.product_type
      const productName = firstDepositOrSavings?.productName ?? firstDepositOrSavings?.product_name
      const reason = firstDepositOrSavings?.reason
      const netCashFlow = result.cashFlow?.netCashFlow
      const estimatedSavings = result.cashFlow?.estimatedSavingsAmount

      if (productType === 'DEPOSIT') {
        return [
          '고객님의 최근 현금흐름 기준으로는 적금보다 예금이 더 적절해 보여요.',
          '',
          netCashFlow != null ? `최근 순현금흐름은 약 ${Number(netCashFlow).toLocaleString()}원입니다.` : null,
          '이미 운용할 수 있는 목돈이 있거나, 매달 추가 납입보다 일정 기간 묶어두는 방식이 더 맞을 때 예금이 유리합니다.',
          productName ? `우선 검토할 상품: ${productName}` : null,
          reason ? `판단 근거: ${reason}` : null,
        ].filter(Boolean).join('\n')
      }

      if (productType === 'SAVINGS') {
        return [
          '고객님의 최근 현금흐름 기준으로는 예금보다 적금이 더 적절해 보여요.',
          '',
          estimatedSavings != null ? `매달 저축 여력은 약 ${Number(estimatedSavings).toLocaleString()}원으로 추정됩니다.` : null,
          '목돈을 한 번에 맡기기보다 매달 꾸준히 모으는 패턴이면 적금이 더 잘 맞습니다.',
          productName ? `우선 검토할 상품: ${productName}` : null,
          reason ? `판단 근거: ${reason}` : null,
        ].filter(Boolean).join('\n')
      }
    } catch {}

    return [
      '거래 내역을 충분히 확인하지 못해 일반 기준으로 안내드릴게요.',
      '',
      '- 이미 모아둔 목돈이 있으면 예금이 더 적절합니다.',
      '- 매달 조금씩 모으고 싶으면 적금이 더 적절합니다.',
      '- 당장 큰 금액을 묶기 어렵다면 소액 자유적금부터 시작하는 편이 좋습니다.',
    ].join('\n')
  }

  function josa(word: string, type: '을를' | '이가' | '과와'): string {
    const code = word.charCodeAt(word.length - 1)
    const hasBatchim = code >= 0xAC00 && (code - 0xAC00) % 28 !== 0
    if (type === '을를') return word + (hasBatchim ? '을' : '를')
    if (type === '이가') return word + (hasBatchim ? '이' : '가')
    return word + (hasBatchim ? '과' : '와')
  }

  function answerProductCompare(text: string): string | null {
    // 구체적 상품명이 포함된 비교 요청은 서버(ProductCompareAgent)로 위임
    const hasSpecificProduct = /AXful|내맘대로|수퍼정기|달러자|맑은하늘|장병내일|청년도약|특★한|쏙머니|당선통장|생계비|GS Pay|모임금고|스타통장|지갑통장|자유입출금|주택청약|청년 주택드림/.test(text)
    if (hasSpecificProduct) return null

    const normalized = text.replace(/\s+/g, '').toLowerCase()
    const hasCompareIntent = ['비교', '차이', '뭐가더', '어느쪽', 'compare', 'difference'].some(keyword => normalized.includes(keyword))
    const hasMeaningIntent = ['뜻', '의미', '뭐야', '뭔가요', '뭔지', '설명', '개념'].some(keyword => normalized.includes(keyword))
    const hasFitIntent = ['맞아', '적합', '나한테', '나에게', '내게', '저한테', 'forme'].some(keyword => normalized.includes(keyword))
    const hasProductContext = ['상품', '예금', '적금', '청약', 'product'].some(keyword => normalized.includes(keyword))
    const isCompare = hasCompareIntent || hasMeaningIntent || (hasFitIntent && hasProductContext)
    if (!isCompare) return null

    const wantsDeposit = normalized.includes('예금')
    const wantsSavings = normalized.includes('적금')
    const wantsSubscription = normalized.includes('청약')
    const wantsPersonal = ['나한테', '나에게', '내게', '저한테', '맞아', '적합', 'forme'].some(keyword => normalized.includes(keyword))
    const recommendProducts = lastRecommendProductsRef.current

    if (wantsPersonal) return null

    if (hasCompareIntent && recommendProducts.length > 0 && !wantsDeposit && !wantsSavings && !wantsSubscription) {
      const lines = ['직전에 추천한 상품들을 기준으로 비교해드릴게요.', '']
      recommendProducts.slice(0, 5).forEach((product, index) => {
        const name = String(product.product_name ?? product.deposit_product_name ?? '상품명 없음')
        const type = String(product.product_type ?? '-')
        const rate = product.base_interest_rate != null ? `${product.base_interest_rate}%` : '-'
        const period = product.min_period_month != null
          ? `${product.min_period_month}${product.max_period_month != null && product.max_period_month !== product.min_period_month ? `~${product.max_period_month}` : ''}개월`
          : '-'
        const amount = product.min_join_amount != null || product.max_join_amount != null
          ? `${product.min_join_amount != null ? `${Number(product.min_join_amount).toLocaleString()}원~` : ''}${product.max_join_amount != null ? `${Number(product.max_join_amount).toLocaleString()}원` : ''}`
          : '-'
        const prefRate = product.pref_rate != null ? `+${product.pref_rate}%` : ''
        const prefCondition = String(product.pref_condition ?? '').trim()
        const reason = String(product.reason ?? '')

        lines.push(`${index + 1}. ${name}`)
        lines.push(`   - 유형: ${type}`)
        lines.push(`   - 금리: ${rate}`)
        if (prefRate || prefCondition) {
          lines.push(`   - 우대금리: ${prefRate || '-'}${prefCondition ? ` (${prefCondition})` : ''}`)
        }
        lines.push(`   - 기간: ${period}`)
        lines.push(`   - 가입금액: ${amount}`)
        if (reason) lines.push(`   - 추천 근거: ${reason}`)
      })
      lines.push('')
      lines.push('정리하면 금리를 우선 보면 금리가 높은 상품, 납입 부담을 우선 보면 가입금액과 기간이 더 유연한 상품을 먼저 보면 됩니다.')
      return lines.join('\n')
    }

    // 구체적인 상품명이 포함된 경우(ex: "AXful 정기예금이랑 내맘대로적금 비교")는 서버로 위임
    const hasSpecificProductName = /AXful|내맘대로|수퍼정기|달러자|맑은하늘|장병내일|청년도약|특★한|쏙머니|당선통장|생계비|GS Pay|모임금고|스타통장|지갑통장|자유입출금|주택청약|청년 주택드림/.test(text)
    if (wantsDeposit && wantsSavings && hasCompareIntent && !wantsPersonal && !hasSpecificProductName) {
      return [
        '예금과 적금의 차이를 안내해 드릴게요.',
        '',
        '예금',
        '- 목돈을 한 번에 맡기고 만기에 원금과 이자를 받는 상품입니다.',
        '- 이미 모아둔 돈을 일정 기간 안정적으로 운용할 때 적합합니다.',
        '- 납입 방식: 가입 시 일시 납입',
        '',
        '적금',
        '- 매달 또는 정해진 주기로 돈을 넣어 목돈을 만드는 상품입니다.',
        '- 아직 목돈은 없지만 꾸준히 저축하고 싶을 때 적합합니다.',
        '- 납입 방식: 정기 납입 또는 자유 납입',
        '',
        '간단히 정리하면, 목돈이 있으면 예금, 매달 모으고 싶으면 적금이 더 잘 맞습니다.',
      ].join('\n')
    }

    if (wantsDeposit && wantsSavings && hasMeaningIntent && !wantsPersonal) {
      return [
        '예금과 적금의 뜻을 쉽게 설명해 드릴게요.',
        '',
        '예금',
        '- 이미 가진 목돈을 은행에 맡겨두고 이자를 받는 상품입니다.',
        '- 대표적으로 정기예금은 한 번에 돈을 넣고 만기까지 유지합니다.',
        '',
        '적금',
        '- 돈을 한 번에 맡기는 게 아니라, 정해진 기간 동안 나눠서 저축하는 상품입니다.',
        '- 매달 일정 금액을 넣는 정기적금과 자유롭게 넣는 자유적금이 있습니다.',
        '',
        '한 줄로 말하면, 예금은 맡기는 상품이고 적금은 모으는 상품입니다.',
      ].join('\n')
    }

    if (hasCompareIntent || hasMeaningIntent) {
      if (wantsDeposit || wantsSavings || wantsSubscription || hasProductContext) {
        return [
          '비교할 대상을 조금 더 구체적으로 알려주세요.',
          '',
          '예시',
          '- 예금 적금 차이',
          '- 예금 적금 중 나한테 맞는 거',
          '- 정기예금이랑 자유적금 비교',
        ].join('\n')
      }

      return [
        '무엇을 비교할지 알려주세요.',
        '',
        '예금과 적금의 차이를 알고 싶다면 "예금 적금 차이"라고 물어보면 되고,',
        '고객님께 더 맞는 상품을 알고 싶다면 "예금 적금 중 나한테 맞는 거"라고 물어보면 현금흐름 기준으로 분석해드릴게요.',
      ].join('\n')
    }

    return null
  }

  function isMaturityQuery(text: string): boolean {
    const normalized = text.replace(/\s+/g, '').toLowerCase()
    const maturityKeywords = [
      '만기',
      '재투자',
      '재예치',
      '재가입',
      '끝나는',
      '끝나가는',
      '끝남',
      '만료',
      '종료예정',
      '곧끝',
      '곧만료',
      '곧종료',
    ]
    return maturityKeywords.some(keyword => normalized.includes(keyword))
  }

  const BEST_KEYWORDS = ['제일 좋', '가장 좋', '최고', '1위', '1순위', '뭐가 좋', '어떤 게 좋', '어떤게 좋', '뭘 선택', '어떤 상품', '뭐 추천', '제일이', '제일을', '제일은', '어떤 걸', '골라줘', '골라 줘', '선택해줘', '선택해 줘', '추천해줘', '추천해 줘']
  const FOLLOWUP_KEYWORDS = ['어떤 면', '왜', '이유', '설명', '어떻게', '근거', '어떤 이유', '좋은 이유', '추천 이유', '더 알려', '구체적', '장점', '단점', '특징', '뭐가 좋', '왜 좋', '어떤 점', '말해봐', '말해 봐', '알려줘', '알려 줘', '뭔데', '어때', '괜찮', '어떤거야', '어떤 거야', '좋아', '좋은가', '괜찮아', '어떻게 돼', '금리가', '기간이', '조건이', '가입하면', '이거 왜', '이게 왜', '왜 이걸', '이게 좋']
  function tryAnswerFromRecommend(text: string): string | null {
    const products = lastRecommendProductsRef.current
    if (!products.length) return null

    // 전체 상품 나열 요청
    const LIST_KEYWORDS = ['나열', '순서대로', '순으로', '다 보여', '전부', '모두 알려', '목록', '리스트', '다시 보여', '전체', '전부 알려', '다 알려', '몇 개야', '몇개야', '몇 가지', '몇가지', '장점순', '유리한 순', '좋은 순', '추천 순', '랭킹', '순위대로', '순위별', '순위별로', '1위부터', '순서별', '상품 순위', '추천 순위', '순위', '순위?', '순위요', '순위야', '순위는', '순위로', '순위 알려', '순위 말해', '순위 보여']
    if (LIST_KEYWORDS.some(kw => text.includes(kw))) {
      const lines = ['현금흐름 분석 기반 추천 순위입니다.\n']
      products.forEach((p, i) => {
        const name = String(p.product_name ?? '')
        const rate = p.base_interest_rate != null ? `기본금리 ${p.base_interest_rate}%` : ''
        const reason = String(p.reason ?? '')
        const score = p.match_score != null ? ` (적합도 ${p.match_score}점)` : ''
        lines.push(`${i + 1}위. ${name}${rate ? ` · ${rate}` : ''}${score}`)
        if (reason) lines.push(`   → ${reason}`)
      })
      return lines.join('\n')
    }

    // 각 상품별 장점/이유 요청 ("각 장점", "각각 장점", "각 상품 장점", "상품별 장점" 등)
    const EACH_KEYWORDS = ['각 ', '각각', '상품별', '각 상품', '하나씩', '순서대로 장점', '장점 말해', '장점을 말', '장점 알려', '이유 말해', '이유 알려', '이유를 말']
    if (EACH_KEYWORDS.some(kw => text.includes(kw)) && FOLLOWUP_KEYWORDS.some(kw => text.includes(kw))) {
      const lines = ['추천 상품별 장점을 안내해 드립니다.\n']
      products.forEach((p, i) => {
        const name = String(p.product_name ?? '')
        const rate = p.base_interest_rate != null ? `기본금리 ${p.base_interest_rate}%` : ''
        const reason = String(p.reason ?? '')
        lines.push(`${i + 1}위. ${name}${rate ? ` · ${rate}` : ''}`)
        if (reason) lines.push(`   → ${reason}`)
      })
      return lines.join('\n')
    }

    // "이유", "왜" 등 단독으로 쓰면 → 추천 상품 전체 이유 안내
    const REASON_ONLY_KEYWORDS = ['이유', '왜', '근거', '장점', '특징', '추천 이유', '어떤 이유', '이유가']
    if (REASON_ONLY_KEYWORDS.some(kw => text.trim() === kw || text.trim() === `${kw}?` || text.trim() === `${kw}야` || text.trim() === `${kw}요`)) {
      const lines = ['추천 상품별 이유를 안내해 드립니다.\n']
      products.forEach((p, i) => {
        const name = String(p.product_name ?? '')
        const rate = p.base_interest_rate != null ? `기본금리 ${p.base_interest_rate}%` : ''
        const reason = String(p.reason ?? '')
        lines.push(`${i + 1}위. ${name}${rate ? ` · ${rate}` : ''}`)
        if (reason) lines.push(`   → ${reason}`)
        else lines.push(`   → 현금흐름 분석 기반 추천 상품입니다.`)
      })
      return lines.join('\n')
    }

    // "이유 알려줘", "왜 추천했어" 등 문장 형태로 이유 질문
    const REASON_PHRASE_KEYWORDS = ['장점', '특징', '이유 알려', '이유를 알', '이유를 말', '왜 추천', '추천한 이유', '추천 이유', '왜 좋', '이유가 뭐', '이유가 있']
    if (REASON_PHRASE_KEYWORDS.some(kw => text.includes(kw))) {
      const lines = ['추천 상품별 이유를 안내해 드립니다.\n']
      products.forEach((p, i) => {
        const name = String(p.product_name ?? '')
        const rate = p.base_interest_rate != null ? `기본금리 ${p.base_interest_rate}%` : ''
        const reason = String(p.reason ?? '')
        lines.push(`${i + 1}위. ${name}${rate ? ` · ${rate}` : ''}`)
        if (reason) lines.push(`   → ${reason}`)
        else lines.push(`   → 현금흐름 분석 기반 추천 상품입니다.`)
      })
      return lines.join('\n')
    }

    // 직전 추천 1위 상품에 대한 후속 질문
    if (lastTopProductRef.current && FOLLOWUP_KEYWORDS.some(kw => text.includes(kw))) {
      const top = lastTopProductRef.current
      const name = String(top.product_name ?? '')
      const rate = top.base_interest_rate != null ? `${top.base_interest_rate}%` : ''
      const reason = String(top.reason ?? '')
      const minM = top.min_period_month != null ? `${top.min_period_month}개월` : ''
      const maxM = top.max_period_month != null ? `${top.max_period_month}개월` : ''
      const period = minM && maxM && minM !== maxM ? `${minM}~${maxM}` : minM
      const lines = [`"${name}"을 추천한 이유입니다.`]
      if (reason) lines.push(`• ${reason}`)
      if (rate) lines.push(`• 기본금리 ${rate}`)
      if (period) lines.push(`• 가입 가능 기간: ${period}`)
      return lines.join('\n')
    }

    // 추천 목록 중 특정 순위 상품 질문 ("1위 장점", "2위 상품 어때" 등)
    const rankMatch = text.match(/([1-5])위/)
    if (rankMatch) {
      const rank = parseInt(rankMatch[1], 10)
      const target = products[rank - 1]
      if (target && FOLLOWUP_KEYWORDS.some(kw => text.includes(kw))) {
        const name = String(target.product_name ?? '')
        const rate = target.base_interest_rate != null ? `${target.base_interest_rate}%` : ''
        const reason = String(target.reason ?? '')
        const lines = [`${rank}위 "${name}"의 특징입니다.`]
        if (reason) lines.push(`• ${reason}`)
        if (rate) lines.push(`• 기본금리 ${rate}`)
        return lines.join('\n')
      }
    }

    if (!BEST_KEYWORDS.some(kw => text.includes(kw))) return null
    const top = products[0]
    lastTopProductRef.current = top
    const name = String(top.product_name ?? '')
    const rate = top.base_interest_rate != null ? `${top.base_interest_rate}%` : ''
    const reason = String(top.reason ?? '')
    const parts = [`추천 상품 중 가장 좋은 것은 "${name}"입니다.`]
    if (rate) parts.push(`기본금리 ${rate}`)
    if (reason) parts.push(`(${reason})`)
    return parts.join(' ')
  }

  // 상품 목록/금리 조회 키워드 패턴
  const PRODUCT_GUIDE_PATTERNS = [
    { keywords: ['청약'], query: '청약 상품 알려줘' },
    { keywords: ['적금'], query: '적금 상품 알려줘' },
    { keywords: ['예금'], query: '예금 상품 알려줘' },
  ]
  // 현금흐름 기반 추천 키워드 (카드 데이터 포함 결과 필요)
  const CASH_FLOW_RECOMMEND_KEYWORDS = [
    '순위', '순위별', '순위대로', '1위부터', '랭킹', '추천 순위',
    '내 현금흐름', '현금흐름 분석', '거래내역 보고', '거래 내역 보고',
    '나한테 맞는 상품', '나에게 맞는 상품', '내 패턴', '내 거래 패턴',
  ]
  const SPENDING_PATTERN_KEYWORDS = [
    '배달', '배달앱', '배민', '요기요', '쿠팡이츠',
    '카페', '스타벅스', '편의점', '쇼핑', '무신사',
    '소비', '지출', '과소비', '많이 써', '많이 썼', '계속 써',
    '썼는데', '썼어', '쓴 것', '쓴거', '결제했어', '샀어',
  ]

  async function handleScenarioMessage(text: string, buttonValue?: string) {
    // 예금/적금/청약 목록 조회 → handleFeature로 라우팅 (상품 카드 표시)
    const trimmed = text.trim()
    const compactText = trimmed.replace(/\s+/g, '')

    if (SPENDING_PATTERN_KEYWORDS.some(kw => trimmed.includes(kw))) {
      await handleFeature('SPENDING_PATTERN', trimmed, true)
      return
    }

    if (isMaturityQuery(trimmed)) {
      await handleFeature('MATURITY_SCHEDULE', trimmed, true)
      return
    }

    if (['내상품', '내가입상품', '가입상품', '내계좌'].some((word) => compactText.includes(word))) {
      if (!isLoggedIn) {
        pendingLoginActionRef.current = 'my_products'
        setMessages([{ id: messageId('auth'), role: 'bot', text: '로그인 후 이용하실 수 있는 서비스입니다.', loginForm: true }])
        return
      }
      await handleFeature('MY_PRODUCTS', trimmed, true)
      return
    }

    const hasKoreanProduct = ['예금', '적금', '청약', '상품'].some((word) => trimmed.includes(word))
    const hasKoreanRecommend = ['추천', '맞는', '나한테', '나에게', '적절', '적합', '뭐가 더', '뭐가더', '뭐가 나', '어느', '순위', '랭킹'].some((word) => trimmed.includes(word))
    const hasKoreanGuide = ['알려', '목록', '종류', '뭐가', '보여', '소개'].some((word) => trimmed.includes(word))
    const isDepositSavingsFitQuestion =
      trimmed.includes('예금') &&
      trimmed.includes('적금') &&
      ['나한테', '나에게', '내게', '저한테', '맞는', '적절', '적합', '뭐가 더', '뭐가더', '뭐가 나', '어느'].some((word) => trimmed.includes(word))

    if (isDepositSavingsFitQuestion) {
      if (!isLoggedIn) {
        pendingLoginActionRef.current = 'recommend'
        setMessages([{ id: messageId('auth'), role: 'bot', text: '로그인 후 이용하실 수 있는 서비스입니다.', loginForm: true }])
        return
      }
      setLoading(true)
      setExpandedRow(null)
      setDataPages({})
      setMessages([{ id: messageId('user'), role: 'user', text }])
      try {
        const cid = customerNo.trim() || getCurrentDepositCustomerId()
        const answer = await answerCashflowRecommend(cid, trimmed)
        setMessages((current) => [...current, { id: messageId('bot'), role: 'bot', text: answer }])
      } catch {
        setMessages((current) => [...current, {
          id: messageId('error'),
          role: 'system',
          text: '요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.',
        }])
      } finally {
        setLoading(false)
      }
      return
    }

    // 구체적 상품명 포함 비교 요청 → executeChatbotFeature 직접 호출
    const hasSpecificProductInCompare = /AXful|내맘대로|수퍼정기|달러자|맑은하늘|장병내일|청년도약|특★한|쏙머니|당선통장|생계비|GS Pay|모임금고|스타통장|지갑통장|자유입출금|주택청약|청년 주택드림/.test(trimmed)
    const hasCompareWord = ['비교', '차이', '어느 쪽', '어느쪽', '뭐가 더'].some(w => trimmed.includes(w))
    if (hasSpecificProductInCompare && hasCompareWord) {
      setLoading(true)
      setExpandedRow(null)
      setDataPages({})
      setMessages([{ id: messageId('user'), role: 'user', text }])
      try {
        const consultationId = await ensureStarted(true)
        const result = await executeChatbotFeature('PRODUCT_COMPARE', {
          customer_no: customerNo.trim() || getCurrentDepositCustomerId(),
          query: trimmed,
          chatbot_consultation_id: consultationId ?? undefined,
        })
        // 분석 텍스트 저장 (후속 질문 응답용), 첫 응답은 짧게 표시
        let shortIntro = '두 상품을 비교했습니다. 아래 표와 AI 분석을 확인해주세요.'
        if (result.data && result.data.length > 0) {
          const row = result.data.find(r => r.row_type === 'compare_product')
          if (row) {
            lastCompareAnalysisRef.current = String(row.analysis ?? '')
            const pa = (row.product_a as Record<string, unknown>)
            const pb = (row.product_b as Record<string, unknown>)
            const nameA = String(pa.product_name ?? '')
            const nameB = String(pb.product_name ?? '')
            lastCompareNamesRef.current = [nameA, nameB]
            shortIntro = `${josa(nameA, '과와')} ${josa(nameB, '을를')} 비교했습니다.`
          }
        }
        setMessages(prev => [...prev, addFeatureResult({ ...result, message: shortIntro })])
      } catch {
        setMessages(prev => [...prev, { id: messageId('error'), role: 'system', text: '요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.' }])
      } finally {
        setLoading(false)
      }
      return
    }

    const isMatureQuery = isMaturityQuery(trimmed)
    const compareAnswer = isMatureQuery ? null : answerProductCompare(trimmed)
    if (compareAnswer) {
      setExpandedRow(null)
      setDataPages({})
      setMessages([
        { id: messageId('user'), role: 'user', text },
        { id: messageId('bot'), role: 'bot', text: compareAnswer },
      ])
      return
    }

    // 비교 후속 질문 처리 ("나한테 적절한 추천이야?" 등)
    const isCompareFollowup = lastCompareAnalysisRef.current &&
      ['나한테', '나에게', '나한', '적절', '적합', '맞아', '맞나', '맞는', '추천이야', '추천인가', '맞게'].some(w => trimmed.includes(w))
    if (isCompareFollowup) {
      const fullAnalysis = lastCompareAnalysisRef.current!
      const names = lastCompareNamesRef.current
      const productMatch = fullAnalysis.match(/내\s*상황엔\s*([^\s이가]+(?:\s+[^\s이가]+)*?)\s*[이가]\s*유리/)
      const recommended = productMatch ? productMatch[1].trim() : null
      const other = names ? names.find(n => n !== recommended) ?? null : null
      const replyText = recommended && other
        ? `${josa(recommended, '과와')} ${josa(other, '을를')} 비교해봤을 때,\n금리·조건 면에서 ${josa(recommended, '이가')} 고객님께 조금 더 유리할 수 있어요 😊\n단, ${other}도 중도해지나 자동갱신 조건에 따라 맞는 경우가 있으니 위 표를 함께 참고해 주세요!`
        : '위 두 상품 모두 좋은 선택이에요! 비교표와 AI 분석을 참고하셔서 고객님 상황에 맞게 선택해 보세요 😊'
      setMessages(prev => [...prev, {
        id: messageId('bot'),
        role: 'bot',
        text: replyText,
      }])
      return
    }

    const followupAnswer = tryAnswerFromRecommend(text)
    if (followupAnswer) {
      setExpandedRow(null)
      setDataPages({})
      setMessages([
        { id: messageId('user'), role: 'user', text },
        { id: messageId('bot'), role: 'bot', text: followupAnswer },
      ])
      return
    }

    // X%이상 상품 보여줘 패턴 처리 (예: "3%이상 상품", "금리 2% 이상 상품들")
    const rateFilterMatch = trimmed.match(/([0-9]+(?:\.[0-9]+)?)\s*%\s*이상/)
    if (rateFilterMatch && ['상품', '보여줘', '보여주', '보여달', '추천', '알려'].some(w => trimmed.includes(w))) {
      const minRate = Number(rateFilterMatch[1])
      setLoading(true)
      setExpandedRow(null)
      setDataPages({})
      pushMessages([{ id: messageId('user'), role: 'user', text }])
      try {
        const allProducts = await fetchDepositProducts()
        const filtered = allProducts.filter(p => {
          if (p.productStatus && p.productStatus !== 'SELLING') return false
          if (p.productType === 'SUBSCRIPTION') return false
          const rate = Number(p.bestRate ?? p.baseInterestRate ?? 0)
          return rate >= minRate
        }).sort((a, b) => Number(b.bestRate ?? b.baseInterestRate ?? 0) - Number(a.bestRate ?? a.baseInterestRate ?? 0))
        const rows = await enrichWithPreferentialRates(filtered.map((p, i) => productToRow(p, i)))
        const result = buildFeatureResult('PRODUCT_GUIDE', `금리 ${minRate}% 이상 상품 ${rows.length}개입니다.`, rows)
        pushMessages([addFeatureResult(result as unknown as Parameters<typeof addFeatureResult>[0])])
      } catch {
        pushMessages([{ id: messageId('error'), role: 'system', text: '상품 정보를 불러오는 중 오류가 발생했습니다.' }])
      } finally {
        setLoading(false)
      }
      return
    }

    // 우대금리 종류 / 우대금리 상품 질문
    const hasPrefKeyword = ['우대금리', '우대 금리'].some(w => trimmed.includes(w))
    const isHaedangSangpum = ['해당하는 상품', '해당 상품'].some(w => trimmed.includes(w))
    if (hasPrefKeyword || isHaedangSangpum) {
      const wantProductList = hasPrefKeyword
        ? ['상품', '리스트', '보여줘', '보여주', '해당'].some(w => trimmed.includes(w))
        : true
      setLoading(true)
      setExpandedRow(null)
      setDataPages({})
      pushMessages([{ id: messageId('user'), role: 'user', text }])
      try {
        const allProducts = await fetchDepositProducts()
        const selling = allProducts.filter(p => (!p.productStatus || p.productStatus === 'SELLING') && p.productType !== 'SUBSCRIPTION')
        const rows = selling.map((p, i) => productToRow(p, i))
        const enriched = await enrichWithPreferentialRates(rows)
        const withPref = enriched.filter(r => r.pref_condition != null && String(r.pref_condition).trim() !== '')

        if (wantProductList) {
          const result = buildFeatureResult('PRODUCT_GUIDE', `우대금리 조건이 있는 상품 ${withPref.length}개입니다.`, withPref)
          pushMessages([addFeatureResult(result as unknown as Parameters<typeof addFeatureResult>[0])])
        } else {
          const conditionSet = new Set<string>()
          withPref.forEach(r => String(r.pref_condition ?? '').split(' / ').forEach(c => { if (c.trim()) conditionSet.add(c.trim()) }))
          const condList = Array.from(conditionSet).slice(0, 10)
          const productLines = withPref.slice(0, 8).map(r => `• ${String(r.product_name ?? '')}: ${String(r.pref_condition ?? '')}${r.pref_rate ? ` (+${r.pref_rate}%)` : ''}`)
          const reply = `우대금리 조건 종류 (${conditionSet.size}가지):\n${condList.map(c => `• ${c}`).join('\n')}${conditionSet.size > 10 ? '\n...' : ''}\n\n상품별 우대금리:\n${productLines.join('\n')}${withPref.length > 8 ? `\n...외 ${withPref.length - 8}개` : ''}\n\n"우대금리 상품 보여줘"라고 하시면 상품 목록을 보여드립니다.`
          pushMessages([{ id: messageId('bot'), role: 'bot', text: reply }])
        }
      } catch {
        pushMessages([{ id: messageId('error'), role: 'system', text: '우대금리 정보를 불러오는 중 오류가 발생했습니다.' }])
      } finally {
        setLoading(false)
      }
      return
    }

    if (hasKoreanProduct && hasKoreanRecommend) {
      if (!isLoggedIn) {
        pendingLoginActionRef.current = 'recommend'
        setMessages([{ id: messageId('auth'), role: 'bot', text: '로그인 후 이용하실 수 있는 서비스입니다.', loginForm: true }])
        return
      }
      await handleFeature('CASH_FLOW_RECOMMEND', trimmed, false)
      return
    }

    if (hasKoreanProduct && hasKoreanGuide && !isMatureQuery) {
      await handleFeature('PRODUCT_GUIDE', trimmed, false)
      return
    }
    // 단순 상품 목록 조회만 라우팅 (다른 의도가 섞인 긴 문장은 제외)
    const hasOtherIntent = ['비교', '차이', '맞는', '적합', '추천', '나한테', '나에게', '뭐가 더', '어느'].some(w => trimmed.includes(w))
    const productGuideMatch = !hasOtherIntent && PRODUCT_GUIDE_PATTERNS.find(p =>
      p.keywords.some(kw =>
        trimmed === kw ||
        trimmed === kw + ' 종류' ||
        trimmed === kw + ' 목록' ||
        trimmed === kw + ' 상품' ||
        trimmed === kw + ' 알려줘' ||
        trimmed === kw + ' 상품 알려줘' ||
        trimmed === kw + ' 뭐가 있어' ||
        trimmed === kw + ' 소개해줘'
      )
    )
    if (productGuideMatch) {
      await handleFeature('PRODUCT_GUIDE', productGuideMatch.query, false)
      return
    }

    // 현금흐름 추천/순위 → 이미 추천된 상품 없을 때만 executeChatbotFeature로 라우팅
    if (CASH_FLOW_RECOMMEND_KEYWORDS.some(kw => trimmed.includes(kw)) && lastRecommendProductsRef.current.length === 0) {
      if (!isLoggedIn) {
        pendingLoginActionRef.current = 'recommend'
        setMessages([{ id: messageId('auth'), role: 'bot', text: '로그인 후 이용하실 수 있는 서비스입니다.', loginForm: true }])
        return
      }
      await handleFeature('CASH_FLOW_RECOMMEND', trimmed, false)
      return
    }

    setLoading(true)
    // 새 질문으로 넘어가면 이전 메시지 삭제, 현재 Q&A만 표시
    setMessages([{ id: messageId('user'), role: 'user', text }])
    setExpandedRow(null)
    setDataPages({})

    try {
      const consultationId = await ensureStarted(true)
      const messageWithCtx = lastRecommendCtx
        ? `${text}\n[직전 추천 상품: ${lastRecommendCtx}]`
        : text
      const reply = await sendChatbotMessage(consultationId, {
        message: messageWithCtx,
        button_value: buttonValue ?? null,
      })
      setMessages(prev => [...prev, {
        id: messageId('bot'),
        role: 'bot',
        text: reply.message,
        buttons: reply.buttons,
        featureCode: reply.feature_code ?? undefined,
        data: reply.feature_data?.length ? reply.feature_data : undefined,
      }])
    } catch {
      setMessages(prev => [...prev, { id: messageId('error'), role: 'system', text: '챗봇 서버 연결에 실패했습니다. 상담 서비스를 확인해주세요.' }])
    } finally {
      setLoading(false)
    }
  }

  async function handleFeature(featureCode: 'MY_ACCOUNTS' | 'MY_PRODUCTS' | 'MY_CASH_FLOW' | 'CASH_FLOW_RECOMMEND' | 'PRODUCT_GUIDE' | 'MATURITY_SCHEDULE' | 'SPENDING_PATTERN', userText: string, replaceMessages = false) {
    setLoading(true)
    if (replaceMessages) {
      setExpandedRow(null)
      setDataPages({})
      setMessages([{ id: messageId('user'), role: 'user', text: userText }])
    } else {
      pushMessages([{ id: messageId('user'), role: 'user', text: userText }])
    }
    try {
      if (featureCode === 'PRODUCT_GUIDE') {
        const result = await executeDepositProductGuide(userText)
        if (replaceMessages) {
          setMessages((current) => [...current, addFeatureResult(result)])
        } else {
          pushMessages([addFeatureResult(result)])
        }
        return
      }

      if (featureCode === 'CASH_FLOW_RECOMMEND') {
        const cid = customerNo.trim() || getCurrentDepositCustomerId()
        const answer = await answerCashflowRecommend(cid, userText)
        const result = buildFeatureResult('CASH_FLOW_RECOMMEND', answer, [])
        if (replaceMessages) {
          setMessages((current) => [...current, addFeatureResult(result)])
        } else {
          pushMessages([addFeatureResult(result)])
        }
        return
      }


      if (featureCode === 'MY_PRODUCTS') {
        const cid = customerNo.trim() || getCurrentDepositCustomerId()
        const apiAccounts = await fetchDepositAccountViewModels(cid)
        const rows = apiAccounts.map((a) => ({
          account_id: a.apiAccountId ?? 0,
          contract_id: a.contractId ?? null,
          account_number: a.number,
          product_name: a.name,
          product_type: a.type,
          saving_type: a.savingType ?? null,
          balance: a.balance,
          account_status: a.accountStatus ?? 'ACTIVE',
          maturity_at: a.maturityDate ?? null,
          started_at: a.createdAt ?? null,
          is_withdrawable: a.isWithdrawable ?? false,
        }))
        const result = buildFeatureResult(
          'MY_PRODUCTS',
          rows.length > 0 ? '가입 상품 조회를 완료했습니다.' : '조회된 가입 상품이 없습니다.',
          rows,
        )
        if (replaceMessages) {
          setMessages((current) => [...current, addFeatureResult(result)])
        } else {
          pushMessages([addFeatureResult(result)])
        }
        return
      }

      const consultationId = hasStarted ? chatbotConsultationId : undefined
      const result = await executeChatbotFeature(featureCode, {
        customer_no: customerNo.trim() || getCurrentDepositCustomerId(),
        query: userText,
        product_type: (featureCode as string) === 'PRODUCT_GUIDE' ? inferProductType(userText) : undefined,
        chatbot_consultation_id: consultationId ?? undefined,
      })
      const displayResult = featureCode === 'SPENDING_PATTERN'
        ? { ...result, data: [] }
        : result
      if (replaceMessages) {
        setMessages((current) => [...current, addFeatureResult(displayResult)])
      } else {
        pushMessages([addFeatureResult(displayResult)])
      }
    } catch {
      const errorMessage: ChatMessage = {
        id: messageId('error'),
        role: 'system',
        text: '\uC694\uCCAD \uCC98\uB9AC \uC911 \uC624\uB958\uAC00 \uBC1C\uC0DD\uD588\uC2B5\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD574\uC8FC\uC138\uC694.',
      }
      if (replaceMessages) {
        setMessages((current) => [...current, errorMessage])
      } else {
        pushMessages([errorMessage])
      }
    } finally {
      setLoading(false)
    }
  }

  async function handleFileSelected(file: File) {
    if (!file) return
    const action = pendingFileAction
    setPendingFileAction(null)

    if (action === 'ENROLLMENT') {
      pushMessages([{ id: messageId('user'), role: 'user', text: `📎 ${file.name} 제출` }])
      setLoading(true)
      try {
        const result = await uploadDocument(file, customerNo || 'GUEST', 'ENROLLMENT')
        pushMessages([{ id: messageId('bot'), role: 'bot', text: result.message }])
      } catch {
        pushMessages([{ id: messageId('bot'), role: 'bot', text: '서류 제출 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.' }])
      } finally {
        setLoading(false)
      }
      return
    }

    // PDF text extraction using pdfjs-dist
    pushMessages([{ id: messageId('user'), role: 'user', text: `📎 ${file.name} 분석 중...` }])
    setLoading(true)
    try {
      const pdfjsLib = await import('pdfjs-dist')
      pdfjsLib.GlobalWorkerOptions.workerSrc = '/pdf.worker.min.mjs'
      const arrayBuffer = await file.arrayBuffer()
      const pdf = await pdfjsLib.getDocument({ data: arrayBuffer }).promise
      const texts: string[] = []
      for (let i = 1; i <= Math.min(pdf.numPages, 20); i++) {
        const page = await pdf.getPage(i)
        const content = await page.getTextContent()
        texts.push(content.items.map((item) => ('str' in item ? (item.str ?? '') : '')).join(' '))
      }
      const fullText = texts.join('\n')
      if (!fullText.trim()) {
        pushMessages([{ id: messageId('bot'), role: 'bot', text: 'PDF에서 텍스트를 추출할 수 없습니다. 스캔 이미지 PDF는 지원되지 않습니다.' }])
        return
      }
      const analyzeType = action as 'CASH_FLOW' | 'TERMS' | 'PRODUCT'
      const result = await analyzeFile(fullText, analyzeType, customerNo || undefined)
      pushMessages([{ id: messageId('bot'), role: 'bot', text: result.result }])
    } catch {
      pushMessages([{ id: messageId('bot'), role: 'bot', text: '파일 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.' }])
    } finally {
      setLoading(false)
    }
  }

  async function handleQuickAction(action: (typeof quickActions)[number]) {
    if (loading) return

    const AUTH_REQUIRED = new Set(['my_products', 'recommend', 'product_guide'])
    if (AUTH_REQUIRED.has(action.type) && !isLoggedIn) {
      pendingLoginActionRef.current = action.type === 'my_products' ? 'my_products' : 'recommend'
      setMessages([{ id: messageId('auth'), role: 'bot', text: '로그인 후 이용하실 수 있는 서비스입니다.', loginForm: true }])
      return
    }

    if (action.type === 'consult') {
      if (!isLoggedIn) {
        setMessages([{ id: messageId('auth'), role: 'bot', text: '상담원 연결은 로그인 후 이용하실 수 있습니다.', loginForm: true }])
        return
      }
      const rawCno = localStorage.getItem('customerNo') || customerNo.trim() || getCurrentDepositCustomerId()
      // customerId(숫자)가 들어온 경우 customerNo 형식으로 변환
      const cno = rawCno && /^\d+$/.test(rawCno)
        ? `CUST${String(rawCno).padStart(3, '0')}`
        : rawCno
      if (!cno) {
        setMessages([{ id: messageId('auth'), role: 'bot', text: '상담원 연결은 로그인 후 이용하실 수 있습니다.', loginForm: true }])
        return
      }
      setLoading(true)
      pushMessages([{ id: messageId('user'), role: 'user', text: '상담원 연결' }])
      try {
        const result = await requestAgentChat(cno)
        setCustomerChatId(result.chat_consultation_id)
        setCustomerChatStatus('WAITING')
        setCustomerChatMessages([])
        pushMessages([{
          id: messageId('bot'),
          role: 'bot',
          text: `상담원 연결 요청이 접수되었습니다.\n상담원이 연결되면 채팅 창이 열립니다. 잠시만 기다려 주세요.`,
        }])
      } catch {
        pushMessages([{ id: messageId('bot'), role: 'bot', text: '상담원 연결 요청 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.' }])
      } finally {
        setLoading(false)
      }
      return
    }
    if (action.type === 'recommend') {
      setProductSearchState({ step: 'period', period: '', amount: '', productType: null, purpose: null, minRate: '' })
      return
    }
    if (action.type === 'my_products') {
      const cid = customerNo.trim() || getCurrentDepositCustomerId()
      if (!cid) {
        pushMessages([{ id: messageId('auth'), role: 'bot', text: '로그인 후 이용하실 수 있는 서비스입니다.', loginForm: true }])
        return
      }
      setLoading(true)
      setExpandedRow(null)
      setDataPages({})
      setMessages([{ id: messageId('user'), role: 'user', text: action.message }])
      try {
        // 계좌 조회와 동일한 소스: deposit API → localStorage 순서
        let rows: Record<string, unknown>[] = []
        try {
          const apiAccounts = await fetchDepositAccountViewModels(cid)
          rows = apiAccounts.map((a) => ({
            account_id: a.apiAccountId ?? 0,
            contract_id: a.contractId ?? null,
            account_number: a.number,
            product_name: a.name,
            product_type: a.type,
            saving_type: a.savingType ?? null,
            balance: a.balance,
            account_status: a.accountStatus ?? 'ACTIVE',
            maturity_at: a.maturityDate ?? null,
            started_at: a.createdAt ?? null,
            is_withdrawable: a.type === '입출금',
          }))
        } catch {
          // deposit API 미응답 시 localStorage fallback (계좌 조회와 동일한 방식)
          const raw = typeof window !== 'undefined' ? localStorage.getItem('joinedAccounts') : null
          if (raw) {
            const stored = JSON.parse(raw) as Array<Record<string, unknown>>
            rows = stored.map((a) => ({
              account_id: a.id ?? 0,
              account_number: a.number ?? '',
              product_name: a.name ?? '',
              product_type: a.type,
              balance: a.balance ?? 0,
              account_status: 'ACTIVE',
              maturity_at: a.maturityDate ?? null,
              started_at: a.createdAt ?? null,
              is_withdrawable: a.type === '입출금',
            }))
          }
        }
        const TYPE_ORDER: Record<string, number> = {
          '입출금': 0,
          '예금': 1,
          '정기적금': 2,
          '자유적금': 3,
          '적금': 2,
          '청약': 4,
        }
        const sortedRows = [...rows].sort((a, b) => {
          const typeA = a.product_type === '적금'
            ? (a.saving_type === 'FREE' ? '자유적금' : '정기적금')
            : String(a.product_type ?? '')
          const typeB = b.product_type === '적금'
            ? (b.saving_type === 'FREE' ? '자유적금' : '정기적금')
            : String(b.product_type ?? '')
          return (TYPE_ORDER[typeA] ?? 9) - (TYPE_ORDER[typeB] ?? 9)
        })
        pushMessages([{
          id: messageId('MY_PRODUCTS'),
          role: 'bot',
          text: sortedRows.length > 0 ? '가입 상품 조회를 완료했습니다.' : '조회된 가입 상품이 없습니다.',
          data: sortedRows,
          featureCode: 'MY_PRODUCTS',
        }])
      } catch {
        pushMessages([{ id: messageId('error'), role: 'system', text: '요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.' }])
      } finally {
        setLoading(false)
      }
      return
    }
    if (action.type === 'product_guide') {
      await handleFeature('PRODUCT_GUIDE', action.query, false)
      return
    }
    await handleScenarioMessage((action as { message: string }).message)
  }

  async function startTerminate(row: Record<string, unknown>) {
    const cid = customerNo.trim() || getCurrentDepositCustomerId()
    let checkingAccounts: MyAccount[] = []
    try {
      const accs = await fetchDepositAccountViewModels(cid)
      const terminatingAccountId = Number(row.account_id ?? 0)
      checkingAccounts = accs
        .filter(a => (a.apiAccountId ?? 0) !== terminatingAccountId)
        .map(a => ({
          account_id: a.apiAccountId ?? 0,
          account_number: a.number,
          balance: a.balance,
          account_alias: a.name,
        }))
    } catch {}
    setTerminateState({
      step: 'method',
      accountId: Number(row.account_id ?? 0),
      accountNumber: String(row.account_number ?? ''),
      productName: String(row.product_name ?? ''),
      balance: Number(row.balance ?? 0),
      contractId: row.contract_id ? Number(row.contract_id) : null,
      method: null,
      targetAccountId: '',
      otherBank: '',
      otherAccount: '',
      checkingAccounts,
      certPin: '',
    })
  }

  async function refreshMyProducts() {
    const cid = customerNo.trim() || getCurrentDepositCustomerId()
    if (!cid) return
    try {
      const apiAccounts = await fetchDepositAccountViewModels(cid)
      const rows = apiAccounts.map((a) => ({
        account_id: a.apiAccountId ?? 0,
        contract_id: a.contractId ?? null,
        account_number: a.number,
        product_name: a.name,
        product_type: a.type,
        saving_type: a.savingType ?? null,
        balance: a.balance,
        account_status: a.accountStatus ?? 'ACTIVE',
        maturity_at: a.maturityDate ?? null,
        started_at: a.createdAt ?? null,
        is_withdrawable: a.type === '입출금',
      }))
      setExpandedRow(null)
      setDataPages({})
      setMessages([
        { id: messageId('user'), role: 'user', text: '내 상품 보여줘' },
        { id: messageId('MY_PRODUCTS'), role: 'bot', text: '가입 상품 조회를 완료했습니다.', data: [...rows], featureCode: 'MY_PRODUCTS' },
      ])
      setDataPages({})
    } catch (e) { console.error('refreshMyProducts error:', e) }
  }

  async function executeTerminate() {
    if (!terminateState) return
    try {
      if (terminateState.contractId) await terminateDepositContract(
        terminateState.contractId,
        'ONLINE_TERMINATION',
        terminateState.targetAccountId ? Number(terminateState.targetAccountId) : undefined
      )
      setTerminateState(s => s && { ...s, step: 'done' })
    } catch {
      setTerminateState(s => s && { ...s, step: 'error' })
      return
    }
    // 해지 후 내 상품 목록 자동 갱신
    await refreshMyProducts()
  }

  async function startTransfer(accountId: number, accountNumber: string, balance: number) {
    if (!isLoggedIn) {
      pendingLoginActionRef.current = 'transfer'
      pushMessages([{
        id: messageId('auth'),
        role: 'bot',
        text: '이체 서비스는 로그인 후 이용하실 수 있습니다.',
        loginForm: true,
      }])
      return
    }
    setTransferState({
      step: 'form',
      fromAccountId: accountId,
      fromAccountNumber: accountNumber,
      fromBalance: balance,
      toAccountNumber: '',
      toBank: '',
      toTab: 'my_accounts',
      myAccounts: [],
      amount: '',
      memo: '이체',
      resultMessage: '',
      balanceAfter: null,
      verifySubStep: 'cert-info',
      certPin: '',
    })
    try {
      let allAccounts: MyAccount[] = []
      try {
        const apiAccounts = await fetchDepositAccountViewModels(getCurrentDepositCustomerId())
        allAccounts = apiAccounts.map((a) => ({
          account_id: a.apiAccountId ?? 0,
          account_number: a.number,
          balance: a.balance,
          account_alias: a.name ?? null,
        }))
      } catch {
        const raw = typeof window !== 'undefined' ? localStorage.getItem('joinedAccounts') : null
        if (raw) {
          const stored = JSON.parse(raw) as Array<Record<string, unknown>>
          allAccounts = stored.map((a) => ({
            account_id: Number(a.id ?? 0),
            account_number: String(a.number ?? ''),
            balance: Number(a.balance ?? 0),
            account_alias: a.name ? String(a.name) : null,
          }))
        }
      }
      const accounts: MyAccount[] = allAccounts.filter((a) => a.account_id !== accountId)
      setTransferState((s) => s && { ...s, myAccounts: accounts })
    } catch {
      // 계좌 로드 실패 시 직접 입력으로 전환
      setTransferState((s) => s && { ...s, toTab: 'direct' })
    }
  }

  async function confirmTransfer() {
    if (!transferState) return
    const amount = parseInt(transferState.amount.replace(/,/g, ''), 10)
    if (!amount || amount <= 0) return
    setTransferState((s) => s && { ...s, step: 'confirm' })
  }

  async function executeTransfer() {
    if (!transferState) return
    const amount = parseInt(transferState.amount.replace(/,/g, ''), 10)
    const targetAccount = transferState.myAccounts.find((account) => account.account_number === transferState.toAccountNumber)
    const customerId = customerNo.trim() || getCurrentDepositCustomerId()
    setTransferState((s) => s && { ...s, step: 'processing' })
    try {
      // 입력받은 인증서 PIN 으로 이 이체에 한정된 승인 토큰을 받는다.
      // 예전에는 certPin 을 6자리 채우면 화면만 넘어가고 값은 버려졌다 —
      // 인증하는 것처럼 보이지만 실제로는 아무것도 확인하지 않는 상태였다.
      let approvalToken: string | undefined
      try {
        const certs = await fetchMyCertificates(customerId)
        const active = certs.find(c => c.status === 'ACTIVE')
        if (!active) {
          setTransferState((s) => s && {
            ...s, step: 'error', needsCertificate: true,
            resultMessage: '이체에는 인증서가 필요합니다.',
          })
          return
        }
        approvalToken = await issueTransferApproval(customerId, {
          fromAccountNo: transferState.fromAccountNumber,
          toAccountNo: transferState.toAccountNumber,
          amount,
          certSerialNumber: active.serialNumber,
          pin: transferState.certPin,
        })
      } catch (certErr: unknown) {
        // 인증서 PIN 실패는 이체 실패와 다르다. 사유를 그대로 보여주고 다시 받는다.
        const detail = (certErr as { response?: { data?: { message?: string } } })?.response?.data?.message
        setTransferState((s) => s && {
          ...s, verifySubStep: 'cert-pin', certPin: '', step: 'verify',
          resultMessage: detail ?? '인증서 비밀번호가 올바르지 않습니다.',
        })
        return
      }

      const result = await executeChatbotTransfer({
        customer_no: customerId,
        from_account_id: transferState.fromAccountId,
        to_account_id: targetAccount?.account_id,
        to_account_number: transferState.toAccountNumber,
        to_bank_name: transferState.toBank || (targetAccount ? 'AXful' : undefined),
        amount,
        memo: transferState.memo || '이체',
        approval_token: approvalToken,
      })
      if (result.status === 'OK') {
        setTransferState((s) => s && { ...s, step: 'done', resultMessage: result.message, balanceAfter: result.balance_after })
        pushMessages([{ id: messageId('transfer'), role: 'bot', text: `✓ ${result.message}` }])
        try { await refreshMyProducts() } catch { /* 갱신 실패해도 이체 성공으로 표시 */ }
      } else {
        setTransferState((s) => s && { ...s, step: 'error', resultMessage: result.message })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { detail?: string; message?: string } }; message?: string })
      const detail = msg?.response?.data?.detail || msg?.response?.data?.message || msg?.message || ''
      console.error('[이체 오류]', detail, err)
      setTransferState((s) => s && { ...s, step: 'error', resultMessage: detail || '이체 처리 중 오류가 발생했습니다.' })
    }
  }

  /**
   * 밖에서 챗봇을 여는 통로.
   *
   * <p>챗봇은 라우트가 아니라 전역 위젯이라 `<Link>` 로 열 수 없다. 홈 퀵메뉴처럼
   * 다른 컴포넌트가 "이 질문으로 챗봇을 열어" 라고 알릴 방법이 필요하다.
   *
   * <p>전역 상태 라이브러리나 Context 를 새로 들이지 않고 DOM 이벤트를 쓴다 —
   * 위젯은 `GlobalChrome` 에 한 번만 마운트되고 여는 쪽은 트리 어디에나 있을 수
   * 있어서, 공통 조상을 만들려면 레이아웃을 건드려야 한다.
   *
   * <p>질문을 함께 받으면 그 질문을 바로 보낸다. 퀵메뉴 "AI 자산분석" 이 챗봇만
   * 열고 사용자가 다시 타이핑해야 한다면 눌러야 할 이유가 없다.
   */
  useEffect(() => {
    function onOpenRequest(e: Event) {
      const query = (e as CustomEvent<{ query?: string }>).detail?.query
      setOpen(true)
      if (query) {
        setInput('')
        void handleScenarioMessage(query)
      }
    }
    window.addEventListener('axful:open-chatbot', onOpenRequest)
    return () => window.removeEventListener('axful:open-chatbot', onOpenRequest)
    // handleScenarioMessage 는 렌더마다 새로 만들어지지만, 리스너가 최신 것을
    // 보게 하려고 의존성에 넣으면 매 렌더마다 재등록된다. 여는 동작만 하므로
    // 최초 등록으로 충분하다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function submitMessage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const text = input.trim()
    if (!text || loading) return
    setInput('')
    await handleScenarioMessage(text)
  }

  function resetChatbotHome(message = '안녕하세요. 원하시는 상품군을 고르거나 현금 흐름 기반 상품 추천을 받아보세요.') {
    setMessages([{ id: 'welcome', role: 'bot', text: message }])
    setChatbotConsultationId(null)
    setExpandedRow(null)
    setDataPages({})
    setTransferState(null)
    setTerminateState(null)
    setProductSearchState(null)
    setInput('')
    setLoading(false)
    setLastRecommendCtx('')
    lastRecommendProductsRef.current = []
    lastTopProductRef.current = null
  }

  const panel = open && mounted
    ? createPortal(
        <div className="fixed inset-0 z-[260] bg-black/20">
          <section
            className="fixed flex w-[420px] max-w-[calc(100vw-32px)] flex-col overflow-hidden rounded-lg border border-kb-border bg-white shadow-2xl relative"
            style={{
              left: '50vw',
              top: `max(8px, calc(50vh - 340px + ${panelOffset.y}px))`,
              height: `min(680px, calc(100vh - 16px))`,
              transform: `translateX(calc(-50% + ${panelOffset.x}px))`,
            }}
          >
            <header
              onPointerDown={startPanelDrag}
              className="flex cursor-move select-none items-center justify-between border-b border-kb-border bg-kb-chat px-3 py-3 text-white"
            >
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onPointerDown={(e) => e.stopPropagation()}
                  onClick={() => resetChatbotHome()}
                  className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-white/15 flex-shrink-0"
                  aria-label="챗봇 홈"
                >
                  <Home className="h-4 w-4" />
                </button>
                <span className="flex h-8 w-8 items-center justify-center rounded-full bg-white/15 flex-shrink-0">
                  <Bot className="h-4 w-4" />
                </span>
                <div>
                  <h2 className="text-sm font-bold">{TEXT.title}</h2>
                  <p className="text-xs text-white/75">{TEXT.subtitle}</p>
                </div>
              </div>
              <div className="flex items-center gap-1">
                {/* 관리자 페이지 진입 */}
                <a
                  href="/admin/login"
                  onPointerDown={(e) => e.stopPropagation()}
                  className="flex h-8 items-center gap-1 rounded-full px-2 text-[11px] font-bold hover:bg-white/15 text-white/80 transition flex-shrink-0"
                >
                  관리자
                </a>
                {/* 상담원 페이지 진입 */}
                <a
                  href="/admin/consultation/chat"
                  onPointerDown={(e) => e.stopPropagation()}
                  className="flex h-8 items-center gap-1 rounded-full px-2 text-[11px] font-bold hover:bg-white/15 text-white/80 transition flex-shrink-0"
                >
                  상담원
                </a>
                {isLoggedIn && (
                  <button
                    type="button"
                    onPointerDown={(e) => e.stopPropagation()}
                    onClick={() => {
                      localStorage.removeItem('access_token')
                      localStorage.removeItem('accessToken')
                      localStorage.removeItem('user')
                      localStorage.removeItem('customerId')
                      setIsLoggedIn(false)
                      setCustomerNo('')
                      setMessages([{ id: 'welcome', role: 'bot', text: '로그아웃되었습니다.' }])
                      setChatbotConsultationId(null)
                      setExpandedRow(null)
                      setDataPages({})
                      setTransferState(null)
                      setProductSearchState(null)
                    }}
                    className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-white/15 flex-shrink-0"
                    aria-label="로그아웃"
                    title="로그아웃"
                  >
                    <LogOut className="h-4 w-4" />
                  </button>
                )}
                <button
                  type="button"
                  onPointerDown={(event) => event.stopPropagation()}
                  onClick={() => setOpen(false)}
                  className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-white/15 flex-shrink-0"
                  aria-label={TEXT.closeChat}
                >
                  <X className="h-5 w-5" />
                </button>
              </div>
            </header>

            {/* ── 상담원 모드 패널 ─────────────────────────────────────── */}
            {/* 상담원 패널은 여기 있었다. agentMode 가 true 가 되는 곳이 없어
                115줄이 한 번도 렌더되지 않았다 — 로그인 폼조차 화면에 없었다.
                상담원 화면은 /admin/consultation/chat 이 담당한다. 위젯이 그 사본을
                더 들고 있을 이유가 없어 걷어냈다. */}

            {/* ── 고객 상담사 채팅 패널 ─────────────────────────────── */}
            {customerChatId && customerChatStatus !== null && (<>
              <div className="flex items-center justify-between border-b border-kb-border px-4 py-2 bg-kb-chat-bg">
                <span className="text-xs font-bold text-kb-chat">
                  {customerChatStatus === 'WAITING' ? '⏳ 상담원 연결 대기 중...' : customerChatStatus === 'CONNECTED' ? '✅ 상담원 연결됨' : '상담 종료'}
                </span>
                {customerChatStatus === 'ENDED' && (
                  <button onClick={() => { setCustomerChatId(null); setCustomerChatStatus(null); setCustomerChatMessages([]); setSatisfactionScore(null); setSatisfactionSubmitted(false) }} className="text-[11px] text-gray-400 hover:underline">닫기</button>
                )}
              </div>
              <div className="flex-1 overflow-y-auto px-4 py-3 space-y-2">
                {customerChatStatus === 'WAITING' && (
                  <div className="flex items-center justify-center h-full">
                    <p className="text-xs text-gray-400">상담원이 곧 연결됩니다...</p>
                  </div>
                )}
                {customerChatMessages.map(m => {
                  const isUser = m.sender_type === 'USER'
                  return (
                    <div key={m.message_id} className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
                      {!isUser && <span className="mr-1 mt-1 text-[9px] text-gray-400 self-start">{m.sender_type === 'BOT' ? 'BOT' : '상담원'}</span>}
                      <div className={`max-w-[75%] rounded-2xl px-3 py-2 text-xs leading-relaxed ${isUser ? 'bg-kb-chat text-white rounded-br-sm' : 'bg-white border border-kb-border text-gray-800 rounded-bl-sm'}`}>
                        {m.message}
                        <div className={`text-[9px] mt-0.5 ${isUser ? 'text-white/60' : 'text-gray-400'}`}>{m.sent_at ? m.sent_at.slice(11, 16) : ''}</div>
                      </div>
                    </div>
                  )
                })}
                <div ref={customerChatBottomRef} />

                {/* 상담 종료 후 만족도 평가 */}
                {customerChatStatus === 'ENDED' && (
                  <div className="mt-3 mx-1 rounded-xl border border-kb-border bg-kb-primary-surface px-4 py-3 text-center">
                    {satisfactionSubmitted ? (
                      <p className="text-xs text-kb-chat font-medium">⭐ 소중한 평가 감사합니다!</p>
                    ) : (
                      <>
                        <p className="text-xs text-gray-600 mb-2 font-medium">상담은 어떠셨나요?</p>
                        <div className="flex justify-center gap-1 mb-3">
                          {[1, 2, 3, 4, 5].map(s => (
                            <button
                              key={s}
                              onClick={() => setSatisfactionScore(s)}
                              className={`text-2xl transition-transform hover:scale-110 ${satisfactionScore && satisfactionScore >= s ? 'text-amber-400' : 'text-gray-300'}`}
                            >★</button>
                          ))}
                        </div>
                        <button
                          disabled={!satisfactionScore}
                          onClick={async () => {
                            if (!customerChatId || !satisfactionScore) return
                            try {
                              await endAgentChat(customerChatId, satisfactionScore)
                              setSatisfactionSubmitted(true)
                            } catch {
                              // 실패 시 다시 시도할 수 있도록 그냥 넘김
                            }
                          }}
                          className="px-4 py-1.5 text-xs font-medium rounded-full bg-kb-chat text-white disabled:opacity-30 hover:opacity-90"
                        >
                          평가 제출
                        </button>
                      </>
                    )}
                  </div>
                )}
              </div>
              {customerChatStatus === 'CONNECTED' && (
                <div className="border-t border-kb-border px-3 py-2 flex gap-2">
                  <input
                    value={customerChatInput}
                    onChange={e => setCustomerChatInput(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleCustomerChatSend() } }}
                    disabled={customerChatSending}
                    placeholder="메시지 입력 (Enter 전송)"
                    className="flex-1 h-9 rounded border border-kb-border px-2 text-xs outline-none focus:border-kb-chat"
                  />
                  <button
                    onClick={handleCustomerChatSend}
                    disabled={customerChatSending || !customerChatInput.trim()}
                    className="h-9 px-3 rounded bg-kb-chat text-white text-xs disabled:opacity-40"
                  >
                    <Send className="h-3.5 w-3.5" />
                  </button>
                </div>
              )}
            </>)}

            {customerChatStatus !== 'CONNECTED' && (transferState ? (<>
                <div className="flex items-center justify-between border-b border-kb-border bg-white px-4 py-3">
                  <button
                    type="button"
                    onClick={() => {
                      if (transferState.step === 'confirm') {
                        setTransferState((s) => s && { ...s, step: 'form' })
                      } else {
                        setTransferState(null)
                      }
                    }}
                    className="flex items-center gap-1 text-xs font-medium text-kb-text-muted hover:text-kb-text"
                  >
                    <Home className="h-4 w-4" />
                  </button>
                  <span className="text-sm font-bold text-kb-text">챗봇 이체</span>
                  <button type="button" onClick={() => setTransferState(null)} className="text-kb-text-muted hover:text-kb-text">
                    <X className="h-4 w-4" />
                  </button>
                </div>

                <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
                  {/* 출금 계좌 */}
                  <div className="rounded-lg border border-kb-border bg-[#F7F5EF] px-4 py-3">
                    <p className="mb-1 text-[11px] text-kb-text-muted">출금 계좌</p>
                    <p className="text-sm font-bold text-kb-text">{transferState.fromAccountNumber}</p>
                    <p className="text-[11px] text-kb-text-muted">
                      잔액 {transferState.fromBalance.toLocaleString('ko-KR')}원
                    </p>
                  </div>

                  {transferState.step === 'form' && (
                    <>
                      <div>
                        <div className="mb-2 flex rounded border border-kb-border overflow-hidden">
                          <button
                            type="button"
                            onClick={() => setTransferState((s) => s && { ...s, toTab: 'my_accounts', toAccountNumber: '', toBank: '' })}
                            className={`flex-1 py-1.5 text-xs font-bold transition ${transferState.toTab === 'my_accounts' ? 'bg-kb-chat text-white' : 'bg-white text-kb-text-muted hover:bg-kb-beige'}`}
                          >
                            당행
                          </button>
                          <button
                            type="button"
                            onClick={() => setTransferState((s) => s && { ...s, toTab: 'direct', toAccountNumber: '', toBank: '' })}
                            className={`flex-1 py-1.5 text-xs font-bold transition ${transferState.toTab === 'direct' ? 'bg-kb-chat text-white' : 'bg-white text-kb-text-muted hover:bg-kb-beige'}`}
                          >
                            타행
                          </button>
                        </div>

                        {transferState.toTab === 'my_accounts' ? (
                          transferState.myAccounts.length === 0 ? (
                            <p className="rounded border border-kb-border bg-[#F7F5EF] px-3 py-3 text-center text-xs text-kb-text-muted">
                              다른 계좌가 없습니다.
                            </p>
                          ) : (
                            <div className="space-y-1.5">
                              {transferState.myAccounts.map((acc) => (
                                <button
                                  key={acc.account_id}
                                  type="button"
                                  onClick={() => setTransferState((s) => s && { ...s, toAccountNumber: acc.account_number })}
                                  className={`w-full rounded border px-3 py-2 text-left text-xs transition ${
                                    transferState.toAccountNumber === acc.account_number
                                      ? 'border-kb-chat bg-kb-chat-bg'
                                      : 'border-kb-border bg-[#F7F5EF] hover:bg-kb-beige'
                                  }`}
                                >
                                  <span className="block font-bold text-kb-text">
                                    {acc.account_alias ?? acc.account_number}
                                  </span>
                                  <span className="text-[11px] text-kb-text-muted">
                                    {acc.account_number} · 잔액 {acc.balance.toLocaleString('ko-KR')}원
                                  </span>
                                </button>
                              ))}
                            </div>
                          )
                        ) : (
                          <div className="space-y-2">
                            <select
                              value={transferState.toBank}
                              onChange={(e) => setTransferState((s) => s && { ...s, toBank: e.target.value })}
                              className="w-full rounded border border-kb-border px-3 py-2 text-sm outline-none focus:border-kb-chat bg-white"
                            >
                              <option value="">은행 선택</option>
                              {['국민은행','신한은행','우리은행','하나은행','농협은행','기업은행','카카오뱅크','케이뱅크','토스뱅크','SC제일은행','한국씨티은행','우체국','새마을금고','신협','수협은행','대구은행','부산은행'].map((b) => (
                                <option key={b} value={b}>{b}</option>
                              ))}
                            </select>
                            <input
                              type="text"
                              value={transferState.toAccountNumber}
                              onChange={(e) => setTransferState((s) => s && { ...s, toAccountNumber: e.target.value })}
                              placeholder="계좌번호 입력 (예: 12345678901234)"
                              className="w-full rounded border border-kb-border px-3 py-2 text-sm outline-none focus:border-kb-chat"
                            />
                          </div>
                        )}
                      </div>
                      <div>
                        <label className="mb-1 block text-xs font-bold text-kb-text">이체 금액</label>
                        <input
                          type="text"
                          inputMode="numeric"
                          value={transferState.amount ? Number(transferState.amount.replace(/,/g, '')).toLocaleString('ko-KR') : ''}
                          onChange={(e) => {
                            const raw = e.target.value.replace(/,/g, '')
                            if (/^\d*$/.test(raw)) setTransferState((s) => s && { ...s, amount: raw })
                          }}
                          placeholder="금액 입력"
                          className="w-full rounded border border-kb-border px-3 py-2 text-sm outline-none focus:border-kb-chat"
                        />
                        <div className="mt-2 flex flex-wrap gap-1.5">
                          {[10000, 50000, 100000, 500000].map((v) => (
                            <button
                              key={v}
                              type="button"
                              onClick={() => setTransferState((s) => s && { ...s, amount: String((parseInt(s.amount || '0', 10)) + v) })}
                              className="rounded border border-kb-border bg-[#F7F5EF] px-2 py-1 text-[11px] font-medium text-kb-text hover:bg-kb-beige"
                            >
                              +{(v / 10000)}만
                            </button>
                          ))}
                          <button
                            type="button"
                            onClick={() => setTransferState((s) => s && { ...s, amount: String(s.fromBalance) })}
                            className="rounded border border-kb-border bg-[#F7F5EF] px-2 py-1 text-[11px] font-medium text-kb-text hover:bg-kb-beige"
                          >
                            전액
                          </button>
                        </div>
                      </div>
                      <div>
                        <label className="mb-1 block text-xs font-bold text-kb-text">메모 (선택)</label>
                        <input
                          type="text"
                          value={transferState.memo}
                          onChange={(e) => setTransferState((s) => s && { ...s, memo: e.target.value })}
                          placeholder="이체"
                          className="w-full rounded border border-kb-border px-3 py-2 text-sm outline-none focus:border-kb-chat"
                        />
                      </div>
                      <button
                        type="button"
                        onClick={confirmTransfer}
                        disabled={!transferState.toAccountNumber || !transferState.amount || (transferState.toTab === 'direct' && !transferState.toBank)}
                        className="w-full rounded bg-kb-chat py-3 text-sm font-bold text-white hover:bg-kb-chat-dark disabled:bg-gray-300"
                      >
                        다음
                      </button>
                    </>
                  )}

                  {transferState.step === 'confirm' && (
                    <>
                      <div className="rounded-lg border border-kb-border bg-white px-4 py-3 space-y-2 text-sm">
                        <div className="flex justify-between">
                          <span className="text-kb-text-muted">수취 계좌</span>
                          <span className="font-bold text-kb-text">{transferState.toAccountNumber}</span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-kb-text-muted">이체 금액</span>
                          <span className="font-bold text-[#1a5fa8]">
                            {parseInt(transferState.amount, 10).toLocaleString('ko-KR')}원
                          </span>
                        </div>
                        {transferState.memo && (
                          <div className="flex justify-between">
                            <span className="text-kb-text-muted">메모</span>
                            <span className="text-kb-text">{transferState.memo}</span>
                          </div>
                        )}
                      </div>
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={() => setTransferState((s) => s && { ...s, step: 'form' })}
                          className="flex-1 rounded border border-kb-border py-3 text-sm font-bold text-kb-text hover:bg-kb-beige"
                        >
                          수정
                        </button>
                        <button
                          type="button"
                          onClick={() => setTransferState((s) => s && { ...s, step: 'verify' })}
                          className="flex-1 rounded bg-kb-chat py-3 text-sm font-bold text-white hover:bg-kb-chat-dark"
                        >
                          다음
                        </button>
                      </div>
                    </>
                  )}

                  {transferState.step === 'verify' && (
                    <>
                      {/* STEP 1: 전자서명 원문 확인 */}
                      {transferState.verifySubStep === 'cert-info' && (
                        <div className="space-y-3">
                          <div className="flex items-center gap-2 mb-1">
                            <div className="border border-gray-300 rounded px-2 py-0.5 text-[10px] font-black text-gray-600 tracking-wider">YESKEY</div>
                            <span className="text-xs font-bold text-kb-text">금융인증서비스</span>
                          </div>
                          <p className="text-xs font-bold text-kb-text">전자서명 원문</p>
                          <div className="bg-gray-50 border border-gray-200 rounded p-3 text-[11px] text-kb-text-body space-y-0.5">
                            <p>이체금액 : {parseInt(transferState.amount).toLocaleString('ko-KR')}원</p>
                            <p>입금계좌번호 : {transferState.toAccountNumber}</p>
                            <p>출금계좌번호 : {transferState.fromAccountNumber}</p>
                            <p>이체수수료 : 0원</p>
                          </div>
                          <div className="flex gap-2">
                            <button type="button"
                              onClick={() => setTransferState((s) => s && { ...s, step: 'confirm' })}
                              className="flex-1 rounded border border-kb-border py-2 text-xs font-bold text-kb-text hover:bg-kb-beige">
                              이전
                            </button>
                            <button type="button"
                              onClick={() => setTransferState((s) => s && { ...s, verifySubStep: 'cert-pin', certPin: '' })}
                              className="flex-1 rounded bg-kb-chat py-2 text-xs font-bold text-white hover:bg-kb-chat-dark">
                              확인
                            </button>
                          </div>
                        </div>
                      )}

                      {/* STEP 2: PIN 입력 */}
                      {transferState.verifySubStep === 'cert-pin' && (
                        <div className="space-y-3">
                          <div className="flex items-center gap-2 mb-1">
                            <div className="border border-gray-300 rounded px-2 py-0.5 text-[10px] font-black text-gray-600 tracking-wider">YESKEY</div>
                            <span className="text-xs font-bold text-kb-text">금융인증서비스</span>
                          </div>
                          <p className="text-xs text-kb-text-muted text-center">비밀번호를 입력해주세요</p>
                          <div className="flex gap-1.5 justify-center">
                            {Array.from({ length: 6 }).map((_, i) => (
                              <div key={i} className={`w-7 h-7 rounded-full border-2 flex items-center justify-center ${i < transferState.certPin.length ? 'bg-kb-text border-kb-text' : 'border-gray-300'}`}>
                                {i < transferState.certPin.length && <span className="w-1.5 h-1.5 bg-white rounded-full" />}
                              </div>
                            ))}
                          </div>
                          <div className="grid grid-cols-3 gap-2">
                            {[[5,2,7],[9,8,0],[6,1,4],['↺',3,'✕']].flat().map((key, i) => (
                              <button key={i} type="button"
                                onClick={() => {
                                  if (key === '↺') { setTransferState((s) => s && { ...s, certPin: '' }); return }
                                  if (key === '✕') { setTransferState((s) => s && { ...s, certPin: s.certPin.slice(0, -1) }); return }
                                  if (!transferState || transferState.certPin.length >= 6) return
                                  const next = transferState.certPin + String(key)
                                  setTransferState((s) => s && { ...s, certPin: next })
                                  if (next.length === 6) {
                                    setTimeout(() => executeTransfer(), 300)
                                  }
                                }}
                                className="py-2 text-sm font-semibold rounded hover:bg-kb-beige border border-kb-border transition-colors">
                                {key}
                              </button>
                            ))}
                          </div>
                        </div>
                      )}
                    </>
                  )}

                  {transferState.step === 'processing' && (
                    <div className="flex items-center justify-center py-8 text-sm text-kb-text-muted">
                      이체 처리 중...
                    </div>
                  )}

                  {(transferState.step === 'done' || transferState.step === 'error') && (
                    <>
                      <div className={`rounded-lg border px-4 py-4 text-center ${transferState.step === 'done' ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'}`}>
                        <p className={`text-lg font-bold mb-1 ${transferState.step === 'done' ? 'text-green-700' : 'text-red-700'}`}>
                          {transferState.step === 'done' ? '이체 완료' : '이체 실패'}
                        </p>
                        <p className="text-sm text-kb-text">{transferState.resultMessage}</p>
                        {/* 막기만 하면 어디로 가야 할지 모른다 — 발급 화면으로 바로 보낸다. */}
                        {transferState.needsCertificate && (
                          <div className="mt-3">
                            <p className="text-xs text-kb-text-muted mb-2">
                              금융인증서를 발급받으면 이체를 이용할 수 있습니다.
                            </p>
                            <Link href="/cert/fin-cert-issue"
                              onClick={() => setOpen(false)}
                              className="inline-block rounded bg-kb-chat px-4 py-2 text-xs font-bold text-white hover:bg-kb-chat-dark">
                              금융인증서 발급하기
                            </Link>
                          </div>
                        )}
                        {transferState.step === 'done' && transferState.balanceAfter !== null && (
                          <p className="mt-2 text-xs text-kb-text-muted">
                            잔여 잔액: {transferState.balanceAfter.toLocaleString('ko-KR')}원
                          </p>
                        )}
                      </div>
                      <button
                        type="button"
                        onClick={() => setTransferState(null)}
                        className="w-full rounded bg-kb-chat py-3 text-sm font-bold text-white hover:bg-kb-chat-dark"
                      >
                        닫기
                      </button>
                    </>
                  )}
                </div>
              </>) : terminateState ? (<>
              <div className="flex items-center justify-between border-b border-kb-border bg-white px-4 py-3">
                <button type="button" onClick={() => setTerminateState(null)}
                  className="flex items-center gap-1 text-xs font-medium text-kb-text-muted hover:text-kb-text">
                  ← 취소
                </button>
                <span className="text-xs font-bold text-kb-text">해지 신청</span>
                <div className="w-12" />
              </div>
              <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
                {/* 해지 계좌 정보 */}
                <div className="rounded border border-kb-border bg-white p-3 text-xs space-y-1.5">
                  <div className="flex justify-between"><span className="text-kb-text-muted">상품명</span><span className="font-bold">{terminateState.productName}</span></div>
                  <div className="flex justify-between"><span className="text-kb-text-muted">계좌번호</span><span>{terminateState.accountNumber}</span></div>
                  <div className="flex justify-between"><span className="text-kb-text-muted">해지금액</span><span className="font-bold text-kb-danger">{terminateState.balance.toLocaleString()}원</span></div>
                </div>

                {terminateState.step === 'method' && (<>
                  <p className="text-xs font-bold text-kb-text">입금 방식을 선택해주세요</p>
                  <div className="space-y-2">
                    {(['cash', 'own', 'other'] as const).map((m) => (
                      <button key={m} type="button"
                        onClick={() => setTerminateState(s => s && { ...s, method: m })}
                        className={`w-full rounded border px-3 py-2 text-xs font-bold text-left transition ${terminateState.method === m ? 'border-kb-danger bg-red-50 text-kb-danger' : 'border-kb-border bg-white text-kb-text hover:bg-kb-beige'}`}>
                        {m === 'cash' ? '💵 현금 수령' : m === 'own' ? '🏦 당행 계좌 입금' : '🏛 타행 계좌 입금'}
                      </button>
                    ))}
                  </div>

                  {terminateState.method === 'own' && (
                    <div>
                      <p className="text-xs text-kb-text-muted mb-1.5">입금 계좌 선택</p>
                      {terminateState.checkingAccounts.length === 0 ? (
                        <p className="text-xs text-kb-text-muted">입출금 계좌가 없습니다.</p>
                      ) : (
                        <div className="space-y-1.5">
                          {terminateState.checkingAccounts.map(acc => (
                            <button key={acc.account_id} type="button"
                              onClick={() => setTerminateState(s => s && { ...s, targetAccountId: String(acc.account_id) })}
                              className={`w-full rounded border px-3 py-2 text-left text-xs transition ${terminateState.targetAccountId === String(acc.account_id) ? 'border-kb-chat bg-kb-chat-bg' : 'border-kb-border bg-white hover:bg-kb-beige'}`}>
                              <p className="font-bold">{acc.account_number}</p>
                              <p className="text-kb-text-muted">{acc.balance.toLocaleString()}원</p>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  )}

                  {terminateState.method === 'other' && (
                    <div className="space-y-2">
                      <select value={terminateState.otherBank}
                        onChange={e => setTerminateState(s => s && { ...s, otherBank: e.target.value })}
                        className="w-full rounded border border-kb-border px-2 py-1.5 text-xs outline-none bg-white">
                        <option value="">은행 선택</option>
                        {['국민은행','신한은행','우리은행','하나은행','농협은행','기업은행','카카오뱅크','케이뱅크','토스뱅크','SC제일은행','한국씨티은행','우체국','새마을금고','신협','수협은행','대구은행','부산은행'].map(b => (
                          <option key={b} value={b}>{b}</option>
                        ))}
                      </select>
                      <input type="text" placeholder="계좌번호 입력"
                        value={terminateState.otherAccount}
                        onChange={e => setTerminateState(s => s && { ...s, otherAccount: e.target.value })}
                        className="w-full rounded border border-kb-border px-2 py-1.5 text-xs outline-none" />
                    </div>
                  )}

                  <button type="button" onClick={() => {
                    if (!terminateState.method) { alert('입금 방식을 선택해주세요.'); return }
                    if (terminateState.method === 'own' && !terminateState.targetAccountId) { alert('입금 계좌를 선택해주세요.'); return }
                    if (terminateState.method === 'other' && (!terminateState.otherBank || !terminateState.otherAccount)) { alert('은행과 계좌번호를 입력해주세요.'); return }
                    setTerminateState(s => s && { ...s, step: 'verify-cert-info', certPin: '' })
                  }}
                    className="w-full rounded bg-kb-danger py-2 text-xs font-bold text-white hover:bg-red-700">
                    해지 확인
                  </button>
                </>)}

                {/* 전자서명 원문 확인 */}
                {terminateState.step === 'verify-cert-info' && (
                  <div className="space-y-3">
                    <div className="flex items-center gap-2 mb-1">
                      <div className="border border-gray-300 rounded px-2 py-0.5 text-[10px] font-black text-gray-600 tracking-wider">YESKEY</div>
                      <span className="text-xs font-bold text-kb-text">금융인증서비스</span>
                    </div>
                    <p className="text-xs font-bold text-kb-text">전자서명 원문</p>
                    <div className="bg-gray-50 border border-gray-200 rounded p-3 text-[11px] text-kb-text-body space-y-0.5">
                      <p>거래종류 : 예금/적금 해지</p>
                      <p>해지계좌번호 : {terminateState.accountNumber}</p>
                      <p>해지계좌명 : {terminateState.productName}</p>
                      <p>해지금액 : {terminateState.balance.toLocaleString()}원</p>
                    </div>
                    <div className="flex gap-2">
                      <button type="button"
                        onClick={() => setTerminateState(s => s && { ...s, step: 'method' })}
                        className="flex-1 rounded border border-kb-border py-2 text-xs font-bold text-kb-text hover:bg-kb-beige">
                        이전
                      </button>
                      <button type="button"
                        onClick={() => setTerminateState(s => s && { ...s, step: 'verify-cert-pin', certPin: '' })}
                        className="flex-1 rounded bg-kb-danger py-2 text-xs font-bold text-white hover:bg-red-700">
                        확인
                      </button>
                    </div>
                  </div>
                )}

                {/* PIN 입력 */}
                {terminateState.step === 'verify-cert-pin' && (
                  <div className="space-y-3">
                    <div className="flex items-center gap-2 mb-1">
                      <div className="border border-gray-300 rounded px-2 py-0.5 text-[10px] font-black text-gray-600 tracking-wider">YESKEY</div>
                      <span className="text-xs font-bold text-kb-text">금융인증서비스</span>
                    </div>
                    <p className="text-xs text-kb-text-muted text-center">비밀번호를 입력해주세요</p>
                    <div className="flex gap-1.5 justify-center">
                      {Array.from({ length: 6 }).map((_, i) => (
                        <div key={i} className={`w-7 h-7 rounded-full border-2 flex items-center justify-center ${i < terminateState.certPin.length ? 'bg-kb-text border-kb-text' : 'border-gray-300'}`}>
                          {i < terminateState.certPin.length && <span className="w-1.5 h-1.5 bg-white rounded-full" />}
                        </div>
                      ))}
                    </div>
                    <div className="grid grid-cols-3 gap-2">
                      {[[5,2,7],[9,8,0],[6,1,4],['↺',3,'✕']].flat().map((key, i) => (
                        <button key={i} type="button"
                          onClick={() => {
                            if (key === '↺') { setTerminateState(s => s && { ...s, certPin: '' }); return }
                            if (key === '✕') { setTerminateState(s => s && { ...s, certPin: s.certPin.slice(0, -1) }); return }
                            if (!terminateState || terminateState.certPin.length >= 6) return
                            const next = terminateState.certPin + String(key)
                            setTerminateState(s => s && { ...s, certPin: next })
                            if (next.length === 6) setTimeout(() => executeTerminate(), 300)
                          }}
                          className="py-2 text-sm font-semibold rounded hover:bg-kb-beige border border-kb-border transition-colors">
                          {key}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {terminateState.step === 'done' && (
                  <div className="text-center py-4 space-y-2">
                    <p className="text-sm font-bold text-kb-text">해지가 완료되었습니다.</p>
                    <p className="text-xs text-kb-text-muted">{terminateState.balance.toLocaleString()}원이 {terminateState.method === 'cash' ? '현금으로 수령' : terminateState.method === 'own' ? '당행 계좌로 입금' : `${terminateState.otherBank} ${terminateState.otherAccount}으로 입금`}됩니다.</p>
                    <button type="button" onClick={() => setTerminateState(null)}
                      className="rounded bg-kb-chat px-4 py-1.5 text-xs font-bold text-white hover:bg-kb-chat-dark">
                      확인
                    </button>
                  </div>
                )}

                {terminateState.step === 'error' && (
                  <div className="text-center py-4 space-y-2">
                    <p className="text-xs text-red-500">해지 처리 중 오류가 발생했습니다.</p>
                    <button type="button" onClick={() => setTerminateState(s => s && { ...s, step: 'method' })}
                      className="rounded border border-kb-border px-4 py-1.5 text-xs text-kb-text hover:bg-kb-beige">
                      다시 시도
                    </button>
                  </div>
                )}
              </div>
            </>) : productSearchState && isLoggedIn ? (<>
              {/* 헤더 */}
              <div className="flex items-center justify-between border-b border-kb-border bg-white px-4 py-3">
                <button type="button" onClick={() => setProductSearchState(null)}
                  className="flex items-center gap-1 text-xs font-medium text-kb-text-muted hover:text-kb-text">
                  ← 취소
                </button>
                <span className="text-xs font-bold text-kb-text">조건 맞춤 추천</span>
                <div className="w-12" />
              </div>
              <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
                {/* 진행 단계 표시 */}
                <div className="flex gap-1">
                  {(['period','amount','type','rate','purpose'] as const).map((s, i) => (
                    <div key={s} className={`flex-1 h-1 rounded-full ${(['period','amount','type','rate','purpose'] as const).indexOf(productSearchState.step as 'period'|'amount'|'type'|'rate'|'purpose') >= i ? 'bg-kb-chat' : 'bg-kb-border'}`} />
                  ))}
                </div>

                {/* Step 1: 가입기간 */}
                {productSearchState.step === 'period' && (
                  <div className="space-y-3">
                    <p className="text-xs font-bold text-kb-text">가입 기간을 입력해주세요</p>
                    <div className="grid grid-cols-4 gap-1.5">
                      {['6개월','12개월','24개월','36개월'].map(v => (
                        <button key={v} type="button"
                          onClick={() => setProductSearchState(s => s && { ...s, period: v.replace('개월',''), step: 'amount' })}
                          className="rounded border border-kb-border py-1.5 text-[11px] hover:border-kb-chat hover:bg-kb-chat-bg">
                          {v}
                        </button>
                      ))}
                    </div>
                    <input type="text" placeholder="직접 입력 (예: 12개월, 1년)"
                      value={productSearchState.period}
                      onChange={e => setProductSearchState(s => s && { ...s, period: e.target.value })}
                      onKeyDown={e => e.key === 'Enter' && productSearchState.period && setProductSearchState(s => s && { ...s, step: 'amount' })}
                      className="w-full rounded border border-kb-border px-3 py-2 text-xs outline-none focus:border-kb-chat" />
                    <button type="button" disabled={!productSearchState.period}
                      onClick={() => setProductSearchState(s => s && { ...s, step: 'amount' })}
                      className="w-full rounded bg-kb-chat py-2 text-xs font-bold text-white hover:bg-kb-chat-dark disabled:opacity-40">
                      다음
                    </button>
                  </div>
                )}

                {/* Step 2: 가입금액 */}
                {productSearchState.step === 'amount' && (
                  <div className="space-y-3">
                    <p className="text-xs font-bold text-kb-text">가입 금액을 입력해주세요</p>
                    <div className="grid grid-cols-3 gap-1.5">
                      {['100만원','500만원','1000만원'].map(v => (
                        <button key={v} type="button"
                          onClick={() => setProductSearchState(s => s && { ...s, amount: v, step: 'type' })}
                          className="rounded border border-kb-border py-1.5 text-[11px] hover:border-kb-chat hover:bg-kb-chat-bg">
                          {v}
                        </button>
                      ))}
                    </div>
                    <input type="text" placeholder="직접 입력 (예: 100만원, 1000000)"
                      value={productSearchState.amount}
                      onChange={e => setProductSearchState(s => s && { ...s, amount: e.target.value })}
                      onKeyDown={e => e.key === 'Enter' && productSearchState.amount && setProductSearchState(s => s && { ...s, step: 'type' })}
                      className="w-full rounded border border-kb-border px-3 py-2 text-xs outline-none focus:border-kb-chat" />
                    <button type="button" disabled={!productSearchState.amount}
                      onClick={() => setProductSearchState(s => s && { ...s, step: 'type' })}
                      className="w-full rounded bg-kb-chat py-2 text-xs font-bold text-white hover:bg-kb-chat-dark disabled:opacity-40">
                      다음
                    </button>
                  </div>
                )}

                {/* Step 3: 상품유형 */}
                {productSearchState.step === 'type' && (
                  <div className="space-y-3">
                    <p className="text-xs font-bold text-kb-text">상품 유형을 선택해주세요</p>
                    {([
                      ['ALL', '전체 추천', '✨'] as const,
                      ['DEPOSIT', '예금', '💳'] as const,
                      ['SAVINGS', '적금', '🪙'] as const,
                    ]).map(([val, label, icon]) => (
                      <button key={val} type="button"
                        onClick={async () => {
                          if (val === 'ALL') {
                            // 전체 추천: product_type 없이 바로 호출
                            setProductSearchState(null)
                            setLoading(true)
                            const cid = customerNo.trim() || getCurrentDepositCustomerId()
                            pushMessages([{ id: messageId('user'), role: 'user', text: '전체 상품 추천 요청' }])
                            try {
                              const result = await executeChatbotFeature('CASH_FLOW_RECOMMEND', {
                                customer_no: cid,
                                query: '전체 상품 추천',
                              })
                              saveRecommendContext(result)
                              setMessages(prev => [...prev.filter(m => m.featureCode !== 'CASH_FLOW_RECOMMEND'), addFeatureResult(result)])
                              window.setTimeout(() => { scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' }) }, 50)
                            } catch {
                              pushMessages([{ id: messageId('error'), role: 'system', text: '조회 중 오류가 발생했습니다.' }])
                            } finally {
                              setLoading(false)
                            }
                          } else {
                            setProductSearchState(s => s && { ...s, productType: val as 'DEPOSIT' | 'SAVINGS', step: 'rate' })
                          }
                        }}
                        className="w-full rounded border border-kb-border px-3 py-2 text-xs font-bold text-left hover:border-kb-chat hover:bg-kb-chat-bg">
                        {icon} {label}
                      </button>
                    ))}
                  </div>
                )}

                {/* Step 4: 최소 금리 */}
                {productSearchState.step === 'rate' && (
                  <div className="space-y-3">
                    <p className="text-xs font-bold text-kb-text">최소 금리를 선택해주세요 <span className="font-normal text-kb-text-muted">(선택 안 하면 전체)</span></p>
                    <div className="grid grid-cols-4 gap-1.5">
                      {['1%','2%','3%','4%'].map(v => (
                        <button key={v} type="button"
                          onClick={() => setProductSearchState(s => s && { ...s, minRate: v.replace('%',''), step: 'purpose' })}
                          className="rounded border border-kb-border py-1.5 text-[11px] hover:border-kb-chat hover:bg-kb-chat-bg">
                          {v} 이상
                        </button>
                      ))}
                    </div>
                    <input type="number" placeholder="직접 입력 (예: 3.5)"
                      value={productSearchState.minRate}
                      onChange={e => setProductSearchState(s => s && { ...s, minRate: e.target.value })}
                      onKeyDown={e => e.key === 'Enter' && setProductSearchState(s => s && { ...s, step: 'purpose' })}
                      className="w-full rounded border border-kb-border px-3 py-2 text-xs outline-none focus:border-kb-chat" />
                    <div className="flex gap-2">
                      <button type="button"
                        onClick={() => setProductSearchState(s => s && { ...s, minRate: '', step: 'purpose' })}
                        className="flex-1 rounded border border-kb-border py-2 text-xs font-bold text-kb-text-muted hover:bg-[#F0F0F0]">
                        제한 없음
                      </button>
                      <button type="button"
                        onClick={() => setProductSearchState(s => s && { ...s, step: 'purpose' })}
                        className="flex-1 rounded bg-kb-chat py-2 text-xs font-bold text-white hover:bg-kb-chat-dark">
                        다음
                      </button>
                    </div>
                  </div>
                )}

                {/* Step 5: 목적 (예금/적금만) */}
                {productSearchState.step === 'purpose' && (
                  <div className="space-y-3">
                    <p className="text-xs font-bold text-kb-text">가입 목적을 선택해주세요</p>
                    {productSearchState.productType === 'DEPOSIT' && (
                      <button type="button"
                        onClick={async () => {
                          const snap = productSearchState
                          setProductSearchState(null)
                          setLoading(true)
                          const cid = customerNo.trim() || getCurrentDepositCustomerId()
                          const parsedAmount = parseKoreanAmount(snap.amount)
                          pushMessages([{ id: messageId('user'), role: 'user', text: `목돈 굴리기 - 예금 추천 (기간 ${snap.period}개월, 금액 ${parsedAmount.toLocaleString()}원)` }])
                          try {
                            const result = await executeChatbotFeature('PRODUCT_SEARCH', {
                              customer_no: cid,
                              product_type: 'DEPOSIT',
                              period: Number(snap.period),
                              amount: parsedAmount,
                            })
                            pushMessages([addFeatureResult(result)])
                            window.setTimeout(() => { scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' }) }, 50)
                          } catch {
                            pushMessages([{ id: messageId('error'), role: 'system', text: '조회 중 오류가 발생했습니다.' }])
                          } finally { setLoading(false) }
                        }}
                        className="w-full rounded border border-kb-border px-3 py-2 text-xs font-bold text-left hover:border-kb-chat hover:bg-kb-chat-bg">
                        💰 목돈 굴리기
                      </button>
                    )}
                    {productSearchState.productType === 'SAVINGS' && (
                      <button type="button"
                        onClick={async () => {
                          const snap = productSearchState
                          setProductSearchState(null)
                          setLoading(true)
                          const cid = customerNo.trim() || getCurrentDepositCustomerId()
                          const parsedAmount = parseKoreanAmount(snap.amount)
                          pushMessages([{ id: messageId('user'), role: 'user', text: `매달 저축 - 적금 추천 (기간 ${snap.period}개월, 월납입 ${parsedAmount.toLocaleString()}원)` }])
                          try {
                            const result = await executeChatbotFeature('PRODUCT_SEARCH', {
                              customer_no: cid,
                              product_type: 'SAVINGS',
                              period: Number(snap.period),
                              amount: parsedAmount,
                            })
                            pushMessages([addFeatureResult(result)])
                            window.setTimeout(() => { scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' }) }, 50)
                          } catch {
                            pushMessages([{ id: messageId('error'), role: 'system', text: '조회 중 오류가 발생했습니다.' }])
                          } finally { setLoading(false) }
                        }}
                        className="w-full rounded border border-kb-border px-3 py-2 text-xs font-bold text-left hover:border-kb-chat hover:bg-kb-chat-bg">
                        📅 매달 저축
                      </button>
                    )}
                  </div>
                )}
              </div>
            </>) : !isLoggedIn ? (
              <div className="flex flex-1 flex-col items-center justify-center gap-6 bg-[#FBFAF7] px-6 py-10 text-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-kb-chat-bg">
                  <Bot className="h-8 w-8 text-kb-chat" />
                </div>
                <div className="space-y-2">
                  <p className="text-base font-bold text-kb-text">로그인이 필요한 서비스입니다</p>
                  <p className="text-xs text-kb-text-muted">로그인 후 맞춤 상품 추천, 계좌 조회 등<br />다양한 챗봇 기능을 이용하실 수 있습니다.</p>
                </div>
                <a
                  href="/login"
                  className="rounded-lg bg-kb-chat px-6 py-3 text-sm font-bold text-white hover:bg-[#245a42]"
                >
                  로그인하기
                </a>
                <button
                  type="button"
                  onClick={() => setIsLoggedIn(true)}
                  className="text-xs text-kb-text-muted underline"
                >
                  비회원으로 계속하기
                </button>
              </div>
            ) : (<>

            <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto bg-[#FBFAF7] px-4 py-4">
              {messages.map((message) => (
                <div key={message.id} className={message.role === 'user' ? 'flex justify-end' : 'flex flex-col items-start'}>
                  <div
                    className={`max-w-[88%] rounded-lg px-3 py-2 text-sm leading-relaxed ${
                      message.role === 'user'
                        ? 'bg-kb-chat text-white'
                        : message.role === 'system'
                          ? 'border border-red-200 bg-red-50 text-red-700'
                          : 'border border-kb-border bg-white text-kb-text'
                    }`}
                  >
                    <p className="whitespace-pre-wrap">{message.text.split(/(우대금리[^\n]*)/).map((part, i) =>
                      part.startsWith('우대금리')
                        ? <span key={i} className="text-orange-600 font-medium">{part}</span>
                        : part
                    )}</p>

                    {(message.compareData || (message.data && message.data.length > 0)) && (() => {
                      const page = dataPages[message.id] ?? 0
                      const dataLen = message.data?.length ?? 0
                      const pageSize = message.featureCode === 'MY_PRODUCTS' ? dataLen : DATA_PAGE_SIZE
                      const totalPages = dataLen > 0 ? Math.ceil(dataLen / pageSize) : 0
                      const startIndex = page * pageSize
                      const visibleRows = message.data?.slice(startIndex, startIndex + pageSize) ?? []

                      if (message.featureCode === 'PRODUCT_SEARCH_COMPARE' && message.compareData) {
                        const renderCol = (rows: Record<string, unknown>[], accentColor: string) => (
                          <div className="space-y-1.5">
                            {rows.length === 0
                              ? <p className="text-[10px] text-kb-text-muted text-center py-2">추천 상품 없음</p>
                              : rows.map((row, i) => (
                              <div key={i} className="rounded border border-kb-border bg-white p-2 text-xs">
                                <div className="flex items-start justify-between gap-1.5 mb-0.5">
                                  <div className="flex items-start gap-1.5 min-w-0">
                                    <span className="flex-shrink-0 rounded px-1.5 py-0.5 text-[10px] font-bold text-white mt-0.5" style={{ backgroundColor: accentColor }}>{i + 1}위</span>
                                    <p className="font-bold text-kb-text leading-tight text-[11px]">{String(row.deposit_product_name ?? row.product_name ?? '')}</p>
                                  </div>
                                  {Number(row.product_id) > 0 && (
                                    <a
                                      href={`/products/deposit/join/product-${Number(row.product_id)}`}
                                      onClick={e => { e.stopPropagation(); setOpen(false) }}
                                      className="flex-shrink-0 rounded border border-kb-chat bg-kb-chat-bg px-2 py-0.5 text-[10px] font-bold text-kb-chat hover:bg-[#D0EBE0]"
                                    >
                                      가입
                                    </a>
                                  )}
                                </div>
                                <div className="flex flex-wrap gap-x-2 text-[10px] text-kb-text-muted ml-0.5">
                                  <span>금리 <b style={{ color: accentColor }}>{row.base_interest_rate != null ? `${row.base_interest_rate}%` : '-'}</b></span>
                                  <span>{row.min_period_month != null ? `${row.min_period_month}${row.max_period_month != null && row.max_period_month !== row.min_period_month ? `~${row.max_period_month}` : ''}개월` : '-'}</span>
                                </div>
                                {row.reason != null && (
                                  <p className="text-[10px] font-medium mt-0.5 ml-0.5" style={{ color: accentColor }}>{String(row.reason)}</p>
                                )}
                                {row.pref_condition != null && String(row.pref_condition).trim() !== '' && (
                                  <p className="mt-0.5 ml-0.5 text-[10px] font-medium text-orange-600">
                                    우대금리{row.pref_rate ? ` +${row.pref_rate}%` : ''} 조건: {String(row.pref_condition)}
                                  </p>
                                )}
                              </div>
                            ))}
                          </div>
                        )
                        return (
                          <div className="mt-2 space-y-2">
                            {/* 채점 기준 요약 */}
                            <div className="rounded bg-[#f5f9f7] border border-[#d0e8dd] p-2 text-[10px] text-kb-text-muted space-y-0.5">
                              <p className="font-bold text-[11px] text-kb-text mb-1">📊 100점 채점 기준</p>
                              <p>• 재정 적합도 <b>40점</b> — 현재 자금 대비 상품 금액 적합성</p>
                              <p>• 예상 수익 <b>30점</b> — 금리×기간 기반 세전 이자 상대 평가</p>
                              <p>• 유동성 매칭 <b>20점</b> — 거래 빈도 vs 상품 만기 적합도</p>
                              <p>• 부가 혜택 <b>10점</b> — 비과세·중도해지·우대금리 여부</p>
                            </div>
                            {/* 양쪽 비교 */}
                            <div className="grid grid-cols-2 gap-1.5">
                              <div>
                                <p className="text-[11px] font-bold mb-1 text-center" style={{ color: KB_CHAT }}>📈 저축 성장형<br/><span className="text-[9px] font-normal text-kb-text-muted">적금 가중치 1.3×</span></p>
                                {renderCol(message.compareData.accumulate, '#2D6A4F')}
                              </div>
                              <div>
                                <p className="text-[11px] font-bold mb-1 text-center" style={{ color: '#1a4a7a' }}>💰 목돈 운용형<br/><span className="text-[9px] font-normal text-kb-text-muted">예금 우선 추천</span></p>
                                {renderCol(message.compareData.lumpSum, '#1a4a7a')}
                              </div>
                            </div>
                          </div>
                        )
                      }

                      if (message.featureCode === 'SAVINGS_GOAL' && message.data && message.data.some(r => r.row_type === 'savings_goal_product')) {
                        const goalRows = message.data.filter(r => r.row_type === 'savings_goal_product')
                        const rankLabel = ['🥇 1위', '🥈 2위', '🥉 3위']
                        return (
                          <div className="mt-3 space-y-2">
                            {goalRows.map((row, index) => {
                              const achievable = Boolean(row.achievable)
                              const plan: {month:number,cumulative:number,interest_earned:number,on_track:boolean}[] = Array.isArray(row.monthly_plan) ? row.monthly_plan : []
                              const step = Math.max(1, Math.floor(plan.length / 4))
                              const midPlan = plan.filter((_, i) => (i + 1) % step === 0).slice(0, 4)
                              return (
                                <div key={index} className={`rounded border p-3 text-xs space-y-2 ${achievable ? 'border-kb-chat bg-kb-chat-bg' : 'border-orange-300 bg-orange-50'}`}>
                                  {/* 순위 + 상품명 + 가입하기 */}
                                  <div className="flex items-center gap-2 flex-wrap">
                                    <span className="flex-shrink-0 rounded bg-[#1a3a5c] px-2 py-0.5 text-[10px] font-bold text-white">
                                      {rankLabel[index] ?? `${index + 1}위`}
                                    </span>
                                    <span className={`flex-shrink-0 rounded px-2 py-0.5 text-[10px] font-bold text-white ${achievable ? 'bg-kb-chat' : 'bg-orange-400'}`}>
                                      {achievable ? '✅ 달성 가능' : '⚠️ 달성 어려움'}
                                    </span>
                                    <p className="font-bold text-kb-text flex-1 text-[11px]">{String(row.product_name ?? '')}</p>
                                    {Number(row.product_id) > 0 && (
                                      <a href={`/products/deposit/join/product-${row.product_id}`} target="_blank" rel="noopener noreferrer"
                                        className="flex-shrink-0 rounded bg-[#1a5fa8] px-2 py-0.5 text-[10px] font-bold text-white hover:bg-[#164d8a]">
                                        가입하기
                                      </a>
                                    )}
                                  </div>
                                  {/* 핵심 수치 */}
                                  <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px]">
                                    <span>금리 <span className="font-bold text-kb-chat">{String(row.base_interest_rate)}%</span></span>
                                    <span>만기수령 <span className="font-bold text-kb-text">{Number(row.maturity_amount).toLocaleString()}원</span></span>
                                    <span>이자 <span className="font-bold text-kb-chat">+{Number(row.interest_amount).toLocaleString()}원</span></span>
                                    {row.required_monthly != null && (
                                      <span>월납입 <span className="font-bold text-kb-text">{Number(row.required_monthly).toLocaleString()}원</span></span>
                                    )}
                                    <span>기간 <span className="font-bold text-kb-text">{String(row.goal_months)}개월</span></span>
                                  </div>
                                  {/* 1위 상품에만 납입 계획표 */}
                                  {index === 0 && midPlan.length > 0 && (
                                    <div className="mt-1">
                                      <p className="text-[10px] text-kb-text-muted mb-1">납입 계획표</p>
                                      <div className="grid grid-cols-4 gap-1">
                                        {midPlan.map(pt => (
                                          <div key={pt.month} className={`rounded p-1 text-center text-[10px] ${pt.on_track ? 'bg-kb-chat/10' : 'bg-orange-100'}`}>
                                            <p className="text-kb-text-muted">{pt.month}개월</p>
                                            <p className="font-bold text-kb-text">{(pt.cumulative / 10000).toFixed(0)}만</p>
                                          </div>
                                        ))}
                                      </div>
                                    </div>
                                  )}
                                </div>
                              )
                            })}
                          </div>
                        )
                      }

                      if (message.featureCode === 'PRODUCT_COMPARE' && message.data && message.data.some(r => r.row_type === 'compare_product')) {
                        const row = message.data.find(r => r.row_type === 'compare_product')!
                        const pa = row.product_a as Record<string, unknown>
                        const pb = row.product_b as Record<string, unknown>
                        const items = row.compare_items as {label:string, a:string, b:string}[]
                        const analysis = String(row.analysis ?? '')
                        return (
                          <div className="mt-3 space-y-2 text-xs">
                            {/* 상품명 헤더 + 가입하기 버튼 */}
                            <div className="grid grid-cols-3 gap-1">
                              <div />
                              {[pa, pb].map((p, i) => (
                                <div key={i} className="rounded bg-[#1a3a5c] p-2 text-center text-[10px] font-bold text-white">
                                  <p>{String(p.product_name ?? '')}</p>
                                  {Number(p.product_id) > 0 && (
                                    <a href={`/products/deposit/join/product-${p.product_id}`} target="_blank" rel="noopener noreferrer"
                                      className="mt-1 inline-block rounded bg-white px-2 py-0.5 text-[9px] font-bold text-[#1a3a5c] hover:bg-gray-100">
                                      가입하기
                                    </a>
                                  )}
                                </div>
                              ))}
                            </div>
                            {/* 비교 테이블 */}
                            {items.map((item, i) => (
                              <div key={i} className={`grid grid-cols-3 gap-1 rounded px-1 py-1 ${i % 2 === 0 ? 'bg-gray-50' : 'bg-white'}`}>
                                <span className="text-[10px] font-semibold text-kb-text-muted flex items-center">{item.label}</span>
                                {[item.a, item.b].map((val, j) => (
                                  <span key={j} className="text-center text-[11px] font-medium text-kb-text">{val}</span>
                                ))}
                              </div>
                            ))}
                            {/* GPT 분석 */}
                            {analysis && (
                              <div className="mt-2 rounded border border-kb-chat bg-kb-chat-bg p-2 text-[11px] text-kb-text whitespace-pre-line">
                                <p className="mb-1 text-[10px] font-bold text-kb-chat">💡 AI 분석</p>
                                {analysis}
                              </div>
                            )}
                          </div>
                        )
                      }

                      if (message.featureCode === 'PRODUCT_GUIDE' || message.featureCode === 'CASH_FLOW_RECOMMEND' || message.featureCode === 'PRODUCT_SEARCH') {
                        const productRows = message.featureCode === 'CASH_FLOW_RECOMMEND'
                          ? message.data!.filter(r => r.row_type === 'recommended_product')
                          : visibleRows
                        return (
                        <div className="mt-3 space-y-2">
                          {productRows.map((row, index) => (
                            <div key={`${message.id}-${index}`} className="rounded border border-kb-border bg-white p-3 text-xs space-y-1">
                              <div className="flex items-center gap-2">
                                {row.rank != null && (
                                  <span className="flex-shrink-0 rounded bg-kb-chat px-2 py-0.5 text-[11px] font-bold text-white">
                                    {String(row.rank)}위
                                  </span>
                                )}
                                <p className="font-bold text-kb-text flex-1">{String(row.deposit_product_name ?? row.product_name ?? '')}</p>
                                {row.product_id != null && Number(row.product_id) > 0 && (
                                  <a
                                    href={`/products/deposit/join/product-${row.product_id}`}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    className="flex-shrink-0 rounded bg-[#1a5fa8] px-2 py-0.5 text-[10px] font-bold text-white hover:bg-[#164d8a]"
                                  >
                                    가입하기
                                  </a>
                                )}
                              </div>
                              <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-kb-text-muted">
                                <span>금리 <span className="font-bold text-kb-chat">{row.base_interest_rate != null ? `${row.base_interest_rate}%` : '-'}</span></span>
                                <span>기간 {row.min_period_month != null ? `${row.min_period_month}${row.max_period_month != null && row.max_period_month !== row.min_period_month ? `~${row.max_period_month}` : ''}개월` : '-'}</span>
                                {row.target_groups != null && <span>가입대상 {String(row.target_groups)}</span>}
                                {row.description != null && <span>{String(row.description)}</span>}
                              </div>
                              {row.reason != null && <p className="text-[11px] text-kb-chat font-medium">{String(row.reason)}</p>}
                              {row.pref_condition != null && String(row.pref_condition).trim() !== '' && (
                                <p className="text-[11px] text-orange-600 font-medium">
                                  ✦ 우대금리{row.pref_rate ? ` +${row.pref_rate}%` : ''} 조건: {String(row.pref_condition)}
                                </p>
                              )}
                              {(row.min_join_amount != null || row.max_join_amount != null) && (
                                <p className="text-[11px] text-kb-text-muted">
                                  가입금액 {row.min_join_amount != null ? `${Number(row.min_join_amount).toLocaleString()}원~` : ''}{row.max_join_amount != null ? `${Number(row.max_join_amount).toLocaleString()}원` : ''}
                                </p>
                              )}
                            </div>
                          ))}
                          {totalPages > 1 && (
                            <div className="flex justify-between text-[11px] text-kb-text-muted">
                              <button type="button" disabled={page === 0} onClick={() => setMessagePage(message.id, page - 1)} className="disabled:opacity-30">{TEXT.prev}</button>
                              <span>{page + 1} / {totalPages}</span>
                              <button type="button" disabled={page >= totalPages - 1} onClick={() => setMessagePage(message.id, page + 1)} className="disabled:opacity-30">{TEXT.next}</button>
                            </div>
                          )}
                        </div>
                      )
                      }

                      return (
                      <div className="mt-3 space-y-2">
                        {visibleRows.map((row, index) => {
                          const absoluteIndex = startIndex + index
                          const summary = rowSummary(row, absoluteIndex)
                          const rowKey = `${message.id}-data-${absoluteIndex}`
                          const isOpen = expandedRow?.key === rowKey
                          return (
                            <div key={rowKey} className="rounded border border-kb-border bg-[#F7F5EF]">
                              <div className="flex w-full items-center gap-2 px-3 py-2">
                                <button
                                  type="button"
                                  onClick={() => setExpandedRow(isOpen ? null : { key: rowKey, title: summary.title, row })}
                                  className="min-w-0 flex-1 text-left"
                                >
                                  <span className="block truncate text-xs font-bold text-kb-text">{summary.title}</span>
                                  <span className="block truncate text-[11px] text-kb-text-muted">{summary.meta}</span>
                                </button>
                                <div className="flex flex-none items-center gap-1.5">
                                  {(row.row_type === 'recommended_product' || message.featureCode === 'PRODUCT_SEARCH_COMPARE') && Number(row.product_id) > 0 && (
                                    <a
                                      href={`/products/deposit/join/product-${Number(row.product_id)}`}
                                      onClick={e => { e.stopPropagation(); setOpen(false) }}
                                      className="rounded border border-kb-chat bg-kb-chat-bg px-2 py-0.5 text-[10px] font-bold text-kb-chat hover:bg-[#D0EBE0]"
                                    >
                                      가입
                                    </a>
                                  )}
                                  {message.featureCode === 'MY_PRODUCTS' && canShowTransferButton(row) && (
                                    <button
                                      type="button"
                                      onClick={(e) => {
                                        e.stopPropagation()
                                        startTransfer(
                                          Number(row.account_id),
                                          String(row.account_number ?? ''),
                                          Number(row.balance ?? 0),
                                        )
                                      }}
                                      className="rounded border border-[#1a5fa8] bg-[#EAF2FB] px-2 py-0.5 text-[10px] font-bold text-[#1a5fa8] hover:bg-[#D0E6F7]"
                                    >
                                      이체
                                    </button>
                                  )}
                                  {message.featureCode === 'MY_PRODUCTS' && (
                                    <button
                                      type="button"
                                      onClick={(e) => { e.stopPropagation(); startTerminate(row) }}
                                      className="rounded border border-kb-danger bg-white px-2 py-0.5 text-[10px] font-bold text-kb-danger hover:bg-red-50"
                                    >
                                      해지
                                    </button>
                                  )}
                                  <button
                                    type="button"
                                    onClick={() => setExpandedRow(isOpen ? null : { key: rowKey, title: summary.title, row })}
                                    className="text-[11px] font-bold text-kb-chat"
                                  >
                                    {TEXT.detail}
                                  </button>
                                </div>
                              </div>
                              {isOpen && (
                                <div className="border-t border-white bg-white px-3 py-2">
                                  <div className="mb-2 flex items-center justify-between">
                                    <p className="truncate text-xs font-bold text-kb-text">{expandedRow.title}</p>
                                    <button
                                      type="button"
                                      onClick={() => setExpandedRow(null)}
                                      className="text-[11px] font-bold text-kb-chat"
                                    >
                                      닫기
                                    </button>
                                  </div>
                                  <dl className="space-y-1 text-xs">
                                    {Object.entries(expandedRow.row)
                                      .filter(([key]) => !['row_type', 'account_id', 'contract_id', 'product_id', 'rate_id'].includes(key))
                                      .map(([key, value]) => (
                                        <div key={key} className="flex justify-between gap-3 border-t border-gray-100 pt-1 first:border-t-0 first:pt-0">
                                          <dt className="flex-none text-kb-text-muted">{formatFieldLabel(key)}</dt>
                                          <dd className="min-w-0 text-right font-medium text-kb-text">{formatDisplayValue(key, value)}</dd>
                                        </div>
                                      ))}
                                  </dl>
                                </div>
                              )}
                            </div>
                          )
                        })}
                        {totalPages > 1 && (
                          <div className="flex items-center justify-between rounded border border-kb-border bg-white px-3 py-2 text-xs">
                            <button
                              type="button"
                              onClick={() => setMessagePage(message.id, Math.max(0, page - 1))}
                              disabled={page === 0}
                              className="font-bold text-kb-chat disabled:text-gray-300"
                            >
                              {TEXT.prev}
                            </button>
                            <span className="text-kb-text-muted">
                              {page + 1} / {totalPages}
                            </span>
                            <button
                              type="button"
                              onClick={() => setMessagePage(message.id, Math.min(totalPages - 1, page + 1))}
                              disabled={page >= totalPages - 1}
                              className="font-bold text-kb-chat disabled:text-gray-300"
                            >
                              {TEXT.next}
                            </button>
                          </div>
                        )}
                      </div>
                      )
                    })()}

                    {message.buttons && message.buttons.length > 0 && (
                      <div className="mt-3 flex flex-wrap gap-2">
                        {message.buttons.map((button) => (
                          <button
                            key={button.id}
                            type="button"
                            onClick={() => handleScenarioMessage(button.text, button.value)}
                            className="rounded border border-kb-chat bg-white px-2.5 py-1 text-xs font-medium text-kb-chat hover:bg-kb-chat-bg"
                          >
                            {button.text}
                          </button>
                        ))}
                      </div>
                    )}
                    {message.link && (
                      <div className="mt-3">
                        <a
                          href={message.link.href}
                          className="inline-flex items-center gap-1.5 rounded bg-kb-chat px-3 py-1.5 text-xs font-bold text-white hover:bg-kb-chat-dark"
                        >
                          <ArrowLeftRight className="h-3 w-3" />
                          {message.link.text}
                        </a>
                      </div>
                    )}
                    {message.role === 'bot' && !message.loginForm && (
                      <div className="flex gap-1.5 mt-2 pt-2 border-t border-kb-border">
                        <button
                          onClick={() => setFeedback(prev => {
                            if (prev[message.id] === 'like') { const n = {...prev}; delete n[message.id]; return n }
                            return { ...prev, [message.id]: 'like' }
                          })}
                          className={`flex items-center gap-1 rounded px-2 py-0.5 text-xs transition-colors ${feedback[message.id] === 'like' ? 'bg-green-100 text-green-600 font-bold' : 'text-kb-text-muted hover:text-green-500'}`}
                        >
                          👍 {feedback[message.id] === 'like' ? '도움됐어요' : '좋아요'}
                        </button>
                        <button
                          onClick={() => setFeedback(prev => {
                            if (prev[message.id] === 'dislike') { const n = {...prev}; delete n[message.id]; return n }
                            return { ...prev, [message.id]: 'dislike' }
                          })}
                          className={`flex items-center gap-1 rounded px-2 py-0.5 text-xs transition-colors ${feedback[message.id] === 'dislike' ? 'bg-red-100 text-red-500 font-bold' : 'text-kb-text-muted hover:text-red-400'}`}
                        >
                          👎 {feedback[message.id] === 'dislike' ? '별로예요' : '싫어요'}
                        </button>
                      </div>
                    )}
                    {message.loginForm && !isLoggedIn && (
                      <InlineLoginForm onSuccess={() => {
                        setIsLoggedIn(true)
                        const cid = getCurrentDepositCustomerId()
                        if (cid) setCustomerNo(cid)
                        setMessages(prev => prev.map(m => m.loginForm ? { ...m, loginForm: false } : m))
                        const pending = pendingLoginActionRef.current
                        pendingLoginActionRef.current = null
                        if (pending === 'welcome') {
                          setMessages([{
                            id: 'welcome',
                            role: 'bot',
                            text: '로그인되었습니다. 원하시는 상품군을 고르거나 현금 흐름 기반 상품 추천을 받아보세요.',
                          }])
                        } else if (pending && pending.startsWith('product_guide:')) {
                          // 로그인 전에 눌렀던 상품 가이드 버튼 재실행
                          const query = pending.replace('product_guide:', '')
                          handleFeature('PRODUCT_GUIDE', query, false)
                        } else if (pending === 'recommend') {
                          setProductSearchState({ step: 'period', period: '', amount: '', productType: null, purpose: null, minRate: '' })
                        } else {
                          // my_products: isLoggedIn이 아직 setState 반영 전이므로 직접 실행
                          const cidVal = cid || customerNo.trim() || getCurrentDepositCustomerId()
                          if (cidVal) {
                            setLoading(true)
                            setExpandedRow(null)
                            setDataPages({})
                            setMessages([{ id: messageId('user'), role: 'user', text: '내 상품 보여줘' }])
                            fetchDepositAccountViewModels(cidVal)
                              .then((apiAccounts) => {
                                const rows = apiAccounts.map((a) => ({
                                  account_id: a.apiAccountId ?? 0,
                                  contract_id: a.contractId ?? null,
                                  account_number: a.number,
                                  product_name: a.name,
                                  product_type: a.type,
                                  saving_type: a.savingType ?? null,
                                  balance: a.balance,
                                  account_status: a.accountStatus ?? 'ACTIVE',
                                  maturity_at: a.maturityDate ?? null,
                                  started_at: a.createdAt ?? null,
                                  is_withdrawable: a.type === '입출금',
                                }))
                                setMessages((prev) => [...prev, {
                                  id: messageId('bot'), role: 'bot' as const,
                                  text: `고객님의 수신 상품 ${rows.length}건을 조회했습니다.`,
                                  data: rows, featureCode: 'MY_PRODUCTS',
                                }])
                              })
                              .catch(() => {
                                setMessages((prev) => [...prev, { id: messageId('err'), role: 'bot' as const, text: '상품 조회 중 오류가 발생했습니다.' }])
                              })
                              .finally(() => setLoading(false))
                          }
                        }
                      }} />
                    )}
                  </div>
                </div>
              ))}
              {loading && (
                <div className="flex justify-start">
                  <div className="rounded-lg border border-kb-border bg-white px-3 py-2 text-xs text-kb-text-muted">{TEXT.loading}</div>
                </div>
              )}
            </div>

            <div className="border-t border-kb-border bg-white px-4 py-3">
              <div className="mb-3 grid grid-cols-3 gap-2">
                {quickActions.map((action) => (
                  <button
                    key={`${action.type}-${action.label}`}
                    type="button"
                    onClick={() => handleQuickAction(action)}
                    disabled={loading}
                    className={`flex min-h-9 items-center justify-center gap-1 rounded border px-2 text-xs font-bold transition disabled:opacity-60 ${
                      action.type === 'recommend'
                        ? 'border-kb-gold bg-[#FFF8DA] text-[#7A5200] hover:bg-[#FFEFA7]'
                        : action.type === 'consult'
                          ? 'border-kb-chat bg-white text-kb-chat hover:bg-kb-chat-bg'
                          : action.type === 'my_products'
                            ? 'border-kb-chat bg-kb-chat-bg text-kb-chat hover:bg-[#D8EEE3]'
                          : 'border-kb-border bg-[#F7F5EF] text-kb-text hover:bg-kb-beige'
                    }`}
                  >
                    {action.type === 'recommend' && <Sparkles className="h-3.5 w-3.5" />}
                    {action.type === 'consult' && <Phone className="h-3.5 w-3.5" />}
                    {action.type === 'my_products' && <PackageSearch className="h-3.5 w-3.5" />}
                    {action.label}
                  </button>
                ))}
              </div>

              {/* 파일 첨부 메뉴 */}
              {showFileMenu && (
                <div className="mb-2 rounded border border-kb-border bg-white shadow-md">
                  {[
                    { action: 'CASH_FLOW' as const, label: '📊 타행 거래내역 분석', accept: '.pdf' },
                    { action: 'TERMS' as const, label: '📋 약관 설명', accept: '.pdf' },
                    { action: 'PRODUCT' as const, label: '📄 상품 설명서 안내', accept: '.pdf' },
                    { action: 'ENROLLMENT' as const, label: '📁 서류 제출', accept: '.pdf,.jpg,.jpeg,.png' },
                  ].map(({ action, label, accept }) => (
                    <button
                      key={action}
                      type="button"
                      className="flex w-full items-center gap-2 px-3 py-2 text-xs hover:bg-kb-beige"
                      onClick={() => {
                        setPendingFileAction(action)
                        setShowFileMenu(false)
                        if (fileInputRef.current) {
                          fileInputRef.current.accept = accept
                          fileInputRef.current.click()
                        }
                      }}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              )}
              <input
                ref={fileInputRef}
                type="file"
                className="hidden"
                onChange={(e) => {
                  const f = e.target.files?.[0]
                  if (f) handleFileSelected(f)
                  e.target.value = ''
                }}
              />
              <form onSubmit={submitMessage} className="flex gap-2">
                <button
                  type="button"
                  disabled={loading}
                  onClick={() => setShowFileMenu((v) => !v)}
                  className="flex h-10 w-10 items-center justify-center rounded border border-kb-border text-kb-text-muted transition hover:bg-kb-beige disabled:opacity-40"
                  aria-label="파일 첨부"
                >
                  <Paperclip className="h-4 w-4" />
                </button>
                <input
                  value={input}
                  onChange={(event) => setInput(event.target.value)}
                  placeholder={TEXT.inputPlaceholder}
                  className="h-10 flex-1 rounded border border-kb-border px-3 text-sm outline-none focus:border-kb-chat"
                />
                <button
                  type="submit"
                  disabled={loading || !input.trim()}
                  className="flex h-10 w-10 items-center justify-center rounded bg-kb-chat text-white transition hover:bg-kb-chat-dark disabled:bg-gray-300"
                  aria-label={TEXT.sendMessage}
                >
                  <Send className="h-4 w-4" />
                </button>
              </form>
            </div>
            </>))}

          </section>

        </div>,
        document.body,
      )
    : null

  return (
    <>
      {showConsult && <ConsultModal onClose={() => setShowConsult(false)} />}
<button
        type="button"
        onClick={() => {
          setPanelOffset({ x: 0, y: 0 })
          setOpen(true)
        }}
        className="fixed bottom-6 right-20 z-[320] flex h-16 w-32 items-center justify-center gap-2 rounded-full bg-red-600 text-white shadow-xl transition hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-500"
        aria-label={TEXT.openChat}
      >
        <MessageCircle className="h-7 w-7" />
        <span className="font-bold">CHATBOT</span>
      </button>
      {panel}
    </>
  )
}

function InlineLoginForm({ onSuccess }: { onSuccess: () => void }) {
  const [tab, setTab] = useState<'cert' | 'id'>('id')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  // 금융인증서
  const [pin, setPin] = useState('')

  // 아이디 로그인
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')

  function switchTab(t: 'cert' | 'id') {
    setTab(t)
    setError('')
    setPin('')
    setLoginId('')
    setPassword('')
  }

  // 로그인 화면(app/(personal)/login)이 쓰는 것과 같은 데모 인증서다.
  // 실제 서비스라면 사용자가 보유 인증서를 고르는 단계가 앞에 온다.
  const DEMO_FIN_CERT_SERIAL = 'FINCERT-TEST-2024-000001'

  async function handleCertLogin() {
    if (pin.length !== 6) { setError('PIN 6자리를 입력해주세요.'); return }
    setError('')
    setLoading(true)
    try {
      // 예전에는 `/api/auth/cert-login` 을 불렀다. 그 라우트는 백엔드를 부르지 않고
      // 하드코딩된 PIN 과 대조해 **가짜 토큰**(`mock.…`)을 내주는 mock 이었다.
      // 화면은 로그인된 것처럼 보이지만 그 토큰으로는 게이트웨이가 전부 거절한다 —
      // "로그인은 됐는데 아무것도 안 되는" 상태가 된다.
      //
      // 로그인 화면이 쓰는 진짜 경로로 맞춘다. customer-service 가 인증서 상태·PIN 을
      // 검증하고 실패를 누적해 잠근다.
      const res = await fetch('/api/customer/cert-login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          certSerialNumber: DEMO_FIN_CERT_SERIAL,
          pin,
          certType: 'CERT_FIN',
        }),
      })
      const payload = await res.json().catch(() => null)
      if (res.ok && payload?.data?.accessToken) {
        const d = payload.data
        localStorage.setItem('accessToken', d.accessToken)
        localStorage.setItem('access_token', d.accessToken)
        localStorage.setItem('customerId', String(d.customerId))
        localStorage.setItem('customerNo', String(d.customerNo ?? d.customerId))
        localStorage.setItem('user', JSON.stringify({
          customer_id: d.customerId, customerNo: d.customerNo, name: d.name,
        }))
        onSuccess()
      } else {
        setError(payload?.message ?? '인증서 비밀번호가 맞지 않습니다.')
      }
    } catch {
      setError('네트워크 오류가 발생했습니다.')
    } finally {
      setLoading(false)
    }
  }

  async function handleIdLogin() {
    if (!loginId || !password) { setError('아이디와 비밀번호를 입력해주세요.'); return }
    setError('')
    setLoading(true)
    try {
      const res = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ loginId, password }),
      })
      const json = await res.json()
      if (!res.ok) { setError(json.message ?? '로그인에 실패했습니다.'); return }
      const d = json.data
      localStorage.setItem('accessToken', d.accessToken)
      localStorage.setItem('access_token', d.accessToken)
      localStorage.setItem('customerId', String(d.customerId))
      localStorage.setItem('user', JSON.stringify({ name: d.name, customerId: d.customerId }))
      onSuccess()
    } catch {
      setError('네트워크 오류가 발생했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="mt-3">
      {/* 탭 */}
      <div className="flex border-b border-kb-border mb-3">
        <button
          type="button"
          onClick={() => switchTab('cert')}
          className={`flex-1 py-1.5 text-[10px] font-bold transition-colors ${tab === 'cert' ? 'border-b-2 border-kb-chat text-kb-chat' : 'text-kb-text-muted'}`}
        >
          금융인증서
        </button>
        <button
          type="button"
          onClick={() => switchTab('id')}
          className={`flex-1 py-1.5 text-[10px] font-bold transition-colors ${tab === 'id' ? 'border-b-2 border-kb-chat text-kb-chat' : 'text-kb-text-muted'}`}
        >
          아이디 로그인
        </button>
      </div>

      {/* 금융인증서 탭 */}
      {tab === 'cert' && (
        <div className="space-y-2">
          <p className="text-[10px] text-kb-text-muted">금융인증서 PIN 6자리를 입력해주세요.</p>
          <div className="flex gap-1 justify-center">
            {Array.from({ length: 6 }).map((_, i) => (
              <div
                key={i}
                className={`w-7 h-7 rounded flex items-center justify-center border ${i < pin.length ? 'bg-kb-chat border-kb-chat' : 'border-kb-border'}`}
              >
                {i < pin.length && <span className="text-white text-[8px]">●</span>}
              </div>
            ))}
          </div>
          <div className="grid grid-cols-3 gap-1">
            {['1','2','3','4','5','6','7','8','9','','0','⌫'].map((d) => (
              <button
                key={d}
                type="button"
                disabled={loading || d === ''}
                onClick={() => {
                  if (d === '⌫') { setPin((p) => p.slice(0, -1)); return }
                  if (d === '') return
                  if (pin.length < 6) setPin((p) => p + d)
                }}
                className={`py-2 text-xs font-medium rounded border border-kb-border transition-colors ${d === '' ? 'invisible' : 'hover:bg-kb-beige'}`}
              >
                {d}
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={handleCertLogin}
            disabled={loading || pin.length !== 6}
            className="w-full rounded bg-kb-chat py-1.5 text-xs font-bold text-white hover:bg-kb-chat-dark disabled:opacity-60"
          >
            {loading ? '로그인 중...' : '확인'}
          </button>
        </div>
      )}

      {/* 아이디 로그인 탭 */}
      {tab === 'id' && (
        <div className="space-y-2">
          <input
            type="text"
            placeholder="아이디"
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
            className="w-full rounded border border-kb-border px-2 py-1.5 text-xs outline-none focus:border-kb-chat"
          />
          <input
            type="password"
            placeholder="비밀번호"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleIdLogin()}
            className="w-full rounded border border-kb-border px-2 py-1.5 text-xs outline-none focus:border-kb-chat"
          />
          <button
            type="button"
            onClick={handleIdLogin}
            disabled={loading}
            className="w-full rounded bg-kb-chat py-1.5 text-xs font-bold text-white hover:bg-kb-chat-dark disabled:opacity-60"
          >
            {loading ? '로그인 중...' : '로그인'}
          </button>
        </div>
      )}

      {error && <p className="mt-2 text-[10px] text-red-500">{error}</p>}
    </div>
  )
}
