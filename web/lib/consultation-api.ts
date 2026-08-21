import axios from 'axios'
import { readAccessToken } from '@/lib/token'

export type ChatbotButton = {
  id: number
  text: string
  value: string
}

export type ChatbotStartResponse = {
  consultation_id: number
  chatbot_consultation_id: number
  node_id: number
  message: string
  buttons: ChatbotButton[]
}

export type ChatbotMessageResponse = ChatbotStartResponse & {
  process_method: string
  agent_transfer_required: boolean
  feature_code?: string
  feature_data?: Record<string, unknown>[]
}

export type ChatbotFeatureExecuteRequest = {
  customer_no?: string
  query?: string
  product_id?: number
  compare_product_ids?: number[]
  staff_id?: string
  chatbot_consultation_id?: number
  amount?: number
  period?: number
  product_type?: string
  purpose?: string
}

export type ChatbotFeatureExecuteResponse = {
  feature_code: string
  status: string
  message: string
  data: Record<string, unknown>[]
  requires_auth: boolean
  requires_staff_auth: boolean
}

// 상담 서비스는 게이트웨이를 거쳐 부른다.
//
// 예전에는 Next 프록시(/api/consultation)로 곧장 갔는데, 그 프록시가 Content-Type 외
// 모든 헤더를 버려서 신원을 실어 보낼 방법이 아예 없었다. 그래서 고객이 누구인지가
// 요청 body 의 customer_no 로만 정해졌고, 남의 번호를 적으면 그 사람의 상품·계약이
// 조회되고 이체까지 나갔다.
//
// 게이트웨이가 JWT 를 검증해 X-Customer-Id 를 주입하므로, 이제 화면은 신원을 보낼
// 필요도 보낼 수단도 없다.
//
// NEXT_PUBLIC_CONSULTATION_API_URL 오버라이드는 없앴다. 상담 서비스를 직접 가리킬 수
// 있으면 게이트웨이를 건너뛰게 되고, 그러면 신원 헤더가 붙지 않아 위의 보호가 통째로
// 사라진다. 실제로 로컬 .env.local 이 그 값을 갖고 있었다 — 고쳐도 그 환경에서는
// 예전 경로로 나가고, 아무 경고 없이 뚫린 상태로 돌아간다.
const consultationApi = axios.create({
  baseURL: `${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8088'}/api/v1/consultation`,
  headers: { 'Content-Type': 'application/json' },
})

consultationApi.interceptors.request.use(config => {
  const token = typeof window !== 'undefined' ? readAccessToken() : null
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export async function startChatbotConsultation(customerNo: string) {
  const { data } = await consultationApi.post<ChatbotStartResponse>('/chatbot/consultations/start', {
    customer_no: customerNo,
    entry_screen: 'WEB_PERSONAL',
    app_version: '0.1.0',
  })
  return data
}

export async function sendChatbotMessage(
  chatbotConsultationId: number,
  payload: { message: string; button_value?: string | null },
) {
  const { data } = await consultationApi.post<ChatbotMessageResponse>(
    `/chatbot/consultations/${chatbotConsultationId}/messages`,
    payload,
  )
  return data
}

export async function executeChatbotFeature(
  featureCode: string,
  payload: ChatbotFeatureExecuteRequest,
) {
  const { data } = await consultationApi.post<ChatbotFeatureExecuteResponse>(
    `/chatbot/features/${featureCode}/execute`,
    payload,
  )
  return data
}

// ── 직원용 조회 (STAFF_*) ────────────────────────────────────────────────────
//
// 고객용과 경로를 나눈 이유는 게이트웨이가 주입하는 신원이 다르기 때문이다.
// 직원용은 X-Employee-Id, 고객용은 X-Customer-Id 로 판정한다. 한 경로로 합치면
// 상담 서비스가 "이 요청을 직원으로 볼지 고객으로 볼지" 를 스스로 정해야 하고,
// 그 판단이 들어가는 순간 잘못 갈리는 경우가 생긴다.
const staffConsultationApi = axios.create({
  baseURL: `${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8088'}/api/v1/internal/consultation`,
  headers: { 'Content-Type': 'application/json' },
})

staffConsultationApi.interceptors.request.use(config => {
  const token = typeof window !== 'undefined' ? readAccessToken() : null
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

/**
 * 직원용 기능 실행. staff_id 를 받지 않는다 — 직원 신원은 JWT 에서만 나온다.
 *
 * 토큰이 없거나 직원 클레임이 없으면 게이트웨이·상담 서비스가 거절한다.
 * 화면에서 "직원 권한이 필요합니다" 로 보이는 것이 정상 동작이다.
 */
export async function executeStaffFeature(
  featureCode: string,
  payload: Omit<ChatbotFeatureExecuteRequest, 'staff_id'>,
) {
  const { data } = await staffConsultationApi.post<ChatbotFeatureExecuteResponse>(
    `/chatbot/features/${featureCode}/execute`,
    payload,
  )
  return data
}

// ── 상담사 채팅 ──────────────────────────────────────────────────────────────

export type AgentQueueItem = {
  chat_consultation_id: number
  consultation_id: number
  customer_no: string
  chatbot_consultation_id: number | null
  waiting_since: string | null
}

export type ChatMessage = {
  message_id: number
  sender_type: 'USER' | 'BOT' | 'AGENT'
  message: string
  sent_at: string | null
  read_yn: string
}

export type ChatConsultation = {
  chat_consultation_id: number
  consultation_id: number
  chatbot_consultation_id: number | null
  status: 'WAITING' | 'CONNECTED' | 'ENDED'
  employee_id: number | null
  agent_requested_at: string | null
  agent_connected_at: string | null
  chat_started_at: string | null
  chat_ended_at: string | null
  active_yn: string
  satisfaction_score: number | null
}

export async function getChatConsultation(chatConsultationId: number): Promise<ChatConsultation> {
  const { data } = await consultationApi.get<ChatConsultation>(`/chat/consultations/${chatConsultationId}`)
  return data
}

export async function getAgentQueue(): Promise<AgentQueueItem[]> {
  const { data } = await consultationApi.get<AgentQueueItem[]>('/chat/queue')
  return data
}

export async function connectAgent(chatConsultationId: number, employeeId: number): Promise<ChatConsultation> {
  const { data } = await consultationApi.post<ChatConsultation>(
    `/chat/consultations/${chatConsultationId}/connect`,
    { employee_id: employeeId },
  )
  return data
}

export async function sendChatMessage(chatConsultationId: number, message: string, senderType: 'USER' | 'AGENT'): Promise<ChatMessage> {
  const { data } = await consultationApi.post<ChatMessage>(
    `/chat/consultations/${chatConsultationId}/messages`,
    { message, sender_type: senderType },
  )
  return data
}

export async function getChatMessages(chatConsultationId: number): Promise<ChatMessage[]> {
  const { data } = await consultationApi.get<ChatMessage[]>(
    `/chat/consultations/${chatConsultationId}/messages`,
  )
  return data
}

export type TransferResult = {
  status: string
  message: string
  transaction_id: number | null
  balance_after: number | null
}

export async function executeChatbotTransfer(payload: {
  customer_no: string
  from_account_id: number
  to_account_id?: number
  to_account_number: string
  to_bank_name?: string
  amount: number
  memo?: string
  /** 인증서 PIN 확인으로 받은 승인 토큰. 없으면 서버 과도기 설정에 따른다. */
  approval_token?: string
}): Promise<TransferResult> {
  const { data } = await consultationApi.post<TransferResult>('/chatbot/transfer', {
    customer_no: payload.customer_no,
    from_account_id: payload.from_account_id,
    to_account_number: payload.to_account_number,
    amount: payload.amount,
    memo: payload.memo ?? '이체',
    approval_token: payload.approval_token,
  })
  return data
}

// ── 상담사 인증 ──────────────────────────────────────────────────────────────

export type AgentLoginResponse = {
  employee_id: number
  login_id: string
  name: string
  role: string
}

export async function agentLogin(loginId: string, password: string): Promise<AgentLoginResponse> {
  const { data } = await consultationApi.post<AgentLoginResponse>('/auth/agent/login', {
    login_id: loginId,
    password,
  })
  return data
}

export type ChatRequestResult = {
  chat_consultation_id: number
  consultation_id: number
  status: string
  agent_requested_at: string | null
}

export async function requestAgentChat(customerNo: string): Promise<ChatRequestResult> {
  const { data } = await consultationApi.post<ChatRequestResult>('/chat/request', {
    customer_no: customerNo,
  })
  return data
}

export async function endChat(chatConsultationId: number, satisfactionScore?: number): Promise<ChatConsultation> {
  const { data } = await consultationApi.post<ChatConsultation>(
    `/chat/consultations/${chatConsultationId}/end`,
    { satisfaction_score: satisfactionScore ?? null },
  )
  return data
}

// ── 상담원 계정 관리 ────────────────────────────────────────────────────────────

export type AgentAccount = {
  employee_id: number
  login_id: string
  name: string
  role: string
  status: string
  created_at: string | null
}

export async function listAgents(): Promise<AgentAccount[]> {
  const { data } = await consultationApi.get<AgentAccount[]>('/agents')
  return data
}

export async function createAgent(payload: { login_id: string; password: string; name: string; role: string }): Promise<AgentAccount> {
  const { data } = await consultationApi.post<AgentAccount>('/agents', payload)
  return data
}

export async function updateAgent(employeeId: number, payload: { name?: string; role?: string; status?: string; password?: string }): Promise<AgentAccount> {
  const { data } = await consultationApi.patch<AgentAccount>(`/agents/${employeeId}`, payload)
  return data
}

export async function deactivateAgent(employeeId: number): Promise<void> {
  await consultationApi.delete(`/agents/${employeeId}`)
}

// ── 상담 이력 조회 ──────────────────────────────────────────────────────────────

export type ChatHistoryItem = {
  chat_consultation_id: number
  consultation_id: number
  customer_no: string
  employee_id: number | null
  status: string
  agent_requested_at: string | null
  agent_connected_at: string | null
  chat_ended_at: string | null
  satisfaction_score: number | null
  message_count: number
}

export async function getChatHistory(params?: { limit?: number; customer_no?: string }): Promise<ChatHistoryItem[]> {
  const query = new URLSearchParams()
  if (params?.limit) query.set('limit', String(params.limit))
  if (params?.customer_no) query.set('customer_no', params.customer_no)
  const { data } = await consultationApi.get<ChatHistoryItem[]>(`/chat/history?${query}`)
  return data
}

// ── 파일 분석 / 서류 제출 ──────────────────────────────────────────────────────

export type FileAnalyzeResponse = {
  analyze_type: string
  result: string
}

export type DocumentUploadResponse = {
  document_id: number
  filename: string
  doc_type: string
  status: string
  message: string
}

export async function analyzeFile(
  text: string,
  analyzeType: 'CASH_FLOW' | 'TERMS' | 'PRODUCT',
  customerNo?: string,
): Promise<FileAnalyzeResponse> {
  const { data } = await consultationApi.post<FileAnalyzeResponse>('/chatbot/file/analyze', {
    text,
    analyze_type: analyzeType,
    customer_no: customerNo,
  })
  return data
}

export async function uploadDocument(
  file: File,
  customerNo: string,
  docType: string = 'ENROLLMENT',
): Promise<DocumentUploadResponse> {
  const form = new FormData()
  form.append('file', file)
  form.append('customer_no', customerNo)
  form.append('doc_type', docType)
  const { data } = await consultationApi.post<DocumentUploadResponse>('/chatbot/documents/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

/* ── 이메일상담 ── */

export type EmailInquiry = {
  inquiry_id: number
  consultation_id: number
  reply_email: string
  title: string
  created_at: string
  answered_at: string | null
  answer_content: string | null
}

/**
 * 이메일상담 접수.
 *
 * 고객번호를 보내지 않는다 — 게이트웨이가 토큰에서 꺼내 넣는다. 화면이 실어 보내면
 * 값을 바꿔 남의 이름으로 문의를 남길 수 있다.
 */
export async function createEmailInquiry(payload: {
  reply_email: string
  title: string
  content: string
}): Promise<EmailInquiry> {
  const { data } = await consultationApi.post<EmailInquiry>('/email-inquiries', payload)
  return data
}

export async function fetchEmailInquiries(): Promise<EmailInquiry[]> {
  const { data } = await consultationApi.get<EmailInquiry[]>('/email-inquiries')
  return data
}
