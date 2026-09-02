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
 * 이미지 중심이 놓일 자리 — 두 원이 겹치는 왼쪽 교점.
 *
 * <p>배경 원(CIRCLE_BACK·CIRCLE_FRONT)의 장식용 좌표로만 쓴다. 이미지 자체의
 * 가로 위치는 더 이상 이 교점을 따라가지 않는다(아래 IMAGE_RIGHT 참고) — 필요하면
 * 세로 중심을 맞출 때 참고 값으로만 남겨 둔다.
 */
const IMAGE_CENTER = leftIntersection(CIRCLE_BACK, CIRCLE_FRONT)
void IMAGE_CENTER // 세로 위치는 HERO_HEIGHT/2 로 고정하므로 현재는 참고용

/**
 * 히어로 이미지 배치.
 *
 * <p>PNG 마다 투명 여백의 크기와 치우침이 달라, 캔버스 크기만 맞추면 그림이
 * 제각각으로 보인다. 그래서 <b>캔버스가 아니라 그림(불투명 영역)</b> 기준으로
 * 맞춘다. 아래 값은 캔버스 크기지만, 실제로 맞춘 것은 그림이다.
 *
 * <p><b>크기</b>는 3번 이미지 기준 — 세 슬라이드 모두 그림 세로 약 295px 로 보인다.
 *
 * <p><b>가로 위치</b>는 1·2번과 3번이 다르다. 1·2번은 그림 오른쪽 끝을 화면
 * 오른쪽에서 <b>130px</b> 에 둔다(교체 전 2번 이미지가 있던 자리). 여기서 더
 * 오른쪽으로 붙이면 그림이 사이드바에 눌린 것처럼 치우쳐 보인다. 3번은 그림이
 * 캔버스를 거의 꽉 채운 가로로 긴 구도라 같은 130px 을 주면 텍스트를 침범해서,
 * 지금처럼 85px 에 둔다.
 *
 * <p>1번의 right 가 음수인 것은 그림 오른쪽에 캔버스 여백이 137px 이나 남아
 * 있어서다. 화면 밖으로 나가는 것은 투명 여백뿐이고 부모가 overflow-hidden 이라
 * 잘려도 보이지 않는다.
 *
 * <p>이미지를 갈아 끼우면 여백 비율이 달라지므로 이 표도 다시 잡아야 한다. 새 PNG
 * 의 불투명 영역(bbox)을 재서, 그림 세로가 295px 이 되도록 캔버스 크기를 줄이고
 * 오른쪽 여백만큼 right 를 당기면 된다.
 */
const HERO_IMAGE_LAYOUT = [
  { width: 613, height: 339, right: 0 },  // personal-hero1 — 오른쪽 여백 137px 을 되돌린 값
  { width: 579, height: 375, right: 7 },   // personal-hero2 — 오른쪽 여백 130px
  { width: 621, height: 395, right: 80 },  // personal-hero3 — 여백이 거의 없다
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
