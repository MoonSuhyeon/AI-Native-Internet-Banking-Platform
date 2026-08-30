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

/**
 * 퀵메뉴 — <b>기능이 아니라 순서</b>다.
 *
 * <p>상단 탭과 드롭다운이 이미 기능별로 나뉘어 있다. 같은 화면을 여기 한 번 더
 * 늘어놓으면 자리만 차지한다. 그래서 이 자리는 <b>무엇부터 해보면 되는가</b>를 놓는다 —
 * 처음 온 사람이 위에서 아래로 누르기만 하면 시연 동선이 그대로 재현된다.
 *
 * <p>세 칸인 이유는 넷째로 넣을 것이 이 제출물의 범위가 아니어서다. 준법·심사 화면은
 * 기존 플랫폼의 기능이라, 나란히 놓으면 무엇이 이번에 만든 것인지 흐려진다.
 *
 * <p>예전에는 넷 다 "AI" 로 시작했지만 셋은 상담 서비스의 챗봇을 열었고 하나는
 * 여신으로 갔다. 둘 다 이 배포에 없어 <b>네 칸이 전부 눌리지 않는 상태</b>였다.
 */
const QUICK_MENUS: QuickMenu[] = [
  {
    label: '이체를 시도한다', href: '/transfer/account',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="17 3 21 7 17 11"/>
        <line x1="21" y1="7" x2="3" y2="7"/>
        <polyline points="7 13 3 17 7 21"/>
        <line x1="3" y1="17" x2="21" y2="17"/>
      </svg>
    ),
  },
  {
    label: '막힌 이유를 본다', href: '/inquiry/transactions',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
        <polyline points="14 2 14 8 20 8"/>
        <line x1="8" y1="13" x2="16" y2="13"/>
        <line x1="8" y1="17" x2="13" y2="17"/>
      </svg>
    ),
  },
  {
    // 조사 콘솔. 직원 전용이라 로그인하지 않았으면 가드가 관리자 로그인으로 보내고,
    // returnUrl 로 이 화면을 기억했다가 되돌려 준다.
    label: 'AI 이상거래 조사', href: '/admin/fraud',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="11" cy="11" r="7"/>
        <line x1="16.5" y1="16.5" x2="21" y2="21"/>
        <path d="M11 8v3l2 1.5"/>
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
          <div className="grid grid-cols-3">
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
