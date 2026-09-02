'use client'

import { usePathname } from 'next/navigation'
import FloatingSidebar from '@/components/layout/FloatingSidebar'
import ChatbotWidget from '@/components/chatbot/ChatbotWidget'
import ScenarioLauncher from '@/components/home/ScenarioLauncher'
import { DEPLOYED } from '@/lib/deployed-services'

/**
 * 전역 플로팅 UI(개인홈 사이드바 + 챗봇) 마운트 게이트.
 * 다온은행(/other-bank)에서는 자체 사이드바(DaonMyMenu)만 쓰므로 전역 UI를 숨긴다.
 * admin 콘솔(/admin)은 자체 AdminSidebar를 쓰므로 고객용 전역 UI를 숨긴다.
 * ★ FloatingSidebar/ChatbotWidget 컴포넌트 자체는 수정하지 않음(팀 자산).
 */
export default function GlobalChrome() {
  const pathname = usePathname()
  if (pathname?.startsWith('/other-bank') || pathname?.startsWith('/admin')) return null

  return (
    <>
      <FloatingSidebar />
      {/* 시연 진입점. 본문을 어지르지 않도록 구석에 둔다 — 누르면 홈 퀵메뉴로 간다. */}
      <ScenarioLauncher />
      {/*
        챗봇은 상담 서비스(consultation-service)를 부른다. 그 서비스가 배포에 없으면
        위젯은 열리는데 답이 오지 않는다 — 없는 것보다 나쁜 상태다. 사용자는 자기가
        뭘 잘못했는지 알 수 없고, 기다리다 서비스 전체를 의심하게 된다.
      */}
      {DEPLOYED.consultation && <ChatbotWidget />}
    </>
  )
}
