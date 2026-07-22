# CI/CD 구축 계획 — Oracle Cloud 1대 배포 → GCP 확장

## 목표

- **1단계**: GitHub Actions 기반 CI/CD로 Oracle Cloud Free Tier 1대(ARM 4코어/24GB RAM)에 전체 마이크로서비스 배포
- **2단계**: 동일한 Docker 이미지를 사용해 GCP(GKE 또는 Cloud Run)로 확장 가능한 구조 확보
- **무료**로 운영하다가 트래픽/데이터가 늘면 GCP로 마이그레이션

## 진행 규칙

- 작업 시작/완료 시 이 파일의 진행 상태(체크리스트)를 업데이트한다.
- 코드 수정 전에 어떤 파일을 고칠지 먼저 기록한다.
- 한 단계 끝낼 때마다 별도 커밋하고 다음 단계 진행 전 보고한다 (자동 연속 진행 금지).
- `.env`, 시크릿, SSH 키는 절대 커밋하지 않는다 (`.env.*.sample`만 커밋).
- 커밋 메시지: `chore(infra): <한글 subject>` 한 줄, body 없음.

---

## 현재 상태 (2026-05-28)

| 항목 | 현황 |
|------|------|
| Dockerfile | consultation ✅, gateway ✅, payment ✅ / **Java 7개 ❌** |
| GitHub Actions | `deploy-service-loan.yml` 1개 (템플릿, 동작 안 함) |
| docker-compose | 인프라(DB×6, Kafka, Redis, Prometheus, Grafana, gateway)만 포함, 앱 서비스 대부분 미포함 |
| 배포 서버 | 없음 (Oracle Cloud 계정 생성 필요) |

### Dockerfile 누락 서비스 (7개)
- customer-service
- deposit-service
- loan-service
- ai-service
- master-service
- review-ai-gateway
- auto-loan-review

### 이미 Dockerfile 있는 서비스
- gateway-service (Java)
- payment-service (Java)
- consultation-service (Python)

---

## 아키텍처 설계 원칙

### 1. Docker 이미지 기반 통일
모든 서비스는 Docker 이미지로 빌드되어 **GitHub Container Registry(GHCR)** 에 저장된다.
- Oracle: `docker compose pull` → 이미지 받아서 실행
- GCP: 동일 이미지를 GKE/Cloud Run에서 사용
- 이미지 태그: `ghcr.io/<owner>/<service>:<git-sha>` + `:latest`

### 2. 환경별 설정 분리
| 파일 | 용도 |
|------|------|
| `.env.local.sample` | 로컬 개발용 (기존 유지) |
| `.env.prod.sample` | Oracle/GCP 공용 운영 템플릿 |
| `infra/docker/docker-compose.prod.yml` | Oracle 배포용 |
| `infra/k8s/` | GCP 전환시 사용 (구조만 미리 준비) |

### 3. 메모리 제약 적용 (Oracle 24GB)
각 서비스에 `mem_limit` 명시:
- Java 서비스: 512MB~1GB
- Elasticsearch: `-Xms512m -Xmx1g`
- Kafka: `KAFKA_HEAP_OPTS=-Xmx512m`
- PostgreSQL: `shared_buffers=256MB`

---

## Phase A — Docker 기반 통일 (선행 필수)

### A1. Java 서비스 Dockerfile 7개 작성

**참고 패턴**: `services/gateway-service/Dockerfile`
```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY services/<service-name>/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**작성 대상**:
- `services/customer-service/Dockerfile`
- `services/deposit-service/Dockerfile`
- `services/loan-service/Dockerfile`
- `services/ai-service/Dockerfile`
- `services/master-service/Dockerfile`
- `services/review-ai-gateway/Dockerfile`
- `services/auto-loan-review/Dockerfile`

**개선 포인트**:
- 멀티스테이지 빌드(builder + runtime)로 이미지 크기 축소
- JVM 메모리 옵션 환경변수화 (`JAVA_OPTS`)
- non-root user 사용
- Healthcheck 추가

**개선된 템플릿**:
```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build
COPY services/<svc>/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /build/dependencies/ ./
COPY --from=builder /build/spring-boot-loader/ ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/ ./
ENV JAVA_OPTS="-Xmx512m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### A2. docker-compose.prod.yml 작성

**위치**: `infra/docker/docker-compose.prod.yml`

**포함 대상**:
- 인프라 7개 (PostgreSQL ×6, Kafka, schema-registry, Redis, Prometheus, Grafana)
- Elasticsearch (신규 추가 — pgvector로 대체할지 검토 필요)
- 앱 서비스 10개 (모두 GHCR 이미지 참조)
- 메모리 제한 (`mem_limit`)
- 로그 드라이버 설정 (`logging.options.max-size=10m`)
- 재시작 정책 (`restart: unless-stopped`)

**환경변수 분리**:
```yaml
loan-service:
  image: ghcr.io/${GITHUB_OWNER}/loan-service:${IMAGE_TAG:-latest}
  env_file:
    - .env.prod
  mem_limit: 768m
  restart: unless-stopped
  depends_on:
    loan-db:
      condition: service_healthy
```

### A3. `.env.prod.sample` 작성

운영 환경변수 템플릿 (DB 비밀번호, JWT secret, 외부 API 키 등).
실제 `.env.prod` 는 Oracle 서버에만 둔다 (커밋 금지).

---

## Phase B — GitHub Actions CI/CD

### B1. 재사용 워크플로우 (`.github/workflows/`)

**`reusable-java-build.yml`** — Java 서비스 공통 빌드
- 입력: `service-name`, `service-path`
- 단계: JDK 17 → gradle 캐시 → `./gradlew :services:<svc>:build` → 테스트
- 출력: JAR 아티팩트

**`reusable-docker-publish.yml`** — Docker 이미지 빌드/push
- 입력: `service-name`, `dockerfile-path`
- 단계: docker buildx → GHCR 로그인 → `docker push ghcr.io/<owner>/<svc>:<sha>,latest`

**`reusable-deploy-oracle.yml`** — Oracle 배포
- 입력: `service-name`
- 단계: SSH → `cd ~/app && docker compose pull <svc> && docker compose up -d <svc>`
- 시크릿 사용: `ORACLE_SSH_HOST`, `ORACLE_SSH_USER`, `ORACLE_SSH_KEY`

### B2. 서비스별 워크플로우 (10개)

각 서비스마다 `.github/workflows/deploy-<service>.yml`:
```yaml
name: Deploy <service>
on:
  push:
    branches: [main]
    paths:
      - 'services/<service>/**'
      - '.github/workflows/deploy-<service>.yml'
jobs:
  build:
    uses: ./.github/workflows/reusable-java-build.yml
    with:
      service-name: <service>
  publish:
    needs: build
    uses: ./.github/workflows/reusable-docker-publish.yml
    with:
      service-name: <service>
  deploy:
    needs: publish
    uses: ./.github/workflows/reusable-deploy-oracle.yml
    with:
      service-name: <service>
    secrets: inherit
```

**작성 목록**:
- `deploy-customer-service.yml`
- `deploy-deposit-service.yml`
- `deploy-loan-service.yml` (기존 템플릿 대체)
- `deploy-payment-service.yml`
- `deploy-ai-service.yml`
- `deploy-master-service.yml`
- `deploy-gateway-service.yml`
- `deploy-review-ai-gateway.yml`
- `deploy-auto-loan-review.yml`
- `deploy-consultation-service.yml` (Python — Java 워크플로우 분기 또는 별도 reusable)

### B3. 인프라 배포 워크플로우

`deploy-infra.yml` — `infra/docker/docker-compose.prod.yml` 변경시 트리거
- 단계: SSH → scp compose 파일 → `docker compose up -d`

### B4. GitHub Secrets 설정 (Settings → Secrets)

| 시크릿 | 용도 |
|--------|------|
| `ORACLE_SSH_HOST` | Oracle 서버 공인 IP |
| `ORACLE_SSH_USER` | `ubuntu` 또는 `opc` |
| `ORACLE_SSH_KEY` | 개인키 (PEM) |
| `GHCR_TOKEN` | GitHub Container Registry write 권한 |
| `JWT_SECRET` | 운영 JWT 시크릿 |
| `OPENAI_API_KEY` | (AI 서비스용) |

---

## Phase C — Oracle Cloud 서버 셋업 (수동)

사용자가 직접 진행해야 하는 단계.

### C1. Oracle Cloud 계정/인스턴스 생성
1. Oracle Cloud 가입 (https://cloud.oracle.com)
2. Always Free 리전 선택 (Seoul/Tokyo 권장)
3. VM 인스턴스 생성:
   - Shape: `VM.Standard.A1.Flex` (ARM, 4 OCPU, 24GB RAM)
   - Image: Ubuntu 22.04
   - SSH 키 생성/등록
4. 네트워크 보안 규칙 (Ingress Rules):
   - 22 (SSH) — 본인 IP만
   - 80, 443 (HTTP/HTTPS)
   - 8080 (Gateway, 또는 80으로 리버스 프록시)

### C2. 서버 초기 셋업 스크립트
**`infra/oracle/bootstrap.sh`** 작성:
```bash
#!/bin/bash
# Docker, docker-compose, fail2ban, ufw 설치
# GHCR 로그인 (PAT 사용)
# 앱 디렉토리 생성 (~/app)
# systemd 서비스 등록 (재부팅시 자동 시작)
```

### C3. 도메인 + HTTPS (선택)
- Cloudflare 무료 + 도메인 (가비아 등)
- Nginx 리버스 프록시 + Let's Encrypt

---

## Phase D — GCP 확장 준비 (코드 레벨만)

**지금은 파일만 만들어두고 실제 GCP 배포는 나중에.**

### D1. Kubernetes 매니페스트 골격
**`infra/k8s/`** 디렉토리 구조:
```
infra/k8s/
  base/
    namespace.yaml
    deployments/
      customer-service.yaml
      deposit-service.yaml
      ...
    services/
      customer-service.yaml
      ...
    configmaps/
      app-config.yaml
    secrets/
      app-secrets.yaml.sample
  overlays/
    oracle/      # (사용 안 함, 자리만)
    gcp/
      kustomization.yaml
      ingress.yaml
```

각 deployment는 동일 GHCR 이미지를 사용 → Oracle/GCP 동일 이미지.

### D2. GCP 전환 시 추가 작업 목록 (문서화만)
- Cloud SQL 마이그레이션 (또는 GKE 내 PostgreSQL)
- Memorystore (Redis 관리형)
- Pub/Sub 또는 Kafka on GKE
- Cloud Monitoring 연동
- Artifact Registry로 이미지 push (GHCR → Artifact Registry 미러링 또는 전환)
- GitHub Actions에 `TARGET=gcp` 분기 추가

---

## 작업 순서 및 커밋 단위

| 순번 | 단계 | 파일 수 | 커밋 메시지 예시 |
|------|------|---------|------------------|
| 1 | A1: Java Dockerfile 7개 작성 | 7 | `chore(infra): Java 서비스 Dockerfile 추가` |
| 2 | A2: docker-compose.prod.yml | 1 | `chore(infra): 운영 docker-compose 작성` |
| 3 | A3: .env.prod.sample | 1 | `chore(infra): 운영 환경변수 템플릿 추가` |
| 4 | B1: 재사용 워크플로우 3개 | 3 | `chore(ci): 재사용 워크플로우 추가` |
| 5 | B2: 서비스별 워크플로우 10개 | 10 | `chore(ci): 서비스별 배포 워크플로우 추가` |
| 6 | B3: 인프라 배포 워크플로우 | 1 | `chore(ci): 인프라 배포 워크플로우 추가` |
| 7 | C2: Oracle bootstrap 스크립트 | 1 | `chore(infra): Oracle 서버 부트스트랩 스크립트` |
| 8 | D1: k8s 매니페스트 골격 | ~15 | `chore(infra): GCP 전환용 k8s 매니페스트 골격` |

**원칙**: 각 단계 완료 후 커밋 + 보고하고 다음 단계 진행 전 사용자 승인 받기.

---

## 작업 체크리스트

| 단계 | 상태 | 내용 |
| --- | --- | --- |
| A1 | 완료 | Java 서비스 Dockerfile 8개 작성 (customer/deposit/loan/ai/master/review-ai-gateway/api-gateway/auto-loan-review) + auto-loan-review settings.gradle 등록 |
| A1+ | 완료 | data-tools 통합 (`/synthetic-data-generator/` + `/services/synthetic-data-generator/` → `services/data-tools/`) + Dockerfile 작성 |
| A1++ | 완료 | deposit-api Dockerfile 작성 (Python FastAPI, 포트 8090) |
| A2 | 완료 | docker-compose.prod.yml 작성 (앱 13 + 인프라 9, 메모리 합계 ~13.5GB) |
| A3 | 완료 | .env.prod.sample 작성 + .gitignore 에 .env.prod 추가 |
| B1 | 완료 | 재사용 워크플로우 3개 (java-build, docker-publish, deploy-oracle) |
| B2 | 완료 | 서비스별 워크플로우 12개 (Java 10 + Python 2 + data-tools publish 전용) + 기존 템플릿 제거 |
| B3 | 완료 | 인프라 배포 워크플로우 (compose/prometheus/grafana sync + reload) |
| C1 | 사용자 | Oracle Cloud VM 생성 (수동) |
| C2 | 완료 | Oracle bootstrap.sh + README (VM 생성부터 자동 기동까지 가이드) |
| C3 | 보류 | 도메인 + HTTPS (선택) |
| D1 | 완료 | k8s 매니페스트 골격 + Kustomize base/overlay 구조 + GCP Ingress + README (확장 패턴) |
| D2 | 보류 | GCP 실제 전환 (나중) |

---

## 검증 계획

각 단계 완료 시 검증 방법.

### Phase A 검증
- 로컬에서 `docker compose -f infra/docker/docker-compose.prod.yml build` 성공
- 로컬에서 전체 스택 기동 → `scripts/verify-all.ps1` 통과

### Phase B 검증
- 워크플로우 lint: `actionlint`
- 더미 커밋으로 워크플로우 트리거 → GHCR 이미지 push 확인
- Oracle 서버에서 `docker compose pull` 성공

### Phase C 검증
- Oracle 서버에서 전체 스택 기동 → 공인 IP로 헬스체크
- gateway → 각 서비스 라우팅 정상

### Phase D 검증
- `kubectl apply --dry-run -k infra/k8s/overlays/gcp` 성공 (실제 배포 안 함)

---

## 위험 요소 및 대응

| 위험 | 대응 |
|------|------|
| Oracle 24GB로 부족 | Elasticsearch 제거 또는 pgvector로 통합, 일부 서비스 OFF |
| Oracle Free Tier 종료 | Hetzner(월 €4)로 마이그레이션, 동일 docker-compose 사용 |
| GHCR 비공개 이미지 인증 실패 | PAT 만료 주기 점검, Oracle에 GHCR 로그인 사전 등록 |
| 배포 중 서비스 중단 | `docker compose up -d` 는 변경 없는 컨테이너는 유지 — 무중단에 가까움 |
| Kafka/Elasticsearch가 ARM 미지원 | ARM 호환 이미지로 명시 (`apache/kafka:3.8.0` 등 검증 필요) |

---

## 참고

- 공통 가이드: `docs/AI_GUIDELINES.md`
- Claude 가이드: `CLAUDE.md`
- 기존 배포 템플릿: `.github/workflows/deploy-service-loan.yml` (대체 예정)
- 기존 로컬 스크립트: `scripts/start-all.ps1`, `scripts/verify-all.ps1`
