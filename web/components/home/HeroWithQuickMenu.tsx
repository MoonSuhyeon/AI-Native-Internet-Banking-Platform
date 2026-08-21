'use client'

import { useState } from 'react'
import Link from 'next/link'
import HeroCarousel from './HeroCarousel'

/**
 * 홈 퀵메뉴.
 *
 * <p><b>예전에는 다섯 개가 전부 "AI" 라고 이름 붙었지만 실제로는 평범한 정적
 * 페이지로 갔다.</b> "AI 자산분석" 은 그냥 계좌조회, "AI 상품 추천" 은 그냥 예금
 * 목록이었다. 그중 하나("AI 음성 어시스턴트")는 <b>대응하는 코드가 레포에 아예
 * 없었고</b>, 링크는 엉뚱하게 대출현황을 가리켰다.
 *
 * <p>지금은 넷 다 실제로 도는 것을 가리킨다. 근거는 이렇다.
 *
 * <ul>
 *   <li>상담·추천 · 자산분석 · 만기관리 → {@code agents/consultation} 의 세 에이전트.
 *       셋 다 실제 거래·계약 데이터를 읽어 계산한다</li>
 *   <li>대출 심사 → {@code agents/auto-loan-review} (Spring Boot)</li>
 * </ul>
 *
 * <p>에이전트 셋은 <b>챗봇 안에서 대화로</b> 동작하므로 링크가 아니라 챗봇을 연다.
 * 질문까지 함께 보낸다 — 챗봇만 열고 사용자가 다시 타이핑해야 한다면 이 버튼을
 * 누를 이유가 없다.
 */
type QuickMenu = {
  label: string
  icon: React.ReactNode
  /** 링크로 가는 것 */
  href?: string
  /** 챗봇을 열고 이 질문을 보내는 것 */
  ask?: string
}

const QUICK_MENUS: QuickMenu[] = [
  {
    // consultation 챗봇 — 현금흐름 분석 기반 추천 + 약관 RAG + 상담사 연결.
    // 예전 라벨은 'AI 상품 추천'(→ 정적 예금 목록)과 'AI 24시간 상담'
    // (→ staff-chat, 직원용 콘솔) 둘로 쪼개져 있었다. 같은 백엔드라 합친다.
    label: 'AI 상담·추천', ask: '현금 흐름 기반으로 상품 추천해줘',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/>
        <line x1="9" y1="10" x2="15" y2="10"/>
        <line x1="9" y1="14" x2="12" y2="14"/>
      </svg>
    ),
  },
  {
    // auto-loan-review 가 실제로 트리거되는 지점.
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
    // spending_pattern_agent. 예전에는 /inquiry/accounts(계좌조회)를 가리켰다 —
    // 분석이 아니라 잔액 목록이라 라벨과 맞지 않았다.
    label: 'AI 자산분석', ask: '이번 달 지출 패턴 분석해줘',
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
    // maturity_agent. 만들어져 있었지만 토글이 꺼져 있어 돌지 않던 것을 켰다.
    label: 'AI 만기 관리', ask: '만기되면 어떻게 하는 게 좋을까?',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="9"/>
        <polyline points="12 7 12 12 15 14"/>
      </svg>
    ),
  },
]


/**
 * 전역 챗봇 위젯을 연다.
 *
 * <p>위젯은 `GlobalChrome` 에 한 번만 마운트되고 이 컴포넌트는 그 형제라, 공통
 * 조상을 통해 상태를 내리려면 레이아웃을 건드려야 한다. 여는 신호 하나 때문에
 * Context 를 새로 들이는 대신 DOM 이벤트로 알린다.
 */
function openChatbot(query?: string) {
  window.dispatchEvent(new CustomEvent('axful:open-chatbot', { detail: { query } }))
}

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
          <div className="grid grid-cols-4">
            {QUICK_MENUS.map((menu, idx) => {
              const inner = (
                <>
                  {idx > 0 && (
                    <div className="absolute left-0 top-1/4 bottom-1/4 w-px bg-gray-100" />
                  )}
                  <div className="w-14 h-14 flex items-center justify-center group-hover:scale-110 transition-transform duration-150">
                    {menu.icon}
                  </div>
                  <p className="text-[18px] font-semibold text-kb-text group-hover:text-kb-primary transition-colors whitespace-nowrap">{menu.label}</p>
                </>
              )
              const cls = "flex flex-col items-center justify-center gap-3 px-4 py-6 hover:bg-kb-primary-bg transition-colors duration-150 group relative"

              // 링크로 가는 것과 챗봇을 여는 것은 다른 요소여야 한다. 챗봇은
              // 라우트가 아니라 전역 위젯이라 <Link> 로 열 수 없고, <a href="#">
              // 로 흉내 내면 주소만 바뀌고 아무 일도 일어나지 않는다.
              return menu.href ? (
                <Link key={menu.label} href={menu.href} className={cls}>{inner}</Link>
              ) : (
                <button
                  key={menu.label}
                  type="button"
                  onClick={() => openChatbot(menu.ask)}
                  className={cls}
                >
                  {inner}
                </button>
              )
            })}
          </div>
        </div>
      </section>
    </>
  )
}
