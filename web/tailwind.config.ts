import type { Config } from "tailwindcss";

const config: Config = {
  darkMode: "class",
  content: [
    "./pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      // ============ 컬러 토큰 ============
      colors: {
        // ── Primary (메인 브랜드 그린) ──
        "kb-primary":         "#0D5C47",  // 메인 브랜드 그린
        "kb-primary-bg":      "#F0FAF7",  // 옅은 그린 배경
        "kb-primary-border":  "#E2F5EF",  // 그린 보더·구분선
        "kb-primary-surface": "#F8FFFE",  // 초옅은 배경
        "kb-primary-dark":    "#3D4F47",  // 진한 그린(헤더 등)

        // ── Accent (민트) ──
        "kb-mint":        "#5BC9A8",
        // 레거시 별칭 (kb-yellow* — 점진적으로 kb-mint로 통일 예정)
        "kb-yellow":      "#5BC9A8",
        "kb-yellow-dark": "#3FA889",
        "kb-yellow-light":"#A8E5D4",

        // ── Secondary ──
        "kb-taupe":       "#5A7569",   // 슬레이트 그린
        "kb-taupe-dark":  "#3D4F47",

        // ── Background / Surface ──
        "kb-beige":       "#F5F0E8",   // 따뜻한 베이지 배경
        "kb-beige-light": "#FAFAF7",   // 크림 흰 배경

        // ── 기업(biz) GNB ──
        "kb-gnb-biz":        "#EBEBEB",  // 기업 GNB 연회색
        "kb-gnb-biz-hover":  "#D8D8D8",  // 기업 GNB 호버
        "kb-gnb-biz-active": "#1B3A6B",  // 기업 GNB active 네이비

        // ── 챗봇 ──
        //
        // 위 kb-primary(#0D5C47)와는 다른 그린이다. 값이 달라 kb-primary 로 바꾸면
        // 화면이 변한다. 이름을 따로 두고 값은 그대로 옮긴다.
        "kb-chat":       "#2D6A4F",  // 챗봇 헤더·강조 그린
        "kb-chat-dark":  "#24563F",  // 챗봇 그린 진한 단계(hover 등)
        "kb-chat-bg":    "#EAF4EF",  // 챗봇 선택 배경

        // ── 상태색 ──
        "kb-danger":     "#E05555",  // 출금·오류 빨강
        "kb-gold":       "#C09B3A",  // 강조 테두리 골드

        // ── 직원용(admin) ──
        //
        // 값은 위 기업 GNB active 와 같지만 쓰임이 다르다. 어드민 화면 57곳이
        // #1B3A6B 를 그대로 적고 있었는데, 기업 GNB 토큰을 빌려 쓰면 나중에 둘 중
        // 하나만 바꾸고 싶을 때 갈라낼 수 없다. 이름을 따로 둔다.
        "kb-admin":          "#1B3A6B",  // 어드민 기본 네이비

        // ── Border ──
        "kb-border":      "#C5D5CD",   // 민트 톤 보더
        "kb-border-dark": "#9AAEA4",

        // ── Text ──
        "kb-text":        "#1A1A1A",
        "kb-text-body":   "#333333",
        "kb-text-muted":  "#777777",
        "kb-text-light":  "#AAAAAA",

        // ── Semantic ──
        "kb-red":  "#D0021B",   // 에러·경고
        "kb-blue": "#0066CC",   // 링크·정보
      },

      // ============ 레이아웃 토큰 ============
      maxWidth: {
        "kb-container": "1280px",
      },
      width: {
        sidebar: "200px",
      },

      // ============ 타이포그래피 토큰 ============
      fontSize: {
        display: ["22px", { lineHeight: "1.45", fontWeight: "700" }],
        h1:      ["20px", { lineHeight: "1.45", fontWeight: "700" }],
        h2:      ["18px", { lineHeight: "1.45", fontWeight: "700" }],
        h3:      ["16px", { lineHeight: "1.55", fontWeight: "700" }],
        body:    ["14px", { lineHeight: "1.75", fontWeight: "400" }],
        caption: ["13px", { lineHeight: "1.65", fontWeight: "400" }],
        small:   ["12px", { lineHeight: "1.65", fontWeight: "400" }],
      },

      // ============ 기타 토큰 ============
      borderRadius: {
        kb:      "4px",
        "kb-sm": "2px",
      },
      transitionDuration: {
        kb: "150ms",
      },
      fontFamily: {
        sans: ["Noto Sans KR", "-apple-system", "sans-serif"],
      },
    },
  },
  plugins: [],
};

export default config;
