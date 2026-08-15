/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  // 타입 오류로 빌드가 실패해야 한다.
  //
  // 켜져 있던 ignoreBuildErrors 때문에, 상담 대기열의 `new Date(waiting_since)` 가
  // null 을 받아 1970년으로 계산되던 것이 빌드에서 한 번도 안 걸렸다. 그 화면의
  // "오래된거 정리" 는 대기 시각을 모르는 상담을 종료시키고 있었다.
  //
  // eslint 는 아직 못 켠다 — 미사용 변수 11건이 남아 있고 그중 6건이
  // components/chatbot/ChatbotWidget.tsx(팀원 작업물)라 임의로 지울 수 없다.
  // 대신 CI 가 lint 를 돌려 눈에 보이게 한다.
  eslint: {
    ignoreDuringBuilds: true,
  },
  webpack: (config) => {
    // pdfjs-dist가 참조하는 canvas/encoding은 브라우저 환경에선 불필요
    config.resolve.alias.canvas = false
    config.resolve.alias.encoding = false
    return config
  },
}

module.exports = nextConfig
