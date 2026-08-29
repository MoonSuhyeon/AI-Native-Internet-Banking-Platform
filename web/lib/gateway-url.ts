/**
 * 게이트웨이 주소를 한 곳에서 정한다.
 *
 * ## 왜 모았나
 *
 * 폴백이 아홉 곳에 흩어져 있었고 전부 `|| 'http://localhost:8080'` 이었다.
 * 운영 이미지는 `ARG NEXT_PUBLIC_API_URL=""` 로 빌드된다 — Caddy 가 같은 오리진에서
 * `/api` 를 게이트웨이로 넘기므로 도메인을 몰라도 되게 하려는 의도였다.
 *
 * 그런데 **빈 문자열은 JS 에서 falsy 라** `"" || 'http://localhost:8080'` 이 그대로
 * localhost 로 떨어졌다. 배포하면 방문자의 브라우저가 **자기 PC 의 8080** 을 부른다.
 * 화면은 멀쩡히 뜨고 모든 API 호출만 조용히 실패한다 — Dockerfile 주석이 말하는
 * 상대경로 동작은 코드에 구현된 적이 없었다.
 *
 * ## 어떻게 정하나
 *
 * 1. `NEXT_PUBLIC_API_URL` 이 있으면 그것. (로컬 `.env.local`, 별도 도메인 배포)
 * 2. 없고 운영 빌드면 **빈 문자열 = 같은 오리진 상대경로.** Caddy 가 넘긴다.
 * 3. 없고 개발 빌드면 `localhost:8080`. 로컬에서 게이트웨이를 직접 띄우는 경우다.
 *
 * 2번과 3번을 가르지 않으면 둘 중 하나가 반드시 깨진다. 상대경로로 통일하면 로컬
 * 개발(3000 포트, 프록시 없음)이 죽고, localhost 로 통일하면 배포가 죽는다.
 *
 * `process.env.NEXT_PUBLIC_*` 와 `process.env.NODE_ENV` 는 Next 가 **빌드 시점에
 * 문자열로 치환한다.** 변수에 담아 우회하면 치환되지 않으므로 여기서 직접 읽는다.
 */
export const GATEWAY_BASE_URL =
  process.env.NEXT_PUBLIC_API_URL ||
  (process.env.NODE_ENV === 'production' ? '' : 'http://localhost:8080')
