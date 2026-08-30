'use client'
import { KB_PRIMARY } from '@/lib/theme'

import Link from 'next/link'
import { useState } from 'react'
import { usePathname } from 'next/navigation'
import ConsultModal from '@/components/layout/ConsultModal'
import { DEPLOYED } from '@/lib/deployed-services'


function IconHome() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 12L12 4l9 8" />
      <path d="M5 10v9a1 1 0 001 1h4v-5h4v5h4a1 1 0 001-1v-9" />
    </svg>
  )
}

function IconPhone() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6 19.79 19.79 0 01-3.07-8.67A2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z" />
    </svg>
  )
}

export default function FloatingSidebar() {
  const pathname = usePathname()
  const [fontSize, setFontSize] = useState<'normal' | 'large'>('normal')
  const [showConsult, setShowConsult] = useState(false)
  const scrollToTop = () => window.scrollTo({ top: 0, behavior: 'smooth' })

  if (pathname === '/login' || pathname.startsWith('/biz')) return null

  const itemCls = "w-full flex flex-col items-center py-3 border-b border-kb-primary-border hover:bg-kb-beige transition-colors duration-150 gap-1 text-kb-text-muted hover:text-kb-text"
  const labelCls = "text-[12px] leading-tight text-center"

  return (
    <>
    {showConsult && <ConsultModal onClose={() => setShowConsult(false)} />}
    <div className="fixed right-0 top-[8%] z-50 flex flex-col items-center w-[80px] shadow-xl rounded-l-2xl overflow-hidden bg-white border border-kb-primary-border border-r-0">

      {/* 홈 */}
      <Link
        href="/"
        className="w-full flex flex-col items-center py-3.5 border-b border-kb-primary-border transition-opacity gap-1 text-white hover:opacity-80"
        style={{ backgroundColor: KB_PRIMARY }}
      >
        <IconHome />
        <span className="text-[10px] font-semibold tracking-wide">HOME</span>
      </Link>


      {/* 전체계좌조회 · 계좌이체 */}
      <Link href="/inquiry/accounts" className={itemCls}>
        <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <rect x="2" y="5" width="20" height="14" rx="2"/>
          <line x1="2" y1="10" x2="22" y2="10"/>
          <line x1="6" y1="15" x2="10" y2="15"/>
        </svg>
        <span className={labelCls}>전체계좌조회</span>
      </Link>
      <Link href="/transfer/account" className={itemCls}>
        <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <line x1="22" y1="12" x2="2" y2="12"/>
          <polyline points="16 6 22 12 16 18"/>
          <polyline points="8 6 2 12 8 18" opacity="0.4"/>
        </svg>
        <span className={labelCls}>계좌이체</span>
      </Link>

      {/* 계산기 */}
      <Link href="/calculator/deposit" className={itemCls}>
        <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <rect x="4" y="2" width="16" height="20" rx="2"/>
          <line x1="8" y1="7" x2="16" y2="7"/>
          <line x1="8" y1="11" x2="10" y2="11"/>
          <line x1="14" y1="11" x2="16" y2="11"/>
          <line x1="8" y1="15" x2="10" y2="15"/>
          <line x1="14" y1="15" x2="16" y2="15"/>
        </svg>
        <span className={labelCls}>예금계산기</span>
      </Link>
      {/* 대출계산기 — 여신을 올리지 않은 배포에서는 내린다. 상품·심사 화면으로
          이어지지 않는 계산기만 남으면 막다른 길이 된다. */}
      {DEPLOYED.loan && (
        <Link href="/calculator/loan" className={itemCls}>
          <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="9"/>
            <line x1="12" y1="7" x2="12" y2="17"/>
            <line x1="7" y1="12" x2="17" y2="12"/>
          </svg>
          <span className={labelCls}>대출계산기</span>
        </Link>
      )}

      {/* 상담신청 */}
      <button onClick={() => setShowConsult(true)} className={itemCls}>
        <IconPhone />
        <span className={labelCls}>상담신청</span>
      </button>

      {/* 이용안내 */}
      <Link href="/support/internet-banking-guide" className={itemCls}>
        <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="12"/>
          <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        <span className={labelCls}>이용안내</span>
      </Link>

      {/* 글자 크기 */}
      <div className="w-full flex items-center justify-center gap-3 py-3 border-b border-kb-primary-border bg-kb-primary-bg">
        <button
          onClick={() => { setFontSize('normal'); document.documentElement.style.fontSize = '14px' }}
          className={`w-7 h-7 rounded-full flex items-center justify-center text-[13px] font-bold transition-all duration-150 ${
            fontSize === 'normal'
              ? 'bg-kb-primary text-white shadow-sm'
              : 'text-kb-text-muted hover:text-kb-text'
          }`}
        >가</button>
        <button
          onClick={() => { setFontSize('large'); document.documentElement.style.fontSize = '16px' }}
          className={`w-7 h-7 rounded-full flex items-center justify-center text-[13px] font-bold transition-all duration-150 ${
            fontSize === 'large'
              ? 'bg-kb-primary text-white shadow-sm'
              : 'text-kb-text-muted hover:text-kb-text'
          }`}
        >가</button>
      </div>

      {/* TOP */}
      <button
        onClick={scrollToTop}
        className="w-full flex flex-col items-center py-3 transition-opacity gap-0.5 text-white hover:opacity-80"
        style={{ backgroundColor: KB_PRIMARY }}
      >
        <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="18 15 12 9 6 15" />
        </svg>
        <span className="text-[10px] font-semibold tracking-wide">TOP</span>
      </button>
    </div>
    </>
  )
}
