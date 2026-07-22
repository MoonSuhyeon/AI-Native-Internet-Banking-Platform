# Consultation Service

작성자: 정혜영

AXful Bank 챗봇 상담 서비스입니다.
고객의 현금흐름을 분석해 맞춤 금융상품을 추천하고, 이체·해지·상담사 연결을 지원합니다.

---

## 목차

- [서비스 구조](#서비스-구조)
- [주요 기능](#주요-기능)
- [100점 채점 모델](#100점-채점-모델)
- [RAG 자유질문 답변](#rag-자유질문-답변)
- [파일 첨부 분석](#파일-첨부-분석)
- [챗봇 위젯 개선 사항](#챗봇-위젯-개선-사항)
- [현금흐름 분석](#현금흐름-분석)
- [나이 기반 상품 필터](#나이-기반-상품-필터)
- [API 엔드포인트](#api-엔드포인트)
- [환경변수](#환경변수)
- [로컬 실행](#로컬-실행)

---

## 서비스 구조

```
consultation-service/
├── app/
│   ├── main.py          # FastAPI 애플리케이션 진입점
│   ├── config.py        # 환경변수 설정 (Pydantic Settings)
│   ├── services.py      # 핵심 비즈니스 로직
│   ├── models.py        # SQLAlchemy ORM 모델
│   ├── schemas.py       # Pydantic 요청/응답 스키마
│   ├── llm.py           # LLM 어댑터 및 Intent 분류기
│   ├── rag.py           # RAG 엔진 (상품 설명서 검색)
│   ├── kafka.py         # Kafka 이벤트 발행
│   └── features/        # 피처별 실행기
├── requirements.txt
└── Dockerfile
```

---

## 주요 기능

### 챗봇 상담

| 기능 | feature_code | 설명 |
|------|-------------|------|
| 상품 안내 | PRODUCT_GUIDE | 예금·적금·청약 상품 목록 및 금리 안내 |
| **만기 예정 조회** | MATURITY_SCHEDULE | 고객의 만기 예정 계약 목록·날짜·금리 안내 |
| **만기 운용 전략** | MATURITY_MANAGEMENT | 재예치·상품 전환·유동성 보유 세 가지 전략 안내 |
| **재투자 추천** | REINVESTMENT_RECOMMEND | 만기 계약별 금액·현금흐름 기반 구체적 상품 추천 |
| **지출 패턴 경고** | SPENDING_PATTERN | 이번 달 카테고리별 지출 vs 직전 2개월 평균 비교 → 이상 지출 감지·경고 |
| **현금흐름 추천** | CASH_FLOW_RECOMMEND | 고객 입출금 패턴 분석 → 맞춤 상품 추천 |
| **조건 맞춤 추천** | PRODUCT_SEARCH | 기간·금액·유형 입력 → 100점 모델로 추천 |
| 금리 안내 | RATE_GUIDE | 기간별 기본금리 + 우대금리 조회 |
| 가입 조건 안내 | JOIN_CONDITION | 대상·채널·기간·금액 조건 안내 |
| 내 현금 흐름 | MY_CASH_FLOW | 고객 입출금 거래 내역 조회 |
| 내 상품 | MY_PRODUCTS | 가입된 예금·적금·청약 상품 조회 |
| **파일 첨부 분석** | FILE_ANALYZE | PDF 업로드 → 거래내역(CASH_FLOW) / 약관(TERMS) / 상품설명서(PRODUCT) 분석 |
| **서류 제출** | ENROLLMENT | 비과세·소득확인 서류 업로드 및 접수 |
| 챗봇 이체 | — | 계좌 간 이체 (보안카드·금융인증서 인증) |
| 상담사 연결 | — | 대기 큐 → 직원 연결 |

### 직원 지원

| 기능 | feature_code |
|------|-------------|
| 고객 정보 조회 | STAFF_CUSTOMER |
| 고객 계약 조회 | STAFF_CONTRACT |
| 고객 계좌 조회 | STAFF_ACCOUNT |
| 고객 거래 내역 | STAFF_CASH_FLOW |
| 이체 흐름 추적 | STAFF_TRANSFER_FLOW |
| 상담 이력 조회 | STAFF_CONSULTATION_HISTORY |

---

## 만기 알림 및 재투자 추천 에이전트 (Agent 3)

`features/maturity_agent.py` + `services.py` + `llm.py` 에 구현되어 있습니다.

### Intent 구조

| Intent | 트리거 키워드 예시 | 응답 내용 |
|--------|-----------------|---------|
| `MATURITY_SCHEDULE` | "다음 달 만기 알려줘", "만기 언제야", "만기 예정 조회" | 만기 예정 계약 목록 (상품명·금액·금리·만기일·D-day) |
| `MATURITY_MANAGEMENT` | "만기 상품 어떻게 해", "만기되면 어떻게" | 재예치·상품 전환·유동성 보유 세 가지 전략 안내 + 만기 예정 상품 목록 |
| `REINVESTMENT_RECOMMEND` | "재투자 추천해줘", "재예치 추천", "재가입 추천" | 만기 계약별 구체적 상품 추천 + 사유 |

### 재투자 추천 로직

`MaturityManagementAgent._recommend_for_target()` 에서 금액·현금흐름 기반으로 전략을 결정합니다.

| 조건 | 전략 | 설명 |
|------|------|------|
| 현금흐름 적자 (`monthly_surplus < 0`) | TERMINATE_OR_KEEP_LIQUID | 유동성 보유 우선 |
| 금액 < 100,000원 | TERMINATE_OR_KEEP_LIQUID | 소액은 입출금 통장 보관 권장 |
| 적금/청약 + 월잉여금 ≥ 500,000원 | SWITCH_PRODUCT | 납입형 적금으로 전환 |
| 금액 ≥ 1,000,000원 | REDEPOSIT | 정기예금 재예치 |
| 그 외 | REDEPOSIT | 다른 상품으로 재가입 |

### 특수 상품 필터

아래 상품은 일반 고객 대상 재투자 추천에서 제외됩니다:

- 장병내일준비적금
- 청년도약계좌
- 청년 주택드림
- 주택청약종합저축

현재 만기 계약과 동일한 상품도 추천에서 제외합니다 (`exclude_product_id` 파라미터).

### 응답 예시

**MATURITY_SCHEDULE**
```
[만기 예정 조회]
- AXful 정기예금 5,000,000원 · 연 2.15%
  만기: 2027-06-09 (약 12개월 후)
- AXful 쏙머니통장 1,000원 · 연 0.10%
  만기: 2026-07-11 (27일 후)

재투자 추천이 필요하시면 '재투자 추천해줘'라고 입력해 주세요.
```

**MATURITY_MANAGEMENT**
```
[만기 운용 전략 안내]
만기 후 선택할 수 있는 방법은 크게 세 가지입니다.

① 재예치 — 목돈은 정기예금으로 재가입해 금리를 유지
② 상품 전환 — 더 높은 금리나 조건이 좋은 다른 상품으로 이동
③ 유동성 보유 — 당장 쓸 돈이 있거나 현금흐름이 불안정할 때 입출금 통장에 보관

현재 만기 예정 상품:
  - AXful 정기예금 5,000,000원 (약 12개월 후 만기)

구체적인 상품 추천이 필요하시면 '재투자 추천해줘'라고 입력해 주세요.
```

**REINVESTMENT_RECOMMEND**
```
[재투자 추천]
거래 데이터가 부족해 보수적인 만기 운용 전략으로 안내합니다.
- AXful 쏙머니통장 1,000원은 만기 후 입출금 통장에 유동성으로 보유하시길 권장합니다.
  사유: 소액은 재예치보다 입출금 통장에 두고 필요 시 활용하는 것이 유리합니다.
- AXful 정기예금 5,000,000원은 12개월 일반정기예금으로 재예치를 추천합니다.
  사유: 목돈은 기간을 나누어 정기예금으로 재예치하면 금리와 유동성을 함께 관리할 수 있습니다.
```

---

## 지출 패턴 경고 에이전트 (Agent 4)

`features/spending_pattern_agent.py` + `services.py` + `llm.py` 에 구현되어 있습니다.

### 인텐트 구조

| 인텐트 | 예시 질문 | 응답 내용 |
|--------|-----------|-----------|
| `SPENDING_PATTERN` | "이번 달 지출 분석해줘", "소비 패턴 보여줘", "이번 달 얼마나 썼어", "과소비 확인해줘", "지출 내역 알려줘" | 카테고리별 지출 집계 + 직전 2개월 평균 대비 이상 지출 경고 |

### 분석 로직

| 단계 | 처리 내용 |
|------|-----------|
| **데이터 수집** | 고객 계좌의 최근 3개월 `direction_type = OUT` 거래 조회 (본인 계좌 간 이체 제외) |
| **카테고리 분류** | `transaction_summary`/`transaction_memo` 키워드 매칭으로 7개 카테고리 분류 |
| **월별 집계** | 이번 달 vs 직전 2개월 평균 카테고리별 비교 |
| **이상 감지** | 이번 달 지출이 평균의 **1.5배 이상**이면 경고 발생 |
| **신규 감지** | 이전에 없던 카테고리에서 5만원 이상 지출 발생 시 신규 알림 |

### 카테고리 분류 기준

| 카테고리 | 키워드 (transaction_summary 기반) |
|----------|-----------------------------------|
| 식비 | 식당, 음식, 카페, 편의점, 배달, 마트, 배민, 요기요, 쿠팡이츠 |
| 교통 | 교통, 버스, 지하철, 택시, 주유, KTX, 고속버스, 주차 |
| 쇼핑 | 쇼핑, 의류, 패션, 신발, 백화점, 쿠팡, 온라인쇼핑 |
| 의료 | 병원, 의원, 약국, 한의원, 치과, 건강검진 |
| 공과금 | 전기, 수도, 가스, 통신, 인터넷, 핸드폰, 관리비 |
| 문화/여가 | 영화, OTT, 게임, 여행, 숙박, 헬스, 운동 |
| 금융 | 보험, 대출, 이자, 연금, 펀드 |
| 기타 | 위 키워드에 해당하지 않는 지출 |

### 응답 예시

```
[지출 패턴 분석]
분석 기준: 2026-06 (이번 달) vs 직전 2개월 평균

⚠ 이상 지출 감지:
  • 이번 달 식비 지출(320,000원)이 평소(130,000원)의 2.5배입니다.
  • 이번 달 쇼핑 지출(210,000원)이 평소(90,000원)의 2.3배입니다.

이번 달 카테고리별 지출:
  • 식비: 320,000원
  • 쇼핑: 210,000원
  • 교통: 85,000원
  • 기타: 45,000원
  합계: 660,000원
```

---

## 100점 채점 모델

`PRODUCT_SEARCH`와 `CASH_FLOW_RECOMMEND` 양쪽에서 동일한 채점 함수(`_rank_products`)를 사용합니다.

### 채점 항목

| 항목 | 배점 | 계산 방식 |
|------|------|-----------|
| **재정 적합도** | 40점 | 예금: `min(투자가능잔액/최소가입금액, 5)/5 × 40`<br>적금: `min(월잉여금/(최소납입액×2), 5)/5 × 40`<br>페르소나 일치 시 ×1.3 가산 |
| **수익성 (ROI)** | 30점 | 고객 실제 납입 가능 금액 기준 만기 이자 → 정규화<br>예금: `투자가능잔액 × 금리 × targetPeriod / 12`<br>적금: `월잉여금 × targetPeriod × 금리 × (targetPeriod+1) / 24` |
| **유동성 매칭** | 20점 | 거래빈도 ≥10 → 단기(12개월 이하) 선호<br>거래빈도 ≤5 → 장기(24개월 이상) 선호<br>중도해지 가능 시 +0.2 가산 |
| **부가 혜택** | 10점 | 비과세 가능 7점 + 중도해지 가능 3점 |

> 합계 100점 만점, 내림차순 정렬 후 TOP 3 추천  
> 동점 시 실제 적용 금리(기본 + 우대) 높은 상품 우선

### 페르소나 분류

| 유형 | 조건 | 우대 상품 유형 |
|------|------|---------------|
| 지출 과다형 | 월잉여금 ≤ 0 | 예금 |
| 저축 성장형 | 잔액 < 월잉여금 × 12 | 적금 |
| 목돈 보유형 | 잔액 ≥ 월잉여금 × 12 | 예금 |

### 우대금리 수치·조건 표시

상품 추천 카드에 우대금리 수치(`+X%`)와 조건 텍스트를 함께 표시합니다.

- **데이터 출처**: deposit DB의 `banking_deposit_product_interest_rates` 테이블에서 `rate_type = 'PREFERENTIAL'`인 행을 상품별로 집계
  - 우대금리 수치: `SUM(rate)` → 카드에 `+X%` 형식 표시
  - 우대금리 조건: `STRING_AGG(condition_description)` → 카드에 조건 텍스트 표시
- **fallback**: DB에 조건 데이터가 없으면 상품명 키워드 기반 조건 사용

| 키워드 | fallback 조건 |
|--------|--------------|
| 내맘대로 | 자동이체 설정 |
| 자유적금 | 자동이체 설정 |
| 맑은하늘 | 맑은하늘 앱 설치 후 인증코드 등록 |
| 직장인우대 | 급여이체 실적 등록 |
| 달러 | 달러 환전 실적 보유 |
| 청년도약 | 소득 요건 충족 확인 |
| 수퍼정기 | 비대면 가입 |
| 정기예금 | 비대면(인터넷·스타뱅킹) 가입 |
| 꿈적금 | 만기 유지 |
| 함께적금 | 2인 이상 공동 가입 |

표시 예시:
```
🎁 우대금리 +0.6% 조건: 자동이체 설정 우대
```

---

### 금리 반영 방식

`base_interest_rate` 대신 `banking_deposit_product_interest_rates` 테이블에서 고객 선택 기간에 맞는 **BASE + PREFERENTIAL 금리를 합산**해 실제 적용금리를 사용합니다.

```
targetPeriod = clamp(사용자 입력기간 or 12, min_period_month, max_period_month)
```

---

## RAG 자유질문 답변

기존 키워드 intent로 분류되지 않는 **자유형 질문**에 대해 상품 DB를 벡터 검색해 자연어로 답변합니다.

### 동작 흐름

```
사용자 자유질문 (intent 미매칭)
    ↓
ProductRagEngine.search(query, top_k=3)
  - 상품 문서를 OpenAI text-embedding-3-small로 임베딩
  - 코사인 유사도로 가장 관련 높은 상품 3개 검색
    ↓
RagAnswerGenerator.answer()
  - OpenAI API 있음 → GPT-4o-mini로 상품 정보 기반 자연어 답변
  - OpenAI API 없음 → 룰 기반 상품 목록 텍스트 폴백
    ↓
검색 결과 없음 → 상담사 연결 폴백
```

### 인덱스 관리

| 시점 | 동작 |
|------|------|
| 서버 시작 | `deposit_banking_products` 테이블의 판매 중 상품 전체 인덱스 빌드 |
| Kafka `ContractCreated` 수신 | 인덱스 자동 재빌드 |
| 재시작 | 인메모리 인덱스이므로 매 시작마다 재빌드 (상품 20개 수준, 약 5초 내외) |

### 자유질문 예시

| 질문 | 답변 방식 |
|------|---------|
| "우대금리 조건이 뭐야?" | 우대금리 조건이 있는 상품 검색 → GPT 설명 |
| "비대면 가입 혜택 있어?" | 비대면 관련 상품 검색 → GPT 설명 |
| "가입 기간이 가장 긴 상품은?" | 상품 검색 → GPT 비교 답변 |
| "청년도약계좌 어떤 상품이야?" | 청년도약 상품 검색 → GPT 설명 |
| "초보자한테 맞는 상품은?" | 관련 상품 검색 → GPT 추천 |

> **기존 추천 기능 미변경**: "예금 추천해줘", "내 현금흐름 분석해줘" 등 기존 intent 키워드가 매칭되는 질문은 기존 추천 로직으로 라우팅됩니다.

### 관련 파일

| 파일 | 역할 |
|------|------|
| `app/rag.py` | `ProductRagEngine` — 임베딩·인덱싱·코사인 유사도 검색 |
| `app/llm.py` | `RagAnswerGenerator` — RAG 결과 → GPT/룰 기반 자연어 답변 생성 |
| `app/services.py` | `_try_rag_answer()` — intent=None 분기에서 RAG 호출 |
| `app/main.py` | 서버 시작 시 인덱스 빌드, Kafka 재빌드 트리거 |

---

## 파일 첨부 분석

챗봇 파일 첨부 기능은 프론트엔드(pdf.js)가 PDF 텍스트를 추출한 뒤 서버로 전송하면, 서버가 분석 결과를 반환하는 구조입니다.

### 분석 유형

| analyze_type | 대상 문서 | 분석 내용 |
|-------------|---------|---------|
| `CASH_FLOW` | 타행 거래내역 | 월 수입·지출 패턴, 잉여자금, 저축 여력, 상품 추천 포인트 |
| `TERMS` | 약관 | 주요 조항 요약, 중도해지·수수료·이자 관련 내용 강조 |
| `PRODUCT` | 상품 설명서 | 금리 조건, 가입 금액·기간, 세제 혜택, 주요 특징 |
| `ENROLLMENT` | 서류 제출 | 파일 저장 후 DB 기록, 접수 완료 메시지 반환 |

### LLM 분석 (`/chatbot/file/analyze`)

`CONSULTATION_OPENAI_API_KEY`가 설정된 경우 **OpenAI GPT-4o-mini**로 분석합니다.  
API 키가 없으면 **룰 기반 텍스트 파싱**으로 자동 폴백합니다.

```json
POST /chatbot/file/analyze
{
  "text": "PDF에서 추출한 텍스트",
  "analyze_type": "CASH_FLOW",
  "customer_no": "9001"
}
```

### 서류 제출 (`/chatbot/documents/upload`)

```
POST /chatbot/documents/upload
Content-Type: multipart/form-data

file=<PDF파일>
customer_no=9001
doc_type=ENROLLMENT
```

업로드된 파일은 `CONSULTATION_UPLOADS_DIR`에 저장되고 `chatbot_documents` 테이블에 기록됩니다.

---

## 챗봇 위젯 개선 사항

`web/components/chatbot/ChatbotWidget.tsx`에 아래 기능이 추가·수정되었습니다.

### 로그인 화면

챗봇을 열면 로그인 여부를 확인합니다.

- 비로그인 상태: "로그인이 필요한 서비스입니다" 안내 + `/login` 이동 버튼
- "비회원으로 계속하기" 버튼: 로그인 없이 챗봇 이용 가능 (고객번호 없이 일반 대화만 가능)

### 상품 카드 가입하기 버튼

`PRODUCT_GUIDE`, `CASH_FLOW_RECOMMEND`, `PRODUCT_SEARCH` 응답의 상품 카드에 **가입하기** 버튼이 표시됩니다.

- 버튼 클릭 시 `/products/deposit/join/{product_id}` 페이지로 이동 (새 탭)
- `product_id`가 없는 카드(외부 상품 등)에는 버튼이 표시되지 않음
- 백엔드 응답의 `product_id` 필드: `services.py`·`user_finance.py`에서 모든 추천 카드에 추가

### 예금/적금 분리 추천

상품 추천 목적 버튼("목돈 굴리기", "월적립 저축" 등)이 `CASH_FLOW_RECOMMEND` 대신 `PRODUCT_SEARCH`를 호출합니다.

- 예금 버튼 → `product_type: "DEPOSIT"` 전달 → DEPOSIT 상품만 반환
- 적금 버튼 → `product_type: "SAVINGS"` 전달 → SAVINGS 상품만 반환
- 백엔드 `_score_products()`에 `preferred_type` 파라미터 추가: 지정된 타입이 아닌 상품은 채점에서 제외

### 금액 필터 개선

사용자가 입력한 금액이 올바르게 상품 필터링에 반영됩니다.

- 한국어 금액 파싱: `parseKoreanAmount()` 헬퍼로 "100만원" → 1,000,000 변환
- 금액이 입력되면 고객 실제 잔액 대신 입력 금액을 기준으로 채점
  - 예금: `total_balance` 를 입력 금액으로 대체
  - 적금: `monthly_surplus` 를 입력 금액으로 대체
- 입력 금액 < 모든 상품 최소 가입금액인 경우: 상품 목록 대신 한국어 안내 메시지 반환
  ```
  입력하신 가입금액 100,000원은 해당 상품들의 최소 가입금액(500,000원~)보다 적습니다.
  금액을 500,000원 이상으로 다시 입력해 주세요.
  ```

---

## 현금흐름 분석

`_analyze_customer_cash_flow()` 함수 집계 기준:

| 항목 | 처리 방식 |
|------|----------|
| 분석 기간 | 최근 3개월 거래만 포함 |
| 테스트 계좌 제외 | 30일 이상 거래 이력 있는 계좌만 분석 대상 |
| 내부 이체 제외 | TRANSFER 타입 전체 제외 |
| 내부 이체 감지 | counterparty_account_id / 계좌번호 / 당일 동일금액 DEPOSIT+WITHDRAW 쌍 |
| 잔액 구분 | `total_balance`: 전체 ACTIVE 합계 (참고용)<br>`investable_balance`: `is_withdrawable=True` 계좌만 (ROI 계산 기준) |

응답 `data[0].cf_debug` 필드에 계산 근거가 포함됩니다:

```
분석계좌=1개 | 기준=최근3개월 | 전체ACTIVE잔액=13,006,030원 |
투자가능잔액(출금가능)=12,906,030원 | 외부입금=9,600,000원 |
외부출금=2,172,000원 | 월잉여금=2,476,000원
```

---

## 나이 기반 상품 필터

`customer-service` API 연동으로 고객 나이를 조회해 가입 자격 없는 상품을 필터링합니다.

```
GET {CUSTOMER_SERVICE_URL}/api/v1/customers/me
Header: X-Customer-Id: {customer_id}
Response: { data: { birthDate: "19900101", ... } }
```

| target_group | 조건 | 처리 |
|-------------|------|------|
| 청년층 (id=3, 만 19~34세) | 나이 > 34 또는 나이 < 19 | 추천 제외 |
| 현역병 (id=4) | 상품명 키워드 기반 | 추천 제외 |
| 일반고객 (id=1) | 모든 나이 | 추천 포함 |

> customer-service 연동 실패(타임아웃 2초) 시 상품명 키워드 필터로 자동 fallback

---

## API 엔드포인트

### 챗봇

| Method | Path | 설명 |
|--------|------|------|
| POST | `/chatbot/consultations/start` | 챗봇 상담 시작 |
| POST | `/chatbot/consultations/{id}/messages` | 메시지 전송 |
| POST | `/chatbot/features/{code}/execute` | 피처 직접 실행 |
| GET | `/chatbot/features` | 피처 목록 조회 |
| POST | `/chatbot/transfer` | 챗봇 이체 실행 |

### 상담사 채팅

| Method | Path | 설명 |
|--------|------|------|
| GET | `/chat/queue` | 대기 큐 조회 |
| POST | `/chat/consultations/{id}/connect` | 상담사 연결 |
| POST | `/chat/consultations/{id}/messages` | 메시지 전송 |
| POST | `/chat/consultations/{id}/end` | 상담 종료 |

### 파일 첨부

| Method | Path | 설명 |
|--------|------|------|
| POST | `/chatbot/file/analyze` | PDF 텍스트 분석 (CASH_FLOW / TERMS / PRODUCT) |
| POST | `/chatbot/documents/upload` | 서류 파일 업로드 및 DB 기록 |

### 기타

| Method | Path | 설명 |
|--------|------|------|
| GET | `/health` | 헬스체크 → `{"status":"UP"}` |
| GET | `/metrics` | Prometheus 메트릭 |

---

## 환경변수

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `CONSULTATION_DATABASE_URL` | **(필수)** | PostgreSQL 연결 URL (`postgresql+psycopg://...`) |
| `CONSULTATION_KAFKA_ENABLED` | `false` | Kafka 이벤트 발행 활성화 여부 |
| `CONSULTATION_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka 브로커 주소 |
| `CONSULTATION_OPENAI_API_KEY` | `""` | OpenAI API 키 (LLM 추천 활성화 시 필요) |
| `CONSULTATION_OPENAI_MODEL` | `gpt-4o-mini` | 사용할 LLM 모델명 |
| `CONSULTATION_CUSTOMER_SERVICE_URL` | `http://localhost:8081` | customer-service 주소 (나이 조회) |
| `CONSULTATION_LANGFUSE_ENABLED` | `false` | Langfuse LLM 트레이싱 활성화 여부 |
| `CONSULTATION_UPLOADS_DIR` | `./uploads` | 서류 업로드 파일 저장 경로 |

---

## DB 스키마 관리

### 소유 테이블

consultation-service는 SQLAlchemy `Base.metadata.create_all()`로 기동 시 아래 테이블을 자동 생성합니다.

| 테이블 | 설명 |
|--------|------|
| `consultation` | 상담 이력 |
| `chatbot_scenario` | 챗봇 시나리오 |
| `chatbot_intent` | 챗봇 의도 분류 |
| `chatbot_node` | 챗봇 노드 |
| `chatbot_node_button` | 챗봇 노드 버튼 |
| `chatbot_node_flow` | 챗봇 노드 흐름 |
| `chatbot_consultation` | 챗봇 상담 세션 |
| `chat_consultation` | 상담사 채팅 상담 |
| `chat_message_history` | 채팅 메시지 이력 |

### deposit-db 공유 구조

consultation-service는 deposit-db를 사용합니다. 고객 계좌·상품·거래 데이터를 직접 SQL로 조회하기 때문입니다.

| deposit-db 테이블 | 용도 |
|------------------|------|
| `deposit_banking_products` | 상품 목록·안내·비교 |
| `deposit_accounts` | 고객 계좌 조회 |
| `deposit_transactions` | 거래내역 조회 |
| `deposit_special_terms` | 우대금리 조건 |
| `deposit_interest_history` | 이자내역 조회 |
| `banking_deposit_product_interest_rates` | 금리 정보 |

> **주의**: consultation-service 전용 DB로 분리하면 위 deposit 테이블을 읽지 못해 챗봇 기능이 작동하지 않습니다.

### 기동 순서

deposit-service → consultation-service 순으로 기동해야 합니다. deposit-service Flyway V12가 먼저 실행돼 구 스키마 chatbot·consultation 테이블을 정리한 뒤, consultation-service가 `create_all()`로 올바른 스키마를 생성합니다.

```
deposit-service 기동 (Flyway V12: 구 chatbot·consultation 테이블 DROP)
  ↓
consultation-service 기동 (SQLAlchemy create_all: 테이블 재생성)
```

docker-compose는 `depends_on: deposit-db`로 순서를 보장합니다.

---

## 로컬 실행

### 가상환경 설정

```bash
cd services/consultation-service
python -m venv .venv

# Windows
.venv\Scripts\activate

# macOS / Linux
source .venv/bin/activate

pip install -r requirements.txt
```

### 실행 (Windows)

```bat
start-8087.bat
```

### 직접 실행

```bash
CONSULTATION_KAFKA_ENABLED=false \
CONSULTATION_DATABASE_URL=postgresql+psycopg://deposit:deposit@localhost:5432/deposit_db \
uvicorn app.main:app --host 0.0.0.0 --port 8087 --reload
```

### 헬스체크 확인

```bash
curl http://localhost:8087/health
# {"status":"UP"}
```

### Docker Compose

```bash
cd services/consultation-service
docker compose up -d
```

서비스 포트: **8087**

> **주의 — 메인 docker-compose와 동시 실행 시 포트 충돌**
>
> 메인 `docker-compose.yml`과 함께 실행하면 아래 두 포트가 충돌합니다. 이미 반영되어 있으나 메인 compose 변경 시 재확인 필요합니다.
>
> | 컨테이너 | 기본 포트 | 변경 포트 | 충돌 대상 |
> |----------|----------|----------|----------|
> | `consultation-postgres` | 5439 | **5440** | `ib-common-db` |
> | `consultation-kafka` | 9092 | **9093** | `ib-kafka` |
