# 배포 가이드

> 최종 갱신: 2026-08-04
> **현재 이 저장소에는 배포 서버가 연결돼 있지 않다.** 배포 잡은 저장소 변수
> `DEPLOY_ENABLED` 로 게이트되어 기본 스킵된다. 새 서버를 붙이는 절차는 §3.

---

## 1. 배포 구조

CI 에서 이미지를 빌드해 GHCR 에 올리고, 서버는 그것을 pull 만 한다.
서버에서 소스를 빌드하지 않는다(재현성 · 서버 리소스 · 롤백 때문).

```
push(main)
   ↓
[test]     reusable-java-build.yml       Gradle 테스트
   ↓
[publish]  reusable-docker-publish.yml   이미지 빌드 → ghcr.io push (SHA 태그)
   ↓
[deploy]   reusable-deploy-ssh.yml       서버 SSH → compose pull → up -d
           ※ vars.DEPLOY_ENABLED == 'true' 일 때만 실행
```

서버에서 실제로 도는 정의는 `infra/docker/docker-compose.prod.yml` 하나다.

### 외부에 열리는 것은 Caddy 뿐

```
인터넷 ─── :443 ─── Caddy ─┬─ /api/*  → api-gateway:8080
                           └─ /*      → web:3000
```

게이트웨이는 **호스트 포트를 열지 않는다.** 밖에 직접 노출하면 HTTPS 를 우회해
평문으로 부를 수 있는 길이 남는다.

`/api` 를 같은 도메인에서 넘기므로 브라우저에게 프론트와 API 가 같은 출처다 —
**CORS 설정이 아예 필요 없고**, 프론트 이미지가 도메인을 몰라도 된다
(`NEXT_PUBLIC_*` 은 빌드 시점에 번들에 박히므로 이 점이 중요하다).

### 서비스별 워크플로

| 워크플로 | 대상 | test 잡 |
|---|---|---|
| `deploy-api-gateway.yml` | api-gateway | ✅ |
| `deploy-customer-service.yml` | customer-service | ✅ |
| `deploy-core-banking.yml` | core-banking (수신+이체 병합) | ✅ |
| `deploy-loan-service.yml` | loan-service | ✅ |
| `deploy-review-ai-gateway.yml` | review-ai-gateway | ✅ |
| `deploy-consultation-service.yml` | consultation-service (Python) | — |
| `deploy-web.yml` | web (Next.js) | 타입·린트 |
| `deploy-infra.yml` | compose · Caddyfile · prometheus · grafana 파일 SCP 동기화 | — |

> 이 표가 오래 낡아 있었다. `deploy-deposit-service.yml`·`deploy-payment-service.yml`
> 을 적어 뒀지만 둘은 병합돼 `deploy-core-banking.yml` 하나가 됐고,
> "loan-service 워크플로가 없다" 고 적혀 있었지만 실제로는 있었다.

### 트리거 경로 함정

`deploy-*.yml` 대부분이 `common/**` · `build.gradle` · `settings.gradle` 을 경로
필터에 포함한다. **이 셋 중 하나만 고쳐도 서비스 5개가 동시에 재배포된다.**
모듈 추가·삭제 리팩토링 시 유의.

---

## 2. 시크릿 · 변수

| 이름 | 종류 | 용도 |
|---|---|---|
| `DEPLOY_ENABLED` | **Variable** | `'true'` 일 때만 deploy 잡 실행. 미설정이면 스킵(= 현재 상태) |
| `DEPLOY_SSH_HOST` | Secret | 배포 서버 주소 |
| `DEPLOY_SSH_USER` | Secret | SSH 계정 |
| `DEPLOY_SSH_KEY` | Secret | SSH 개인키 |
| `OPENAI_API_KEY` | Secret | LLM eval LIVE 채점. 없으면 STUB 구조검증만 수행 |
| `GITHUB_TOKEN` | — | GHCR push 용. **GitHub 이 자동 제공, 설정 불필요** |

설정 위치: 저장소 → Settings → Secrets and variables → Actions
(Secrets 탭과 Variables 탭이 분리돼 있다. `DEPLOY_ENABLED` 는 **Variables**)

> 포크는 원본 저장소의 시크릿을 상속받지 않는다. 배포가 필요 없는 포크에서
> `DEPLOY_ENABLED` 를 켜지 않으면 CI 는 test · publish 까지만 돌고 초록색을 유지한다.

---

## 3. 새 배포 서버 붙이는 절차

### 3-0. 도메인을 먼저 붙인다

Caddy 가 Let's Encrypt 에서 인증서를 받으려면 **도메인이 이미 이 서버를 가리켜야
한다.** 순서를 바꾸면 발급 검증이 실패하고, 반복 실패는 도메인당 주 5회 한도에
걸려 그 주 내내 HTTPS 를 못 켠다.

```
A 레코드   example.com       → <VM 외부 IP>
A 레코드   www.example.com   → <VM 외부 IP>   (쓸 경우)
```

전파를 확인한 뒤 다음 단계로 간다.

```bash
dig +short example.com        # VM IP 가 나와야 한다
```

방화벽은 **80·443 만** 연다. 80 은 Let's Encrypt 검증에 필요하다(Caddy 가 443 으로
넘긴다). 8080 을 열 이유는 없다 — 게이트웨이는 Caddy 뒤에 있다.

### 3-1. 서버 사전 준비

```bash
# Docker + compose plugin 설치 후
mkdir -p ~/app && cd ~/app

# GHCR 로그인 (이미지 pull 권한)
echo "$GITHUB_PAT" | docker login ghcr.io -u <github-user> --password-stdin

# .env.prod 작성 (커밋 금지 — .env.prod.sample 참고)
vi .env.prod
```

`.env.prod` 에는 각 DB 비밀번호, `JWT_SECRET`, `CRYPTO_KEY_BASE64`,
`GITHUB_OWNER`, 서비스별 `*_IMAGE_TAG`, 그리고 도메인 관련 값
(`SITE_DOMAIN`·`ACME_EMAIL`)이 들어간다. `.env.prod.sample` 이 정본이다.

한 줄로 생성할 수 있는 것들:

```bash
{
  echo "JWT_SECRET=$(openssl rand -base64 48)"
  echo "RRN_CRYPTO_KEY=$(openssl rand -base64 48)"
  echo "IDENTITY_CI_SECRET=$(openssl rand -base64 48)"
  echo "CRYPTO_KEY_BASE64=$(openssl rand -base64 32)"
  echo "CONSULTATION_GATEWAY_SHARED_SECRET=$(openssl rand -hex 24)"
  echo "AGENT_PII_SALT=$(openssl rand -hex 16)"
} >> .env.prod
```

### 3-2. compose 파일 배치 후 최초 기동

`deploy-infra.yml` 이 `infra/**` 를 `~/app/` 으로 SCP 동기화한다. 최초 1회는 수동으로 올려도 된다.

```bash
docker compose -f infra/docker/docker-compose.prod.yml --env-file .env.prod pull
docker compose -f infra/docker/docker-compose.prod.yml --env-file .env.prod up -d
```

### 3-3. GitHub 설정

1. Secrets 에 `DEPLOY_SSH_HOST` · `DEPLOY_SSH_USER` · `DEPLOY_SSH_KEY` 등록
2. Variables 에 `DEPLOY_ENABLED = true` 등록
3. 다음 push 부터 deploy 잡이 활성화된다

---

## 4. 배포 전 체크리스트 (첫 배포·재배포 공통)

아래 다섯은 **빠지면 서비스가 뜨지 않거나 기능이 조용히 멎는다.** 순서대로 확인한다.

> 절 번호가 `3-4` 로 둘 있었고 그중 하나("확인")는 아래 "배포 직후 확인" 과 같은
> 내용이었다. 합쳤다.

### ① 시크릿 세 개 — 없으면 customer-service 가 기동을 거부한다

```bash
grep -E "JWT_SECRET|RRN_CRYPTO_KEY|IDENTITY_CI_SECRET" .env.prod
```

| 변수 | 없으면 |
|---|---|
| `JWT_SECRET` | 레포에 적힌 개발용 기본값으로 뜬다 → 누구나 임의 고객·역할의 토큰을 위조할 수 있다 |
| `RRN_CRYPTO_KEY` | 주민번호 AES 키가 공개값이 된다 → DB 유출 시 암호화가 무의미 |
| `IDENTITY_CI_SECRET` | 본인확인 CI 파생값이 공개 시크릿 기반이 된다 |

`DevSecretGuard` 가 운영 프로파일에서 이 값들이 비었거나 기본값이면 **기동을 중단**한다.
"안전하지 않은데 조용히 뜨는 것"이 가장 나쁜 상태라서 그렇게 만들었다.

```bash
# 생성 예
echo "RRN_CRYPTO_KEY=$(openssl rand -base64 48)" >> .env.prod
```

> ⚠️ 이미 운영 중인 DB 가 있다면 `RRN_CRYPTO_KEY` 를 바꾸는 순간 기존 주민번호 암호문을
> 복호화할 수 없다. 값을 바꾸기 전에 `docker exec ib-customer-service env | grep RRN`
> 으로 현재 주입값을 먼저 확인할 것.

### ② 서비스 간 주소

```bash
grep CONSULTATION_CORE_BANKING_URL .env.prod   # http://core-banking:8082
```

챗봇 이체는 core-banking 을 거친다(락·멱등키·한도·소유권 검증을 그쪽이 갖고 있다).
기본값이 `localhost` 라 컨테이너 안에서는 자기 자신을 부르고 이체가 실패한다.

### ③ 이체 승인(step-up) 전제

이체에는 인증서 승인 토큰이 필요하다(`TRANSFER_APPROVAL_REQUIRED` 기본값 `true`).

- 데모 고객(user01~03)의 인증서는 마이그레이션 `V32` 가 심는다. Flyway 가 돌았는지 확인.
- 인증서가 없는 고객은 이체 화면에서 발급 안내로 막힌다 — 의도된 동작이다.
- 무언가 막히면 `TRANSFER_APPROVAL_REQUIRED=false` 로 되돌린 뒤,
  `"승인 토큰 없이 처리됨"` 로그로 어느 경로가 토큰을 안 보내는지 확인한다.

### ④ 감사 스풀 경로

```bash
grep CONSULTATION_HARNESS_AUDIT_SPOOL_PATH .env.prod
```

감사 저장이 실패하면 이 파일에 쌓였다가 `python -m app.audit_replay` 로 복구된다.
**컨테이너에 볼륨이 붙어 있어야 한다** — 없으면 재시작과 함께 스풀도 사라져 복구 장치가
있으나 마나가 된다.

### ⑤ 도메인 · 인증서 — Caddy

```bash
grep -E "SITE_DOMAIN|ACME_EMAIL" .env.prod
docker logs ib-caddy --tail 30
```

| 로그에 보이는 것 | 뜻 |
|---|---|
| `certificate obtained successfully` | 정상 |
| `no such host` · `DNS problem` | DNS 가 아직 이 서버를 안 가리킨다. §3-0 |
| `too many certificates already issued` | 발급 한도(주 5회). **그 주에는 못 켠다** — 스테이징 발급기로 먼저 시험할 것 |
| `connection refused` on :80 | 방화벽이 80 을 막고 있다. 검증에 필요하다 |

인증서는 `caddy-data` 볼륨에 남는다. **볼륨을 지우면 재발급을 시도하다 한도에
걸린다** — `docker compose down -v` 를 함부로 쓰지 말 것.

### 배포 직후 확인

```bash
docker compose -f infra/docker/docker-compose.prod.yml --env-file .env.prod ps

# 밖에서 — HTTPS 와 프론트
curl -fsS https://<도메인>/ -o /dev/null -w '%{http_code}
'

# 안에서 — 게이트웨이는 호스트 포트를 열지 않으므로 컨테이너 망으로 본다
docker exec ib-caddy wget -qO- http://api-gateway:8080/actuator/health

# 이체 한 번 돌려보고 게이트 로그 확인
docker logs ib-core-banking --since 10m | grep "승인 토큰"
```

> `curl http://<host>:8080/...` 은 이제 **연결되지 않는 것이 정상이다.**
> 게이트웨이를 Caddy 뒤로 넣으면서 호스트 포트를 닫았다.

"승인 토큰 없이 처리됨" 이 보이면 토큰을 안 보내는 경로가 남아 있다는 뜻이다.

---

## 5. 운영 메모

### 리소스 상한
`infra/docker/docker-compose.prod.yml` 의 `mem_limit` 값은 **Oracle Cloud Free Tier
24GB 기준**으로 잡혀 있다(합계 약 14GB). 다른 스펙의 서버로 옮기면 조정할 것.

### 로컬 실행과의 차이
로컬은 `docker-compose.yml` + `start.ps1` 을 쓴다. 로컬은 인프라만 컨테이너로
띄우고 앱은 `bootRun` 하는 구성이라 prod 와 실행 방식이 다르다.

### 컨테이너·볼륨 정리
compose 에서 서비스를 제거해도 서버의 기존 컨테이너·볼륨은 자동 삭제되지 않는다.

```bash
docker compose -f infra/docker/docker-compose.prod.yml --env-file .env.prod up -d --remove-orphans
docker volume ls        # 볼륨은 --remove-orphans 로 지워지지 않음
docker volume rm <name>
```

---

## 6. 이력

- **NCP(네이버 클라우드) 배포 세트 제거 (2026-08-04)** — 서버에서 `git pull` 후
  직접 빌드하는 방식이었다. GHCR 방식과 중복이고 test 잡 · 이미지 태그 · 롤백이
  없어 열등하므로 `deploy.yml` 과 `docker-compose.server.yml` 을 제거했다.
  시크릿도 `DEPLOY_*` / `ORACLE_SSH_*` 두 세트로 갈려 있던 것을 `DEPLOY_SSH_*` 로 통일.
- **`reusable-deploy-oracle.yml` → `reusable-deploy-ssh.yml` 개명 (2026-08-04)** —
  특정 클라우드 종속 이름 제거.
- **loan-service Flyway V35 이슈 해소** — 구 문서에 기록돼 있던
  "V35 가 없는 테이블에 시드해 기동 실패" 문제는 해결됐다. 어드바이저리 시드가
  advisory 스트림으로 이전되어 V35 에는 주석만 남아 있고, 우회용
  `SPRING_FLYWAY_TARGET` 설정도 제거됐다.
