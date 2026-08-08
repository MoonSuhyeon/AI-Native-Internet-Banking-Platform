# AI-Native Internet Banking

AI Agent가 계좌·이체·자산관리·대출·결제·이상거래·고객지원·서류·상담·운영 등 **인터넷뱅킹의 주요 업무를 직접 수행하고 오케스트레이션하는 AI-Native 금융 플랫폼**입니다.

기존 인터넷뱅킹이 사용자가 화면에서 기능을 직접 찾아 실행하는 구조라면, 본 프로젝트는 고객의 요청과 금융 데이터를 기반으로 Agent가 **조회 → 분석 → 판단 → 실행 → 검증**까지 수행하도록 업무 흐름을 재설계합니다.

단순한 금융 상담 챗봇이 아니라, 실제 금융 업무를 기능 단위로 분해하고 각 업무에 필요한 **Tool·API·데이터·정책을 Agent Workflow로 연결**하여 반복적인 직원 업무와 고객의 수작업을 자동화하는 것을 목표로 합니다.

## Agent 업무 범위

| 영역       | Agent 역할                                     |
| -------- | -------------------------------------------- |
| **계좌**   | 잔액·거래내역 조회 및 금융 흐름 분석                        |
| **이체**   | 이체 요청 → 거래 검증 → 사용자 승인 → 실행 → 결과 확인          |
| **자산관리** | 현금흐름 분석 → 저축 여력 진단 → 금융상품 추천                 |
| **대출**   | 상환부담 분석 → 대출 가능성·부담 진단 → 결과 안내               |
| **결제**   | 예정 결제·고정비 분석 및 현금흐름 영향 진단                    |
| **이상거래** | 의심 거래 탐지 → 거래·인증 정보 조사 → 필요 시 담당자 Escalation |
| **고객지원** | 금융상품·거래·정책·업무 절차 문의 자동 응대                    |
| **서류**   | 금융 업무에 필요한 서류 안내 → 제출·수집 → 형식·내용 검증          |
| **상담**   | 고객 금융 상황 분석 → 상담 내용 정리 → 맞춤형 추천안 생성          |
| **운영**   | 반복적인 내부 업무 자동화 및 예외 업무 Human Escalation      |

## 핵심 구조

```text
                    Customer
                       │
                       ↓
              AI Banking Agent
                       │
                Intent / Task
                  Analysis
                       │
                       ↓
              Agent Orchestrator
                       │
        ┌──────────────┼──────────────┐
        ↓              ↓              ↓
     Account         Transfer       Finance
      Tools           Tools          Tools
        │              │              │
        ├──── Loan / Payment / FDS ───┤
        │              │              │
        └──────────────┼──────────────┘
                       ↓
                Decision / Action
                       │
              ┌────────┴────────┐
              ↓                 ↓
        Auto Process       Human Review
              │                 │
              ↓                 ↓
        Result Verify       Escalation
              │
              ↓
        Customer Response
```

본 프로젝트는 **“사람이 인터넷뱅킹을 사용하는 서비스”에서 “AI가 인터넷뱅킹 업무를 수행하고 사람은 필요한 순간에 승인·판단하는 서비스”로의 전환**을 지향합니다.


---

## 서비스 구성

| 서비스 | 언어 / 프레임워크 | 포트 | 역할 |
|---|---|---|---|
| `gateway-service` | Java 17 / Spring Cloud Gateway | 8080 | API 게이트웨이, 라우팅, Rate-limit |
| `customer-service` | Java 17 / Spring Boot 3.x | 8081 | 고객 정보, 인증(ID·금융인증서) |
| `deposit-service` | Java 17 / Spring Boot 3.x | 8082 | 예금상품·계약·계좌·거래·상품추천 |
| `deposit-api` | Python 3.11 / FastAPI | 8082 (proxy) | deposit-service 앞단 경량 레이어, 이체 실행·소유주 검증 |
| `loan-service` | Java 17 / Spring Boot 3.x | 8083 | 대출 신청~상환 전 생애주기 + RAG |
| `master-service` | Java 17 / Spring Boot 3.x | 8085 | 공통 코드·마스터 데이터 |
| `ai-service` | Java 17 / Spring Boot 3.x | 8086 | AI 모델 서빙, 임베딩, RAG 벡터검색 |
| `auto-loan-review` | Java 17 / Spring Boot 3.x | 8089 | 자동심사 에이전트, 편향검증, 4-eye 승인, 드리프트·공정성 모니터링 |
| `review-ai-gateway` | Java 17 / Spring Boot 3.x | 8088 | 심사 AI 라우팅 게이트웨이 |
| `doc-agent` | Java 17 / Spring Boot 3.x | 8087¹ | 대출 서류 심사(OCR 추출·위변조 검증·라우팅), loan-service와 REST 연동 |
| `payment-service` | Java 17 / Spring Boot 3.x | 8080² | 타행이체(EXTERNAL) 청산 — KFTC/BOK 망 라우팅, Outbox·Saga 보상 |
| `fraud-investigation-agent` | Python / LangGraph + FastAPI | 8090 | 이상거래 조사 에이전트 — 시나리오 경합·HITL 승인 |
| `api-gateway` | Java 17 / Spring Cloud Gateway | — | (보조 게이트웨이) |
| `consultation-service` | Python 3.11 / FastAPI | 8087¹ | 챗봇·상담사 채팅, LLM 폴백, 챗봇 이체 실행 |
| `web` | Next.js 15 / TypeScript | 3001 | 고객·어드민 통합 프런트엔드 |
| `common` | Java | — | 서비스 공통 모듈 (`com.bank.common.security.BankRole` 등) |
| `infra` | Docker Compose | — | PostgreSQL 16, Redis 7, Prometheus, Grafana |

> ¹ `doc-agent`와 `consultation-service`는 기본 포트가 모두 `8087`로 겹친다. 동시에 기동하지 않거나 `DOC_AGENT_APP_PORT`로 분리한다.
> ² `payment-service`와 `gateway-service`는 기본 포트가 모두 `8080`으로 겹친다. 로컬에서 함께 띄우려면 `PAYMENT_APP_PORT`로 분리한다.

---

## 기술 스택

| 구분 | 기술 |
|---|---|
| 백엔드 | Java 17, Spring Boot 3.3.5, Gradle Multi-module |
| 챗봇/AI 경량 서비스 | Python 3.11, FastAPI, SQLAlchemy, Pydantic v2 |
| 프런트엔드 | Next.js 15, TypeScript, TanStack Query 5.x, Tailwind CSS, shadcn/ui |
| DB | PostgreSQL 16 (서비스별 독립 DB), pgvector (AI/RAG) |
| 캐시 | Redis 7 |
| 메시지 큐 | Apache Kafka 3.8, Confluent Schema Registry 7.6 |
| AI/LLM | Spring AI, OpenAI GPT-4o-mini, Vertex AI |
| 모니터링 | Prometheus, Grafana, Loki, Promtail, Langfuse, Phoenix (OpenTelemetry) |
| 테스트 | JUnit 5, Mockito, @SpringBootTest / pytest 8 |
| 패키지 루트 | `com.bank` |

---

## 모듈 구조

```
internet_banking/
├── docker-compose.yml
├── common/
├── services/
│   ├── customer-service/
│   │   └── config/SecurityConfig.java   # CORS 허용 (localhost:3001)
│   ├── deposit-service/
│   │   ├── service/TransactionService.java          # 이체 실행, 한도 검증
│   │   ├── service/IdempotentTransactionSaver.java  # 멱등성 키 중복 방지
│   │   └── resources/db/migration/                  # Flyway V1~V14
│   ├── deposit-api/
│   │   └── app/main.py                  # 이체 엔드포인트, 소유주 검증
│   ├── loan-service/
│   ├── consultation-service/
│   │   └── app/
│   │       ├── main.py                  # FastAPI 앱, 라우터, CORS
│   │       ├── services.py              # ChatbotService, ChatService — 인텐트 분류·기능 라우팅
│   │       ├── features/
│   │       │   ├── product_compare.py   # ProductCompareAgent — 상품명 매칭·비교표·GPT 분석
│   │       │   ├── savings_goal.py      # SavingsGoalAgent — 저축 목표 멀티턴
│   │       │   └── cash_flow.py         # 현금흐름 기반 추천
│   │       └── kafka.py
│   └── (기타 서비스)
├── web/
│   ├── app/
│   │   ├── (personal)/
│   │   │   ├── login/page.tsx           # ID·금융인증서 통합 세션 저장
│   │   │   ├── logout/page.tsx          # sessionStorage 전체 초기화
│   │   │   └── products/deposit/join/[id]/page.tsx
│   │   └── api/
│   │       ├── customer/cert-login/route.ts  # 금융인증서 SSR 프록시
│   │       └── v1/auth/cert-login/route.ts   # 구 경로 호환
│   └── components/
│       ├── layout/Header.tsx            # pathname 변경 시 세션 재평가
│       └── chatbot/ChatbotWidget.tsx    # 챗봇 위젯
├── infra/
└── docs/
```

---

## deposit-service 주요 기능

### 상품 관리

- 예금·적금·청약·입출금 상품 조회 (유형별 필터링, 판매 상태 기준)
- 상품별 기본금리·우대금리·가입기간·가입금액 조건 관리
- 가입 대상 그룹(연령·직군 등) 연동 — `GET /products` 응답에 `targetGroups[]` 포함 (`targetGroupId`, `targetGroupName`, `minAge`, `maxAge`)
- 상품 `savingType` 필드(`REGULAR` / `FREE`)로 정기적금·자유적금 탭 분류
- 상품 slug는 항상 `product-{productId}` 패턴으로 생성 — 상품명 하드코딩 맵(`PRODUCT_NAME_TO_SLUG`) 제거, DB 상품명 변경 시 조용한 오동작 방지
- `getDepositSlugByProductId(productId)` — `productName` 파라미터 제거됨. 상세 페이지 라우팅이 필요한 경우 `productId`를 직접 쿼리 파라미터로 전달할 것

### 계약 관리

- 계약 생성·조회·상태변경·해지·만기 처리
- 가입금액 검증: `minJoinAmount` 이상, `maxJoinAmount` 이하
- 계좌 자동 생성, 우대금리 이력, 특약 동의 관리

### 계좌 관리

- 계좌 목록 조회 (고객 ID 기준, CLOSED 계좌 제외)
- 계좌 유형별 분류: 예금 / 적금 / 청약 / 입출금
- `isWithdrawable` 플래그 기반 출금 가능 계좌 구분 (값이 없는 경우 계좌명으로 fallback)

### 거래 관리

- 입금·출금·이체 거래 원장(Transaction) 기록
- 당행이체(`INTERNAL`): 수취 계좌 실존 여부 및 계좌번호 일치 검증
- 타행이체(`EXTERNAL`): 상대 은행 코드·이름·예금주명 기록
- 이체 실행 지점 단일화 — result 페이지에서만 호출, 중복 실행 방지

### 이체 멱등성 (idempotency key)

동일한 요청이 네트워크 재시도 등으로 중복 실행되는 것을 방지한다.

- deposit-api가 `idempotency_key`(UUID v4)를 생성해 이체 요청에 포함
- `IdempotentTransactionSaver`가 `REQUIRES_NEW` 트랜잭션으로 키 유일성 보장
  - 이미 처리된 키: 기존 Transaction 반환 (중복 실행 없음)
  - 신규 키: 정상 저장 후 반환
- DB: `deposit_transactions.idempotency_key` 컬럼 + 부분 유니크 인덱스 (NULL 제외)

```sql
-- V13 마이그레이션 (자동 적용)
ALTER TABLE deposit_transactions ADD COLUMN idempotency_key VARCHAR(64) NULL;
CREATE UNIQUE INDEX uq_deposit_transactions_idempotency_key
  ON deposit_transactions (idempotency_key) WHERE idempotency_key IS NOT NULL;
```

> **주의**: deposit-service가 V13 마이그레이션 적용 전에 기동 중이었다면 컬럼이 없어 이체·거래 조회가 500으로 실패한다. 위 SQL을 직접 실행하거나 서비스를 재기동한다.

### 이체 일일 한도 검증

이체 요청마다 당일(KST 00:00~23:59) 누적 금액·횟수를 검증한다.

- 초과 시: `DAILY_TRANSFER_AMOUNT_EXCEEDED` / `DAILY_TRANSFER_COUNT_EXCEEDED` 에러 코드 반환
- 한도 미설정 시 검증 건너뜀 (`null` 허용)
- KST 기준 자정 리셋 (`ZoneId.of("Asia/Seoul")`)

### 상품 추천 (CashflowBasedRecommendService)

고객의 최근 거래내역 현금흐름을 분석해 예금·적금 상품을 최대 5개 추천한다. 외부 AI/LLM 호출 없이 100점 채점 모델로 동작한다.

#### 추천 흐름

```
순현금흐름(netCashFlow) / periodMonth → monthlySavings
→ 필터링 → 100점 채점 → 총점 내림차순 상위 5개 반환
```

#### 필터링 규칙 (점수 산정 전 제외)

| 조건 | 제외 상품 |
|---|---|
| `ProductType.SUBSCRIPTION` | 청약 상품 전체 (사용자 요청 시) |
| 상품명·설명에 "군인", "장병", "군무원", "사병", "병사" 포함 | 특수 대상 상품 |
| **1순위** 상품 `targetGroups[].maxAge < 고객 만 나이` | 나이 상한 초과 (DB 기준) |
| **2순위** `targetGroups` 데이터 없을 때 "청년도약", "청년우대" 등 키워드 포함 + 고객 나이 > 34 | 청년 전용 상품 (키워드 fallback) |
| DEPOSIT — `currentBalance` < minJoinAmount | 잔액 부족 |
| DEPOSIT — `currentBalance` > maxJoinAmount | 한도 초과 |
| SAVINGS — `monthlySavings` < minJoinAmount | 월 저축 여력 부족 |

나이 조건 판단 우선순위:
- **1순위**: `deposit_target_groups.min_age` / `max_age` 컬럼 기준 (DB). 나이 제한이 있는 그룹 중 고객이 하나라도 충족하면 통과.
- **2순위**: `targetGroups` 데이터가 없거나 나이 필드가 null이면 상품명·설명의 "청년" 키워드 보조 필터링.
- **3순위**: 고객 생년월일 조회 실패 시 키워드 보조 필터링만 적용.

#### 100점 채점 모델

| 항목 | 배점 | 산정 방식 |
|---|---|---|
| 재정 적합도 | 40점 | 예금: `currentBalance / minJoinAmount` 비율 평가. 적금(자유적금 포함): `monthlySavings / (minJoinAmount×2)` 비율 평가. 저축 성장형(연간 저축 가능액 > 현재 잔액) 적금에 ×1.30 가중치 부여 |
| 예상 수익 | 30점 | 실제 이자액 계산 후 후보 풀 내 상대 정규화(1위=30점). 예금: `잔액×금리×기간/12`, 적금(자유적금 포함): `monthlySavings × Σ가중개월 × 금리/12` |
| 유동성 매칭 | 20점 | 거래 빈도와 상품 만기를 기준으로 고객의 자금 고정 가능성을 평가합니다. 입출금 거래가 적은 고객은 자금이 일정 기간 묶이는 중장기 상품에 더 높은 점수를 부여하고, 거래가 많은 고객은 짧은 만기 또는 유동성이 높은 상품에 상대적으로 높은 점수를 부여합니다. 조기해지 허용 시 +2점 |
| 부가 혜택 | 10점 | 비과세 혜택 +6점, 중도해지 가능 +4점 |

#### 월 잉여자금 부족(monthlySavings ≤ 0) 예외 처리

월 잉여자금이 0 이하인 경우 신규 저축 여력이 부족한 것으로 판단하여 현재 보유 잔액을 기준으로 예금 중심 fallback 추천을 제공합니다. 적금 상품은 추천 후보에서 제외되며, 응답의 `fallbackReason` 필드에 사유 문구가 포함됩니다.

#### bestRate 계산

```
bestRate = BASE 금리 최댓값 + PREFERENTIAL 금리 전체 합산
```

> 기간별 BASE 구간이 여럿이면 최댓값만 취하고, 우대금리는 조건 무관 전부 합산한다.

```
GET /api/products/recommend-agent?customerId={customerId}&periodMonth={periodMonth}
X-Customer-Id: {customerId}
```

---

## 계좌이체 전체 흐름

### 개인뱅킹 이체 (웹 → deposit-api → deposit-service)

| 단계 | 프런트 라우트 | 역할 |
|---|---|---|
| STEP 1. 이체정보 입력 | `transfer/account/page.tsx` | 출금 계좌 선택(입출금 타입만 표시, 예금·적금·청약 제외), 당행/타행 탭 전환, 입금 계좌·금액 입력 |
| STEP 2. 이체정보 확인 | `transfer/confirm/page.tsx` | 금융인증서 PIN 확인 후 result로 이동 |
| STEP 3. 이체결과 | `transfer/result/page.tsx` | `executeDepositTransfer()` → 실제 이체 실행 |

#### deposit-api 이체 요청 파라미터

```
POST /api/transactions/transfer
X-Customer-Id: {customerId}
```

| 파라미터 | 설명 |
|---|---|
| `fromAccountId` | 출금 계좌 ID |
| `toAccountId` | 당행이체 시 입금 계좌 ID (미전달 시 `toAccountNo`로 자동 조회) |
| `toAccountNo` | 입금 계좌번호 |
| `amount` | 이체금액 |
| `transferType` | `INTERNAL`(당행) 또는 `EXTERNAL`(타행) |
| `counterpartyBankCode` | 타행 은행 코드 |
| `counterpartyBankName` | 타행 은행명 |
| `counterpartyName` | 수취인 성명 |
| `channelType` | 항상 `INTERNET` |

#### deposit-api 소유주 검증 (보안)

- `X-Customer-Id` 헤더 값과 출금 계좌의 `customer_id` 불일치 시 `403 Forbidden` 반환
- `counterparty_account_id`를 이체 기록에 저장해 거래 추적 가능

> 위 deposit-api 경로는 **당행이체(INTERNAL)** 전용이다. **타행이체(EXTERNAL)** 는 아래 payment-service 경로를 탄다.

### 타행이체 (EXTERNAL) — payment-service

웹은 `transferType`에 따라 경로를 분기한다. 타행이체는 deposit-api가 아니라 `payment-service`를 호출하며, 외부 청산망(KFTC/BOK)을 거치므로 **비동기(202 Accepted)** 로 처리된다.

| 유형 | 경로 | 엔드포인트 | 완결 |
|---|---|---|---|
| 당행 INTERNAL | web → deposit-api → deposit-service | `POST /api/transactions/transfer` | 동기 (200) |
| 타행 EXTERNAL (< 10억) | web → payment-service → Kafka **KFTC** | `POST /api/v1/payments` | 비동기 (202) |
| 타행 EXTERNAL (≥ 10억) | web → payment-service → Kafka **BOK** | `POST /api/v1/payments` | 비동기 (202) |

```
POST /api/v1/payments (payment-service:8080)
X-User-Id: {userId}
X-Auth-Token-Id: {authTokenNo}      # web에서 이체마다 생성, 형식 T{timestamp}{random}
X-Idempotency-Key: {idempotencyKey} # 중복 제출 방지
```

- **은행코드 매핑**: web의 `PAYMENT_BANK_CODE_MAP`이 브라우저 코드를 금융결제원 표준 3자리 코드로 변환(예: `IBK→003`, `NH→011`, 데모용 `DAON→088`).
- **라우팅**: `receiverBankCode`가 자행이 아니면 EXTERNAL. 금액 ≥ 10억이면 한은망(BOK), 미만이면 금융결제원(KFTC).
- **내부 처리**: `payment_instruction` 생성 → 송신계좌 검증 → 출금 → 분개(당좌/청산대기) → Outbox로 `KFTC_REQUEST_SENT`/`BOK_REQUEST_SENT` 발행 → 외부망 응답 수신 후 상태 전이(`CLEARING → COMPLETED`/`REVERSING`).
- **인프라**: payment-service는 3개 Kafka 클러스터(KFTC·BOK·Internal)를 사용하며, 발행은 Outbox 패턴·실패 시 Saga 보상 트랜잭션으로 처리한다.

> ⚠ 데모 환경에서는 외부망 응답 측이 목(mock)이므로, 실제 수취은행 검증 없이 `CLEARING` 이후 시뮬레이션 응답으로 완결된다.

### 챗봇 이체 (ChatbotWidget → consultation-service → deposit-api)

```
ChatbotWidget (웹)
  └─ POST /chatbot/transfer (consultation-service:8087)
       └─ POST /api/transactions/transfer (deposit-api:8082)
            └─ deposit-service TransactionService
```

- 비로그인 상태에서 이체 버튼 클릭 시: 로그인 안내 → 로그인 완료 시 이체 재시작
- 챗봇 위젯 열기(`onOpen`) 시 `/api/v1/customers/me` 서버 토큰 검증 → 만료·없음 시 localStorage 초기화 후 로그인 유도

---

## 예금 상품 가입 흐름

| 단계 | 라우트 | 설명 |
|---|---|---|
| 상품 목록 | `/products/deposit/list` | 탭별 상품 목록, 가입하기 버튼 |
| 상품 상세 | `/products/deposit/[id]` | 상품 안내·금리·약관 상세 |
| 가입 | `/products/deposit/join/[id]` | 약관동의 → 정보입력 → 정보확인 3단계 |

지원 상품 유형: `예금` / `정기적금` / `자유적금` / `입출금자유` / `주택청약`

| 유형 | 약관 | 가입기간 | 이자지급방법 |
|---|---|---|---|
| 예금 | 거치식예금약관 포함 | O | O |
| 적금 | 적립식예금약관 포함 | O | O |
| 입출금자유 | 보통예금약관 포함 | X | X |
| 주택청약 | 주택청약종합저축약관 | O | O |

---

## 계좌 해지 흐름

| 단계 | 설명 |
|---|---|
| 계좌 선택 | 계좌조회 페이지의 각 계좌 카드에서 **해지** 버튼 클릭 → `accountId` 쿼리 파라미터와 함께 해지 페이지로 이동 |
| 해지 확인 | `accountId`가 전달되면 계좌 선택 단계를 건너뛰고 2단계(해지계좌 확인)로 자동 진입 |
| 비밀번호 입력 | 마우스 숫자패드 또는 키보드 입력 |
| 지급 방법 선택 | **당행 계좌**(내 입출금 계좌 중 선택) / **타행 계좌**(은행 선택 + 계좌번호 직접 입력) / **현금** |
| 완료 | 해지 API 호출 → 잔액 `accountOverrides` localStorage에 반영 |

```
GET /products/deposit/inquiry/terminate?accountId={accountId}
```

---

## 거래조회 개선

- 계좌 목록 로드 후 각 계좌별로 거래내역을 병렬 조회(`Promise.all`) → 전체 거래를 한 번에 로드
- 거래 필터링 기준: `accountNumber` 일치 **또는** `accountId` 일치 — 계좌번호가 없는 거래도 누락 없이 표시
- 선택 계좌·캘린더 계좌 초기값을 첫 번째 계좌로 자동 설정

---

## 인증 (customer-service)

### 로그인 방식

| 방식 | 엔드포인트 | 설명 |
|---|---|---|
| ID/비밀번호 | `POST /api/v1/auth/login` | 일반 로그인 |
| 금융인증서 (FinCert) | `POST /api/v1/auth/cert-login` | 인증서 PIN 기반 로그인 |

### 보안카드 에러코드 (CUST_140~144)

| 코드 | HTTP | 설명 |
|---|---|---|
| `CUST_140` | 404 | 활성 보안카드 없음 |
| `CUST_141` | 409 | 이미 활성 보안카드 존재 |
| `CUST_142` | 401 | 보안카드 코드 불일치 |
| `CUST_143` | 410 | 보안카드 챌린지 만료·없음 |
| `CUST_144` | 400 | 챌린지에 없는 위치 코드 포함 |

### 금융인증서 로그인 SSR 프록시

브라우저에서 `customer-service:8081`을 직접 호출하면 CORS 오류가 발생하므로, Next.js 서버에서 프록시한다.

```
웹 브라우저 → POST /api/customer/cert-login (Next.js SSR)
               └─ POST http://localhost:8081/api/v1/auth/cert-login (customer-service)
```

- 파일: `web/app/api/customer/cert-login/route.ts`
- 8초 AbortController 타임아웃, customer-service 미기동 시 503 반환
- PIN 패드: 고정 순서 (`1 2 3 … 0`), 키보드 입력 지원

### 세션 저장

로그인 성공 시 `sessionStorage`에 저장:

| 키 | 값 |
|---|---|
| `accessToken` | JWT 액세스 토큰 |
| `customerId` | 고객 ID |
| `user` | `{ name, customerId }` JSON |

`persistLoginState()` 헬퍼(`login/page.tsx`)가 ID·인증서 두 경로 모두에서 동일 항목을 저장한다.

### 로그아웃

`logout/page.tsx`에서 `sessionStorage.clear()` + `setUser(null)` 실행 → 헤더 즉시 비로그인 상태 전환.  
`Header.tsx`는 `useEffect([pathname])`으로 경로 변경마다 세션 재평가, `/logout` 진입 시 즉시 `setUser(null)`.

---

## 직원 인증·권한 (BankRole) 및 어드민 콘솔

고객·직원 모두 `POST /api/v1/auth/login`을 사용하며, 로그인 시 직원 디렉터리(`employee` 테이블)의 `grade_code`로 직원 여부를 판정해 JWT `roles` 클레임을 발급한다.

### BankRole 권한 모델

`common/src/main/java/com/bank/common/security/BankRole.java`에 단일 정의된 역할 enum이다.

| 역할 | 구분 | 설명 |
|---|---|---|
| `CUSTOMER` | 고객 | 일반 고객 (어드민 콘솔 접근 불가) |
| `TELLER` / `DEPUTY_MANAGER` / `BRANCH_MANAGER` | 지점 | 창구·부지점장·지점장 |
| `HQ_REVIEWER` / `HQ_RISK` / `HQ_MARKETING` | 본사 | 심사·리스크·마케팅 |
| `COMPLIANCE` / `OPS` / `INTERNAL` | 본사 | 컴플라이언스·운영·내부 |
| `ADMIN` | 시스템 | 전 권한 |

- 권한 그룹 상수(`EMPLOYEE_ROLES`, `CUSTOMER_VIEW_ROLES`, `AUDIT_VIEW_ROLES`, `FDS_ROLES` 등)로 API·화면을 게이팅한다.
- 백엔드: `/api/v1/internal/**` 관리 API는 `EMPLOYEE_ROLES`로 보호. 게이트웨이가 검증한 직원 신원은 `X-User-Id`·`X-User-Role` 헤더로 전달된다.

### 어드민 콘솔 (`web/app/(admin)/admin/`)

- 로그인 성공 시 JWT의 `roles`를 `localStorage['admin_roles']`(BankRole authority 배열)에 저장한다. **표시용 신원(`admin_user`)에는 역할 정보가 없으므로 역할 판정은 항상 `admin_roles`를 읽는다.**
- 사이드바·화면은 섹션별 `bankRoles`로 노출을 제어한다. 예: 대출 본심사(`/admin/loan/review`)는 `DEPUTY_MANAGER·OPS·BRANCH_MANAGER·HQ_REVIEWER`만 노출.
- 주요 섹션: 고객 조회·감사로그·가입통계 / 상담 / AI 감사·격리 / 대출(계약·본심사·담보·서류·정책·신용정보·알림·본인확인) / AI 심사지원(RAG 문서·자문규칙·서류검토) / 운영·감사(EOD·break-glass).

> 데모 직원 계정은 `customer-service` 시드(`employee_directory`) 참고 — 공통 비밀번호 `Employee1234!`.

---

## consultation-service 주요 기능

### 챗봇 상담

- Python · FastAPI 기반 시나리오형 챗봇
- 시나리오 노드·버튼·플로우 DB 구조, 멀티턴 대화 상태 관리
- 키워드 기반 Intent 분류 → 기능 코드 실행 라우팅

#### 챗봇 상품 추천 — 백엔드 단일 채점

`CASH_FLOW_RECOMMEND` 기능 실행 시 채점은 **consultation-service 백엔드(`_rank_products()`)에서만** 수행한다. 프런트엔드(`ChatbotWidget.tsx`)는 백엔드 결과를 표시하는 역할에 집중하며, 브라우저에서 거래 데이터를 직접 집계하거나 채점하지 않는다.

| 항목 | 배점 | 계산 기준 |
|---|---|---|
| 재정 적합도 | 40점 | DB에서 직접 집계한 잔액·월 잉여금 기준. 저축 성장형 진단 시 적금에 1.3× 가중치 |
| 예상 수익 (ROI) | 30점 | 금리×기간 기반 세전 이자 상대 평가, 후보 풀 내 정규화 |
| 유동성 매칭 | 20점 | 거래 빈도 vs 상품 만기 적합도 |
| 부가 혜택 | 10점 | 비과세·중도해지·우대금리 여부 합산 |

#### 챗봇 파일 첨부 분석 (4가지 기능)

챗봇 입력창 왼쪽의 📎 버튼을 누르면 파일 첨부 메뉴가 열린다. 4가지 기능을 지원한다.

| 메뉴 항목 | 분석 유형 | 지원 파일 | 동작 |
|---|---|---|---|
| 📊 타행 거래내역 분석 | `CASH_FLOW` | PDF | 타행 거래내역 PDF를 업로드하면 월별 수입·지출 패턴, 저축 여력, 금융상품 추천 포인트를 AI가 분석해 답변 |
| 📋 약관 설명 | `TERMS` | PDF | 약관 PDF를 업로드하면 중도해지 조건·수수료·주의사항 등 핵심 내용을 고객이 이해하기 쉬운 언어로 요약 |
| 📄 상품 설명서 안내 | `PRODUCT` | PDF | 상품 설명서 PDF를 업로드하면 금리 조건·가입 조건·해지 방법·세제 혜택을 정리해 안내 |
| 📁 서류 제출 | `ENROLLMENT` | PDF, JPG, PNG | 비과세 확인서 등 서류를 서버에 업로드·저장, DB 기록 후 처리 예정 안내 |

**처리 흐름**

```
[사용자] 📎 → 파일 선택
  ↓
[CASH_FLOW / TERMS / PRODUCT]
  브라우저에서 pdfjs-dist로 텍스트 추출 (최대 20페이지)
  ↓
  POST /chatbot/file/analyze  (consultation-service:8087)
    text, analyze_type → OpenAI GPT-4o-mini 분석 → result 반환
  ↓
  챗봇 메시지로 분석 결과 표시

[ENROLLMENT]
  파일 원본 multipart 전송
  ↓
  POST /chatbot/documents/upload  (consultation-service:8087)
    파일 저장 (uploads/ 디렉터리) + chatbot_document 테이블 DB 기록
  ↓
  "영업일 1~3일 내 처리 예정" 안내 메시지 표시
```

**환경 변수 (consultation-service)**

| 변수 | 기본값 | 설명 |
|---|---|---|
| `CONSULTATION_OPENAI_API_KEY` | (필수) | GPT-4o-mini 호출 키. 미설정 시 503 반환 |
| `CONSULTATION_UPLOADS_DIR` | `./uploads` | 서류 파일 저장 경로 |

> **볼륨 마운트 필수**: 컨테이너 재시작 시 업로드 파일이 삭제되지 않도록 `docker-compose.yml`에 named volume `consultation_uploads:/app/uploads`가 설정되어 있다. 별도 경로를 사용할 경우 `CONSULTATION_UPLOADS_DIR` 환경변수와 volume mount 경로를 함께 변경한다.

**파일 업로드 제한**: 허용 형식 PDF·JPG·PNG, 최대 10MB. MIME 타입 및 확장자 이중 검증 후 초과 시 400 반환.

**DB 테이블**: `chatbot_document` — `document_id`, `customer_no`, `original_filename`, `stored_path`, `doc_type`, `file_size_bytes`, `status`. 서비스 기동 시 SQLAlchemy `create_all`로 자동 생성된다(`services/consultation-service/sql/ddl.sql` 참고).

#### 챗봇 조건 맞춤 상품 추천 — 프런트엔드 5단계 플로우

`상품 추천` 버튼으로 진입하는 **조건 맞춤 추천** 흐름은 프런트엔드(`ChatbotWidget.tsx`)에서 독립적으로 동작한다. 백엔드 CASH_FLOW_RECOMMEND 와 별개로, 고객이 직접 조건을 지정하면 프런트에서 필터링·채점 후 상위 3개를 추천한다.

| 단계 | 입력 |
|---|---|
| 1단계 | 가입 기간 (6·12·24·36개월 또는 직접 입력) |
| 2단계 | 가입 금액 (100·500·1000만원 또는 직접 입력) |
| 3단계 | 상품 유형 (전체 추천 / 예금 / 적금) |
| 4단계 | 최소 금리 (1·2·3·4% 이상 또는 직접 입력 / 제한 없음) |
| 5단계 | 가입 목적 (목돈 굴리기 / 매달 저축하기) |

- **결과 카드**: 각 추천 상품 우측에 **가입** 버튼 표시 → `/products/deposit/join/product-{productId}` 로 바로 이동
- **예금/적금 분리**: 예금 선택 시 `DEPOSIT` 타입 상품만, 적금 선택 시 `SAVINGS` 타입 상품만 추천

#### 챗봇 우대금리 조회

| 입력 예시 | 동작 |
|---|---|
| "우대금리 종류 말해줘" | 판매 중 상품별 우대금리 조건·비율 텍스트 응답 |
| "우대금리 상품 보여줘" | 우대금리 조건이 있는 상품 목록을 카드 UI로 표시 |
| "해당하는 상품 리스트 보여줘" | 동일 (우대금리 상품 카드 목록) |

상품 카드에는 우대금리 조건(`pref_condition`)과 추가 금리(`pref_rate`)가 주황색으로 강조 표시된다.

#### 챗봇 상품 비교 분석 에이전트 (PRODUCT_COMPARE)

두 상품을 이름으로 지정하면 8개 항목을 비교하는 테이블 카드와 GPT 기반 맞춤 분석을 제공한다.

**트리거 조건**

- 입력 메시지에 구체적인 상품명(`AXful`, `내맘대로`, `수퍼정기`, `달러자`, `맑은하늘` 등) + 비교 키워드(`비교`, `차이`, `어느 쪽`, `뭐가 더`)가 함께 포함된 경우

**처리 흐름**

```
ChatbotWidget (웹)
  ↓ executeChatbotFeature('PRODUCT_COMPARE', { query })  — sendChatbotMessage 우회
consultation-service
  └─ ProductCompareAgent.execute()
       ├─ _find_products_by_name()  : 토큰 채점으로 DB에서 상품 2개 자동 매칭
       ├─ 8개 항목 비교표 생성
       │     유형 / 기본금리 / 가입기간 / 세제혜택 / 중도해지 / 자동갱신 / 우대금리조건 / 상품설명
       └─ GPT-4o-mini 분석 : "내 상황엔 A/B가 유리한 이유" 1~2문장
  ↓ feature_code="PRODUCT_COMPARE", data=[{row_type:"compare_product", ...}]
ChatbotWidget
  └─ 3열 비교 테이블 카드 + 💡 AI 분석 박스 렌더링
```

**상품명 자동 매칭 (토큰 스코어링)**

- 상품명을 공백·구분자로 토큰 분리 후 4자 이상 토큰이 쿼리에 몇 개 포함되는지로 점수 산정
- 점수가 같을 경우 짧은 상품명 우선 → "AXful 정기예금", "AXful 내맘대로적금" 정확 구분 가능

**비교 테이블 카드 구성**

| 열 | 내용 |
|---|---|
| 항목 | 유형·기본금리·가입기간·세제혜택·중도해지·자동갱신·우대금리조건·상품설명 |
| 상품 A | 첫 번째 매칭 상품값 |
| 상품 B | 두 번째 매칭 상품값 |

각 상품 헤더 아래에 **가입하기** 버튼이 표시되어 `/products/deposit/join/{product_id}` 로 바로 이동 가능하다.

**백엔드 응답 구조**

```json
{
  "feature_code": "PRODUCT_COMPARE",
  "status": "OK",
  "message": "GPT 분석 텍스트",
  "data": [
    {
      "row_type": "compare_product",
      "product_a": { "product_id": 1, "product_name": "AXful 정기예금", "product_type": "예금", ... },
      "product_b": { "product_id": 5, "product_name": "AXful 내맘대로적금", "product_type": "적금", ... },
      "compare_items": [
        { "label": "유형", "a": "예금", "b": "적금" },
        { "label": "기본금리", "a": "2.15%", "b": "2.95%" },
        ...
      ],
      "analysis": "GPT 분석 요약"
    }
  ]
}
```

**관련 파일**

| 파일 | 역할 |
|---|---|
| `services/consultation-service/app/features/product_compare.py` | `ProductCompareAgent` — 상품 매칭·비교표 생성·GPT 분석 |
| `services/consultation-service/app/services.py` | `_execute_product_compare()` — PRODUCT_COMPARE 인텐트 라우팅 |
| `web/components/chatbot/ChatbotWidget.tsx` | 비교 테이블 카드 렌더링, 후속 질문 처리 |
| `web/lib/consultation-api.ts` | `ChatbotMessageResponse` 타입에 `feature_code`, `feature_data` 포함 |

**후속 질문 처리**

비교 결과 직후 "나한테 적절한 추천이야?" 등의 질문 시 두 상품을 모두 언급하며 추천 상품과 비추천 상품을 구분해 안내한다. `lastCompareNamesRef` + `lastCompareAnalysisRef`로 직전 비교 컨텍스트를 유지한다.

**예금·적금 적합 판단 (`answerDepositSavingsFit`)**

"예금이랑 적금 중 나한테 맞는 거" 질문 시 deposit-service 추천 에이전트(`fetchDepositRecommendAgent`)를 호출해 판단한다. `GET /api/v1/customers/me`의 `birthDate`로 고객 만 나이를 계산해 백엔드에 전달하며, 연령 기반 상품 필터링은 deposit-service에서 처리된다.

**fallback 추천 (monthlySavings ≤ 0)**

월 잉여금이 0 이하인 경우 점수 기반 추천 대신 현재 보유 잔액 기준 예금 중심 fallback 추천을 제공하며, 응답의 `fallbackReason` 필드에 사유 문구가 포함된다.

| 카테고리 | 기능 코드 | 설명 |
|---|---|---|
| PRODUCT_ADVICE | PRODUCT_GUIDE | 예금·적금·청약 상품 안내 (인증 필요) |
| | RATE_GUIDE | 금리·우대금리 안내 |
| | JOIN_CONDITION | 가입 조건 안내 |
| | PRODUCT_COMPARE | 상품 비교 |
| | TERMS_RAG | 약관 검색 |
| | FAQ | 자주 묻는 질문 |
| USER_FINANCE | MY_ACCOUNTS | 내 계좌 조회 |
| | MY_PRODUCTS | 가입 상품 조회 |
| | CONTRACT_STATUS | 계약 상태 조회 |
| | MATURITY_SCHEDULE | 만기 예정 조회 |
| | INTEREST_HISTORY | 이자 내역 조회 |
| | MY_CASH_FLOW | 현금 흐름 조회 |
| | CASH_FLOW_RECOMMEND | 현금흐름 기반 상품 추천 |
| | SAVINGS_GOAL | 저축 목표 멀티턴 추천 |
| STAFF_SUPPORT | STAFF_CUSTOMER | 직원용 고객 정보 조회 |
| | STAFF_CONTRACT | 직원용 고객 계약 조회 |
| | STAFF_ACCOUNT | 직원용 고객 계좌 조회 |
| | STAFF_TRANSFER_FLOW | 직원용 이체 흐름 조회 |
| | STAFF_CONSULTATION_HISTORY | 상담 이력 조회 |
| | STAFF_CASH_FLOW | 직원용 현금 흐름 조회 |

**인증 필요 기능 코드**: `MY_ACCOUNTS`, `MY_PRODUCTS`, `CONTRACT_STATUS`, `MATURITY_SCHEDULE`, `INTEREST_HISTORY`, `MY_CASH_FLOW`, `CASH_FLOW_RECOMMEND`, `SAVINGS_GOAL`, `PRODUCT_GUIDE`

비로그인 상태에서 위 기능 실행 시 로그인 안내 메시지를 표시하고 로그인 완료 후 재실행한다.

### 상품 추천 채점 — 백엔드 단일화 원칙

`CASH_FLOW_RECOMMEND` 실행 시 채점 로직은 **consultation-service 백엔드(`_rank_products()`)에서만** 수행한다.

- 채점 모델: 재정 적합도 40점·ROI 30점·유동성 20점·부가혜택 10점 (100점 만점)
- DB에서 직접 집계한 거래 데이터 기반으로 점수를 계산해 프론트에 최종 순위만 반환
- 프론트(ChatbotWidget)는 백엔드 결과를 **표시**하는 역할에 집중하며, 브라우저에서 거래 데이터를 재집계하거나 직접 채점하지 않음
- 채점 기준 변경은 백엔드 한 곳에서만 관리 → 프론트·백 결과 불일치 방지

### 챗봇 토큰 검증

챗봇 위젯 열기(`onOpen`) 시 `/api/v1/customers/me`로 토큰 유효성을 검증한다.

- 유효: 정상 진입
- 만료/없음: localStorage의 `accessToken`, `access_token`, `customerId`, `user` 일괄 초기화 → 로그인 유도

### 상담원/관리자 로그인 분리

상담 서비스는 역할에 따라 진입 경로가 다르다.

| 역할 | 진입 경로 | 허용 계정 |
|---|---|---|
| 상담원 (AGENT) | `/admin/consultation/chat` | agent01, agent02 |
| 슈퍼바이저 / 관리자 | `/admin/login` | super01 (SUPERVISOR), admin01 (ADMIN) |

- 챗봇 헤더 **관리자** 버튼 → `/admin/login`, **상담원** 버튼 → `/admin/consultation/chat`
- 사이트 상단 헤더 **관리자** 버튼 → `/admin/login`
- `/admin/login`에서 AGENT 계정으로 로그인 시도 시 거부 (SUPERVISOR·ADMIN만 허용)

#### 상담원 계정 (consultation-service DB)

| 계정 | 역할 | 비밀번호 |
|---|---|---|
| agent01 | AGENT | 1234 |
| agent02 | AGENT | 1234 |
| super01 | SUPERVISOR | 1234 |
| admin01 | ADMIN | 1234 |

### 상담원 채팅 페이지 (`/admin/consultation/chat`)

- 로그인 후 대기 중인 상담 목록 자동 폴링 (5초 간격)
- 상담 수락 → 고객과 실시간 메시지 교환
- 상담 종료 버튼으로 채팅 종료
- 헤더에서 **상담 이력** / **통계** / **계정 관리**(SUPERVISOR·ADMIN만) 바로가기 제공
- 로그아웃 시 세션 초기화 후 로그인 폼으로 복귀 (관리자 로그인 페이지로 이동하지 않음)
- 오래된 WAITING 상태(10분 초과) 일괄 정리 버튼 제공

### 상담 이력 조회 (`/admin/consultation/history`)

- 종료된 상담 목록 조회
- 고객별 채팅 내역 확인
- 상담원·관리자 모두 접근 가능

### 만족도 / 통계 대시보드 (`/admin/consultation/stats`)

- 전체 KPI: 총 상담 수·종료 완료·상담중·대기중·평균 만족도
- 최근 7일 상담 건수 바 차트
- **상담사별 카드**: 종료 건수·평균 만족도·평균 소요시간·평균 대기시간·만족도 분포 막대

### 상담원 계정 관리 (`/admin/consultation/agents`)

- 직원 목록 조회·상태 관리
- **SUPERVISOR·ADMIN만 접근 가능** (AGENT는 메뉴 미노출)

### 고객 챗봇 상담원 연결

- 챗봇에서 **상담원 연결** 버튼 클릭 → 대기열 등록 (WAITING)
- 상담원 수락 시 실시간 채팅 화면으로 전환 (CONNECTED)
- 채팅 종료 후 별점(1~5) 만족도 평가 화면 표시
- 별점 선택 후 **평가 제출** 클릭 시에만 "소중한 평가 감사합니다!" 표시
- 고객 채팅 상태를 sessionStorage에 저장해 페이지 이동 후에도 채팅 유지
- 새 채팅 시작 시 이전 만족도 상태 자동 초기화

### 상담사 실시간 채팅

- 챗봇 → 상담사 핸드오프 흐름 (상담 접수·메시지 이력·채팅 종료)
- 상담사 대기열 조회 및 연결 수락
- 만족도 점수 기록
- **WAITING 상태 누적 방지**: `chat_ended_at` → `agent_connected_at` → `agent_requested_at` 순서로 상태 판정해 종료된 상담이 WAITING으로 남지 않도록 처리

### Kafka 연동

| Topic | 이벤트 | 방향 |
|---|---|---|
| `consultation.chatbot.events` | ChatbotConsultationStarted, ChatbotMessageHandled, ChatbotAgentTransferRequested | Producer |
| `consultation.chatbot.message` | ChatbotMessageReceived | Producer + Consumer |
| `consultation.chat.events` | AgentConnected, ChatMessageSent, ChatEnded | Producer |
| `deposit.contract.events` | ContractCreated | Consumer |

Kafka 활성화: `.env`에서 `CONSULTATION_KAFKA_ENABLED=true` 설정

### 주요 API

```
GET  /health
GET  /chatbot/features
POST /chatbot/features/{feature_code}/execute
POST /chatbot/consultations/start
POST /chatbot/consultations/{id}/messages
POST /chatbot/transfer
POST /chatbot/file/analyze
POST /chatbot/documents/upload
GET  /chat/queue
POST /chat/consultations/{id}/connect
POST /chat/consultations/{id}/messages
GET  /chat/consultations/{id}/messages
POST /chat/consultations/{id}/end
GET  /metrics
```

---

## loan-service 주요 기능

### 대출 심사 흐름

```
신청 → 가심사 → DSR 계산 → 신용평가 → 신용점수 → 본심사
→ 담보/LTV → 보증인/보증보험 → 서류 → 전자서명 → 본인확인 → 약정실행
```

### 대출 관리 흐름

```
계약 → 상환 / 부분상환 / 선납 → 연체 → 금리변경 → 만기 → 해지
```

### 심사 자동 트리거 연쇄

신청 접수 후 가심사·신용평가·DSR 산출이 트랜잭션 커밋 직후(`AFTER_COMMIT`) 비동기로 연쇄 실행된다. 각 단계는 직전 단계의 결과 이벤트를 구독한다.

```
신청 접수(SUBMITTED)
  └─[AFTER_COMMIT]→ 가심사 자동 실행 (CreditScoreEngine이 PASS/REJECT 자동 판정)
       └─ PASS 시 PrescreeningPassedEvent
            └─[AFTER_COMMIT]→ CB 신용평가 자동 실행 (가심사 score·grade·limit 재사용)
                 └─ CreditEvaluationCompletedEvent
                      └─[AFTER_COMMIT]→ DSR 산출 자동 실행 (신청 시 입력 연소득 기준)
```

| 리스너 | 구독 이벤트 | 동작 |
|---|---|---|
| `PrescreeningAutoTriggerListener` | `ApplicationSubmittedEvent` | 가심사 엔진 호출 (`prescResultCd=null`로 엔진이 판정) |
| `CreditEvaluationAutoTriggerListener` | `PrescreeningPassedEvent` | 가심사 결과를 CB 입력으로 재사용해 신용평가 row 생성 |
| `DsrAutoTriggerListener` | `CreditEvaluationCompletedEvent` | 신청 시 추정 연소득 기준 DSR 산출 |

- **AFTER_COMMIT 고정**: 커밋 전 실행 시 신청 row 미영속으로 `LOAN_012` 발생 → 커밋 후로 고정
- **멱등성**: 이미 가심사된 건(`LOAN_046`)·가심사 불가 상태(`LOAN_047`)는 로그만 남기고 무시
- **비차단**: 각 단계 실패는 직전 단계 결과에 영향 없음 (로그만 기록)
- **비활성화**: `loan.auto-trigger.enabled=false` 설정 시 전체 비활성 (통합테스트는 각 단계를 직접 통제). 미설정 시 기본 활성

### 문서 심사 (doc-agent 연동)

대출 신청 서류는 `doc-agent` 서비스가 3단계로 처리한다(L1 OCR/추출 → L2 위변조 검증 → L3 라우팅). loan-service의 `DocAgentClient`(RestClient)가 `POST /api/documents/submit`을 **동기 REST**로 호출한다(최대 3회 재시도, 지수 백오프). 위변조 의심 건은 휴먼리뷰 큐(`GET /api/documents/queue`)로 보류된다.

### Agentic RAG (유사사례 검색)

| 컴포넌트 | 역할 |
|---|---|
| `SimilarCaseExporter` | 심사 완료 건을 청크로 변환, 임베딩·저장 |
| `ai-service` / `auto-loan-review` | 벡터 검색 위임, 정책문서 시드 |

- **검색 백엔드**: `ai.rag.backend=inline|es`로 전환. `inline`은 PostgreSQL+pgvector 코사인 유사도, `es`는 Elasticsearch 하이브리드(**BM25 + kNN을 RRF로 융합**, nori 형태소 분석).
- **정책문서 시드**: `EsPolicyCorpusSeedLoader`가 정책 청크를 임베딩(`text-embedding-005`)해 `kb_policy` 인덱스에 색인. 재기동 시 동일 문서 ID로 덮어써 멱등.

### 자동심사·편향검증

`auto-loan-review` 서비스가 규칙 기반 + LLM 심사 의견을 생성하고, 편향검증 에이전트가 성별·나이·지역 편향 여부를 검사한다. 임계치 초과 시 4-eye 승인 프로세스로 에스컬레이션한다.

### 모델 드리프트·공정성 모니터링 (auto-loan-review)

`agent_audit_log`(자동심사 감사 기록)를 원천으로 두 가지 배치를 돌려 Prometheus 메트릭으로 노출한다.

| 항목 | 주기 | 기준 | 메트릭 |
|---|---|---|---|
| **드리프트(PSI)** | 매주 월 02:00 | 피처 분포 안정성. 경고 0.10 / 심각 0.20 | `ai.drift.psi.value`, `ai.drift.psi.critical.total` |
| **공정성** | 매월 1일 03:00 | 연령대별 승인률 편차 > 0.05 시 flag | `ai.fairness.flagged.total` |

- 결과는 `psi_drift_result`(주간)·`fairness_report`(월간) 테이블에 적재.
- 설정: `ai.drift.psi-cron`, `ai.drift.fairness-cron`, `ai.drift.psi-features`.

---

## 이상거래 조사 에이전트 (fraud-investigation-agent)

`fraud-investigation-agent/` (Python · LangGraph + FastAPI, 포트 8090). 이상거래 사건을 받아 5개 공격 시나리오(보이스피싱·계정탈취·자금세탁·내부자부정·정상)를 동시에 경합시키며, 증거에 따라 조회 도구를 선택해 가설 신뢰도를 갱신하고 권고를 생성한다.

| 엔드포인트 | 역할 |
|---|---|
| `GET /api/cases` | 조사 큐(입력 후보) 조회 |
| `POST /api/investigate` | 사건 조사 → 단계별 트레이스 + 권고 (HITL 대기) |
| `POST /api/approve` | 분석가 승인(HITL + RBAC) 후 동작 실행 |

- **HITL + RBAC**: 에이전트는 권고까지만 생성하고, 지급정지·STR 등 실제 동작은 분석가 승인 후 실행한다.
- **안전 모드**: 결정적 사실(사망·후견)은 fail-closed로 즉시 종료, 예산 소진 시 fail-soft로 부분 결과 인계.
- **실데이터 연동**: `get_auth_events`가 customer-service의 `GET /api/v1/internal/auth/{id}/events`(인증 실패 횟수 등)를 호출해 계정탈취 신호를 판별. 그 외 도구(STR·AML·디바이스 등)는 현재 목(mock).

> 어드민 조사 화면(`web/app/(admin)/admin/fraud/`)은 별도 브랜치(customer)에서 관리되며 `main`에는 아직 병합되어 있지 않다.

---

## 로컬 실행

### 사전 조건

- Java 17+, Docker Desktop, Python 3.11, Node.js 20+

### 인프라 실행

```powershell
docker compose up -d
```

**기본 컨테이너 및 포트:**

| 컨테이너 | 포트 | 설명 |
|---|---|---|
| customer-db | 5432 | 고객·인증 DB |
| deposit-db | 5433 | 예적금 DB |
| loan-db | 5434 | 여신 DB (pgvector) |
| payment-db | 5435 | 결제 DB (A 은행) |
| payment-db-b | 5441 | 결제 DB (B 은행) |
| master-db | 5436 | 마스터 코드 DB |
| ai-db | 5437 | AI 벡터 DB (pgvector) |
| langfuse-db | 5439 | LLM 추적 DB |
| Redis | 6379 | 세션·캐시 |
| Kafka | 9092 | 이벤트 스트림 |
| Schema Registry | 18081 | Kafka 스키마 관리 |
| Prometheus | 9090 | 메트릭 수집 |
| Grafana | 3000 | 대시보드 |
| Langfuse | 3001 | LLM 호출 추적 |
| Phoenix (OTel) | 6006 | OpenTelemetry 트레이싱 |

**선택적 프로필:**

```powershell
# RAG (Elasticsearch + Kibana + Kafka Connect)
docker compose --profile rag up -d

# 서류 에이전트 (MinIO + Vault + doc-agent-db)
docker compose --profile doc up -d
```

### 백엔드 서비스 실행

```powershell
.\gradlew :services:customer-service:bootRun   # 8081
.\gradlew :services:deposit-service:bootRun    # 8082
.\gradlew :services:loan-service:bootRun       # 8083
.\gradlew :services:master-service:bootRun     # 8085
.\gradlew :services:ai-service:bootRun         # 8086
```

### deposit-api 실행

```powershell
cd services/deposit-api
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8082
```

### consultation-service 실행

```powershell
# Docker 권장
cd services/consultation-service
docker compose up -d

# 직접 실행
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8087
```

> consultation-service 독립 docker-compose는 PostgreSQL을 포트 `5439`에 노출한다. 메인 compose와 포트 충돌 없음.

### customer-service 환경변수

```powershell
$env:CRYPTO_KEY_BASE64="bG9hbi1zZXJ2aWNlLWRldi1hZXMta2V5LTMyYnl0ZXM="
.\gradlew :services:customer-service:bootRun
```

> 운영 환경에서는 반드시 별도 32바이트 AES-256 키(Base64)를 사용한다.

### 데모 계정

| 역할 | 로그인 ID | 비밀번호 | 진입 경로 |
|------|-----------|----------|-----------|
| 고객 (홍길동) | 금융인증서 로그인 | 인증서 PIN | `/login` |
| 고객 (테스트) | user01 / user02 / user03 | Employee1234! | `/login` |
| 직원 | employee01 | Employee1234! | `/admin/login` |
| 관리자 (BankRole) | admin01 | Employee1234! | `/admin/login` |
| 상담원 | agent01 / agent02 | 1234 | `/admin/consultation/chat` |
| 슈퍼바이저 | super01 | 1234 | `/admin/login` |
| 상담 관리자 | admin01 | 1234 | `/admin/login` |

### 프런트엔드 실행

```powershell
cd web
npm install
npm run dev   # http://localhost:3001
```

`.env.local` 예시:

```env
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_DEPOSIT_API_URL=http://localhost:8082/api
NEXT_PUBLIC_CONSULTATION_API_URL=http://localhost:8087
NEXT_PUBLIC_LOAN_API_URL=http://localhost:8083
NEXT_PUBLIC_ADVISORY_API_URL=http://localhost:8084
NEXT_PUBLIC_MASTER_API_URL=http://localhost:8085
NEXT_PUBLIC_AI_API_URL=http://localhost:8086
NEXT_PUBLIC_PAYMENT_API_URL=http://localhost:8087
CUSTOMER_API_URL=http://localhost:8081
```

`CUSTOMER_API_URL`은 SSR 전용 변수 (서버 → customer-service 직접 프록시용, `NEXT_PUBLIC_` 없음).

---

## DB 마이그레이션 현황 (deposit-service)

Flyway로 스키마를 관리하며 서비스 기동 시 자동 적용된다.

| 버전 | 내용 |
|---|---|
| V1~V3 | 초기 스키마 (상품·계약·계좌·거래 테이블) |
| V4 | 공통 약관 연결 테이블 |
| V5~V8 | 마스터 코드·그룹·시드 데이터 |
| V12 | 이체 일일 한도 컬럼 추가 |
| V13 | `deposit_transactions.idempotency_key` 컬럼 + 부분 유니크 인덱스 |
| V14 | `deposit_target_groups.min_age` / `max_age` 컬럼 추가. 청년고객 19~34세, 국군장병 18~27세로 초기값 설정 |
| V15 | 홍길동(customer_id=9001) 테스트 계좌·거래 시드 데이터 추가 |
| V16 | 고아 테이블 정리, 홍길동 계좌 잔액 현실화(현금흐름 추천 시나리오 기준), 거래 날짜 90일 이내 재조정 |

> 서비스 재기동 없이 컬럼이 추가된 경우 Hibernate가 SELECT 시 해당 컬럼을 포함해 500 오류가 발생한다. DB에 직접 DDL을 실행하거나 서비스를 재기동한다.

---

## 테스트 실행

```powershell
# deposit-service
.\gradlew :services:deposit-service:test

# loan-service
.\gradlew :services:loan-service:test

# consultation-service (1614개)
cd services/consultation-service
python -m pytest tests/ -q
```

### deposit-service 테스트 주요 커버리지

| 테스트 클래스 | 주요 시나리오 |
|---|---|
| `TransactionServiceTest` | 이체 성공, 잔액 부족, 일일 금액 한도 초과, 일일 횟수 한도 초과, 한도 미설정 건너뜀, KST 자정 경계값 |
| `TransactionRepositoryTest` | 거래 유형별 조회, 기간별 합산 |
| `AccountServiceTest` | 계좌 목록, 출금 가능 여부 |
| `ContractServiceTest` | 계약 생성·해지·만기, 가입금액 범위 검증 |
| `ProductServiceTest` | 상품 조회·필터, 추천 에이전트 |

---

## 모니터링

| 도구 | 주소 | 설명 |
|---|---|---|
| Prometheus | `http://localhost:9090` | 메트릭 수집 |
| Grafana | `http://localhost:3000` | 대시보드 |

대시보드는 어드민 콘솔 `/admin/monitoring` 에서도 볼 수 있다 (운영·리스크 권한).

- 어드민 임베드: [`docs/monitoring/ADMIN_MONITORING_EMBED.md`](docs/monitoring/ADMIN_MONITORING_EMBED.md)
- 여신 AI 메트릭: [`docs/monitoring/loan-service-metrics.md`](docs/monitoring/loan-service-metrics.md)
- 서류 심사(doc-agent) 메트릭: [`docs/monitoring/doc-agent-metrics.md`](docs/monitoring/doc-agent-metrics.md)
- 챗봇 모니터링: [`docs/monitoring/CHATBOT_GUIDE.md`](docs/monitoring/CHATBOT_GUIDE.md)
- LLM/RAG 모니터링: [`docs/monitoring/LLM_RAG_MONITORING_GUIDE.md`](docs/monitoring/LLM_RAG_MONITORING_GUIDE.md)

---

## 주의사항

- `main` 브랜치 직접 커밋/푸시 금지
- `.env` 파일 커밋 금지
- 운영/스테이징 DB 접속 명령 금지
- AI 모델명·세션 링크를 커밋 메시지·PR에 삽입 금지

공통 AI 작업 가이드: [`docs/AI_GUIDELINES.md`](docs/AI_GUIDELINES.md)  
Claude Code 추가 설정: [`CLAUDE.md`](CLAUDE.md)
