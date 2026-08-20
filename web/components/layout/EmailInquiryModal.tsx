'use client'

import { useEffect, useState } from 'react'

import { createEmailInquiry, fetchEmailInquiries, type EmailInquiry } from '@/lib/consultation-api'
import { KB_BORDER, KB_PRIMARY, KB_TEXT_MUTED } from '@/lib/theme'

/**
 * 이메일상담 접수.
 *
 * <p>상담 모달과 FAQ 화면의 "이메일상담하기" 버튼에 핸들러가 없었다. 24시간
 * 접수한다고 써 놓고 접수할 곳이 없었다.
 *
 * <p>답변받을 주소를 따로 받는다. 가입 이메일과 다를 수 있고, 여기를 틀리면 접수는
 * 되고 답변만 사라진다 — 고객은 그것을 모른 채 기다린다.
 */
export default function EmailInquiryModal({ onClose }: { onClose: () => void }) {
  const [replyEmail, setReplyEmail] = useState('')
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [done, setDone] = useState<EmailInquiry | null>(null)
  const [history, setHistory] = useState<EmailInquiry[]>([])

  useEffect(() => {
    let alive = true
    // 로그인 전이면 조회가 403 이다. 접수 화면 자체가 막히면 안 되므로 비워 둔다.
    fetchEmailInquiries()
      .then(rows => { if (alive) setHistory(rows) })
      .catch(() => { if (alive) setHistory([]) })
    return () => { alive = false }
  }, [done])

  async function submit() {
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(replyEmail)) {
      return setError('답변받을 이메일 주소를 정확히 입력해 주세요.')
    }
    if (!title.trim()) return setError('제목을 입력해 주세요.')
    if (!content.trim()) return setError('문의 내용을 입력해 주세요.')

    setSending(true)
    try {
      const created = await createEmailInquiry({
        reply_email: replyEmail.trim(),
        title: title.trim(),
        content: content.trim(),
      })
      setDone(created)
      setError('')
      setTitle(''); setContent('')
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status
      setError(status === 403
        ? '로그인 후 이용할 수 있습니다.'
        : '접수하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="fixed inset-0 z-[210] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[520px] max-h-[86vh] flex flex-col"
        onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">이메일상담</span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-4">
          {done ? (
            <div className="border rounded-lg p-4 space-y-1" style={{ borderColor: KB_BORDER }}>
              <p className="text-[14px] font-bold text-kb-text">접수되었습니다.</p>
              <p className="text-[13px]" style={{ color: KB_TEXT_MUTED }}>
                {done.reply_email} 으로 답변드립니다. 접수번호 {done.inquiry_id}
              </p>
              <button onClick={() => setDone(null)}
                className="mt-2 border rounded-lg px-4 py-1.5 text-[12px] text-kb-text-body hover:bg-kb-primary-bg transition-colors"
                style={{ borderColor: KB_BORDER }}>
                추가로 문의하기
              </button>
            </div>
          ) : (
            <div className="space-y-2">
              <input value={replyEmail} onChange={e => setReplyEmail(e.target.value)}
                placeholder="답변받을 이메일"
                className="w-full border rounded-lg px-3 py-2 text-[13px] outline-none"
                style={{ borderColor: KB_BORDER }} />
              <input value={title} onChange={e => setTitle(e.target.value)} maxLength={200}
                placeholder="제목"
                className="w-full border rounded-lg px-3 py-2 text-[13px] outline-none"
                style={{ borderColor: KB_BORDER }} />
              <textarea value={content} onChange={e => setContent(e.target.value)} maxLength={5000} rows={6}
                placeholder="문의 내용을 입력해 주세요."
                className="w-full border rounded-lg px-3 py-2 text-[13px] outline-none resize-none"
                style={{ borderColor: KB_BORDER }} />

              {error && <p className="text-[12px] text-kb-red">{error}</p>}

              <button onClick={submit} disabled={sending}
                className="w-full rounded-lg py-2 text-[13px] font-bold text-white disabled:opacity-50"
                style={{ backgroundColor: KB_PRIMARY }}>
                {sending ? '접수 중…' : '접수하기'}
              </button>
              <p className="text-[11px]" style={{ color: KB_TEXT_MUTED }}>
                영업일 기준 1~3일 내에 답변드립니다.
              </p>
            </div>
          )}

          {history.length > 0 && (
            <div className="border-t pt-4" style={{ borderColor: KB_BORDER }}>
              <p className="text-[13px] font-semibold text-kb-text mb-2">내 문의 내역</p>
              <ul className="space-y-2">
                {history.map(h => (
                  <li key={h.inquiry_id} className="border rounded-lg px-3 py-2 text-[12px]"
                    style={{ borderColor: KB_BORDER }}>
                    <div className="flex justify-between gap-2">
                      <span className="font-semibold text-kb-text truncate">{h.title}</span>
                      <span className="shrink-0" style={{ color: h.answered_at ? KB_PRIMARY : KB_TEXT_MUTED }}>
                        {h.answered_at ? '답변완료' : '접수됨'}
                      </span>
                    </div>
                    {h.answer_content && (
                      <p className="mt-1 whitespace-pre-wrap" style={{ color: KB_TEXT_MUTED }}>
                        {h.answer_content}
                      </p>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
