# 어드민 모니터링 화면 (Grafana 임베드)

> 화면: `/admin/monitoring`
> 구현: `web/app/(admin)/admin/monitoring/page.tsx`, `web/app/api/monitoring/dashboards/route.ts`
> 접근 권한: `ROLE_OPS`, `ROLE_HQ_RISK` (+ `ROLE_ADMIN` 은 항상 통과)

Grafana 대시보드를 어드민 콘솔 안에서 본다. 3000 포트를 따로 열지 않아도 된다.

---

## 1. 쓰는 법

```bash
docker compose up -d prometheus grafana
cd web && npm run dev
```

어드민에 운영·리스크 권한으로 로그인한 뒤 좌측 **전사 공통 → 모니터링 → 대시보드**.

Grafana 가 안 떠 있으면 빈 화면 대신 이유와 실행 명령을 보여준다.

---

## 2. 구조

```
브라우저 ─┬─ (목록) ─→ Next 서버 라우트 ─→ Grafana /api/search
          └─ (대시보드) ────── iframe ────→ Grafana /d/<uid>?kiosk
```

**목록은 서버를 거치고 대시보드는 브라우저가 직접 가져온다.** 이유가 다르다.

- 목록: 브라우저에서 Grafana 를 직접 부르면 **CORS 로 막힌다**
  (Grafana 는 `Access-Control-Allow-Origin` 을 주지 않는다). 그래서 Next 라우트를 거친다.
- 대시보드: `iframe` 은 CORS 대상이 아니다. 대신 **X-Frame-Options** 가 걸린다 (§3).

목록을 코드에 박지 않고 Grafana 에서 읽는 이유는, 박아두면 대시보드를 추가·삭제할 때마다
화면이 조용히 어긋나기 때문이다.

### 환경변수

| 변수 | 쓰는 곳 | 기본값 |
|---|---|---|
| `NEXT_PUBLIC_GRAFANA_URL` | 브라우저(iframe) | `http://localhost:3000` |
| `GRAFANA_URL` | Next 서버(목록 조회) | `NEXT_PUBLIC_GRAFANA_URL` → `http://localhost:3000` |

둘을 나눠 둔 것은 주소가 다를 수 있어서다. 컨테이너 안에서는 `grafana:3000` 이지만
브라우저는 그 이름을 모른다.

---

## 3. 로컬 전용 설정 — prod 로 옮기지 말 것

루트 `docker-compose.yml` 의 grafana 에만 들어 있다.

```yaml
GF_SECURITY_ALLOW_EMBEDDING: "true"   # 기본은 X-Frame-Options: deny
GF_AUTH_ANONYMOUS_ENABLED: "true"     # 어드민에서 별도 로그인 없이 보이게
GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
```

**왜 prod 에 그대로 쓰면 안 되는가.**

`iframe` 은 서버가 아니라 **방문자 브라우저가 직접 가져온다.** 배포 환경에서 같은 방식을
쓰려면 Grafana 를 인터넷에 공개해야 하고, 익명 Viewer 까지 켜면 누구나 인증 없이
PromQL 로 전체 지표를 조회할 수 있다.

지금 `infra/docker/docker-compose.prod.yml` 은 **api-gateway(8080) 하나만 노출**한다.
Grafana·Prometheus·DB 는 `ports:` 가 없어 내부망에만 있고, Grafana 관리자 비밀번호도
로컬과 달리 기본값 없이 `.env.prod` 에서만 받는다. 의식하고 조인 구성이라,
위 설정을 옮기는 것은 그 구성을 되돌리는 변경이다.

루트 `docker-compose.yml` 은 배포 대상이 아니다 — `deploy-infra` 워크플로는
`infra/docker/docker-compose.prod.yml` 과 `infra/{prometheus,grafana,ai-db}` 만 동기화한다.
그래서 위 설정이 prod 로 새어 나갈 경로는 없다.

### 배포하려면

게이트웨이가 JWT 를 검증한 뒤 넘기는 **auth proxy** 로 가야 한다.

```
브라우저 → api-gateway(8080, JWT + BankRole 검사)
             → Grafana (내부망 유지, GF_AUTH_PROXY_ENABLED)
```

Grafana 의 auth proxy 는 `X-WEBAUTH-USER` 헤더를 신뢰한다. **그 헤더를 프록시에서 온
요청에만 신뢰하도록 막는 것이 이 방식의 핵심이다.** 헤더를 위조할 수 있으면 인증이 없는 것과
같으므로, 게이트웨이를 거치지 않은 요청이 차단되는지 반드시 테스트로 확인할 것.

---

## 4. 접근 제어

사이드바에서 메뉴를 감추는 것은 **표시일 뿐 접근 제어가 아니다.** 주소를 직접 치면 열린다.
그래서 화면 자체에서도 검사한다 (`loan/eod` 와 같은 방식).

```tsx
const roles = useAdminRoles()
const allowed = hasAnyRole(roles, BankRole.OPS, BankRole.HQ_RISK)
```

`useAdminRoles` 는 마운트 후에 값이 채워진다. 판정 전에 '권한 없음'을 그리면 정상
사용자에게도 한 번 스치므로, 역할이 비어 있는 동안은 아무것도 그리지 않는다
(역할이 정말 없으면 `AdminGuard` 가 로그인으로 보낸다).

`web/e2e/admin-monitoring.spec.ts` 가 허용·차단 양쪽을 고정한다. 게이팅이 풀려도 화면은
멀쩡히 뜨기 때문에 눈으로는 회귀를 알아채기 어렵다. Grafana 없이도 도니 CI 에서 돈다.

> 지금 `ROLE_COMPLIANCE` 는 못 본다. 에이전트 채택률은 감사 관점에서도 볼 만한 지표라
> 필요하면 `allowed` 와 사이드바 `bankRoles` 에 추가하면 된다 (두 곳 모두 고쳐야 한다).

---

## 5. 알려진 제약

- **iframe 안은 Grafana 의 세계다.** 어드민 디자인이 적용되지 않고, 시간 범위·새로고침도
  Grafana 것이 함께 보인다. `kiosk` 파라미터로 메뉴만 숨긴 상태다.
- **포트 3000 을 브라우저가 직접 열 수 있어야 한다.** 원격 접속·터널 환경에서는
  `NEXT_PUBLIC_GRAFANA_URL` 을 그 환경에서 닿는 주소로 바꿔야 한다.
- 어드민 개발 서버는 3001 이다. kb-clone 도 3001 을 쓰므로 어느 쪽이 떠 있는지 확인할 것.
