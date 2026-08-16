'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import {
  getAgentQueue,
  connectAgent,
  sendChatMessage,
  getChatMessages,
  getChatConsultation,
  endChat,
  agentLogin,
  AgentLoginResponse,
  AgentQueueItem,
  ChatConsultation,
  ChatMessage,
} from '@/lib/consultation-api'

const dt = (v: string | null) => (v ? v.slice(0, 16).replace('T', ' ') : '-')

/** 대기 시각을 아는 상담 중 10분을 넘긴 것. 모르면(null) 오래됐다고 보지 않는다. */
const STALE_MS = 10 * 60 * 1000
const isStale = (waitingSince: string | null) =>
  waitingSince !== null && Date.now() - new Date(waitingSince).getTime() > STALE_MS
const SS_AGENT = 'chatAgent'
const SS_CONSULT = 'chatConsultationId'

export default function ConsultationChatPage() {
  const [agent, setAgent] = useState<AgentLoginResponse | null>(null)
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [loginError, setLoginError] = useState('')
  const [loginLoading, setLoginLoading] = useState(false)

  const [queue, setQueue] = useState<AgentQueueItem[]>([])
  const [queueLoading, setQueueLoading] = useState(false)
  const [selected, setSelected] = useState<AgentQueueItem | null>(null)
  const [consultation, setConsultation] = useState<ChatConsultation | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  // 페이지 진입 시 항상 로그인 폼 표시 (sessionStorage 자동 복원 없음)
  useEffect(() => {
    sessionStorage.removeItem(SS_AGENT)
  }, [])

  // agent 로그인 시 sessionStorage 저장, 로그아웃 시 제거
  const saveAgent = (info: AgentLoginResponse) => {
    sessionStorage.setItem(SS_AGENT, JSON.stringify(info))
    setAgent(info)
  }
  const clearAgent = () => {
    sessionStorage.removeItem(SS_AGENT)
    sessionStorage.removeItem(SS_CONSULT)
    setAgent(null)
  }

  // consultation 복원: agent가 있고 sessionStorage에 ID가 있으면 불러옴
  useEffect(() => {
    if (!agent) return
    const savedId = sessionStorage.getItem(SS_CONSULT)
    if (!savedId || consultation) return
    getChatConsultation(Number(savedId))
      .then(chat => {
        if (chat.status !== 'ENDED') {
          setConsultation(chat)
          getChatMessages(chat.chat_consultation_id).then(setMessages)
        } else {
          sessionStorage.removeItem(SS_CONSULT)
        }
      })
      .catch(() => sessionStorage.removeItem(SS_CONSULT))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent])

  async function onLogin(e: React.FormEvent) {
    e.preventDefault()
    setLoginError('')
    setLoginLoading(true)
    try {
      const info = await agentLogin(loginId, password)
      localStorage.setItem('agentRole', info.role)
      // AdminSidebar가 상담 메뉴를 표시하도록 BankRole 설정
      const bankRoles = (info.role === 'ADMIN' || info.role === 'SUPERVISOR')
        ? ['ROLE_ADMIN']
        : ['ROLE_TELLER']
      localStorage.setItem('admin_roles', JSON.stringify(bankRoles))
      saveAgent(info)
    } catch (err: unknown) {
      const axErr = err as { response?: { status?: number; data?: { detail?: string } }; message?: string }
      if (axErr.response?.status === 401) {
        setLoginError('아이디 또는 비밀번호가 올바르지 않습니다.')
      } else if (!axErr.response) {
        setLoginError(`서버에 연결할 수 없습니다. (${axErr.message ?? 'Network Error'})`)
      } else {
        setLoginError(axErr.response.data?.detail ?? `오류가 발생했습니다. (${axErr.response.status})`)
      }
    } finally {
      setLoginLoading(false)
    }
  }

  const loadQueue = useCallback(async () => {
    setQueueLoading(true)
    try {
      setQueue(await getAgentQueue())
    } catch {
      // 조용히 무시 — 주기적 폴링이므로 일시 실패는 허용
    } finally {
      setQueueLoading(false)
    }
  }, [])

  // 5초마다 대기열 폴링 (로그인 후에만)
  useEffect(() => {
    if (!agent) return
    loadQueue()
    const id = setInterval(loadQueue, 5000)
    return () => clearInterval(id)
  }, [agent, loadQueue])

  // 채팅 메시지 주기 갱신 (상담 연결 중일 때만)
  useEffect(() => {
    if (!consultation || consultation.status === 'ENDED') return
    const id = setInterval(async () => {
      try {
        setMessages(await getChatMessages(consultation.chat_consultation_id))
      } catch { /* ignore */ }
    }, 3000)
    return () => clearInterval(id)
  }, [consultation])

  // 새 메시지가 오면 스크롤 아래로
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  async function onAccept(item: AgentQueueItem) {
    if (!agent) return
    setError('')
    setNotice('')
    try {
      const chat = await connectAgent(item.chat_consultation_id, agent.employee_id)
      setSelected(item)
      setConsultation(chat)
      sessionStorage.setItem(SS_CONSULT, String(chat.chat_consultation_id))
      const msgs = await getChatMessages(chat.chat_consultation_id)
      setMessages(msgs)
      setNotice(`${item.customer_no} 상담 수락 완료`)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '수락에 실패했습니다.')
    }
  }

  async function onSend() {
    if (!input.trim() || !consultation) return
    setSending(true)
    setError('')
    try {
      const msg = await sendChatMessage(consultation.chat_consultation_id, input.trim(), 'AGENT')
      setMessages(prev => [...prev, msg])
      setInput('')
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '메시지 전송에 실패했습니다.')
    } finally {
      setSending(false)
    }
  }

  async function onEnd() {
    if (!consultation) return
    setError('')
    try {
      const updated = await endChat(consultation.chat_consultation_id)
      setConsultation(updated)
      sessionStorage.removeItem(SS_CONSULT)
      setNotice('상담이 종료되었습니다.')
      loadQueue()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '종료에 실패했습니다.')
    }
  }

  const isEnded = consultation?.status === 'ENDED'

  // ── 로그인 화면 ──────────────────────────────────────────────────────────────
  if (!agent) {
    return (
      <div className="flex min-h-screen bg-gray-50">
        <AdminSidebar />
        <main className="flex-1 flex items-center justify-center">
          <div className="w-80 bg-white rounded-xl shadow-sm border border-gray-200 px-8 py-9">
            <div className="mb-6 text-center">
              <div className="inline-flex items-center justify-center w-12 h-12 rounded-full bg-kb-admin/10 mb-3">
                <svg className="w-6 h-6 text-kb-admin" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                </svg>
              </div>
              <h2 className="text-[15px] font-semibold text-gray-800">상담원 로그인</h2>
              <p className="text-xs text-gray-400 mt-1">상담 채팅을 시작하려면 로그인하세요</p>
              <p className="text-[11px] text-blue-500 mt-1">테스트: agent01 / 1234</p>
            </div>

            <form onSubmit={onLogin} className="space-y-3">
              <div>
                <label className="block text-xs text-gray-600 mb-1">아이디</label>
                <input
                  type="text"
                  value={loginId}
                  onChange={e => setLoginId(e.target.value)}
                  autoComplete="username"
                  required
                  className="w-full h-9 rounded-lg border border-gray-300 px-3 text-[13px] text-gray-800 focus:outline-none focus:border-kb-admin"
                />
              </div>
              <div>
                <label className="block text-xs text-gray-600 mb-1">비밀번호</label>
                <input
                  type="password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  autoComplete="current-password"
                  required
                  className="w-full h-9 rounded-lg border border-gray-300 px-3 text-[13px] text-gray-800 focus:outline-none focus:border-kb-admin"
                />
              </div>

              {loginError && (
                <p className="text-xs text-red-500 pt-1">{loginError}</p>
              )}

              <button
                type="submit"
                disabled={loginLoading}
                className="w-full h-10 mt-1 bg-kb-admin text-white text-[13px] font-medium rounded-lg hover:opacity-90 disabled:opacity-50"
              >
                {loginLoading ? '로그인 중…' : '로그인'}
              </button>
            </form>
          </div>
        </main>
      </div>
    )
  }

  // ── 채팅 화면 ────────────────────────────────────────────────────────────────
  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 flex flex-col overflow-hidden">
        <div className="bg-white border-b border-gray-200 px-6 py-3 flex items-center justify-between shrink-0">
          <span className="text-xs text-gray-500">
            상담 &gt; <span className="text-gray-800 font-medium">상담원 채팅</span>
          </span>
          <div className="flex items-center gap-3">
            <span className="text-[12px] text-gray-600">
              {agent.name}
              <span className="ml-1.5 text-[10px] text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded">{agent.role}</span>
            </span>
            <a href="/admin/consultation/history" className="text-xs px-3 py-1 rounded border border-gray-300 text-gray-500 hover:bg-gray-50 transition-colors">상담 이력</a>
            <a href="/admin/consultation/stats" className="text-xs px-3 py-1 rounded border border-gray-300 text-gray-500 hover:bg-gray-50 transition-colors">통계</a>
            {(agent.role === 'SUPERVISOR' || agent.role === 'ADMIN') && (
              <a
                href="/admin/consultation/agents"
                className="text-xs px-3 py-1 rounded border border-blue-300 text-blue-500 hover:bg-blue-50 transition-colors"
              >
                계정 관리
              </a>
            )}
            <button
              onClick={() => { localStorage.removeItem('agentRole'); localStorage.removeItem('admin_roles'); clearAgent(); setQueue([]); setSelected(null); setConsultation(null); setMessages([]) }}
              className="text-xs px-3 py-1 rounded border border-gray-300 text-gray-500 hover:border-red-400 hover:text-red-500 hover:bg-red-50 transition-colors"
            >
              로그아웃
            </button>
          </div>
        </div>

        <div className="flex flex-1 overflow-hidden">
          {/* ── 대기열 패널 ── */}
          <aside className="w-72 bg-white border-r border-gray-200 flex flex-col shrink-0">
            <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
              <span className="text-sm font-semibold text-gray-700">대기 목록</span>
              <div className="flex gap-2">
                <button onClick={async () => {
                  // waiting_since 가 없으면 건드리지 않는다. new Date(null) 은 1970년이라
                  // "10분 초과"로 판정되고, 대기 시각을 모르는 상담이 종료돼 버린다.
                  const old = queue.filter(i => isStale(i.waiting_since))
                  for (const i of old) {
                    // 게이트웨이 경유. 프록시로 직접 부르면 직원 신원이 서비스에 닿지 않는다.
                    await endChat(i.chat_consultation_id)
                  }
                  loadQueue()
                }} className="text-xs text-red-400 hover:underline">오래된거 정리</button>
                <button onClick={loadQueue} disabled={queueLoading}
                  className="text-xs text-kb-admin hover:underline disabled:opacity-40">
                  {queueLoading ? '갱신 중…' : '새로고침'}
                </button>
              </div>
            </div>

            <div className="flex-1 overflow-y-auto divide-y divide-gray-100">
              {queue.length === 0 && !queueLoading && (
                <p className="px-4 py-8 text-center text-xs text-gray-400">대기 중인 상담이 없습니다.</p>
              )}
              {queue.map(item => {
                const isActive = selected?.chat_consultation_id === item.chat_consultation_id
                return (
                  <div key={item.chat_consultation_id}
                    className={`px-4 py-3 ${isActive ? 'bg-blue-50' : 'hover:bg-gray-50'}`}>
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-[13px] font-medium text-gray-800">{item.customer_no}</span>
                      <span className="text-[10px] text-gray-400">#{item.chat_consultation_id}</span>
                    </div>
                    <div className="text-[11px] text-gray-400 mb-2">
                      대기 시작 {dt(item.waiting_since)}
                    </div>
                    {isActive ? (
                      <span className="text-[11px] text-blue-600 font-medium">진행 중</span>
                    ) : (
                      <button onClick={() => onAccept(item)}
                        className="text-[12px] bg-kb-admin text-white px-3 py-1 rounded hover:opacity-90">
                        수락
                      </button>
                    )}
                  </div>
                )
              })}
            </div>
          </aside>

          {/* ── 채팅 패널 ── */}
          <section className="flex-1 flex flex-col overflow-hidden">
            {error && (
              <div className="mx-4 mt-3 bg-red-50 border border-red-200 rounded px-4 py-2 text-xs text-red-700 shrink-0">
                {error}
              </div>
            )}
            {notice && (
              <div className="mx-4 mt-3 bg-green-50 border border-green-200 rounded px-4 py-2 text-xs text-green-700 shrink-0">
                {notice}
              </div>
            )}

            {!consultation ? (
              <div className="flex-1 flex items-center justify-center">
                <p className="text-sm text-gray-400">왼쪽 대기 목록에서 상담을 수락하세요.</p>
              </div>
            ) : (
              <>
                {/* 상담 헤더 */}
                <div className="bg-white border-b border-gray-200 px-5 py-3 flex items-center justify-between shrink-0">
                  <div>
                    <span className="text-sm font-semibold text-gray-800">{selected?.customer_no}</span>
                    <span className={`ml-2 text-[11px] px-2 py-0.5 rounded-full font-medium ${
                      isEnded ? 'bg-gray-100 text-gray-500' : 'bg-teal-100 text-teal-700'
                    }`}>
                      {isEnded ? '종료' : '상담 중'}
                    </span>
                  </div>
                  {!isEnded && (
                    <button onClick={onEnd}
                      className="text-xs border border-red-400 text-red-500 px-3 py-1 rounded hover:bg-red-50">
                      상담 종료
                    </button>
                  )}
                </div>

                {/* 메시지 목록 */}
                <div className="flex-1 overflow-y-auto px-5 py-4 space-y-3">
                  {messages.map(m => {
                    const isAgent = m.sender_type === 'AGENT'
                    const isBot = m.sender_type === 'BOT'
                    return (
                      <div key={m.message_id} className={`flex ${isAgent ? 'justify-end' : 'justify-start'}`}>
                        {!isAgent && (
                          <span className="mr-2 mt-1 text-[10px] font-semibold text-gray-400 self-start">
                            {isBot ? 'BOT' : '고객'}
                          </span>
                        )}
                        <div className={`max-w-xs lg:max-w-md px-4 py-2 rounded-2xl text-[13px] leading-relaxed ${
                          isAgent
                            ? 'bg-kb-admin text-white rounded-br-sm'
                            : isBot
                            ? 'bg-gray-100 text-gray-600 rounded-bl-sm'
                            : 'bg-white border border-gray-200 text-gray-800 rounded-bl-sm shadow-sm'
                        }`}>
                          {m.message}
                          <div className={`text-[10px] mt-1 ${isAgent ? 'text-blue-200' : 'text-gray-400'}`}>
                            {dt(m.sent_at)}
                          </div>
                        </div>
                      </div>
                    )
                  })}
                  <div ref={bottomRef} />
                </div>

                {/* 입력창 */}
                <div className="bg-white border-t border-gray-200 px-4 py-3 flex gap-2 shrink-0">
                  <input
                    value={input}
                    onChange={e => setInput(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onSend() } }}
                    disabled={isEnded || sending}
                    placeholder={isEnded ? '상담이 종료되었습니다.' : '메시지를 입력하세요 (Enter 전송)'}
                    className="flex-1 h-10 rounded border border-gray-300 px-3 text-[13px] focus:outline-none focus:border-kb-admin disabled:bg-gray-50 disabled:text-gray-400"
                  />
                  <button onClick={onSend} disabled={isEnded || sending || !input.trim()}
                    className="h-10 px-5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-40">
                    {sending ? '전송 중…' : '전송'}
                  </button>
                </div>
              </>
            )}
          </section>
        </div>
      </main>
    </div>
  )
}
