/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  // 타입 오류로 빌드가 실패해야 한다.
  //
  // 켜져 있던 ignoreBuildErrors 때문에, 상담 대기열의 `new Date(waiting_since)` 가
  // null 을 받아 1970년으로 계산되던 것이 빌드에서 한 번도 안 걸렸다. 그 화면의
  // "오래된거 정리" 는 대기 시각을 모르는 상담을 종료시키고 있었다.
  //
  // eslint 도 빌드를 막는다. 미사용 변수 11건이 남아 있어 못 켜고 있었는데,
  // 그중 대부분이 도달 불가 코드였다 — ChatbotWidget 의 상담원 패널은 agentMode 가
  // true 가 되는 곳이 없어 115줄이 한 번도 렌더되지 않았고, 그것을 받치던 state·
  // 함수·import 가 미사용으로 남아 있던 것이다. 걷어내니 0건이 됐다.
  webpack: (config) => {
    // pdfjs-dist가 참조하는 canvas/encoding은 브라우저 환경에선 불필요
    config.resolve.alias.canvas = false
    config.resolve.alias.encoding = false
    return config
  },
}

module.exports = nextConfig
