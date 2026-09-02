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
 * 두 원이 겹치는 왼쪽 교점 — 지금은 <b>참고용</b>이다.
 *
 * <p>한동안 그림의 가로 중심을 이 교점(x 약 315)에 맞췄지만, 그러면 그림이
 * 사이드바 쪽으로 치우쳐 보였다. 지금은 아래 CONTENT_CENTER_RIGHT 를 쓴다.
 * 배경 원을 옮길 때 이미지 자리를 가늠하는 값으로만 남겨 둔다.
 */
const IMAGE_CENTER = leftIntersection(CIRCLE_BACK, CIRCLE_FRONT)
void IMAGE_CENTER

/** 그림이 화면에 보일 세로 크기. 3번 이미지가 그려지던 크기를 기준으로 잡았다. */
const CONTENT_HEIGHT = 295

/**
 * 그림의 가로 중심이 놓일 자리 — 화면 오른쪽 끝에서 잰 거리다.
 *
 * <p>2번 이미지를 한 번 다른 그림으로 갈아 끼웠다가 되돌린 적이 있는데, 그
 * <b>이전</b>에 지금과 같은 그림이 쓰이던 때의 자리다(당시 값 width 634 ·
 * right 130 → 그림 중심이 오른쪽에서 441px). 두 원의 교점(약 315)보다 126px
 * 더 왼쪽이고, 그만큼 텍스트와의 간격이 좁아져 배경 원 안에 들어와 보인다.
 */
const CONTENT_CENTER_RIGHT = 441

/**
 * 이미지마다 다른 두 가지 — 원본 캔버스 크기와, 그 안에서 <b>그림</b>(불투명
 * 영역)이 차지하는 사각형. PNG 를 직접 재서 넣는다.
 *
 * <p>투명 여백이 이미지마다 제각각이라, 캔버스만 보고 크기·위치를 맞추면 그림이
 * 저마다 다른 크기로 다른 자리에 놓인다. 실제로 맞춰야 하는 것은 캔버스가 아니라
 * 그림이므로, 계산의 출발점을 캔버스가 아닌 이 실측값으로 둔다.
 *
 * <p>{@code right} 를 준 이미지는 기준선 대신 그 값을 쓴다. <b>이미지를 갈아
 * 끼우면 여기 숫자도 다시 재야 한다</b>(불투명 영역 bbox).
 */
const HERO_IMAGE_SOURCES = [
  { canvas: { w: 672, h: 371 }, content: { left: 261, right: 522, top: 31, bottom: 354 } },
  { canvas: { w: 621, h: 402 }, content: { left: 150, right: 482, top: 43, bottom: 359 } },
  // 3번만 기준선을 따르지 않는다. 그림이 캔버스를 가로로 꽉 채운 구도라 중심을
  // 맞추면 왼쪽 끝이 텍스트를 덮는다. 지금 자리가 3번에는 맞다.
  { canvas: { w: 621, h: 402 }, content: { left: 12, right: 621, top: 63, bottom: 363 }, right: 85 },
]

/**
 * 실측값 → 실제로 쓸 크기와 가로 위치.
 *
 * <p>그림 세로가 CONTENT_HEIGHT 가 되도록 캔버스를 줄이고, 그림의 가로 중심이
 * CONTENT_CENTER_RIGHT 에 오도록 캔버스 오른쪽 여백만큼 되민다. 여백이 큰 이미지는
 * right 가 음수일 수 있는데, 화면 밖으로 나가는 것은 투명 여백뿐이고 부모가
 * overflow-hidden 이라 잘려도 보이지 않는다.
 */
const HERO_IMAGE_LAYOUT = HERO_IMAGE_SOURCES.map(({ canvas, content, right }) => {
  const scale = CONTENT_HEIGHT / (content.bottom - content.top)
  const centerToCanvasRight = (canvas.w - (content.left + content.right) / 2) * scale
  return {
    width: Math.round(canvas.w * scale),
    height: Math.round(canvas.h * scale),
    right: right ?? Math.round(CONTENT_CENTER_RIGHT - centerToCanvasRight),
  }
})

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
  const image = HERO_IMAGE_LAYOUT[current] ?? HERO_IMAGE_LAYOUT[HERO_IMAGE_LAYOUT.length - 1]

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


      {/* 히어로 이미지 — 크기·위치는 HERO_IMAGE_LAYOUT 이 정본이다. */}
      <div
        className="absolute -translate-y-1/2 pointer-events-none"
        style={{ right: image.right, top: HERO_HEIGHT / 2 }}
      >
        <Image
          src={`/images/personal-hero${current + 1}.png`}
          alt={slide.badge}
          width={image.width}
          height={image.height}
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
