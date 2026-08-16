'use client'
import { KB_PRIMARY } from '@/lib/theme'

import Link from 'next/link'
import { useEffect, useRef, useState } from 'react'
import { Send, RefreshCw } from 'lucide-react'
import {
  getAgentQueue,
  connectAgent,
  sendChatMessage,
  getChatMessages,
  endChat,
} from '@/lib/consultation-api'
import type { AgentQueueItem, ChatMessage } from '@/lib/consultation-api'


type Step = 'login' | 'queue' | 'chat'

function timeStr(dt?: string | null) {
  const d = dt ? new Date(dt) : new Date()
  return d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

export default function StaffChatPage() {
  const [step, setStep] = useState<Step>('login')
  const [employeeId, setEmployeeId] = useState('')
  const [employeeIdNum, setEmployeeIdNum] = useState<number | null>(null)
  const [queue, setQueue] = useState<AgentQueueItem[]>([])
  const [queueLoading, setQueueLoading] = useState(false)
  const [chatConsultationId, setChatConsultationId] = useState<number | null>(null)
  const [currentCustomer, setCurrentCustomer] = useState<AgentQueueItem | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const lastIdRef = useRef<number>(0)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages])

  useEffect(() => {
    return () => { if (pollRef.current) clearInterval(pollRef.current) }
  }, [])

  function login() {
    const id = parseInt(employeeId.trim())
    if (!id || isNaN(id)) { alert('직원 ID를 숫자로 입력해주세요.'); return }
    setEmployeeIdNum(id)
    setStep('queue')
    loadQueue()
  }

  async function loadQueue() {
    setQueueLoading(true)
    try {
      const q = await getAgentQueue()
      setQueue(q)
    } catch {
      alert('대기열을 불러올 수 없습니다. 상담 서비스를 확인해주세요.')
    } finally {
      setQueueLoading(false)
    }
  }

  async function accept(item: AgentQueueItem) {
    if (!employeeIdNum) return
    try {
      await connectAgent(item.chat_consultation_id, employeeIdNum)
      setCurrentCustomer(item)
      setChatConsultationId(item.chat_consultation_id)
      lastIdRef.current = 0
      setMessages([])
      setStep('chat')
      startPolling(item.chat_consultation_id)
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '알 수 없는 오류'
      alert(`연결에 실패했습니다: ${msg}`)
    }
  }

  function startPolling(chatId: number) {
    if (pollRef.current) clearInterval(pollRef.current)
    // 즉시 1회 로드
    fetchMessages(chatId)
    pollRef.current = setInterval(() => fetchMessages(chatId), 2000)
  }

  async function fetchMessages(chatId: number) {
    try {
      const msgs = await getChatMessages(chatId)
      if (msgs.length > 0) {
        const newLastId = msgs[msgs.length - 1].message_id
        if (newLastId > lastIdRef.current) {
          lastIdRef.current = newLastId
          setMessages(msgs)
        }
      }
    } catch {}
  }

  async function send() {
    const text = input.trim()
    if (!text || sending || !chatConsultationId) return
    setInput('')
    setSending(true)
    try {
      await sendChatMessage(chatConsultationId, text, 'AGENT')
      await fetchMessages(chatConsultationId)
    } catch {
      alert('메시지 전송에 실패했습니다.')
    } finally {
      setSending(false)
    }
  }

  async function handleEnd() {
    if (!chatConsultationId) return
    if (!confirm('상담을 종료하시겠습니까?')) return
    if (pollRef.current) clearInterval(pollRef.current)
    try {
      await endChat(chatConsultationId)
    } catch {}
    setChatConsultationId(null)
    setCurrentCustomer(null)
    setMessages([])
    setStep('queue')
    loadQueue()
  }

  return (
    <div className="min-h-screen bg-white">
      <div className="max-w-kb-container mx-auto px-6 py-8">
        {/* 브레드크럼 */}
        <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
          <Link href="/" className="hover:underline">홈</Link>
          <span>›</span>
          <span>고객센터</span>
          <span>›</span>
          <span className="font-semibold text-kb-text">상담원 채팅 관리</span>
        </div>

        <h1 className="text-[20px] font-bold text-kb-text mb-6">상담원 채팅 관리</h1>

        {/* 로그인 */}
        {step === 'login' && (
          <div className="border border-kb-border p-8 max-w-sm">
            <h2 className="text-[16px] font-bold text-kb-text mb-4">직원 로그인</h2>
            <p className="text-[13px] text-kb-text-muted mb-4">직원 ID를 입력하여 상담 관리 화면에 접속하세요.</p>
            <label className="block text-[13px] font-semibold text-kb-text mb-1">직원 ID</label>
            <input
              type="number"
              value={employeeId}
              onChange={e => setEmployeeId(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') login() }}
              placeholder="숫자 ID 입력"
              className="w-full border border-kb-border px-3 py-2 text-[13px] outline-none mb-4 focus:border-kb-mint"
            />
            <button onClick={login}
              className="w-full py-2.5 text-[14px] font-bold hover:opacity-90 transition-opacity"
              style={{ backgroundColor: KB_PRIMARY }}>
              로그인
            </button>
          </div>
        )}

        {/* 대기열 */}
        {step === 'queue' && (
          <div className="max-w-2xl">
            <div className="flex items-center justify-between mb-4">
              <div>
                <span className="text-[14px] font-semibold text-kb-text">직원 ID: {employeeIdNum}</span>
                <span className="ml-3 text-[13px] text-kb-text-muted">연결 대기 고객 목록</span>
              </div>
              <button onClick={loadQueue} disabled={queueLoading}
                className="flex items-center gap-1.5 border border-kb-border px-4 py-1.5 text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors disabled:opacity-50">
                <RefreshCw className={`h-4 w-4 ${queueLoading ? 'animate-spin' : ''}`} />
                새로고침
              </button>
            </div>

            {queue.length === 0 ? (
              <div className="border border-kb-border p-10 text-center text-[14px] text-kb-text-muted">
                대기 중인 고객이 없습니다.
              </div>
            ) : (
              <div className="border border-kb-border border-t-2 border-t-kb-primary">
                <table className="w-full text-[13px]">
                  <thead className="bg-kb-primary-bg text-kb-text-muted text-[12px]">
                    <tr>
                      <th className="px-4 py-2.5 text-left font-medium">상담 ID</th>
                      <th className="px-4 py-2.5 text-left font-medium">고객번호</th>
                      <th className="px-4 py-2.5 text-left font-medium">대기 시작</th>
                      <th className="px-4 py-2.5 text-center font-medium">수락</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-kb-border">
                    {queue.map(item => (
                      <tr key={item.chat_consultation_id} className="hover:bg-kb-primary-bg">
                        <td className="px-4 py-3 text-kb-text-muted">#{item.chat_consultation_id}</td>
                        <td className="px-4 py-3 font-medium text-kb-text">{item.customer_no}</td>
                        <td className="px-4 py-3 text-kb-text-muted">{timeStr(item.waiting_since)}</td>
                        <td className="px-4 py-3 text-center">
                          <button onClick={() => accept(item)}
                            className="px-4 py-1.5 text-[12px] font-bold hover:opacity-90 transition-opacity"
                            style={{ backgroundColor: KB_PRIMARY }}>
                            상담 수락
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {/* 채팅 */}
        {step === 'chat' && currentCustomer && (
          <div className="max-w-2xl">
            <div className="flex items-center justify-between mb-3">
              <div className="text-[13px] text-kb-text-muted">
                고객: <span className="font-bold text-kb-text">{currentCustomer.customer_no}</span>
                <span className="ml-3">상담 ID: #{chatConsultationId}</span>
              </div>
              <button onClick={handleEnd}
                className="border border-kb-danger px-4 py-1.5 text-[12px] text-kb-danger hover:bg-red-50 transition-colors">
                상담 종료
              </button>
            </div>

            <div className="border border-kb-border flex flex-col" style={{ height: 520 }}>
              <div className="px-4 py-3 border-b border-kb-border bg-kb-mint flex items-center gap-2">
                <span className="text-[14px] font-bold text-kb-text">채팅 상담 중</span>
                <span className="text-[12px] text-kb-text opacity-70">— {currentCustomer.customer_no}</span>
              </div>

              <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-4 space-y-3 bg-kb-primary-bg">
                {messages.map(msg => (
                  <div key={msg.message_id}
                    className={`flex ${msg.sender_type === 'AGENT' ? 'justify-end' : 'justify-start'} gap-2`}>
                    {msg.sender_type !== 'AGENT' && (
                      <div className={`w-8 h-8 rounded-full flex items-center justify-center text-[11px] font-bold text-white flex-shrink-0 mt-1 ${
                        msg.sender_type === 'USER' ? 'bg-kb-primary' : 'bg-kb-text-muted'
                      }`}>
                        {msg.sender_type === 'USER' ? '고객' : '봇'}
                      </div>
                    )}
                    <div className={`max-w-[75%] flex flex-col gap-1 ${msg.sender_type === 'AGENT' ? 'items-end' : 'items-start'}`}>
                      {msg.sender_type !== 'AGENT' && (
                        <span className="text-[11px] text-kb-text-muted">
                          {msg.sender_type === 'USER' ? '고객' : '봇'}
                        </span>
                      )}
                      <div className={`px-3 py-2 text-[13px] leading-relaxed whitespace-pre-wrap rounded-lg ${
                        msg.sender_type === 'AGENT'
                          ? 'text-white'
                          : 'bg-white border border-kb-border text-kb-text'
                      }`}>
                        {msg.message}
                      </div>
                      <span className="text-[11px] text-kb-text-muted">{timeStr(msg.sent_at)}</span>
                    </div>
                  </div>
                ))}
                {messages.length === 0 && (
                  <div className="text-center text-[13px] text-kb-text-muted py-8">
                    상담이 연결되었습니다. 메시지를 입력하세요.
                  </div>
                )}
              </div>

              <div className="border-t border-kb-border px-3 py-2 flex gap-2 bg-white">
                <input value={input}
                  onChange={e => setInput(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }}
                  placeholder="고객에게 메시지를 입력하세요"
                  className="flex-1 text-[13px] outline-none px-2 py-1" />
                <button onClick={send} disabled={!input.trim() || sending}
                  className="flex h-9 w-9 items-center justify-center rounded text-white disabled:bg-gray-300 transition-opacity hover:opacity-85" style={{ backgroundColor: KB_PRIMARY }}>
                  <Send className="h-4 w-4" />
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
