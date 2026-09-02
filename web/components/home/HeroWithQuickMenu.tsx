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
  /**
   * 순서 표시("시연 1"). 라벨과 <b>합치지 않는다.</b>
   *
   * <p>합치면 라벨이 "시연 3 AI 이상거래 조사" 가 되어, 'AI' 로 시작하는 메뉴에
   * 실제 에이전트가 있는지 보는 검사(AiMenuBackingTest)가 그 항목을 못 읽는다.
   * 검사가 조용히 무력해지는 것을 막으려고 필드를 나눈다.
   */
  step: string
  label: string
  icon: React.ReactNode
  /** 링크로 가는 것 */
  href?: string
  /** 챗봇을 열고 이 질문을 보내는 것 */
  ask?: string
  /**
   * 카드 아래 한 줄 설명. <b>눌러서 바로 보이지 않는 것</b>만 적는다.
   *
   * <p>위험 안내는 신호 둘(반복 이체 + 고액)이 쌓여야 뜬다. 조건을 모르면 이체
   * 화면만 보고 지나가므로, 그 조건을 카드에 적는다.
   */
  hint?: string
}

/**
 * 퀵메뉴 — <b>기능이 아니라 순서</b>다.
 *
 * <p>상단 탭과 드롭다운이 이미 기능별로 나뉘어 있다. 같은 화면을 여기 한 번 더
 * 늘어놓으면 자리만 차지한다. 그래서 이 자리는 <b>무엇부터 해보면 되는가</b>를 놓는다 —
 * 처음 온 사람이 위에서 아래로 누르기만 하면 시연 동선이 그대로 재현된다.
 *
 * <p>칸 순서는 <b>시연 순서 그대로</b>다 — 정상 이체 → 위험 안내 → 차단 확인 →
 * 조사. 앞의 셋은 고객 화면, 마지막이 직원 화면이다. 한동안 위험 안내가 빠져 있었는데,
 * 그것이 고객이 실제로 보는 유일한 이상거래 화면이라 시연에서 빠지면 이 제출물의
 * 절반이 안 보인다. 넷째로 준법·심사 화면을 넣지 않는 이유는 그대로다 — 기존
 * 플랫폼의 기능이라, 나란히 놓으면 무엇이 이번에 만든 것인지 흐려진다.
 *
 * <p>예전에는 넷 다 "AI" 로 시작했지만 셋은 상담 서비스의 챗봇을 열었고 하나는
 * 여신으로 갔다. 둘 다 이 배포에 없어 <b>네 칸이 전부 눌리지 않는 상태</b>였다.
 */
const QUICK_MENUS: QuickMenu[] = [
  {
    step: '시연 1', label: '정상 이체',
    href: '/transfer/account',
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
    // 받는 계좌와 금액을 미리 채워 보낸다. 심사자가 채워야 할 칸이 남아 있으면
    // 그 자리에서 시연이 끊긴다. 조사 큐에 남는 케이스도 대본과 같은 값이 된다.
    step: '시연 2', label: '위험 안내 받기',
    href: '/transfer/account?to=001-2000-0000017&bank=AXful&amount=12000000',
    hint: '소액을 여러 번 이체한 뒤 1천만원 이상을 시도하면 위험 안내가 표시됩니다',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="#1F2937" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 3l9 16H3l9-16z"/>
        <line x1="12" y1="9" x2="12" y2="13"/>
        <line x1="12" y1="16" x2="12.01" y2="16"/>
      </svg>
    ),
  },
  {
    step: '시연 3', label: '거래 차단 확인',
    href: '/inquiry/transactions',
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
    step: '시연 4', label: 'AI 이상거래 조사',
    href: '/admin/fraud',
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
          <div className="grid grid-cols-4">
            {QUICK_MENUS.map((menu, idx) => {
              const inner = (
                <>
                  {idx > 0 && (
                    <div className="absolute left-0 top-1/4 bottom-1/4 w-px bg-gray-100" />
                  )}
                  <div className="w-12 h-12 flex-shrink-0 flex items-center justify-center group-hover:scale-110 transition-transform duration-150">
                    {menu.icon}
                  </div>
                  <div className="flex flex-col items-start gap-0.5">
                    <span className="text-[12px] font-bold tracking-wide text-kb-text-muted group-hover:text-kb-primary transition-colors">
                      {menu.step}
                    </span>
                    <p className="text-[18px] font-semibold text-kb-text group-hover:text-kb-primary transition-colors whitespace-nowrap">{menu.label}</p>
                    {menu.hint && (
                      <p className="text-[11px] leading-snug text-kb-text-muted max-w-[190px]">{menu.hint}</p>
                    )}
                  </div>
                </>
              )
              // 아이콘을 위가 아니라 <b>왼쪽</b>에 둔다. 라벨이 두 줄(순서·이름)이라
              // 세로로 쌓으면 아이콘까지 세 층이 되어 띠가 두꺼워진다.
              //
              // 세로 여백은 넉넉히 준다. 가로로 눕히면 내용 높이가 절반이 되는데
              // 여백까지 줄이면 띠가 눌린 것처럼 보인다 — 한 줄이던 시절의 높이가
              // 이 자리에 맞았다.
              const cls = "flex items-center justify-center gap-3 px-4 py-12 hover:bg-kb-primary-bg transition-colors duration-150 group relative"

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
