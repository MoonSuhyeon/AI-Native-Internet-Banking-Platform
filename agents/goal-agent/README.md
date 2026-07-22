# Goal Agent Service

FastAPI 기반 AI 에이전트 서비스. 금융 목표 달성 플래너, 만기 알림·재투자 추천, 지출 패턴 분석 세 가지 에이전트를 제공합니다.

---

## 목차

- [아키텍처](#아키텍처)
- [에이전트 목록](#에이전트-목록)
- [실행 방법](#실행-방법)
- [API 명세](#api-명세)
- [환경 변수](#환경-변수)
- [Mock 모드](#mock-모드)
- [데이터베이스](#데이터베이스)

---

## 아키텍처

```
goal-agent (FastAPI, port 8086)
├── agent_goal_planner.py      # 금융 목표 달성 플래너 (룰 기반)
├── agent_goal_chat.py         # Goal Agent (Tool Calling / Claude API)
├── agent_maturity.py          # 만기 알림·재투자 추천 (룰 기반)
├── agent_maturity_chat.py     # 만기 에이전트 (Tool Calling / Claude API)
├── agent_spending_chat.py     # 지출 패턴 분석 에이전트 (Tool Calling / Mock)
├── main.py                    # FastAPI 라우터
├── config.py                  # 환경 변수 설정
├── database.py                # SQLAlchemy 세션
├── models.py                  # ORM 모델 (deposit_db)
└── services.py                # 입출금·계약 도메인 서비스
```

**동작 방식**

1. `ANTHROPIC_API_KEY`가 설정된 경우 → Claude Tool Calling 에이전트 실행
2. API Key 없거나 에이전트 비활성화 시 → Mock/룰 기반 fallback 자동 전환

---

## 에이전트 목록

### 1. Goal Agent — 금융 목표 달성 플래너

고객이 목표 금액과 기간을 입력하면 최적 금융 계획을 수립합니다.

- 잔여 기간·목표 금액 기반 월 적금액 산정
- 추천 예금 상품 연동
- 자연어 대화 지원 (`/agent/goal/chat`)

### 2. Maturity Agent — 만기 알림·재투자 추천 에이전트

30일 이내 만기 예정 계약을 탐지하고 재투자 시나리오를 제안합니다.

**재투자 시나리오 4종**

| 시나리오 | 설명 |
|---|---|
| `AUTO_RENEWAL` | 동일 상품 자동 재투자 |
| `REINVEST_NEW` | 신규 추천 상품으로 전환 |
| `WITHDRAW_ALL` | 전액 출금 |
| `PARTIAL_REINVEST` | 일부 재투자 + 일부 출금 |

환경 변수 `MATURITY_AGENT_ENABLED=false` 시 룰 기반 fallback으로 동작합니다.

### 3. Spending Pattern Agent — 지출 패턴 분석 에이전트

월간 지출 데이터를 분석하여 소비 구조 진단, 이상 지출 탐지, 절약 시나리오를 제공합니다.

**분석 6단계 (Tool Calling)**

| 순서 | 도구 | 역할 |
|---|---|---|
| 1 | `get_transaction_history` | 최근 3개월 거래내역 조회 |
| 2 | `analyze_spending_by_category` | 카테고리별 집계 및 직전 2개월 평균 비교 |
| 3 | `detect_anomalies` | 이상 지출 탐지 (증가율 기준) |
| 4 | `find_saving_opportunities` | 절약 가능 항목 탐색 |
| 5 | `generate_improvement_plan` | 개선 방안 생성 |
| 6 | `set_monthly_spending_goal` | 다음 달 소비 목표 설정 |

**자연어 입력 지원**

거래 데이터가 없어도 자연어 메시지에서 소비 행동 패턴을 추출하여 분석합니다.

```
"요즘 배달앱만 계속 써"
→ 배달 카테고리 감지 → 행동 기반 추정 분석 → 7단계 결과 출력
```

**부정 표현 처리**

```
"쇼핑은 거의 안 했어"
→ 쇼핑 카테고리 anomaly/plan 제외
```

**출력 형식 (7단계)**

```
### 1. 소비 구조 분석
### 2. 증감 분석 (수치 기반)
### 3. 카테고리 분석 (입력에 존재하는 것만)
### 4. 원인 분석
### 5. 절약 시나리오
### 6. 다음 달 목표
### 7. 한 줄 요약
```

---

## 실행 방법

### 사전 조건

- Python 3.11+
- PostgreSQL (deposit_db)
- Docker (선택)

### 로컬 실행

```bash
cd services/goal-agent

# 의존성 설치
pip install -r requirements.txt

# 환경 변수 설정 (.env)
cp .env.example .env  # 아래 환경 변수 섹션 참고

# DB 마이그레이션 (최초 1회)
python init_db.py

# 서버 실행
uvicorn app.main:app --host 0.0.0.0 --port 8086 --reload
```

### Docker

```bash
docker compose up goal-agent
```

---

## API 명세

### Health Check

```
GET /health
→ {"status": "ok", "tables": 19}
```

---

### Goal Agent

#### 목표 분석 (룰 기반)

```
POST /agent/goal/analyze
Content-Type: application/json

{
  "customer_id": "C001",
  "goal_amount": 50000000,
  "goal_months": 36
}
```

#### 자연어 채팅

```
POST /agent/goal/chat
Content-Type: application/json

{
  "customer_id": "C001",
  "message": "3년 안에 5000만원 모으고 싶어"
}
```

---

### Maturity Agent

#### 만기 예정 계약 조회

```
GET /agent/maturity/upcoming?days=30
```

#### 재투자 추천 조회

```
GET /agent/maturity/{contract_id}/recommendations
```

#### 만기 시나리오 실행

```
POST /agent/maturity/{contract_id}/process
Content-Type: application/json

{
  "scenario": "AUTO_RENEWAL"
}
```

#### 자연어 채팅

```
POST /agent/maturity/chat
Content-Type: application/json

{
  "customer_id": "C001",
  "message": "만기 예정 계약 확인해줘"
}
```

---

### Spending Pattern Agent

```
POST /agent/spending/chat
Content-Type: application/json

{
  "customer_id": "C001",
  "message": "이번 달 배달이 너무 많이 나온 것 같아"
}
```

**응답 예시**

```json
{
  "agent_type": "SPENDING_PATTERN_AGENT_MOCK",
  "this_month_total": 850000,
  "prev_avg_total": 620000,
  "anomalies": [
    {
      "category": "배달",
      "this_month_amount": 340000,
      "prev_avg_amount": 180000,
      "ratio": 1.9,
      "message": "배달 지출이 평균 대비 1.9배 증가"
    }
  ],
  "plan_items": [...],
  "spending_goals": [...],
  "total_estimated_saving": 115000,
  "message": "### 1. 소비 구조 분석\n...",
  "behavior_analysis": null,
  "behavior_categories": null,
  "warning": null
}
```

**자연어 입력 시 (거래 데이터 없음)**

```json
{
  "agent_type": "SPENDING_PATTERN_AGENT_MOCK",
  "behavior_analysis": "### 1. 소비 구조 분석\n...",
  "behavior_categories": ["배달", "카페"],
  "warning": "거래 데이터가 없어 메시지 기반 행동 추정 분석을 수행했습니다."
}
```

---

## 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `DATABASE_URL` | - | PostgreSQL 접속 URL (필수) |
| `ANTHROPIC_API_KEY` | (빈값) | Claude API Key. 없으면 Mock 모드로 자동 전환 |
| `SPENDING_AGENT_ENABLED` | `true` | 지출 패턴 에이전트 활성화 여부 |
| `MATURITY_AGENT_ENABLED` | `false` | 만기 에이전트 활성화 여부 |
| `API_KEY` | (빈값) | 내부 서비스 인증 토큰 (`X-Internal-Token` 헤더) |

**.env 예시**

```env
DATABASE_URL=postgresql+psycopg://deposit:deposit@localhost:5433/deposit_db
ANTHROPIC_API_KEY=
SPENDING_AGENT_ENABLED=true
MATURITY_AGENT_ENABLED=false
```

---

## Mock 모드

`ANTHROPIC_API_KEY`가 없거나 에이전트가 비활성화된 경우 Mock 모드로 동작합니다.

**Spending Agent Mock 동작 방식**

| 조건 | 동작 |
|---|---|
| 거래 데이터 있음 | DB 집계 + 자연어 힌트 결합 분석 |
| 거래 데이터 없음 | 자연어 키워드 추출 → 행동 추정 분석 |

**감지 카테고리**

`배달`, `카페`, `편의점`, `쇼핑`, `구독`, `식비`

**부정 표현 자동 감지**

"쇼핑은 거의 안 했어", "배달 안 해" 등 부정 표현을 감지하면 해당 카테고리를 분석 결과에서 제외합니다.

---

## 데이터베이스

`deposit_db` PostgreSQL 스키마를 사용합니다.

주요 테이블:

| 테이블 | 설명 |
|---|---|
| `deposit_transactions` | 입출금 거래내역 |
| `deposit_contracts` | 예금 계약 |
| `deposit_accounts` | 예금 계좌 |
| `deposit_banking_products` | 은행 예금 상품 |
| `customers` | 고객 정보 |

지출 패턴 분석은 `deposit_transactions`의 `transaction_type = 'PAYMENT'` 거래를 `description` 키워드 기반으로 카테고리 분류합니다.

---

## 프론트엔드 연동

Next.js (`web`) → `/api/agent/spending` 라우트 → `http://localhost:8086/agent/spending/chat`

```
web/app/api/agent/spending/route.ts
web/app/(personal)/spending/page.tsx
```

페이지 접속: `http://localhost:3001/spending`
