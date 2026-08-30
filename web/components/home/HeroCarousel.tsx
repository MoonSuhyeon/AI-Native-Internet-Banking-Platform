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
 * <p>배경 원(CIRCLE_BACK·CIRCLE_FRONT)의 장식용 좌표로만 쓴다. 이미지 자체의
 * 가로 위치는 더 이상 이 교점을 따라가지 않는다(아래 IMAGE_RIGHT 참고) — 필요하면
 * 세로 중심을 맞출 때 참고 값으로만 남겨 둔다.
 */
const IMAGE_CENTER = leftIntersection(CIRCLE_BACK, CIRCLE_FRONT)
void IMAGE_CENTER // 세로 위치는 HERO_HEIGHT/2 로 고정하므로 현재는 참고용

/**
 * 이미지 오른쪽 끝 — 화면의 <b>실제 오른쪽 끝</b>에 거의 붙인다.
 *
 * <p><b>예전에는</b> 텍스트와 이미지 사이 공백의 절반만큼 이미지를 왼쪽으로 미는
 * 공식(교점 기준 + 화면폭의 25%)을 썼다. 화면이 넓어질수록 이미지를 텍스트 쪽으로
 * 당겨서 "이미지가 화면 오른쪽에 외따로 뜨는 것"은 막았지만, 그 대가로 1920px
 * 화면에서 이미지 오른쪽 끝과 화면 끝 사이에 ~210px 짜리 배경색 띠가 남았다.
 * FloatingSidebar(고정 80px 폭, z-50)가 그 위에 얹히면서, "이미지 → 배경색
 * 여백 → 사이드바" 순서로 눈에 띄게 어색해 보이는 원인이 됐다(특히 이미지 자체
 * 여백이 큰 원본을 썼을 때 배로 두드러졌다).
 *
 * <p><b>지금은</b> 화면 폭과 무관하게 사이드바 폭(80px)보다 작은 음수로 고정한다.
 * FloatingSidebar 가 항상 그 위에 떠 있는 불투명 흰 배경이라, 이미지가 살짝
 * 사이드바 밑으로 파고들어도 잘려 보이지 않고, 오히려 배경색 여백이 완전히
 * 사라진다. 초광폭 모니터에서 이미지가 텍스트와 조금 멀어져 보일 수 있지만,
 * 여기서는 "사이드바 바로 앞 여백"을 없애는 쪽을 우선한다.
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
          가로는 화면 실제 오른쪽 끝(IMAGE_RIGHT, 사이드바 밑으로 살짝 파고듦)에
          붙이고, 세로는 교점의 y 대신 히어로 한가운데(HERO_HEIGHT/2)에 둔다.
          교점의 y(237)를 그대로 쓰면 위아래 여백이 237 대 163 으로 어긋나 보인다 —
          기하학적으로는 맞아도 눈에는 아래로 처진 것으로 읽힌다. */}
      <div className="absolute -translate-y-1/2 pointer-events-none"
        style={{
          right: current === 0 ? 190 : current === 2 ? 140 : IMAGE_RIGHT,
          top: HERO_HEIGHT / 2,
        }}
      >
        <Image
        src={`/images/personal-hero${current + 1}.png`}
        alt={slide.badge}
        width={
            current === 0 ? 467 :
            current === 1 ? 634 :
            640
        }
        height={
            current === 0 ? 295 :
            current === 1 ? 402 :
            407
        }
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
