'use client'
import { KB_MINT,KB_PRIMARY,KB_PRIMARY_BG } from '@/lib/theme'

import { useEffect, useCallback } from 'react'
import Link from 'next/link'
import Image from 'next/image'


/**
 * 히어로 슬라이드.
 *
 * <p><b>왜 셋 다 이상거래 조사인가.</b> 이 배포는 해커톤 제출용이고 심사위원에게
 * 주어지는 것은 URL 하나뿐이다. 안내 문서도 영상도 함께 가지 않으므로, 첫 화면에서
 * 무엇을 만들었는지 보이지 않으면 찾아가지 못한다.
 *
 * <p>세 장이 같은 말을 세 번 하지 않는다 — 조사가 어떻게 도는가(1), 무엇을 먼저
 * 보는가(2), 실행을 누가 통제하는가(3)로 갈린다.
 *
 * <p><b>링크가 /login 인 이유.</b> 조사 화면은 직원 전용이라 로그인이 필요하고,
 * returnUrl 을 달아 두면 로그인 직후 그 화면으로 이어진다. 바로 /admin/fraud 로
 * 보내면 가드가 로그인으로 되돌리면서 어디로 가려 했는지를 잃는다.
 *
 * <p>이미지는 순번으로 정해진다(/images/personal-heroN.png). 슬라이드를 늘리면
 * 그 번호의 파일도 함께 넣어야 한다.
 */
export const HERO_SLIDES = [
  {
    badge: '이상거래 조사',
    title: 'FDS 위험 신호를\n스스로 조사하는 AI 에이전트',
    desc: '다섯 가지 공격 가설을 경합시키며\n필요한 자료를 골라 단계적으로 검증합니다',
    cta: '이상거래 분석 보기',
    href: '/login?returnUrl=%2Fadmin%2Ffraud',
    bg: '#E8F7F3',
    accent: KB_PRIMARY,
    imageScale: 1,
  },
  {
    badge: '규제 준수',
    title: '규정상 확인이 필요한 거래를\n먼저 보는 책임 트리아지',
    desc: '사망·후견처럼 확인이 필요한 사실은\n이상 점수가 낮아도 큐 맨 앞에 둡니다',
    cta: '이상거래 분석 보기',
    href: '/login?returnUrl=%2Fadmin%2Ffraud',
    bg: '#EDF3FF',
    accent: '#1E3A8A',
    imageScale: 1,
  },
  {
    badge: '사람 승인',
    title: '에이전트는 권고만,\n실행은 사람 승인',
    desc: '권한이 검증된 담당자만 승인과 실행을 할 수 있고,\n그 과정은 기록됩니다',
    cta: '이상거래 분석 보기',
    href: '/login?returnUrl=%2Fadmin%2Ffraud',
    bg: KB_PRIMARY_BG,
    accent: KB_MINT,
    imageScale: 1,
  },
]

/**
 * 장식과 이미지가 공유하는 기준점. 히어로 오른쪽 끝에서 이만큼 떨어진 지점이며,
 * 세로는 한가운데다.
 *
 * <p>여기를 옮기면 원과 이미지가 함께 움직인다. 따로 맞출 필요가 없다.
 */
const ART_ANCHOR_RIGHT = 470

/** 두 원이 기준점에서 벌어지는 거리. 대칭이라 겹치는 한가운데가 기준점이 된다. */
const CIRCLE_SPREAD_X = 40
const CIRCLE_SPREAD_Y = 100

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

      {/* 배경 장식 · 히어로 이미지 — 기준점 하나를 공유한다.
          예전에는 원과 이미지가 서로 다른 좌표계에서 각자 px 로 놓여 있었다.
          원 중심은 오른쪽에서 140·220px, 이미지 중심은 480px 이라 300px 넘게
          어긋났고, 원이 배경이 아니라 따로 노는 장식으로 보였다.
          지금은 셋 다 이 한 점을 기준으로 놓이므로 어긋날 수가 없다. */}
      <div className="absolute pointer-events-none" style={{ right: ART_ANCHOR_RIGHT, top: '50%' }}>

        {/* 두 원은 기준점을 사이에 두고 대칭이다 — 그래서 겹치는 한가운데가
            기준점과 정확히 일치한다. 오프셋 부호만 반대다. */}
        <div className="absolute rounded-full opacity-20"
          style={{
            width: 400, height: 400, left: -CIRCLE_SPREAD_X, top: -CIRCLE_SPREAD_Y,
            transform: 'translate(-50%, -50%)', backgroundColor: slide.accent,
          }} />
        <div className="absolute rounded-full opacity-10"
          style={{
            width: 280, height: 280, left: CIRCLE_SPREAD_X, top: CIRCLE_SPREAD_Y,
            transform: 'translate(-50%, -50%)', backgroundColor: slide.accent,
          }} />

        <div className="absolute" style={{ transform: 'translate(-50%, -50%)' }}>
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
            {slide.cta}
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
