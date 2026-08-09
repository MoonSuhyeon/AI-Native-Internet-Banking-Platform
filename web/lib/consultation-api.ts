import axios from 'axios'
import { executeDepositTransfer } from '@/lib/deposit-api'

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

const consultationApi = axios.create({
  baseURL: process.env.NEXT_PUBLIC_CONSULTATION_API_URL || '/api/consultation',
  headers: { 'Content-Type': 'application/json' },
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
// 고객용 기능과 경로를 나눈 이유는 신원 때문이다. 직원용 5종은 고객 개인정보·계좌·
// 거래·상담이력을 열기 때문에 "누가 열람했는가"가 남아야 하는데, 위 consultationApi
// 는 Next 프록시(/api/consultation)를 그대로 지나가고 그 프록시는 Content-Type 외
// 모든 헤더를 버린다. 즉 저 경로로는 신원을 실어 보낼 방법이 없다.
//
// 그래서 직원용만 **게이트웨이**로 나간다. 게이트웨이가 JWT 를 검증해
// X-Employee-Id 를 주입하므로, 화면이 직원 ID 를 보낼 필요도 보낼 수단도 없다.
// (고객 챗봇 경로는 건드리지 않았다 — 신원 요구가 다르고 담당도 다르다.)
const staffConsultationApi = axios.create({
  baseURL: `${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8088'}/api/v1/internal/consultation`,
  headers: { 'Content-Type': 'application/json' },
})

staffConsultationApi.interceptors.request.use(config => {
  const token = typeof window !== 'undefined' ? localStorage.getItem('accessToken') : null
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
