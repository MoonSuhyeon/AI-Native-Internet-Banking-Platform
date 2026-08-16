'use client'
import { KB_MINT,KB_PRIMARY,KB_PRIMARY_BG } from '@/lib/theme'

import { useEffect, useCallback } from 'react'
import Link from 'next/link'
import Image from 'next/image'


export const HERO_SLIDES = [
  {
    badge: 'AI 예금 추천',
    title: 'AI가 분석한\n나만의 예금 전략',
    desc: '수천만 건의 금융 데이터로 학습한 AI가\n내 패턴에 딱 맞는 최적의 예금상품을 추천합니다',
    href: '/products/deposit/list',
    bg: '#E8F7F3',
    accent: KB_PRIMARY,
    tag: '수신 AI',
    imageScale: 1,
  },
  {
    badge: 'AI 대출 심사',
    title: '편견 없는 AI의\n공정한 대출 심사',
    desc: '사람의 편견을 제거한 AI 심사로\n더 많은 고객이 합리적인 금융 혜택을 누립니다',
    href: '/loans/apply',
    bg: '#EDF3FF',
    accent: '#1E3A8A',
    tag: '여신 AI',
    imageScale: 1.15,
  },
  {
    badge: 'AI 금융 관리',
    title: '선제적으로 움직이는\nAI 금융 파트너',
    desc: '시장 변화를 먼저 읽고 알려주는 AI가\n내 금융 자산을 더 스마트하게 지킵니다',
    href: '/products/loan/status',
    bg: KB_PRIMARY_BG,
    accent: KB_MINT,
    tag: '사후관리 AI',
    imageScale: 1.2,
  },
]

interface HeroCarouselProps {
  current: number
  paused: boolean
  onChangeTo: (index: number) => void
  onPausedChange: (paused: boolean) => void
}

export default function HeroCarousel({ current, paused, onChangeTo, onPausedChange }: HeroCarouselProps) {
  const next = useCallback(() => onChangeTo((current + 1) % HERO_SLIDES.length), [onChangeTo, current])
  const prev = () => onChangeTo((current - 1 + HERO_SLIDES.length) % HERO_SLIDES.length)

  useEffect(() => {
    if (paused) return
    const timer = setInterval(next, 5000)
    return () => clearInterval(timer)
  }, [paused, next])

  const slide = HERO_SLIDES[current]

  return (
    <div className="relative w-full overflow-hidden transition-colors duration-700"
      style={{ backgroundColor: slide.bg, height: '400px' }}>

      {/* 배경 장식 */}
      <div className="absolute right-0 top-0 bottom-0 w-1/2 pointer-events-none overflow-hidden">
        <div className="absolute right-[-60px] top-[-60px] w-[400px] h-[400px] rounded-full opacity-20"
          style={{ backgroundColor: slide.accent }} />
        <div className="absolute right-[80px] bottom-[-80px] w-[280px] h-[280px] rounded-full opacity-10"
          style={{ backgroundColor: slide.accent }} />
      </div>

      {/* 히어로 이미지 */}
      <div className="absolute right-[180px] top-1/2 -translate-y-1/2 pointer-events-none">
        <Image
          src={`/images/personal-hero${current + 1}.png`}
          alt={slide.badge}
          width={600}
          height={380}
          className="object-contain drop-shadow-lg"
          style={{ transform: `scale(${slide.imageScale})` }}
          priority
        />
      </div>


      <div className="max-w-kb-container mx-auto px-8 h-full flex items-center">
        <div className="flex flex-col gap-4 max-w-[560px]">

          {/* 배지 */}
          <span className="inline-block self-start px-4 py-1.5 text-[14px] font-bold text-white rounded-full"
            style={{ backgroundColor: slide.accent }}>
            {slide.badge}
          </span>

          {/* 제목 */}
          <h2 className="text-[40px] font-bold text-kb-text leading-tight whitespace-pre-line tracking-tight">
            {slide.title}
          </h2>

          {/* 설명 */}
          <p className="text-[16px] text-kb-text-body whitespace-pre-line">{slide.desc}</p>

          {/* 버튼 */}
          <Link href={slide.href}
            className="self-start mt-1 px-6 py-2.5 text-[15px] font-bold text-white rounded-full transition-opacity hover:opacity-85"
            style={{ backgroundColor: slide.accent }}>
            자세히 보기
          </Link>

          {/* 네비게이션 */}
          <div className="flex items-center gap-2 mt-3">
            <button onClick={prev} className="text-kb-text-muted hover:text-kb-text text-lg leading-none">‹</button>
            {HERO_SLIDES.map((_, i) => (
              <button key={i} onClick={() => onChangeTo(i)}
                className="rounded-full transition-all duration-300"
                style={{
                  width: i === current ? 24 : 8,
                  height: 8,
                  backgroundColor: i === current ? slide.accent : '#CBD5E1',
                }} />
            ))}
            <button onClick={next} className="text-kb-text-muted hover:text-kb-text text-lg leading-none">›</button>
            <button onClick={() => onPausedChange(!paused)}
              className="ml-1 text-kb-text-muted hover:text-kb-text text-xs">
              {paused ? '▶' : '⏸'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
