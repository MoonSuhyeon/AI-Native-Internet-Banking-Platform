'use client'
import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER } from '@/lib/theme'

import { useState } from 'react'
import Link from 'next/link'

const ALL_ITEMS = [
  {
    type: '새소식',
    title: 'AXful AI 금융비서 「AX Assistant」 정식 출시 안내',
    date: '2026.05.15',
    href: '/support/notice/ax-assistant',
  },
  {
    type: '새소식',
    title: 'AI 기반 실시간 이상거래 탐지 시스템 전면 도입 안내',
    date: '2026.05.10',
    href: '/support/notice/ai-fraud-detection',
  },
  {
    type: '이벤트',
    title: 'AI 포트폴리오 추천 서비스 론칭 기념 특별 금리 이벤트',
    date: '2026.05.01 ~ 2026.07.31',
    href: '/support/event/ai-portfolio-launch',
  },
  {
    type: '이벤트',
    title: 'AX 뱅킹 앱 리뉴얼 기념 AI 챌린지 이벤트',
    date: '2026.04.20 ~ 2026.06.30',
    href: '/support/event/ax-banking-renewal',
  },
]

const TABS = ['전체', '새소식', '이벤트'] as const
type Tab = typeof TABS[number]

export default function NewsListPage() {
  const [activeTab, setActiveTab] = useState<Tab>('전체')

  const filtered = activeTab === '전체'
    ? ALL_ITEMS
    : ALL_ITEMS.filter(item => item.type === activeTab)

  return (
    <main className="min-h-screen" style={{ backgroundColor: '#F8FDFB' }}>
      <div className="max-w-kb-container mx-auto px-8 py-12">

        {/* 브레드크럼 */}
        <div className="flex items-center gap-2 text-[12px] text-kb-text-muted mb-6">
          <Link href="/" className="hover:text-kb-text transition-colors">홈</Link>
          <span>›</span>
          <span>고객센터</span>
          <span>›</span>
          <span className="font-semibold text-kb-text">새소식 / 이벤트</span>
        </div>

        {/* 페이지 제목 */}
        <h1 className="text-[24px] font-bold text-kb-text mb-2">새소식 / 이벤트</h1>
        <p className="text-[13px] text-kb-text-muted mb-8">AXful Bank의 최신 소식과 이벤트를 확인하세요.</p>

        {/* 탭 */}
        <div className="flex gap-0 mb-6 border-b-2 border-kb-primary-border">
          {TABS.map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className="px-6 py-3 text-[14px] font-semibold transition-colors relative"
              style={{
                color: activeTab === tab ? KB_PRIMARY : '#94A3B8',
              }}
            >
              {tab}
              {activeTab === tab && (
                <span
                  className="absolute bottom-[-2px] left-0 right-0 h-[2px]"
                  style={{ backgroundColor: KB_PRIMARY }}
                />
              )}
            </button>
          ))}
        </div>

        {/* 목록 */}
        <div className="bg-white rounded-2xl shadow-sm overflow-hidden" style={{ border: `1px solid ${KB_MINT}20` }}>
          {filtered.length === 0 ? (
            <p className="text-center text-kb-text-muted py-16 text-[14px]">해당하는 게시물이 없습니다.</p>
          ) : (
            <ul className="divide-y" style={{ borderColor: KB_PRIMARY_BORDER }}>
              {filtered.map((item, idx) => (
                <li key={idx}>
                  <Link
                    href={item.href}
                    className="flex items-center gap-4 px-6 py-5 transition-colors group hover:bg-kb-primary-bg"
                  >
                    {/* 배지 */}
                    <span
                      className="flex-shrink-0 text-[11px] font-bold px-2.5 py-1 rounded-full"
                      style={
                        item.type === '새소식'
                          ? { backgroundColor: KB_PRIMARY_BG, color: KB_PRIMARY, border: '1px solid #0D5C4730' }
                          : { backgroundColor: '#E8FAF5', color: KB_MINT, border: '1px solid #5BC9A840' }
                      }
                    >
                      {item.type}
                    </span>

                    {/* 제목 */}
                    <span className="flex-1 text-[14px] text-kb-text-body group-hover:text-kb-primary transition-colors font-medium">
                      {item.title}
                    </span>

                    {/* 날짜 */}
                    <span className="text-[12px] text-kb-text-muted flex-shrink-0">{item.date}</span>

                    {/* 화살표 */}
                    <span
                      className="flex-shrink-0 w-6 h-6 rounded-full flex items-center justify-center text-[12px] font-bold transition-colors"
                      style={{ backgroundColor: KB_PRIMARY_BG, color: KB_PRIMARY }}
                    >
                      ›
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>

      </div>
    </main>
  )
}
