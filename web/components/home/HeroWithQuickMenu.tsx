'use client'

import { useState } from 'react'
import Link from 'next/link'
import HeroCarousel from './HeroCarousel'

const QUICK_MENUS = [
  {
    label: 'AI 상품 추천', href: '/products/deposit/list',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/>
      </svg>
    ),
  },
  {
    label: 'AI 대출 심사', href: '/loans/apply',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
        <polyline points="14 2 14 8 20 8"/>
        <polyline points="9 15 11 17 15 13"/>
      </svg>
    ),
  },
  {
    label: 'AI 음성 어시스턴트', href: '/products/loan/status',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z"/>
        <path d="M19 10v2a7 7 0 01-14 0v-2"/>
        <line x1="12" y1="19" x2="12" y2="23"/>
        <line x1="8" y1="23" x2="16" y2="23"/>
      </svg>
    ),
  },
  {
    label: 'AI 자산분석', href: '/inquiry/accounts',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <line x1="18" y1="20" x2="18" y2="10"/>
        <line x1="12" y1="20" x2="12" y2="4"/>
        <line x1="6" y1="20" x2="6" y2="14"/>
        <polyline points="2 20 22 20"/>
      </svg>
    ),
  },
  {
    label: 'AI 24시간 상담', href: '/support/consultation/staff-chat',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/>
        <line x1="9" y1="10" x2="15" y2="10"/>
        <line x1="9" y1="14" x2="12" y2="14"/>
      </svg>
    ),
  },
]

export default function HeroWithQuickMenu() {
  const [current, setCurrent] = useState(0)
  const [paused, setPaused] = useState(false)

  return (
    <>
      <HeroCarousel
        current={current}
        paused={paused}
        onChangeTo={setCurrent}
        onPausedChange={setPaused}
      />

      {/* 퀵메뉴 */}
      <section className="relative z-10 bg-white shadow-sm border-b border-gray-100">
        <div className="max-w-kb-container mx-auto">
          <div className="grid grid-cols-5">
            {QUICK_MENUS.map((menu, idx) => (
              <Link key={menu.href} href={menu.href}
                className="flex flex-col items-center justify-center gap-3 px-4 py-6 hover:bg-kb-primary-bg transition-colors duration-150 group relative">
                {idx > 0 && (
                  <div className="absolute left-0 top-1/4 bottom-1/4 w-px bg-gray-100" />
                )}
                <div className="w-14 h-14 flex items-center justify-center group-hover:scale-110 transition-transform duration-150">
                  {menu.icon}
                </div>
                <p className="text-[18px] font-semibold text-kb-text group-hover:text-kb-primary transition-colors whitespace-nowrap">{menu.label}</p>
              </Link>
            ))}
          </div>
        </div>
      </section>
    </>
  )
}
