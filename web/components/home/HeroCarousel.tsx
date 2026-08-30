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
 * 히어로 이미지 상자의 오른쪽 여백(px). **작을수록 오른쪽으로 간다.**
 *
 * <p>상자 너비가 600 이므로 이미지 중심은 오른쪽 끝에서 {@code IMAGE_RIGHT + 300}
 * 지점에 온다. 배경 원은 이 값을 따라오지 않는다 — 원은 오른쪽 끝에 고정이다.
 *
 * <p>원과 이미지를 한 기준점에 묶어 본 적이 있는데, 그러면 이미지를 옮길 때마다
 * 원이 따라와 배경이 통째로 움직였다. 둘은 하는 일이 달라 좌표도 따로 둔다.
 */
const IMAGE_RIGHT = 130

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

      {/* 배경 장식 — 오른쪽 끝에 고정. 이미지를 옮겨도 따라오지 않는다. */}
      <div className="absolute right-0 top-0 bottom-0 w-1/2 pointer-events-none overflow-hidden">
        <div className="absolute right-[-60px] top-[-60px] w-[400px] h-[400px] rounded-full opacity-20"
          style={{ backgroundColor: slide.accent }} />
        <div className="absolute right-[80px] bottom-[-80px] w-[280px] h-[280px] rounded-full opacity-10"
          style={{ backgroundColor: slide.accent }} />
      </div>

      {/* 히어로 이미지 — 가로 위치는 IMAGE_RIGHT 하나로 정한다. */}
      <div className="absolute top-1/2 -translate-y-1/2 pointer-events-none"
        style={{ right: IMAGE_RIGHT }}>
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
