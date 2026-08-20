'use client'

import { useState } from 'react'

import Link from 'next/link'
import EmailInquiryModal from '@/components/layout/EmailInquiryModal'
import { KB_MINT } from '@/lib/theme'

type Props = {
  onClose: () => void
}

function IconPhone() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-7 h-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6 19.79 19.79 0 01-3.07-8.67A2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z" />
    </svg>
  )
}

function IconChat() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-7 h-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
    </svg>
  )
}

function IconMail() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-7 h-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
      <polyline points="22,6 12,13 2,6" />
    </svg>
  )
}

export default function ConsultModal({ onClose }: Props) {
  // 이메일상담 접수. 버튼이 있는데 접수할 곳이 없었다.
  const [emailOpen, setEmailOpen] = useState(false)

  return (
    <div className="fixed inset-0 z-[300] flex items-center justify-center bg-black/40" onClick={onClose}>
      <div
        className="bg-white shadow-2xl"
        style={{ width: 700, maxWidth: '95vw' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="flex items-center justify-between px-5 py-3" style={{ backgroundColor: KB_MINT }}>
          <span className="text-[16px] font-bold text-kb-text">상담신청</span>
          <span className="text-[13px] font-bold text-kb-text flex items-center gap-1">
            <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2">
              <path d="M10 2L3 7v6c0 4 2.5 7 7 8 4.5-1 7-4 7-8V7L10 2z" fill="#1A1A1A" stroke="none" />
            </svg>
            AXful Bank
          </span>
        </div>

        {/* 카드 3개 */}
        <div className="flex gap-3 p-4">
          {/* 전화상담 */}
          <div className="flex-1 bg-[#FAF8F2] border border-kb-border p-4 space-y-2">
            <div className="flex items-center gap-2 text-kb-text mb-1">
              <IconPhone />
              <span className="text-[15px] font-bold">전화상담</span>
            </div>
            <p className="text-[15px] font-bold" style={{ color: '#2563EB' }}>1588-9999</p>
            <p className="text-[12px] text-kb-text-muted">09:00~16:00 (은행영무일 제외)</p>
            <p className="text-[12px] text-kb-text-muted">* 펀드/신탁 상담시간(09:00~18:00)</p>
            <p className="text-[12px] text-kb-text font-semibold leading-snug mt-2">
              상품이 어렵게 느껴 지시나요?<br />
              전문상담직원이 상품관련<br />
              궁금증을 해결해드립니다.
            </p>
            <p className="text-[11px] text-kb-text-muted leading-relaxed mt-2">
              * 예금/대출/펀드/신탁 이외의 문의사항은 상담에 제한이 있으므로 1588-9999로 이용해주시기 바랍니다.
            </p>
            <p className="text-[11px] text-kb-text-muted leading-relaxed">
              * 개인정보 보호관련으로 고객님의 계좌번호를 미리 준비하시면 보다 신속하게 상담을 도와드릴 수 있습니다.
            </p>
          </div>

          {/* 채팅상담 */}
          <div className="flex-1 bg-[#FAF8F2] border border-kb-border p-4 space-y-2">
            <div className="flex items-center gap-2 text-kb-text mb-1">
              <IconChat />
              <span className="text-[15px] font-bold">채팅상담</span>
            </div>
            <p className="text-[15px] font-bold" style={{ color: '#2563EB' }}>24시간 365일</p>
            <p className="text-[12px] text-kb-text-muted">언제든지 신청가능</p>
            <p className="text-[12px] text-kb-text-body leading-relaxed mt-2">
              상담직원과 실시간 채팅상담을 하실 수 있습니다.
            </p>
            <div className="pt-3">
              <button
                className="border border-kb-border px-5 py-2 text-[12px] text-kb-text-body hover:bg-kb-beige transition-colors"
                onClick={() => window.open('http://localhost:8087/chat', '_blank')}
              >
                채팅상담하기
              </button>
            </div>
          </div>

          {/* 이메일상담 */}
          <div className="flex-1 bg-[#FAF8F2] border border-kb-border p-4 space-y-2">
            <div className="flex items-center gap-2 text-kb-text mb-1">
              <IconMail />
              <span className="text-[15px] font-bold">이메일상담</span>
            </div>
            <p className="text-[15px] font-bold" style={{ color: '#2563EB' }}>24시간 365일</p>
            <p className="text-[12px] text-kb-text-muted">언제든지 신청가능이나</p>
            <p className="text-[12px] text-kb-text-body leading-relaxed mt-2">
              문의하신 내용은 이메일로 답변드립니다.
            </p>
            <div className="pt-2 space-y-2">
              <Link href="/support/faq" onClick={onClose}
                className="block border border-kb-border px-5 py-2 text-[12px] text-kb-text-body hover:bg-kb-beige transition-colors w-full text-left">
                고객상담 FAQ
              </Link>
              <button onClick={() => setEmailOpen(true)}
                className="block border border-kb-border px-5 py-2 text-[12px] text-kb-text-body hover:bg-kb-beige transition-colors w-full text-left">
                이메일상담하기
              </button>
            </div>
          </div>
        </div>

        {/* 닫기 */}
        <div className="flex justify-end px-4 pb-3">
          <button
            onClick={onClose}
            className="text-[12px] text-kb-text-muted hover:text-kb-text flex items-center gap-1"
          >
            닫기 <span className="text-[11px]">✕</span>
          </button>
        </div>
      </div>

      {emailOpen && <EmailInquiryModal onClose={() => setEmailOpen(false)} />}
    </div>
  )
}
