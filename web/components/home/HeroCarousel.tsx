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
 * <p><b>링크는 조사 화면을 바로 가리킨다.</b> 로그인 주소를 적어 두지 않는다 —
 * 이미 로그인한 사람까지 로그인 화면을 거치게 되고, 로그인 경로가 바뀌면 여기도
 * 함께 고쳐야 한다.
 *
 * <p>로그인하지 않았으면 가드가 관리자 로그인으로 보내고 returnUrl 로 이 화면을
 * 기억한다(AdminGuard.loginPathFor). 로그인 뒤 여기로 돌아온다.
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
    href: '/admin/fraud',
    bg: '#E8F7F3',
    accent: KB_PRIMARY,
  },
  {
    badge: '규제 준수',
    title: '규정상 확인이 필요한 거래를\n먼저 보는 책임 트리아지',
    desc: '사망·후견처럼 확인이 필요한 사실은\n이상 점수가 낮아도 큐 맨 앞에 둡니다',
    cta: '이상거래 분석 보기',
    href: '/admin/fraud',
    bg: '#EDF3FF',
    accent: '#1E3A8A',
  },
  {
    badge: '사람 승인',
    title: '에이전트는 권고만,\n실행은 사람 승인',
    desc: '권한이 검증된 담당자만 승인과 실행을 할 수 있고,\n그 과정은 기록됩니다',
    cta: '이상거래 분석 보기',
    href: '/admin/fraud',
    bg: KB_PRIMARY_BG,
    accent: KB_MINT,
  },
]

const HERO_HEIGHT = 400
/**
 * 배경 원 둘.
 *
 * <p>{@code x} 는 화면 오른쪽 끝에서 중심까지의 거리다 — <b>클수록 왼쪽</b>이다.
 * {@code y} 는 히어로 위에서 중심까지다. 원을 그리는 것도, 아래에서 교점을 구하는
 * 것도 이 값 하나를 쓴다. 예전에는 CSS 클래스에 좌표가 박혀 있어 원을 옮기면
 * 이미지 위치를 손으로 다시 맞춰야 했다.
 */
const CIRCLE_BACK = { x: 140, y: 140, r: 200, opacity: 0.2 }
const CIRCLE_FRONT = { x: 220, y: 340, r: 140, opacity: 0.1 }

type Circle = typeof CIRCLE_BACK

/** 이미지 상자 너비. 실제 그림 크기는 여기에 맞춰 object-contain 으로 들어간다. */
const IMAGE_BOX_WIDTH = 600

/**
 * 두 원이 만나는 두 점 중 <b>화면에서 더 왼쪽</b>에 있는 점.
 *
 * <p>x 는 오른쪽 끝에서 잰 거리라 큰 쪽이 왼쪽이다. 두 교점은 중심을 잇는 선을
 * 기준으로 대칭이므로, 그 선에 수직인 두 방향 중 x 가 커지는 쪽을 고른다.
 */
function leftIntersection(a: Circle, b: Circle): { x: number; y: number } {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const d = Math.hypot(dx, dy)
  // a 중심에서 두 교점을 잇는 선까지의 거리
  const t = (d * d + a.r * a.r - b.r * b.r) / (2 * d)
  // 그 선 위 지점에서 교점까지의 거리
  const h = Math.sqrt(Math.max(0, a.r * a.r - t * t))
  const mx = a.x + (t * dx) / d
  const my = a.y + (t * dy) / d
  return { x: mx + (h * dy) / d, y: my - (h * dx) / d }
}

/**
 * 이미지 중심이 놓일 자리 — 두 원이 겹치는 왼쪽 교점.
 *
 * <p>좌표를 손으로 맞추지 않는다. 원(CIRCLE_BACK·CIRCLE_FRONT)을 옮기면 이미지가
 * 따라온다. 예전에는 둘이 서로 다른 px 로 놓여 있어, 원을 건드릴 때마다 이미지를
 * 다시 맞춰야 했다.
 */
const IMAGE_CENTER = leftIntersection(CIRCLE_BACK, CIRCLE_FRONT)

// 텍스트 단이 놓이는 규격. tailwind.config 의 kb-container 와 히어로 마크업에서 온다.
const KB_CONTAINER = 1280
const CONTAINER_PADDING = 32
const TEXT_MAX_WIDTH = 560

/** 교점에 맞췄을 때의 오른쪽 여백. 여기서부터 왼쪽으로 민다. */
const IMAGE_RIGHT_BASE = IMAGE_CENTER.x - IMAGE_BOX_WIDTH / 2

/**
 * 텍스트와 이미지 사이 공백의 <b>절반만큼</b> 이미지를 왼쪽으로 민다.
 *
 * <p>화면 폭 W 에서
 * <pre>
 *   텍스트 오른쪽 끝 = (W - 1280)/2 + 32 + 560 = W/2 - 48
 *   이미지 왼쪽 끝   = W - IMAGE_RIGHT_BASE - 600
 *   공백            = W/2 - IMAGE_RIGHT_BASE - 552
 * </pre>
 * 1920 에서 393px 이고, 그 절반인 196.5px 을 민다. 그러면 이미지가 텍스트와 화면
 * 오른쪽 끝 사이에 거의 가운데로 놓인다.
 *
 * <p><b>고정 px 로 두지 않는 이유.</b> 공백이 화면 폭을 따라 변한다. 1920 에서 맞춘
 * 값을 박아 두면 다른 폭에서는 다시 어긋난다. 그래서 폭의 함수로 쓴다.
 *
 * <p>{@code max()} 는 바닥이다. 폭이 좁아 공백이 음수가 되면 이미지가 원래보다
 * 오른쪽으로 밀려 잘리므로, 교점 위치보다 오른쪽으로는 가지 않게 막는다.
 */
const IMAGE_RIGHT_OFFSET =
  (IMAGE_BOX_WIDTH + CONTAINER_PADDING + TEXT_MAX_WIDTH - KB_CONTAINER / 2) / 2
  - IMAGE_RIGHT_BASE / 2

const IMAGE_RIGHT = `max(${IMAGE_RIGHT_BASE}px, calc(25% - ${IMAGE_RIGHT_OFFSET}px))`

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
      style={{ backgroundColor: slide.bg, height: HERO_HEIGHT }}>

      {/* 배경 장식 — 좌표는 CIRCLE_BACK · CIRCLE_FRONT 가 정본이다. */}
      <div className="absolute right-0 top-0 bottom-0 w-1/2 pointer-events-none overflow-hidden">
        {[CIRCLE_BACK, CIRCLE_FRONT].map((c, i) => (
          <div key={i} className="absolute rounded-full"
            style={{
              width: c.r * 2, height: c.r * 2,
              right: c.x - c.r, top: c.y - c.r,
              opacity: c.opacity, backgroundColor: slide.accent,
            }} />
        ))}
      </div>


      {/* 히어로 이미지.
          가로는 두 원의 왼쪽 교점에 맞추고, 세로는 히어로 한가운데에 둔다.
          교점의 y(237)를 그대로 쓰면 위아래 여백이 237 대 163 으로 어긋나 보인다 —
          기하학적으로는 맞아도 눈에는 아래로 처진 것으로 읽힌다. */}
      <div className="absolute -translate-y-1/2 pointer-events-none"
        style={{ right: IMAGE_RIGHT, top: HERO_HEIGHT / 2 }}>
        <Image
          src={`/images/personal-hero${current + 1}.png`}
          alt={slide.badge}
          width={IMAGE_BOX_WIDTH}
          height={380}
          className="object-contain drop-shadow-lg"
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
