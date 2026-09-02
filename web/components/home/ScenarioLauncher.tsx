'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'

/**
 * 시나리오 체험 진입점 — 화면 구석에 뜨는 작은 버튼.
 *
 * <p><b>왜 본문이 아니라 여기인가.</b> 시연 동선은 홈 퀵메뉴 네 칸에 그대로 있다.
 * 그 위에 "체험하기" 머리말을 얹어 봤더니, 일반 금융 메뉴 사이에 안내문이 끼어
 * 본문이 어수선해졌다. 그래서 <b>안내는 화면 밖으로 빼고 메뉴는 그대로</b> 둔다.
 *
 * <p><b>무엇을 하는가.</b> 새 화면을 만들지 않는다. 퀵메뉴 자리로 스크롤해 잠깐
 * 강조할 뿐이다(<code>#demo-scenario</code>). 같은 목록을 여기 한 번 더 그리면
 * 나중에 한쪽만 고쳐지고, 심사자는 어느 쪽이 진짜인지 알 수 없게 된다.
 *
 * <p>자리는 챗봇 버튼(bottom-6 right-20) 바로 위다. 오른쪽 가장자리는
 * FloatingSidebar(80px)가 쓰고 있어 그 안쪽에 선다.
 */
export default function ScenarioLauncher() {
  const pathname = usePathname()

  /**
   * 이미 홈에 있으면 라우터를 태우지 않는다.
   *
   * <p>앱 라우터는 같은 페이지 해시 이동을 자기 방식으로 처리해서 hashchange 가
   * 뜨지 않는다 — 주소만 바뀌고 강조는 안 걸린다. 그래서 홈에서는 이벤트로 알린다
   * (챗봇을 여는 방식과 같다). 다른 화면에서는 평범한 링크로 홈에 가고, 홈이 뜨면서
   * 해시를 보고 스스로 강조한다.
   */
  function onClick(e: React.MouseEvent) {
    if (pathname !== '/') return
    e.preventDefault()
    history.replaceState(null, '', '#demo-scenario')
    window.dispatchEvent(new CustomEvent('axful:show-scenario'))
  }

  return (
    <Link
      href="/#demo-scenario"
      onClick={onClick}
      className="fixed bottom-24 right-20 z-[310] flex items-center gap-2 rounded-full bg-kb-primary px-4 py-2.5 text-white shadow-xl transition hover:opacity-90"
      aria-label="AI 에이전트 시나리오 체험 메뉴로 이동"
    >
      <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polygon points="6 4 20 12 6 20 6 4" />
      </svg>
      <span className="text-[13px] font-bold whitespace-nowrap">시나리오 체험</span>
    </Link>
  )
}
