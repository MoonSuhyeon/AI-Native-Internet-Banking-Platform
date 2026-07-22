# LLM / RAG 모니터링 가이드

> 대상 도구: **Langfuse** (auto-loan-review · consultation-service · goal-agent · review-ai-gateway), **Prometheus** (advisory-service RAG)
> 대상 독자: 개발팀 전원
> 환경: 로컬 Docker Compose 및 NCP 서버

---

## 이 가이드는 무엇인가요?

Grafana는 서비스의 **인프라 상태**(HTTP 응답시간, JVM 메모리, DB 커넥션 등)를 봅니다.
하지만 우리 프로젝트는 AI(LLM)와 RAG를 사용하는 서비스가 있는데, 인프라 지표만으로는 아래 질문에 답할 수 없습니다.

> "LLM이 왜 이상한 답을 했지?" "어떤 프롬프트가 들어갔지?" "토큰을 얼마나 썼지?" "어느 단계가 느린 거지?"

이런 질문에 답하는 도구가 **Langfuse**입니다.

### 도구별 역할

| 도구 | 역할 | 주요 확인 사항 |
|------|------|--------------|
| **Grafana** | 인프라 메트릭 | HTTP 응답시간, JVM 메모리, DB 커넥션 등 서비스 상태 |
| **Langfuse** | LLM 호출 추적 | 프롬프트/응답 내용, 토큰 비용, 응답시간 분포 |
| **Prometheus** | advisory-service RAG 메트릭 | 검색 지연시간, 임베딩 오류율 (이 가이드 하단 §7 참고) |

> LLM 전용 Grafana 대시보드(`auto-loan-review-llm.json`, `llm-overview.json`)는 현재 `infra/grafana/disabled_dashboards/`에 비활성화 상태입니다. LLM 트레이싱 목적에는 Langfuse가 훨씬 적합하기 때문입니다.

---

## 배경 지식: 주요 용어

이 가이드를 처음 읽는다면 아래 용어를 먼저 확인하세요.

### LLM (Large Language Model, 대규모 언어 모델)

GPT, Claude, Gemini처럼 자연어를 이해하고 생성하는 AI 모델입니다. 우리 프로젝트에서는 대출 심사 요약, 상품 추천, 감사 분석 등에 사용합니다.

### RAG (Retrieval-Augmented Generation, 검색 기반 생성)

LLM에게 질문할 때 관련 문서를 먼저 검색한 뒤 그 내용을 참고해서 답변하게 만드는 방식입니다. 예를 들어 고객이 "이 상품의 이자는 어떻게 받나요?"라고 물으면, 먼저 상품 DB에서 관련 내용을 검색(`rag-search`)한 후 LLM이 그 내용을 바탕으로 답변을 생성합니다.

### 프롬프트 (Prompt)

LLM에게 보내는 입력 텍스트입니다. "당신은 대출 심사 전문가입니다. 다음 고객 정보를 분석하세요..." 같은 형태입니다. 프롬프트 내용에 따라 AI 답변 품질이 크게 달라집니다.

### 토큰 (Token)

LLM이 텍스트를 처리하는 단위입니다. 대략 영어 단어 하나, 한국어 음절 1~2개가 토큰 1개에 해당합니다. API 비용은 토큰 수에 비례해 청구됩니다. "안녕하세요"는 약 4~5토큰입니다.

### 트레이스, 스팬, 제너레이션 (Langfuse 용어)

Langfuse는 LLM 호출을 계층 구조로 기록합니다.

```
트레이스 (Trace) — 하나의 요청 전체
 ├─ 스팬 (Span) — 내부 처리 단계 (예: RAG 검색)
 └─ 제너레이션 (Generation) — 실제 LLM 호출 1회
```

예를 들어 대출 심사 요청 1건이 들어오면:
- **트레이스** 1개 생성 (`auto-loan-review`) → 이 요청 전체를 감싸는 단위
- **스팬** 1개 생성 (`rag-search`) → 판례 검색 단계
- **제너레이션** 1개 생성 (`review_report_track1`) → 실제 Gemini LLM 호출

### P50, P95 (백분위수)

응답시간 분포를 나타냅니다.

- **P50**: 100건 중 빠른 순서로 50번째 — "보통 속도"
- **P95**: 100건 중 빠른 순서로 95번째 — "느린 상위 5%의 기준"

P95가 10초라면 100건 중 5건은 10초 이상 걸린다는 의미입니다. 서비스 체감 품질은 P50보다 P95로 판단하는 것이 더 정확합니다.

---

## 1. 서비스별 LLM/RAG 연결 현황

| 서비스 | LLM 모델 | Langfuse 연결 | Prometheus RAG | 비고 |
|--------|---------|:------------:|:--------------:|------|
| auto-loan-review | Gemini 2.0 | ✅ 구현 완료 | — | `LANGFUSE_ENABLED=true` 시 활성화 |
| consultation-service | OpenAI GPT-4o-mini | ✅ 구현 완료 | — | `CONSULTATION_LANGFUSE_ENABLED=true` 시 활성화 |
| goal-agent | Claude claude-opus-4-8 | ✅ 구현 완료 | — | `LANGFUSE_ENABLED=true` 시 활성화. API 키 없으면 Mock 자동 전환 |
| review-ai-gateway | Claude claude-opus-4-8 | ✅ 구현 완료 | — | `LANGFUSE_ENABLED=true` + `LLM_PROVIDER=claude` 필요 |
| advisory-service | — (LLM 없음) | ❌ 해당 없음 | ✅ 검색·임베딩 메트릭 | Prometheus로 관찰 (§7 참고) |

> **Mock이란?** 실제 LLM API를 호출하지 않고 미리 만들어둔 가짜 응답을 반환하는 모드입니다. API 키가 없거나 비용을 아끼고 싶을 때 사용합니다. Mock 모드에서는 Langfuse에 트레이스가 쌓이지 않습니다.

**Langfuse 기본값은 비활성입니다.** 현재 루트 `.env` 파일에 `LANGFUSE_ENABLED=true`가 설정되어 있어 auto-loan-review와 review-ai-gateway는 컨테이너 재시작 후 자동 활성화됩니다. consultation-service와 goal-agent는 각 서비스 환경변수로 별도 제어합니다.

> **루트 `.env` 파일이란?** 프로젝트 최상위 폴더(`internet_banking/`)에 있는 `.env` 파일입니다. docker-compose.yml이 이 파일을 읽어 각 서비스 컨테이너에 환경변수를 주입합니다.

---

## 2. 접속 방법

### 로컬 환경 (개발 PC)

| 도구 | URL | 인증 |
|------|-----|------|
| Langfuse | `http://localhost:3001` | 아래 최초 가입 절차 참고 |
| Arize Phoenix | `http://localhost:6006` | 없음 (현재 미사용, 데이터 없음 — §8 참고) |

로컬에서 Langfuse를 사용하려면 먼저 컨테이너를 실행해야 합니다.

```powershell
# 프로젝트 루트 디렉터리(internet_banking/)에서 실행
docker compose up -d langfuse langfuse-db
```

> `-d` 옵션은 백그라운드 실행(detach)을 의미합니다. 명령 실행 후 터미널을 닫아도 컨테이너는 계속 동작합니다.

### 서버 환경 (NCP)

| 도구 | URL | 인증 |
|------|-----|------|
| Langfuse | `https://langfuse.axfulbank.store` | 아래 최초 가입 절차 참고 |
| Arize Phoenix | `http://101.79.17.205:6006` | 없음 (현재 미사용) |

서버에서는 이미 컨테이너가 실행 중입니다. 별도 시작 명령이 필요 없습니다.

> **서버 Langfuse 접속이 안 된다면**: `https://langfuse.axfulbank.store`는 nginx 리버스 프록시를 통해 서버 내부 3001 포트로 연결됩니다. 접속이 안 될 경우 아래 방법으로 우회할 수 있습니다.
>
> - **SSH 터널**: 로컬 터미널에서 아래 명령 실행 후 브라우저에서 `http://localhost:13001` 접속
>   ```bash
>   ssh -L 13001:localhost:3001 root@101.79.17.205
>   ```
>   (포트 3001은 로컬 Langfuse와 충돌할 수 있으므로 13001로 포워딩)
> - **IP 직접 접근**: NCP ACG에서 TCP 포트 `3001`을 오픈한 뒤 `http://101.79.17.205:3001` 접속

### 로컬과 서버 Langfuse는 완전히 독립적

로컬(`localhost:3001`)과 서버(`101.79.17.205:3001`)는 DB가 분리된 별개의 Langfuse 인스턴스입니다.

- **데이터 미공유**: 로컬에서 발생한 트레이스는 서버에서 볼 수 없고, 반대도 마찬가지입니다
- **API 키 별도 발급**: 로컬 Langfuse에서 발급한 키는 서버에서 사용할 수 없습니다. 서버 환경에서 추적하려면 서버 Langfuse(`101.79.17.205:3001`)에 접속해 별도로 API 키를 발급하고 서버의 `.env`에 입력해야 합니다
- **계정 별도 생성**: 로컬에서 만든 계정·프로젝트는 서버에 없습니다. 서버 Langfuse에도 별도로 회원가입·프로젝트 생성이 필요합니다

> **서버 환경에서 Langfuse 트레이스를 수집하려면**: 서버 Langfuse(`https://langfuse.axfulbank.store`)에 가입·프로젝트 생성·API 키 발급 후, 서버에 SSH 접속하여 `~/internet_banking/.env`의 `LANGFUSE_SECRET_KEY`, `LANGFUSE_PUBLIC_KEY`를 서버 Langfuse에서 발급한 키로 교체하고 서비스를 재시작해야 합니다. 현재 서버 `.env`에 입력된 키는 로컬 Langfuse용입니다.

### 최초 가입 및 프로젝트 생성 (처음 한 번만)

우리가 사용하는 Langfuse는 self-hosted(직접 서버에 설치된) 버전입니다. 외부 서비스 가입이 아니라 우리 Docker 컨테이너에 직접 계정을 만드는 방식입니다.

**1단계: 관리자 계정 생성**
1. `http://localhost:3001` 접속
2. **Sign up** 클릭 → 이메일·비밀번호 입력 후 계정 생성
3. 첫 번째로 가입한 계정이 관리자(Admin)가 됩니다

**2단계: 프로젝트 생성**
1. 로그인 후 **New Project** 클릭
2. 프로젝트 이름 입력 (예: `AXFul-Bank`) → **Create**
3. 이 프로젝트 안에 모든 서비스의 트레이스가 쌓입니다

**3단계: API 키 발급**
1. 우측 상단 프로젝트 이름 클릭 → **Settings** → **API Keys**
2. **Create new API key** → **Secret Key**, **Public Key** 복사
3. 복사한 키를 루트 `.env`의 `LANGFUSE_SECRET_KEY`, `LANGFUSE_PUBLIC_KEY`에 입력

### 팀원 초대 방법

팀원마다 계정을 별도로 만들어 같은 프로젝트에 초대할 수 있습니다. **이메일·비밀번호를 공유할 필요가 없습니다.**

1. **Settings** → **Members** → **Invite Members**
2. 팀원 이메일 입력 후 초대 전송
3. 팀원이 초대 링크로 접속해 본인 계정 생성 → 같은 프로젝트 접근 가능

> **초대 이메일이 오지 않는 경우**: self-hosted Langfuse는 이메일 발송이 기본 비활성화되어 있습니다. 초대 후 **Settings → Members**에서 초대 링크를 복사해 팀원에게 직접 공유하세요.

---

## 3. Langfuse 사용 가이드

### 3-1. 화면 구성

로그인 후 프로젝트를 선택하면 아래 메뉴가 나타납니다.

**Dashboard (첫 화면)** — 전체 현황을 한눈에 요약

| 항목 | 설명 |
|------|------|
| Total Traces | 총 LLM 호출 건수 (트레이스 = 요청 1건) |
| Model costs | 모델별 누적 토큰 비용 ($) |
| Trace latencies | 전체 응답시간 분포 (P50, P95) |
| Model latencies | 모델별 응답시간 그래프 |

**Tracing → Traces** — LLM 호출 목록

각 행이 서비스에서 발생한 LLM 호출 1건입니다. 클릭하면 다음을 확인할 수 있습니다.

| 탭 | 확인 내용 |
|----|---------|
| Input | LLM에게 보낸 프롬프트 전문 |
| Output | LLM이 반환한 응답 전문 |
| Usage | 입력 토큰 수 / 출력 토큰 수 / 예상 비용 |
| Latency | 단계별 소요 시간 |

### 3-2. 서비스별 트레이스 이름

Langfuse 목록에서 `Name` 컬럼을 보면 어느 서비스·어느 기능에서 호출된 것인지 구분할 수 있습니다.

| 트레이스 이름 | 서비스 | 발생 시점 | 종류 |
|-------------|--------|---------|------|
| `auto-loan-review` | auto-loan-review | LLM 호출 또는 RAG 검색 요청 전체 | 트레이스 |
| `review_report_track1` / `track2` / `track3` | auto-loan-review | 심사 트랙별 Gemini LLM 실제 응답 생성 | 제너레이션 |
| `rag-search` | auto-loan-review | 정책 문서 pgvector 검색 | 스팬 |
| `rag-search` | consultation-service | 상품 정보 RAG 검색 | 스팬 |
| `llm-document-analyze` | consultation-service | 고객이 올린 PDF 문서 분석 | 스팬 |
| `llm-rag-answer` | consultation-service | RAG 검색 결과를 바탕으로 자유질문 답변 생성 | 스팬 |
| `llm-product-compare` | consultation-service | 두 상품을 GPT가 비교 분석 | 스팬 |
| `llm-savings-recommend` | consultation-service | 저축 목표에 맞는 상품 GPT 추천 | 스팬 |
| `goal-agent` | goal-agent | 목표 달성 플래너 에이전트 실행 | 스팬 |
| `maturity-agent` | goal-agent | 만기 재투자 에이전트 실행 | 스팬 |
| `spending-agent` | goal-agent | 지출 패턴 관리 에이전트 실행 | 스팬 |
| `audit-analysis` | review-ai-gateway | 감사 분석 요청 전체 | 트레이스 |
| `completeWithTools` | review-ai-gateway | Claude Tool Calling 실제 응답 생성 | 제너레이션 |

> **`rag-search` 이름이 두 서비스에서 겹치는 이유**: auto-loan-review와 consultation-service가 독립적으로 같은 이름을 붙였습니다. Tags 컬럼에 `auto-loan-review` 태그가 있으면 auto-loan-review, 없으면 consultation-service입니다.

### 3-3. 정상 / 주의 기준

| 항목 | 정상 | 주의 | 위험 |
|------|------|------|------|
| LLM 응답시간 P50 (보통 속도) | < 2초 | 2~5초 | 5초 초과 |
| LLM 응답시간 P95 (느린 상위 5%) | < 5초 | 5~10초 | 10초 초과 |
| 시간당 비용 | 서비스별 다름 | 전일 대비 2배 이상 | — |
| 오류 트레이스 | 없음 | 간헐적 | 지속 발생 |

### 3-4. 이상 징후별 확인 순서

**LLM 응답이 갑자기 느려졌다**
1. Dashboard → `Trace latencies` P95 값 확인
2. 특정 트레이스 이름에서만 느린지 Traces 목록에서 Name 필터로 좁히기
3. 느린 트레이스 클릭 → Latency 탭에서 어떤 스팬이 오래 걸리는지 확인
4. Input 탭에서 프롬프트 길이가 비정상적으로 길어진 건 아닌지 확인

**비용이 갑자기 늘었다**
1. Dashboard → `Model costs` 에서 어떤 모델이 급증했는지 확인
2. Traces → Usage 컬럼에서 토큰 수가 비정상적으로 많은 호출 찾기
3. 해당 트레이스 → Input 탭에서 프롬프트가 왜 길어졌는지 확인

**LLM이 이상한 답변을 했다**
1. Traces → 문제가 발생한 시점의 트레이스 클릭
2. Input 탭 → 어떤 프롬프트가 들어갔는지 확인 (잘못된 데이터가 포함됐는지)
3. Output 탭 → AI 응답 전문 확인

---

## 4. 서비스별 활성화 방법

### Langfuse API 키 발급

Langfuse에서 트레이스를 기록하려면 API 키가 필요합니다.

1. `http://localhost:3001` 접속 → 로그인
2. 우측 상단 프로젝트 이름 클릭 → **Settings** → **API Keys**
3. `Create new API key` 클릭 → **Secret Key**, **Public Key** 복사

발급한 키를 아래 각 서비스 설정에 넣습니다.

---

### auto-loan-review

**이미 설정 완료입니다.** 루트 `.env`에 아래 항목이 설정되어 있고, `docker-compose.yml`이 자동으로 컨테이너에 주입합니다. 서비스 재시작만 하면 됩니다.

```
# internet_banking/.env (현재 설정됨)
LANGFUSE_ENABLED=true
LANGFUSE_SECRET_KEY=sk-lf-xxxx   ← 이미 입력됨
LANGFUSE_PUBLIC_KEY=pk-lf-xxxx   ← 이미 입력됨
```

> 컨테이너 안에서는 `LANGFUSE_HOST=http://langfuse:3000`으로 자동 주입됩니다. 컨테이너끼리는 Docker 내부 네트워크로 통신하기 때문에 `localhost:3001`이 아닌 서비스 이름(`langfuse`)으로 접근합니다.

**추적되는 기능**: Gemini LLM 호출 (`review_report_track1/2/3`), pgvector RAG 검색 (`rag-search`)

---

### consultation-service

**이미 설정 완료입니다.** `agents/consultation/.env` 파일에 아래 항목이 설정되어 있습니다. 서비스 재시작만 하면 됩니다.

```
# agents/consultation/.env (현재 설정됨)
CONSULTATION_LANGFUSE_ENABLED=true
CONSULTATION_LANGFUSE_SECRET_KEY=sk-lf-xxxx   ← 이미 입력됨
CONSULTATION_LANGFUSE_PUBLIC_KEY=pk-lf-xxxx   ← 이미 입력됨
CONSULTATION_LANGFUSE_HOST=http://host.docker.internal:3001
```

> **`host.docker.internal`이 뭔가요?** consultation-service는 메인 docker-compose와 분리된 자체 docker-compose로 실행됩니다. 이 컨테이너 안에서 `localhost:3001`은 자기 자신을 가리키기 때문에 Langfuse에 접근할 수 없습니다. `host.docker.internal`은 Docker 컨테이너에서 호스트 PC의 포트에 접근하기 위한 특수 주소입니다 (Windows/Mac Docker Desktop에서 동작).

**추적되는 기능**: RAG 검색(`rag-search`), PDF 문서 분석(`llm-document-analyze`), 자유질문 답변(`llm-rag-answer`), 상품 비교(`llm-product-compare`), 저축 추천(`llm-savings-recommend`)

단, GPT를 실제로 호출하는 기능(문서 분석, 상품 비교 등)은 `CONSULTATION_OPENAI_API_KEY`가 설정된 경우에만 동작합니다. RAG 검색은 OpenAI 임베딩 API를 사용하므로 `CONSULTATION_OPENAI_API_KEY` 설정 시 항상 추적됩니다.

---

### goal-agent

goal-agent는 환경변수 접두사(prefix)가 없어 **루트 `.env`의 `LANGFUSE_*` 변수를 그대로 사용**합니다. 이미 설정된 상태입니다.

```
# internet_banking/.env (현재 설정됨)
LANGFUSE_ENABLED=true
LANGFUSE_SECRET_KEY=sk-lf-xxxx   ← 이미 입력됨
LANGFUSE_PUBLIC_KEY=pk-lf-xxxx   ← 이미 입력됨
LANGFUSE_HOST=http://localhost:3001
```

**추적되는 기능**: 목표 달성 플래너(`goal-agent`), 만기 재투자(`maturity-agent`), 지출 패턴(`spending-agent`)

`ANTHROPIC_API_KEY`가 설정되지 않으면 Mock 모드로 자동 전환되어 Langfuse에 트레이스가 쌓이지 않습니다.

---

### review-ai-gateway

루트 `.env` + `docker-compose.yml` 자동 주입으로 Langfuse 키는 이미 설정되어 있습니다.

단, 기본 LLM이 **Mock** 모드이기 때문에 실제 Claude를 호출하도록 별도 설정이 필요합니다. Mock 모드에서는 실제 LLM API 호출이 없으므로 Langfuse에도 트레이스가 쌓이지 않습니다.

```
# review-ai-gateway 실 Claude 호출로 전환할 때
LLM_PROVIDER=claude
```

> `CLAUDE_API_KEY`는 이미 루트 `.env`에 입력되어 있습니다. `LLM_PROVIDER=claude` 설정만 추가하면 됩니다.

**추적되는 기능**: 감사 분석 요청 전체(`audit-analysis`), Claude Tool Calling 응답 생성(`completeWithTools`)

---

## 5. 연결 검증 방법

처음 세팅하거나 환경이 바뀐 뒤에는 아래 체크리스트로 정상 동작 여부를 확인합니다.

### Step 1. Langfuse 컨테이너 확인

```powershell
docker ps | Select-String "langfuse"
```

아래 두 컨테이너가 모두 `Up` 상태여야 합니다.

```
ib-langfuse       ... Up ...
ib-langfuse-db    ... Up ...
```

보이지 않으면 실행합니다.
```powershell
docker compose up -d langfuse langfuse-db
```

### Step 2. 서비스 기동 시 활성화 로그 확인

**Java 서비스(auto-loan-review, review-ai-gateway)**
```
INFO  LangfuseService - [Langfuse] LLM 추적 활성화 → http://langfuse:3000
```

**Python 서비스(consultation-service, goal-agent)**
```
INFO  root - [Langfuse] LLM 추적 활성화 → http://host.docker.internal:3001
```

이 로그가 없으면 `LANGFUSE_ENABLED` 설정을 확인하세요.

### Step 3. 실제 요청 발생 후 Langfuse 확인

서비스의 LLM 기능을 실제로 호출한 뒤 `http://localhost:3001` → Tracing → Traces에서 트레이스가 생성되는지 확인합니다.

| 서비스 | 테스트 방법 |
|--------|----------|
| auto-loan-review | 아래 curl 명령어로 직접 호출 → `auto-loan-review` 트레이스 확인 |
| consultation-service | 상품 관련 자유질문 전송 → `rag-search` 스팬 확인 |
| goal-agent | `POST /agent/goal/chat` 호출 → `goal-agent` 트레이스 확인 |
| review-ai-gateway | `POST /internal/audit/analyze` + `LLM_PROVIDER=claude` 설정 → `audit-analysis` 트레이스 확인 |

> **auto-loan-review 주의**: UI에서 대출 신청을 해도 LLM이 실행되지 않습니다. 그 이유는 다음과 같습니다.
>
> auto-loan-review의 처리 흐름은 두 단계로 나뉩니다:
>
> 1. **동기 단계** (즉시 응답): loan-service가 `/evaluate`를 호출하면 ML 모델 + RuleEngine만 실행하고 바로 결과를 반환합니다. 이 단계에서는 LLM을 호출하지 않습니다.
> 2. **비동기 단계** (백그라운드): `revId`(대출 심사 이력 ID)가 요청에 포함된 경우에만 LLM 파이프라인(사유 분석 → 리포트 생성 → 에이전트 의견)이 백그라운드에서 실행됩니다. 완료 후 결과를 loan-service에 콜백으로 전송합니다.
>
> 문제는 loan-service가 `/evaluate`를 호출할 때 `revId=null`로 보낸다는 점입니다. auto-loan-review 코드가 `revId`가 없으면 "LLM 파이프라인 스킵"으로 처리하므로, **UI에서 대출 신청을 아무리 해도 LLM이 실행되지 않고 Langfuse 트레이스도 생성되지 않습니다.**
>
> LLM 트레이스를 발생시키려면 아래처럼 `revId`를 포함해서 auto-loan-review에 직접 curl로 호출해야 합니다.
>
> ```bash
> # 서버에서 실행 (로컬 환경이면 localhost:8089로 변경)
> curl -s -X POST http://localhost:8089/api/ai/auto-review/evaluate \
>   -H "Content-Type: application/json" \
>   -H "X-Internal-Token: " \
>   -d '{"revId":999,"annualIncomeKw":50000,"requestedAmountKw":30000,"requestedPeriodMo":36,"purposeCd":"LIVING","occupation":"EMPLOYEE","creditScoreProxy":720,"productCode":"DEMO_MORTGAGE"}'
> ```
>
> - `revId: 999` — 실제 DB에 없는 임의 번호입니다. auto-loan-review가 LLM 파이프라인을 실행하게 만들기 위한 값으로, 실제 심사 이력이 없으므로 완료 후 loan-service로 결과를 보낼 때 "존재하지 않는 revId"로 실패하지만 Langfuse 트레이스는 정상적으로 기록됩니다.
> - `X-Internal-Token: ` — 내부 서비스 간 인증 토큰입니다. 개발/데모 환경에서는 빈 값이라도 통과합니다.
>
> curl 실행 후 즉시 응답이 오지만 LLM 파이프라인은 백그라운드에서 별도로 실행됩니다. **약 20~30초 기다린 뒤** Langfuse에서 `auto-loan-review` 트레이스를 확인하세요.

> **트레이스가 바로 안 보여도 정상입니다.** Langfuse SDK는 트레이스를 비동기로 전송합니다. 요청 직후에는 목록에 나타나지 않을 수 있으며, **5~10초 후 새로고침**하면 보입니다.

---

## 6. 데이터가 안 보일 때

| 증상 | 원인 | 조치 |
|------|------|------|
| Langfuse 접속 안 됨 | 컨테이너 미실행 | `docker compose up -d langfuse langfuse-db` |
| 트레이스가 전혀 없음 | `LANGFUSE_ENABLED=false` | `.env`에서 `true`로 변경 후 서비스 재시작 |
| 트레이스가 없음 | LLM 호출이 발생하지 않음 | LLM 기능을 사용하는 API 직접 호출 후 확인 |
| 요청했는데 트레이스가 안 보임 | 비동기 전송 지연 | 5~10초 후 새로고침 |
| auto-loan-review 트레이스 없음 | `AI_LLM_PROVIDER=stub` (기본값) — stub은 실제 API를 호출하지 않고 미리 만들어둔 가짜 응답을 반환하는 모드. LLM 호출이 없으므로 Langfuse에도 아무것도 남지 않음 | 루트 `.env`에 `AI_LLM_PROVIDER=gemini-openai-compat` 설정. (`gemini-openai-compat`은 Google Gemini API를 OpenAI SDK 호환 방식으로 호출하는 모드. `GEMINI_API_KEY`도 필요하며 이미 루트 `.env`에 입력되어 있음) |
| auto-loan-review 트레이스 없음 | LLM 파이프라인이 `revId`가 없으면 실행되지 않음. UI에서 대출 신청을 해도 loan-service가 `revId=null`로 요청하므로 LLM이 스킵됨 | §5의 curl 명령어처럼 `revId`를 포함하여 직접 호출해야만 Langfuse 트레이스가 생성됨 |
| auto-loan-review Gemini API 로그에 `429 RESOURCE_EXHAUSTED` 오류 | Gemini 무료 티어 일일 할당량 소진. Google은 무료 사용자에게 하루에 처리할 수 있는 요청 수를 제한하며, 한도를 초과하면 이 오류를 반환함 | 다음 날 자정(태평양 표준시 기준) 리셋 후 재시도. 또는 Google AI Studio에서 유료 API 플랜으로 전환 |
| auto-loan-review Gemini API 응답 없음 또는 인증 오류 | `.env` 파일의 `GEMINI_API_KEY` 값이 `<AIzaSyB...>` 처럼 꺾쇠(`< >`)로 감싸진 상태로 저장되어 있음. 이 경우 API 키 자체가 `<키값>`이라는 문자열로 전달되어 인증 실패 | 서버 접속 후 다음 명령으로 꺾쇠를 제거: `sed -i 's/^GEMINI_API_KEY=<\(.*\)>$/GEMINI_API_KEY=\1/' ~/internet_banking/.env` 실행 후 auto-loan-review 재시작 |
| goal-agent 트레이스 없음 | `ANTHROPIC_API_KEY` 미설정 → Mock 모드 | 루트 `.env`에 `ANTHROPIC_API_KEY` 추가 |
| review-ai-gateway 트레이스 없음 | `LLM_PROVIDER=mock` (기본값) | `LLM_PROVIDER=claude` + `CLAUDE_API_KEY` 설정 |
| consultation-service 연결 실패 | Docker 컨테이너 안에서 `localhost:3001`은 자기 자신 | `CONSULTATION_LANGFUSE_HOST=http://host.docker.internal:3001` 확인 |

---

## 7. Advisory-service RAG 메트릭 (Prometheus)

advisory-service는 LLM을 사용하지 않고 pgvector 벡터 검색만 사용합니다. LLM이 없으니 Langfuse를 쓸 필요가 없고, 대신 **Prometheus 메트릭**으로 검색·임베딩 성능을 관찰합니다.

> **임베딩(Embedding)이란?** 텍스트를 수백 개 숫자의 벡터로 변환하는 과정입니다. "비슷한 의미의 문장"이 "비슷한 숫자 패턴"을 갖도록 변환하여 RAG 검색의 기반이 됩니다. OpenAI의 text-embedding-3-small 모델을 사용합니다.

### 수집 메트릭

| 메트릭 | 설명 | 구분 태그 |
|--------|------|---------|
| `advisory_rag_search_duration_seconds` | 벡터 검색 소요 시간 | `kind` (POLICY_CITATION: 정책 문서 \| SIMILAR_CASE: 유사 판례), `status` (success \| error) |
| `advisory_rag_search_results` | 검색 1회당 반환된 결과 건수 | `kind` |
| `advisory_rag_embedding_duration_seconds` | OpenAI 임베딩 API 호출 소요 시간 | `model`, `status` |
| `advisory_rag_embedding_calls_total` | 임베딩 API 누적 호출 횟수 | `model`, `status` |

### 권장 PromQL

```promql
# 검색 p95 지연시간 (정책 문서 / 유사 판례 구분)
histogram_quantile(0.95,
  sum(rate(advisory_rag_search_duration_seconds_bucket{status="success"}[5m])) by (kind, le)
)

# 임베딩 API 오류율
sum(rate(advisory_rag_embedding_calls_total{status="error"}[5m]))
/ sum(rate(advisory_rag_embedding_calls_total[5m]))
```

### Prometheus Alert

| Alert 이름 | 발동 조건 | 심각도 |
|-----------|---------|--------|
| `AdvisoryRagSearchFailRateHigh` | 검색 실패율 > 5%가 5분 이상 지속 | critical |
| `AdvisoryRagEmbeddingLatencySlow` | 임베딩 p95 > 2초가 5분 이상 지속 | warning |

---

## 8. Arize Phoenix

`requirements.txt`에 `arize-phoenix-otel` 패키지가 설치되어 있으나, 현재 어떤 서비스에서도 초기화 코드가 없습니다. `http://localhost:6006`에 접속해도 데이터가 수집되지 않습니다.

향후 OpenTelemetry 기반 상세 트레이싱이 필요할 때 consultation-service `main.py`에 Phoenix 초기화 코드를 추가하면 됩니다.

---

## 9. 관련 파일 위치

### Langfuse 연동 코드

| 파일 | 역할 |
|------|------|
| `agents/auto-loan-review/src/main/java/.../LangfuseService.java` | Langfuse HTTP API로 trace/span/generation 데이터 전송 |
| `agents/auto-loan-review/src/main/java/.../GeminiOpenAiCompatLlmClient.java` | LLM 호출 시 generation 기록 |
| `agents/auto-loan-review/src/main/java/.../RagSearchService.java` | RAG 검색 시 span 기록 |
| `agents/review-ai-gateway/src/main/java/.../LangfuseService.java` | Langfuse HTTP API로 trace/generation 데이터 전송 |
| `agents/review-ai-gateway/src/main/java/.../ClaudeLlmClient.java` | Claude 호출 시 trace + generation 기록 |
| `agents/consultation/app/main.py` | 앱 시작 시 `Langfuse()` 인스턴스를 직접 생성하여 초기화. 이 코드가 실행되어야 `rag.py`, `llm.py`의 `@observe` 데코레이터가 Langfuse와 연결됨 |
| `agents/consultation/app/rag.py` | `@observe(name="rag-search")` 데코레이터 — RAG 검색 함수에 붙어 있으며, 함수가 호출될 때 자동으로 Langfuse에 span을 기록 |
| `agents/consultation/app/llm.py` | `@observe(name="llm-document-analyze")`, `@observe(name="llm-rag-answer")` |
| `agents/consultation/app/features/product_compare.py` | `_langfuse_trace()` 헬퍼 함수로 `llm-product-compare` 트레이스 기록. `@observe` 데코레이터 대신 헬퍼를 사용하는 이유: `@observe`는 Python 모듈이 임포트되는 시점에 함수에 적용되는데, 이 시점에 Langfuse가 아직 초기화되지 않아 트레이스가 생성되지 않는 문제가 있었음. 함수 내부에서 직접 `Langfuse()` 인스턴스를 생성하는 헬퍼 방식은 실제 함수가 호출될 때 초기화되므로 이 문제가 없음 |
| `agents/consultation/app/features/savings_goal.py` | `@observe(name="llm-savings-recommend")` |
| `agents/goal-agent/app/main.py` | `_setup_langfuse()` — 앱 시작 시 Langfuse 클라이언트 초기화 |
| `agents/goal-agent/app/agent_goal_chat.py` | `@observe(name="goal-agent")` |
| `agents/goal-agent/app/agent_maturity_chat.py` | `@observe(name="maturity-agent")` |
| `agents/goal-agent/app/agent_spending_chat.py` | `@observe(name="spending-agent")` |

### Advisory RAG 메트릭

| 파일 | 역할 |
|------|------|
| `agents/advisory-service/src/main/java/.../AdvisoryMetrics.java` | RAG 검색·임베딩 Prometheus 메트릭 수집 |

---

## 10. 관련 가이드

| 문서 | 내용 |
|------|------|
| [INTERNET_BANKING_SERVICE_OVERVIEW_GUIDE.md](INTERNET_BANKING_SERVICE_OVERVIEW_GUIDE.md) | Grafana 서비스 인프라 대시보드 |
| [CHATBOT_GUIDE.md](CHATBOT_GUIDE.md) | 챗봇 상담 Grafana 대시보드 (Prometheus 메트릭) |
| [ML_LOAN_REVIEW_GUIDE.md](ML_LOAN_REVIEW_GUIDE.md) | ML 대출 심사 에이전트 모니터링 |
| [AGENT_UNIFIED_MONITORING_GUIDE.md](AGENT_UNIFIED_MONITORING_GUIDE.md) | 에이전트 통합 모니터링 (Prometheus 기반) |
| [INFRA_PORTS.md](INFRA_PORTS.md) | 모니터링 인프라 포트 및 설정 파일 위치 |
