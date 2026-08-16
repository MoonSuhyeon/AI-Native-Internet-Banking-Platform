'use client'

import { useState } from 'react'

import ConsultModal from '@/components/layout/ConsultModal'

/**
 * 푸터의 상담 채널 버튼.
 *
 * 예전에는 '챗봇/채팅/이메일상담(24시간) ▾' 이 다른 푸터 항목들과 함께 배열로
 * 그려졌고, ▾ 가 붙어 있었지만 열릴 메뉴가 없었다. 상담 채널을 보여주는 모달은
 * 이미 있으므로(ConsultModal, 네 화면이 쓴다) 그것을 연다.
 */
export default function FooterConsultButton() {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="flex items-center gap-1.5 border border-kb-border px-4 py-2
                   text-sm text-kb-text-body bg-white hover:bg-kb-beige-light transition-colors"
      >
        챗봇/채팅/이메일상담(24시간)
      </button>
      {open && <ConsultModal onClose={() => setOpen(false)} />}
    </>
  )
}
