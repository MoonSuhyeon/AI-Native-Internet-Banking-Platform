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

### 서비스별 워크플로

| 워크플로 | 대상 | test 잡 |
|---|---|---|
| `deploy-api-gateway.yml` | api-gateway | ✅ |
| `deploy-customer-service.yml` | customer-service | ✅ |
| `deploy-deposit-service.yml` | deposit-service | ✅ |
| `deploy-payment-service.yml` | payment-service | ✅ |
| `deploy-review-ai-gateway.yml` | review-ai-gateway | ✅ |
| `deploy-consultation-service.yml` | consultation-service (Python) | — |
| `deploy-infra.yml` | compose · prometheus · grafana 파일 SCP 동기화 | — |

> ⚠️ **loan-service 전용 배포 워크플로가 없다.** 새 서버 구축 시 추가 필요.

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
`GITHUB_OWNER`, 서비스별 `*_IMAGE_TAG` 가 들어간다.

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

### 3-4. 확인

```bash
docker compose -f infra/docker/docker-compose.prod.yml --env-file .env.prod ps
curl -fsS http://<host>:8080/actuator/health
```

---

## 4. 운영 메모

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

## 5. 이력

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
