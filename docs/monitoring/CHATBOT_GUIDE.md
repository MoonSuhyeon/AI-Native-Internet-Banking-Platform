# 챗봇 상담 모니터링 가이드

> 대상 대시보드: **챗봇 상담 모니터링**
> 대상 독자: 개발팀 전원
> 환경: 로컬 직접 설치 기준 (`consultation-service` + Prometheus + Grafana)

---

## 이 가이드는 무엇인가요?

고객이 챗봇 상담을 시작하면 consultation-service는 시나리오·LLM·기능 실행 중 하나의 방식으로 응답하고, 필요 시 상담사에게 이관합니다. 이 대시보드는 챗봇이 지금 정상적으로 작동하는지, LLM 응답이 느려지거나 오류가 발생하진 않는지, Kafka 이벤트가 제대로 흐르는지 한눈에 확인할 수 있도록 만들어졌습니다.

---

## 1. 접속 방법

| 도구 | URL | 계정 |
|------|-----|------|
| Grafana (대시보드) | `http://localhost:3000` | admin / admin |
| Prometheus (알림 확인) | `http://localhost:9090/alerts` | 없음 |
| consultation-service 메트릭 원본 | `http://localhost:8087/metrics` | 없음 |

대시보드 경로: Grafana → **Dashboards** → **챗봇 상담 모니터링**

---

## 2. 대시보드 구성

| 섹션 | 패널 | 설명 |
|------|------|------|
| 세션 현황 | 활성 세션 수, 세션 시작, 상담사 이관율, 평균 만족도 | 챗봇 사용 현황 한눈에 |
| 메시지 라우팅 | 처리 방식별 추이, 처리 방식 분포 | 어떤 방식으로 응답하고 있는지 |
| LLM | 응답시간 P50/P95, 오류율, 오류 추이 | LLM 호출 성능 및 안정성 |
| Kafka | 이벤트 발행률, 이벤트 소비율 | 이벤트 파이프라인 상태 |
| HTTP | 요청률, 응답시간 P95, 5xx 오류율 | API 레이어 전반 상태 |
| 세션 종료 | 세션 종료 추이, 만족도 점수 분포 | 상담 완결 및 고객 만족도 |

---

## 3. 세션 현황 섹션

**용어:**
- **이관 (Handoff)** — 챗봇이 처리하지 못하거나 고객이 요청해서 실제 상담사에게 연결되는 것.
- **만족도 점수** — 상담 종료 시 고객이 입력하는 1~5점 평점.

### 활성 세션 수
- 현재 진행 중인 챗봇 상담 세션 수.
- 서비스 재시작 시 0으로 초기화된다.

| 등급 | 수치 |
|------|------|
| 정상 | 0 ~ 200 |
| 주의 | 200 초과 (알림 발생) |

### 세션 시작 (건/분)
- 분당 새로 시작된 챗봇 세션 수.
- 갑자기 0으로 떨어지면 서비스 다운 또는 업스트림(프론트엔드) 장애 의심.

### 상담사 이관율
- 지금까지 시작된 전체 세션 중 상담사로 이관된 세션의 비율.
- 높을수록 챗봇이 스스로 처리하지 못하는 문의가 많다는 뜻.

| 등급 | 비율 |
|------|------|
| 정상 | 30% 미만 |
| 주의 | 30% 이상 (알림 발생) |

> **참고**: 이 수치는 서비스가 시작된 이후의 **누적 비율**이다. 서비스를 재시작하면 카운터가 0에서 다시 시작되므로, 재시작 직후 첫 세션이 이관되면 100%로 표시될 수 있다. 시간이 지나면서 자연스럽게 안정된 비율로 수렴한다.

### 평균 만족도
- 상담 종료 시 입력된 만족도 점수의 평균 (1~5점).
- 3.5 미만이면 챗봇 응답 품질 점검 필요.

---

## 4. 메시지 라우팅 섹션

**용어:**
- **process_method** — 챗봇이 메시지 하나를 어떤 방식으로 처리했는지를 나타내는 분류값. 하나의 세션에서도 메시지마다 다른 방식으로 처리될 수 있다.

### 메시지 처리 방식별 추이
- 시간축으로 어떤 방식으로 응답했는지 보여준다.

| process_method | 한국어 | 의미 |
|---------------|--------|------|
| SCENARIO | 시나리오 흐름 | 버튼을 눌러 정해진 대화 흐름으로 처리 |
| STAFF_REQUEST | 상담사 이관 | 챗봇이 처리 못하거나 고객이 요청해 상담사로 연결 |
| FEATURE_PRODUCT_GUIDE | 상품 안내 | 예금·적금·청약 상품 목록 조회 |
| FEATURE_RATE_GUIDE | 금리 안내 | 기본금리·우대금리 조회 |
| FEATURE_JOIN_CONDITION | 가입 조건 안내 | 가입 대상·기간·금액 조건 조회 |
| FEATURE_PRODUCT_COMPARE | 상품 비교 | 두 상품 간 차이 비교 |
| FEATURE_TERMS_RAG | 약관 안내 | 중도해지·수수료 등 약관 검색 |
| FEATURE_FAQ | 자주 묻는 질문 | FAQ 고정 답변 제공 |
| FEATURE_CASH_FLOW_RECOMMEND | 맞춤 상품 추천 | 고객 현금흐름 분석 후 상품 추천 |
| FEATURE_MY_ACCOUNTS | 내 계좌 조회 | 본인 계좌 목록·잔액 조회 |
| FEATURE_INTEREST_HISTORY | 이자 내역 조회 | 이자 지급 내역 조회 |
| FEATURE_SAVINGS_GOAL | 저축 목표 계산 | 목표 금액 달성까지 기간·납입액 계산 |

> **참고**: 현재 LLM이 연결되지 않아 자연어 자유 응답은 지원되지 않는다. intent를 인식하지 못한 메시지는 모두 STAFF_REQUEST(상담사 이관)로 처리된다.

### 메시지 처리 방식 분포 (1h)
- 최근 1시간 누적 분포를 파이 차트로 보여준다.
- 실제로 사용된 방식만 표시된다 (0건인 항목은 제외).

---

## 5. LLM 섹션

> **⚠️ 현재 상태: LLM 미연결**
>
> consultation-service는 LLM(OpenAI GPT) 연결 설정(`CONSULTATION_OPENAI_API_KEY`, `CONSULTATION_OPENAI_MODEL`)은 준비되어 있지만, 실제 LLM API를 호출하는 코드가 아직 구현되지 않았다. 현재 챗봇은 키워드 기반 규칙 엔진으로만 동작한다.
>
> 따라서 **LLM 관련 패널 3개(응답시간, 오류율, 오류 추이)는 지금 모두 "No data"가 정상**이다. LLM이 연결되면 자동으로 데이터가 채워진다.

**용어 (LLM 연결 이후 참고용):**
- **LLM** — 챗봇이 자연어로 응답하기 위해 호출하는 AI 언어 모델 (OpenAI GPT 예정).
- **p50 / p95** — 응답시간 분포. p95 = 100건 중 느린 5건을 제외한 나머지의 최대 응답시간.

### LLM 응답시간 P50/P95
- LLM 호출부터 응답 수신까지 걸린 시간. (LLM 연결 후 수집 시작)

| 등급 | P50 | P95 |
|------|-----|-----|
| 정상 | 3초 미만 | 10초 미만 |
| 주의 | 3~5초 | 10~20초 (알림 발생) |
| 위험 | 5초 초과 | 20초 초과 |

### LLM 오류율
- LLM 호출 중 에러가 발생한 비율. (LLM 연결 후 수집 시작)

| 등급 | 비율 |
|------|------|
| 정상 | 0% |
| 주의 | 5% 미만 |
| 위험 | 10% 이상 (알림 발생) |

### LLM 오류 발생 추이
- 오류가 특정 시점에 몰려 발생하면: 외부 API 장애.
- 지속적으로 분산 발생하면: 특정 입력 패턴이 API 오류를 유발하는 것.

---

## 6. Kafka 섹션

> `CONSULTATION_KAFKA_ENABLED=false`이면 이 섹션은 항상 No data이다.

### Kafka 이벤트 발행률 (topic / status)
- 챗봇 이벤트가 Kafka 토픽으로 정상 발행되고 있는지 확인.
- `status=success`만 보여야 정상. `status=error`가 나타나면 즉시 확인 필요.

| 토픽 | 발행 이벤트 |
|------|-----------|
| `consultation.chatbot.events` | 세션 시작, 메시지 처리, 이관 요청 |
| `consultation.chat.events` | 상담사 채팅 종료 |

### Kafka 이벤트 소비율 (event_type / status)
- consultation-service가 구독 중인 토픽(예: `deposit.contract.events`)에서 메시지를 소비한 현황.
- `status=success`가 정상.

---

## 7. HTTP 섹션

### HTTP 요청률 (endpoint별)
- 각 API 엔드포인트의 초당 요청 수.
- `/chatbot/consultations/start`가 주요 지표.

### HTTP 응답시간 P95 (endpoint별)
- p95 응답시간이 2초를 넘으면 사용자 체감 성능 저하.

### HTTP 5xx 오류율
- 서버 내부 에러 비율. 0이 정상.
- Kafka 토픽 미생성 등 환경 문제 시 5xx 급등 가능.

---

## 8. 세션 종료 섹션

### 세션 종료 추이
- 세션이 종료되는 패턴 확인. 시작 추이와 비교해 대칭적이어야 정상.

### 만족도 점수 분포 (1h 누적)
- 각 점수대에 쌓인 종료 건수.

| 상황 | 해석 |
|------|------|
| ≤5.0 > ≤4.0 | 5점이 가장 많음 — 정상 |
| ≤2.0에 쏠림 | 챗봇 응답 품질 문제 — 즉시 확인 |
| 전체 0 | 상담이 종료되지 않거나 점수 미입력 |

---

## 9. 이상 징후 패턴별 확인 순서

### 챗봇 응답이 갑자기 느려졌다
1. **LLM 응답시간 P95** 확인 — 10초 초과 여부
2. **LLM 오류율** 확인 — 오류로 인한 재시도 여부
3. OpenAI API 상태 페이지 확인
4. consultation-service 로그에서 timeout 에러 확인

### 상담사 이관이 급증했다
1. **메시지 처리 방식 추이**에서 STAFF_REQUEST 비중 확인
2. **LLM 오류율** 확인 — LLM 실패로 인한 강제 이관 여부
3. LLM 오류가 없으면: 고객이 직접 이관 요청하는 케이스 증가 (STAFF_REQUEST 비중 확인)

### Kafka 발행 에러가 발생했다
1. Kafka 브로커 상태 확인: `docker ps --filter "name=kafka"`
2. 토픽 존재 여부 확인: `docker exec consultation-kafka kafka-topics.sh --list --bootstrap-server localhost:9092`
3. 토픽이 없으면 생성: `docker exec consultation-kafka kafka-topics.sh --create --topic <토픽명> --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1`

### 만족도가 갑자기 낮아졌다
1. **LLM 응답시간** 확인 — 응답이 너무 느리면 불만족 증가
2. **STAFF_REQUEST 비중** 확인 — 오류 이관이 잦으면 불만족 증가
3. 챗봇 시나리오 또는 LLM 프롬프트 최근 변경 이력 확인

---

## 10. 알림 규칙

| 알림명 | 조건 | 지연 | 심각도 |
|--------|------|------|--------|
| ChatbotActiveSessions | 활성 세션 > 200 | 5분 | warning |
| ChatbotHandoffRateHigh | 이관율 > 30% | 3분 | warning |
| ChatbotLlmErrorHigh | LLM 오류율 > 10% | 2분 | critical |
| ChatbotLlmResponseSlow | LLM P95 > 10초 | 3분 | warning |
| ChatbotKafkaPublishErrorHigh | Kafka 발행 오류율 > 5% | 2분 | critical |

알림 상태 확인: `http://localhost:9090/alerts`

---

## 11. 로컬 실행 방법

consultation-service는 자체 `docker-compose.yml`로 실행된다 (메인 `docker-compose.yml`과 별도).

```bash
# 프로젝트 루트에서
cd agents/consultation
docker compose up -d
```

서비스가 뜨면 `http://localhost:8087/health` 에서 `{"status":"UP"}` 응답을 확인한다.

> Prometheus와 Grafana는 메인 `docker-compose.yml`로 별도 기동되어 있어야 한다.

### 테스트 데이터 넣기

아래 순서대로 실행하면 세션 시작 → 메시지 처리 → 이관 → 종료까지 한 사이클이 완성된다.

```bash
# 1. 세션 시작 (entry_screen은 WEB_PERSONAL, HOME 등 실제 진입 화면 구분)
curl -X POST http://localhost:8087/chatbot/consultations/start \
  -H "Content-Type: application/json" \
  -d '{"customer_no":"TEST001", "entry_screen":"WEB_PERSONAL", "app_version":"1.0.0"}'
# → chatbot_consultation_id 값을 메모해둔다 (이하 {ID})

# 2. 메시지 전송 — FEATURE 경로 (상품 질문)
curl -X POST http://localhost:8087/chatbot/consultations/{ID}/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"적금 금리 알려줘"}'

# 3. 메시지 전송 — STAFF_REQUEST 경로 (이관 발생)
curl -X POST http://localhost:8087/chatbot/consultations/{ID}/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"직접 상담 받고 싶어요"}'

# 4. 이관된 세션 확인
curl http://localhost:8087/chat/queue
# → chat_consultation_id 값을 메모해둔다 (이하 {CHAT_ID})

# 5. 상담 종료 + 만족도 입력 (chatbot_session_ended_total, satisfaction_score 수집)
curl -X POST http://localhost:8087/chat/consultations/{CHAT_ID}/end \
  -H "Content-Type: application/json" \
  -d '{"satisfaction_score": 4}'
```

> **주의**: `chatbot_session_ended_total`과 `satisfaction_score`는 상담사 이관 후 `/chat/consultations/{id}/end` 를 호출해야 기록된다. 이관 없이 창을 닫은 세션은 종료 카운트에 포함되지 않는다.

---

## 12. "No data" 표시 시 대처법

| 패널 | No data / 0 원인 | 조치 |
|------|-----------------|------|
| LLM 응답시간, 오류율, 오류 추이 | **현재 LLM 미연결** | 정상 — LLM 구현 후 자동으로 채워짐 |
| LLM 토큰 사용량, 평균 토큰/호출 | 현재 LLM 미연결 | 정상 |
| 세션 시작 (건/분) | 지금 세션이 생성되지 않는 중 | 정상 — 트래픽 있을 때만 0이 아닌 값 표시 |
| Kafka 이벤트 발행/소비 | KAFKA_ENABLED=false 또는 트래픽 없음 | `.env`에서 `CONSULTATION_KAFKA_ENABLED=true` 확인 |
| HTTP 5xx 오류율 | 서버 에러 없음 | 정상 |
| 전체 패널 | Prometheus 미수집 | `http://localhost:9090/targets` 에서 consultation-service UP 여부 확인 |

---

## 13. 관련 파일 위치

| 파일 | 역할 |
|------|------|
| `infra/prometheus/alerts.yml` | 알림 규칙 (chatbot 그룹) |
| `infra/prometheus/prometheus.yml` | 스크레이핑 대상 (consultation-service 포함) |
| `infra/grafana/provisioning/dashboards/chatbot.json` | 대시보드 정의 파일 |
| `agents/consultation/app/metrics.py` | 메트릭 정의 |
| `agents/consultation/app/main.py` | 세션·메시지·만족도 계측 |
| `agents/consultation/app/llm.py` | LLM 응답시간·오류·토큰 계측 |
| `agents/consultation/app/kafka.py` | Kafka 발행·소비 계측 |
| `agents/consultation/.env` | 환경 변수 (API 키, Kafka 설정 등) |

---

## 14. 관련 가이드

| 문서 | 내용 |
|------|------|
| [INTERNET_BANKING_SERVICE_OVERVIEW_GUIDE.md](INTERNET_BANKING_SERVICE_OVERVIEW_GUIDE.md) | Service Overview 대시보드 — HTTP, JVM, DB, 인증/보안 |
| [KAFKA_PAYMENT_GUIDE.md](KAFKA_PAYMENT_GUIDE.md) | Kafka Payment 대시보드 — Consumer Lag, Outbox, DLQ |
| [ML_LOAN_REVIEW_GUIDE.md](ML_LOAN_REVIEW_GUIDE.md) | ML 대출 심사 모니터링 — 추론 성능, 드리프트 감지 |
| [INFRA_PORTS.md](INFRA_PORTS.md) | 모니터링 인프라 포트 및 설정 파일 위치 |
