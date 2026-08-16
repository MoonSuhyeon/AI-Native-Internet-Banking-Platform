'use client'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import { KB_GNB_BIZ_ACTIVE } from '@/lib/theme'

const SLIDES = [
  {
    badge: '다온 개인금융',
    title: '당신의 일상에 든든하게,\n다온은행이 함께합니다',
    desc: '예금부터 대출, 자산관리까지\n다온 개인금융 서비스를 소개합니다.',
  },
  {
    badge: '비대면전용 신용대출',
    title: '은행방문이 필요없는\n간편 신용대출',
    desc: '다온 직장인 신용대출을 소개합니다!',
  },
  {
    badge: '다온 첫거래 우대',
    title: '첫 거래 고객님께 드리는\n특별한 금리 혜택',
    desc: '예적금 우대금리와 수수료 면제까지\n첫 거래 고객 전용 혜택을 누려보세요!',
  },
]

const BADGE_BG = '#384d84'

export default function DaonHeroCarousel() {
  const [current, setCurrent] = useState(0)
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    if (paused) return
    const t = setInterval(() => setCurrent((c) => (c + 1) % SLIDES.length), 4500)
    return () => clearInterval(t)
  }, [paused])

  const slide = SLIDES[current]

  return (
    <section
      className="overflow-hidden bg-white"
      style={{ height: '400px' }}
    >
      <div className="max-w-kb-container mx-auto px-6 h-full flex items-center justify-between">
        <div className="pl-14 flex flex-col gap-3 max-w-[50%] pt-14">

          {/* 제목 — 고정 높이로 배지 위치 고정 */}
          <p
            className="text-[26px] font-normal text-kb-text whitespace-pre-line leading-snug overflow-hidden"
            style={{ height: '76px' }}
          >
            {slide.title}
          </p>

          {/* 배지 */}
          <span
            style={{
              display: 'inline-block',
              alignSelf: 'flex-start',
              height: '52px',
              lineHeight: '52px',
              padding: '0 20px',
              backgroundColor: BADGE_BG,
              color: '#fff',
              fontWeight: 700,
              fontSize: '34px',
              whiteSpace: 'nowrap',
              borderRadius: '0 8px 0 8px',
            }}
          >
            {slide.badge}
          </span>

          {/* 설명 */}
          <p className="text-base text-kb-text-body leading-relaxed whitespace-pre-line">
            {slide.desc}
          </p>

          {/* 바로가기 */}
          <Link href="#" className="text-base text-kb-text underline mt-1">바로가기</Link>

          {/* 컨트롤 */}
          <div className="flex items-center gap-2 mt-2">
            <button onClick={() => setCurrent((c) => (c - 1 + SLIDES.length) % SLIDES.length)}
              className="w-6 h-6 flex items-center justify-center text-kb-text-muted hover:text-kb-text">‹</button>
            {SLIDES.map((_, i) => (
              <button key={i} onClick={() => setCurrent(i)}
                className={`rounded-full transition-all ${i === current ? 'w-5 h-2 bg-kb-text' : 'w-2 h-2 bg-kb-text/30'}`} />
            ))}
            <button onClick={() => setCurrent((c) => (c + 1) % SLIDES.length)}
              className="w-6 h-6 flex items-center justify-center text-kb-text-muted hover:text-kb-text">›</button>
            <button onClick={() => setPaused(!paused)}
              className="w-6 h-6 flex items-center justify-center text-kb-text-muted hover:text-kb-text text-xs">
              {paused ? '▶' : '⏸'}
            </button>
          </div>

        </div>

        {/* 이미지 영역 — 네이비 그라데이션 일러스트 */}
        <div className="relative flex-shrink-0 mr-52 rounded-2xl overflow-hidden flex items-center justify-center"
          style={{ width: '400px', height: '330px', background: `linear-gradient(135deg, ${KB_GNB_BIZ_ACTIVE} 0%, #384d84 60%, #5a73a8 100%)` }}>
          <div className="text-center text-white">
            <p className="text-[64px] font-extrabold tracking-tight leading-none">DAON</p>
            <p className="text-[18px] font-medium tracking-[0.3em] mt-2 text-white/80">PERSONAL BANK</p>
            <div className="mt-6 mx-auto w-16 h-1 rounded-full bg-white/50" />
          </div>
        </div>

      </div>
    </section>
  )
}
