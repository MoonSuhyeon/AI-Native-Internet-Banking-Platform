/**
 * 브랜드 색상 상수 (인라인 style 전용).
 *
 * - className 에서는 Tailwind 토큰을 쓴다: `bg-kb-primary`, `text-kb-primary`, ...
 * - `style={{}}` 안에서는 아래 상수를 import 해서 쓴다: `style={{ color: KB_PRIMARY }}`
 *
 * **이 파일은 tailwind.config.ts 의 색 토큰과 1:1 이다.** 이름은 토큰을 대문자·
 * 밑줄로 바꾼 것이고 값은 같다. 어긋나면 `ThemeTokenSyncTest` 가 잡는다.
 *
 * 예전에는 여덟 개만 있어서, 상수가 없는 색은 인라인에 값을 그대로 적을 수밖에 없었다.
 * 그렇게 적힌 것이 361회였고 그중 183회는 상수가 있는데도 값을 적은 것이었다.
 */

export const KB_PRIMARY         = '#0D5C47' // 메인 브랜드 그린
export const KB_PRIMARY_BG      = '#F0FAF7' // 옅은 그린 배경
export const KB_PRIMARY_BORDER  = '#E2F5EF' // 그린 보더·구분선
export const KB_PRIMARY_SURFACE = '#F8FFFE' // 초옅은 배경
export const KB_PRIMARY_DARK    = '#3D4F47' // 진한 그린(헤더 등)

export const KB_MINT            = '#5BC9A8'

export const KB_YELLOW          = '#5BC9A8'
export const KB_YELLOW_DARK     = '#3FA889'
export const KB_YELLOW_LIGHT    = '#A8E5D4'

export const KB_TAUPE           = '#5A7569' // 슬레이트 그린
export const KB_TAUPE_DARK      = '#3D4F47'

export const KB_BEIGE           = '#F5F0E8' // 따뜻한 베이지 배경
export const KB_BEIGE_LIGHT     = '#FAFAF7' // 크림 흰 배경

export const KB_GNB_BIZ         = '#EBEBEB' // 기업 GNB 연회색
export const KB_GNB_BIZ_HOVER   = '#D8D8D8' // 기업 GNB 호버
export const KB_GNB_BIZ_ACTIVE  = '#1B3A6B' // 기업 GNB active 네이비

export const KB_CHAT            = '#2D6A4F' // 챗봇 헤더·강조 그린
export const KB_CHAT_DARK       = '#24563F' // 챗봇 그린 진한 단계(hover 등)
export const KB_CHAT_BG         = '#EAF4EF' // 챗봇 선택 배경

export const KB_DANGER          = '#E05555' // 출금·오류 빨강

export const KB_GOLD            = '#C09B3A' // 강조 테두리 골드

export const KB_ADMIN           = '#1B3A6B' // 어드민 기본 네이비

export const KB_BORDER          = '#C5D5CD' // 민트 톤 보더
export const KB_BORDER_DARK     = '#9AAEA4'

export const KB_TEXT            = '#1A1A1A'
export const KB_TEXT_BODY       = '#333333'
export const KB_TEXT_MUTED      = '#777777'
export const KB_TEXT_LIGHT      = '#AAAAAA'

export const KB_RED             = '#D0021B' // 에러·경고

export const KB_BLUE            = '#0066CC' // 링크·정보

// ── 다온은행(타행 시연) — AXful 팔레트와 분리 ──
export const DAON_NAVY       = '#1B3A6B' // 다온 브랜드 네이비
export const DAON_NAVY_MID   = '#384D84' // 그라디언트 중간
export const DAON_NAVY_LIGHT = '#5A73A8' // 그라디언트 끝
export const DAON_SURFACE       = '#F5F0E8'
export const DAON_SURFACE_LIGHT = '#FAFAF7'
