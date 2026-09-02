'use client'

import Link from 'next/link'
import { QUICK_MENUS } from './HeroWithQuickMenu'
import { KB_MINT, KB_PRIMARY_BG } from '@/lib/theme'

/**
 * 로그인 홈의 기능 탐색 줄.
 *
 * <p><b>왜 필요한가.</b> 홈은 로그인 여부로 화면이 통째로 갈린다 — 로그인하면
 * 히어로 캐러셀도 퀵메뉴도 렌더되지 않는다. 그래서 이체 한 번 하고 홈으로 돌아온
 * 사람은 그다음으로 갈 길을 잃었다. 주소를 직접 치거나 로그아웃해야 다시 보였다.
 *
 * <p><b>비로그인 홈을 복제하지 않는다.</b> 목록은 QUICK_MENUS 하나뿐이고 여기서는
 * 작은 줄로만 그린다. 같은 것을 두 벌 적어 두면 한쪽만 고쳐지고, 어느 쪽이 진짜인지
 * 알 수 없게 된다. 큰 카드를 그대로 옮겨 오면 로그인 홈이 광고판처럼 보이기도 한다.
 */
export default function ServiceScenarioMenu() {
  return (
    <section className="py-6 bg-white">
      <div className="max-w-kb-container mx-auto px-8">
        <div className="rounded-2xl px-6 py-5" style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}30` }}>
          <h2 className="text-[16px] font-bold text-kb-text mb-3">AXful Bank 주요 기능</h2>
          <div className="grid grid-cols-4 gap-2">
            {QUICK_MENUS.map(menu => (
              <Link
                key={menu.label}
                href={menu.href ?? '/'}
                className="flex items-center gap-2.5 rounded-xl bg-white px-4 py-3 border transition-colors hover:bg-kb-primary-bg"
                style={{ borderColor: `${KB_MINT}40` }}
              >
                <span className="text-[14px] font-semibold text-kb-text whitespace-nowrap">{menu.label}</span>
              </Link>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
