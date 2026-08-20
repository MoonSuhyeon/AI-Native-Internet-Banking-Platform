# 대출 심사원 보조 자동 대출 심사 — 큰 그림

<!-- plan-status -->
> **상태: 🔨 진행 중** — Phase 1.5·1.6 완료, 1.7(RAG) 보류. 문서 머리말이 스스로 밝힌다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> Last updated: 2026-05-26
> Status: Phase 1.5/1.6 완료, Phase 1.7 RAG 보류, Pre-Review Agent (A4~A9) 완료
> Owner: ai-service 팀

본 문서는 자동 대출 심사 보조 시스템의 **시스템 비전·구성 요소·책임 분담·의사결정 흐름**을 한 곳에 정리한 source of truth. 신규 합류자가 5분 안에 의도를 파악하고, 후속 phase 작성 시 본 문서를 참조한다.

상세는 별도 문서로 분리:
- [docs/ai/DATASETS.md](../ai/DATASETS.md) — 외부 데이터셋 출처·라이선스
- [docs/ai/MODEL_CARDS.md](../ai/MODEL_CARDS.md) — 모델 버전·평가·한계
- [docs/ai/PROMPT_REGISTRY.md](../ai/PROMPT_REGISTRY.md) — 프롬프트 카탈로그
- `docs/ai/COMPLIANCE.md` (작성 예정) — 규제 인용

---

## 1. 시스템 비전 — 트리아지 + 사전처리 자동화 + LLM 리포트

**대상**: 사람 심사원(여신 심사원)
**목적**: 심사원의 품을 줄여 **애매한 케이스에 집중**하게 만든다.

### 처리 방식 — 3 트랙 분기

| 트랙 | 비율(목표) | 처리 |
|------|----------|------|
| **Track 1 — 자동 승인 권고** | 30~40% | PD 매우 낮음 + 모든 정책 룰 통과 + 서류 완비. 심사원은 **확인 sign-off** 5분 이내 |
| **Track 2 — 자동 반려 권고** | 15~25% | Hard fail (신용점수 미달, 연체 진행 중, DSR/LTV 위반, 서류 부적격). 심사원은 **사유 확인** 후 통보 |
| **Track 3 — 사람 심사 필수** | 40~55% | 애매한 PD 구간 + 고액 + 특수 케이스. 자동 분석 결과를 카드로 받아 **심층 판단** |

**핵심 원칙**: 명확한 케이스(승인·반려 둘 다)는 자동 처리, 사람은 모호한 케이스에만 집중.

### 자동화하는 부분 (백엔드 사전처리, 9 Step)

심사원이 신청을 열기 *전에* 백엔드가 다 끝내놓는 일들:

1. **서류 완비성 체크**: 상품별 필수 서류 N종 모두 제출됐는지
2. **서류 진위·유효성**: 발급일자 / 유효기간 / 위변조 의심 패턴 (OCR + 검증)
3. **데이터 일관성**: 신청서 진술 vs 서류 내용 매칭 (이름·생년월일·소득)
4. **CB 점수 조회**: NICE/KCB API 호출(외부)
5. **DSR / LTV 자동 계산**: 거래내역·자산 기반
6. **정책 룰 자동 매칭**: hard constraint(DSR ≤ 40, LTV ≤ 70, 연체 24m, 직업안정성 등)
7. **PD 산출**: ML 모델 인퍼런스
8. **트랙 분기**: 위 결과 종합해 Track 1/2/3 분류
9. **LLM 리포트 생성**: 모든 분석 결과 + 근거를 자연스러운 한국어 심사 리포트로

### 자동화하지 않는 부분 (사람 영역)

- Track 3 (모호한 케이스)의 최종 판단
- Track 1·2의 sign-off (자동 결과 검증)
- Track 1·2의 sample audit (무작위 5% 등 검토)
- 정책 매트릭스 변경 (신용정책위원회)
- 분쟁·이의 처리

### 핵심 가치

- 심사원 1인당 처리 가능 신청 수 **3~5배** (Track 1·2 자동 처리)
- 심사 의견 작성 시간 **70% 단축** (LLM 리포트 초안)
- 일관성 ↑ (정책 룰 동일 적용)
- 사각지대 ↓ (서류 누락·진위 자동 검출)
- 사람 = 가치 높은 일(애매한 케이스)에 집중

---

## 2. 사용자 시나리오 (트랙별)

### Track 1 — 자동 승인 권고 (심사원 5분)

1. 신청 접수 → 백엔드가 사전처리 9 단계 자동 실행 (~10초)
2. 심사원 대시보드에 "자동 승인 권고" 카드 — PD 0.02, 모든 룰 통과, 서류 완비
3. LLM 리포트 1단락 자동 생성
   > "신청자 김OO은 NICE 870, DSR 22%, 안정적 근로소득 5년, 신청 사유 일관. 정책 매트릭스 PD 임계치 0.08 대비 0.02로 충분히 안전. 자동 승인 권고."
4. 심사원 클릭 → sign-off → 통보

### Track 2 — 자동 반려 권고 (심사원 3분)

1. 신청 접수 → 사전처리
2. Hard fail 검출 (예: NICE 540, 진행 중 연체 2건)
3. LLM 리포트:
   > "신청자 박OO은 NICE 540 (자행 정책 최소 600 미달), 진행 중 연체 2건 합 850만원. 신용정보법 §X 및 자행 정책서 §Y 기준 반려 사유에 해당. 통보 시 거절 사유: 신용도 미달."
4. 심사원 확인 → sign-off → 거절 통보 (사유 문구도 LLM 초안)

### Track 3 — 사람 심사 필수 (심사원 30분~수시간)

1. 신청 접수 → 사전처리
2. 모호 신호 검출 (예: PD 0.09, DSR 38%, 신청 사유 불일치 의심)
3. 심사원 화면에 다음 모두 표시:
   - PD + SHAP top-5 위험 요인
   - 정책 룰 통과/실패 상세
   - 서류 검증 결과
   - **유사 과거 케이스 5건** (RAG)
   - **신청 사유 plausibility 분석** (LLM)
   - **심사 리포트 초안** (LLM, 심사원이 수정)
   - 정책·규제 인용 (RAG)
4. 심사원 심층 검토 → 의견 작성 → 결정

### 공통 — 감사·재현성

- 모든 트랙의 자동 분석 결과는 `audit_log` 영구 보존
- Track 1·2 의 5% 무작위 표본은 정기 검토(품질 감사)
- 분쟁 발생 시 동일 입력으로 자동 분석 재현 가능

---

## 3. 컴포넌트 큰 그림 — 사전처리 파이프라인

```
신청 접수 (loan-service)
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 1. 서류 검증                                       │
│  - 필수 서류 완비성, 발급일자/유효기간, OCR 진위·일관성  │
└──────────────────────────────────────────────────────┘
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 2. 외부 데이터 조회 (비동기 병렬)                   │
│  - NICE/KCB API (CB 점수)                              │
│  - 자행 거래내역·자산                                   │
└──────────────────────────────────────────────────────┘
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 3. 결정론 계산                                    │
│  - DSR / LTV / 소득안정성 / 연체이력                    │
└──────────────────────────────────────────────────────┘
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 4. 정책 룰 매칭 (Rule Engine)                     │
│  - Hard constraint (DSR ≤ 40, LTV ≤ 70 등)            │
│  - PD 임계치 매트릭스 (상품 × 세그먼트, 자행 정책서)     │
└──────────────────────────────────────────────────────┘
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 5. ML 인퍼런스                                    │
│  - PD 모델(LightGBM, ONNX) → 디폴트 확률 + SHAP top-k  │
└──────────────────────────────────────────────────────┘
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 6. 트랙 분기                                      │
│  Hard fail               → Track 2 (자동 반려)         │
│  PD ≪ 매트릭스 임계 & all OK → Track 1 (자동 승인)     │
│  그 외                    → Track 3 (사람 심사)         │
└──────────────────────────────────────────────────────┘
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 7. LLM 보강 (외부 API + PII 마스킹)                │
│  - 신청 사유 plausibility (PurposeAnalysis)             │
│  - 진술 vs 거래내역 불일치 검출                          │
│  └ RAG 도구: retrieve_similar_cases / retrieve_policy   │
└──────────────────────────────────────────────────────┘
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 8. LLM 리포트 생성                                │
│  - 트랙별 톤 다른 심사 리포트 초안                       │
│  - 근거 인용(정책·유사케이스) 강제 grounding            │
└──────────────────────────────────────────────────────┘
    ↓
┌──────────────────────────────────────────────────────┐
│ Step 9. 감사 로그·DB 적재                              │
│  - LOAN_REVIEW (reviewer=SYSTEM, round=0)              │
│  - audit_log (입력·출력·모델버전·프롬프트해시)           │
└──────────────────────────────────────────────────────┘
    ↓
심사원 대시보드에 카드 출력 (Track 별 UI 다름)
    ↓
사람 심사원 sign-off / 심층 검토 → LOAN_REVIEW round=1
```

**응답 시간 목표**:
- Step 1~6 (트랙 분기까지): p99 < 1.5s (sync)
- Step 7~8 (LLM 보강·리포트): p99 < 8s (async push)

---

## 4. ML — 트리아지 분류기의 변별력

**원칙: ML 은 변별력만 산출. 모든 의사결정 임계치는 자행 신용정책서·금감원 가이드라인을 그대로 따른다 (자율 결정 X).**

### ML 모델 = PD 모델 1개

- **알고리즘**: LightGBM 이진 분류
- **출력**: 12개월 내 디폴트 확률 (0~1) — **PD (Probability of Default)**
- **부산물**: SHAP top-k (별도 모델 X, 라이브러리)

### 트리아지에서의 역할

PD 가 **트랙 분기**의 핵심 신호:

```
Hard fail (정책 룰 위반)              → Track 2 (자동 반려)
PD ≤ 매트릭스[product][seg] × 0.3     → Track 1 (자동 승인 — 충분히 여유)
PD ≤ 매트릭스[product][seg]            → Track 3 (사람 심사, 위험 보통)
PD > 매트릭스[product][seg]            → Track 2 (자동 반려, PD 초과)
```

→ **"안전 여유(0.3 배수)"** 가 Track 1/3 가르는 핵심. 매트릭스 임계치는 정책서, 안전 여유 비율도 정책서 또는 거버넌스 위원회.

### PD 출력의 다른 활용 (모두 정책 룰·수식)

| 결과 | 산출 방식 | 출처 |
|------|---------|------|
| 승인/반려 (트랙 분기) | PD × 매트릭스 룰 | 정책서 |
| 한도 | DSR/LTV/소득배수 룰 | 법·정책서 |
| 금리 | base_rate + α × PD | 가격 정책 |
| 거절 사유 설명 | SHAP top-k → LLM 자연어 변환 | 모델 + LLM |

### 입력 feature 명세 (~30개)

| 카테고리 | feature |
|---------|---------|
| 신용 | credit_score_proxy(NICE/KCB), delinquency_history_24m, total_debt, credit_card_util |
| 재무 | dsr, ltv, annual_income, monthly_cashflow_mean/std, savings_balance |
| 인구 | age, sex, occupation, education, housing_type, province, marital_status |
| 신청 | product_code, requested_amount, requested_period_mo, purpose_cd |
| LLM 보강 | purpose_plausibility, purpose_specificity, statement_inconsistency_score |
| 규칙 | rule_score, hard_fail_count |
| 매크로 | policy_rate, household_credit_yoy, delinquency_rate_segment (ECOS 시계열, snapshot 시점 매칭) |

→ 약 80개에서 시작 → SHAP 분석으로 상위 30개 압축.

### 평가 지표

- **변별력**: AUC ≥ 0.85, KS ≥ 0.40 (합성 oracle 기준)
- **트랙 분포**: Track 1/2/3 비율이 정책서 목표(30~40 / 15~25 / 40~55) 안에 들어오는지
- **트랙 1·2 정확성**: sample audit 검토 결과 자동 결정 오류율 ≤ 1%
- **공정성**: sex/age/segment 별 PD 분포 격차 — 트랙 1 진입률·트랙 2 진입률 모두 4/5ths rule 충족
- **안정성**: PSI 일일, 0.2 초과 시 알람

### 학습 데이터

- **현재 (Phase 1.1)**: 합성 14만 (`synthetic_application`). NVIDIA Nemotron-Personas-Korea + KOSIS 분포 + Home Credit EXT_SOURCE
- **실데이터 확보 후**: transfer learning. feature 명세는 `synthetic_application` 컬럼명과 동일하게 유지해 매끄러운 교체
- **외부 reference**: Home Credit(307k×122), Lending Club — feature 명세 sanity check 용, 직접 학습 X

### 모델 한 가지로 충분한 이유

- 트랙 분기·한도·금리는 정책 룰 + PD 단일 입력
- 사기 탐지·LGD·EAD 같은 별도 모델은 본 MVP 범위 외 (Phase 2+)
- 1 모델로 단순 운영 → 학습·재배포·MRM 부담 ↓

---

## 5. LLM — 리포트 생성기로서의 역할

**원칙**: LLM 은 결정하지 않는다. **근거 정리 + 자연어 변환**만.

### 호스팅 전략

- PoC: Vertex AI Gemini 2.0 Flash Free tier
- 운영: Claude Haiku 4.5 (한국어 리포트 품질)
- 모든 호출 전 `PiiMaskingFilter` 통과 (`com.bank.ai.privacy`)

### 사용처

**1. 심사 리포트 자동 생성 (가장 큰 비중)**

입력: 트랙·PD·SHAP·룰 결과·서류 검증·CB 점수·RAG 결과 모두
출력: 트랙별 톤 다른 한국어 심사 리포트 1~3 문단

- Track 1: 간결한 승인 근거 (1 문단)
- Track 2: 거절 사유 + 통보 문구 초안 (2 문단)
- Track 3: 심층 분석 보고서 — 위험요인·강점·유사케이스·권고 의견 (3 문단)

형식: structured output (`ReviewReport(track, summary, risk_factors[], strengths[], recommendation, citations[])`)

**Grounding 강제**: 모든 인용은 RAG retrieve 결과 id 포함. 인용 없는 주장 거부.

**2. 신청 사유 plausibility 분석**

- 입력: 페르소나 + `purpose_text`
- 출력: `PurposeAnalysis(plausibility 0~1, specificity 0~1, redFlags[])`
- 결과는 ML feature 로 합류 (LLM 자체가 결정 X)

**3. 진술-거래내역 불일치 검출**

- 입력: 신청 진술(소득·직업 등) + 거래내역 *요약 메타*(raw X)
- 출력: 불일치 점수 + 의심 항목 리스트

**4. 거절 통보 문구 생성** (Track 2)

- 입력: 거절 사유 코드 + 정책서 근거
- 출력: 고객용 안내문 초안 (법·정책 인용)

### 공통 원칙

- structured output 강제 (`BeanOutputConverter`) — 자유 텍스트 응답 거부
- LLM 출력은 항상 (a) feature 화해 ML 보강 또는 (b) 심사원 정보 제공
- LLM 자체가 승인/거절 결정 X
- 프롬프트 인젝션 방어: `<user_content>` delimiter + system hierarchy
- 토큰 비용 메트릭(`MeterRegistry`) + 일일 cap → 초과 시 LLM 단계 skip(리포트는 템플릿)
- 프롬프트 카탈로그 `docs/ai/PROMPT_REGISTRY.md` 에 버전 관리

---

## 6. RAG — 리포트 grounding 의 근거 공급원

**원칙**: LLM 리포트의 모든 인용은 RAG 검색 결과여야 한다. 인용 없는 주장은 거부.

### 6.1 트랙별 RAG 활용도

| 트랙 | RAG 사용 | 호출 패턴 |
|------|---------|----------|
| **Track 1 자동 승인** | 최소 (정책 근거 1~2건) | `retrieve_policy` 1회 — 승인 근거 인용 |
| **Track 2 자동 반려** | 중간 (거절 사유 정책 인용 필수) | `retrieve_policy` 1회 — 거절 사유 법·정책 명시 |
| **Track 3 사람 심사** | 최대 (3개 코퍼스 모두) | `retrieve_similar_cases` + `retrieve_policy` + 필요 시 `retrieve_faq` |

### 6.2 무엇을 검색하나 (3개 코퍼스)

| 코퍼스 | 용도 | 출처 | 갱신 주기 |
|--------|------|------|----------|
| `kb_similar_cases` | Track 3 "유사 과거 5건" + 심사원 검색 | LOAN_REVIEW 누적 + 합성 데이터 | 일 |
| `kb_policy_regulation` | 모든 트랙 리포트 인용 — 거절 사유·승인 근거의 법·정책 출처 | 금감원 가이드, 자행 여신정책서, 신용정보법, 정책 매트릭스 자체 | 분기 |
| `kb_internal_faq` | Track 3 심사원 Q&A | 사내 wiki, FAQ | 월 |

### 6.3 어떻게 구축

- **벡터 스토어**: PostgreSQL **pgvector** (ai_db 에 얹기, 별도 Pinecone X)
  - 스키마: `ai_embedding(id, corpus, source_id, chunk_text, embedding vector(1024), metadata jsonb)`
- **임베딩**: bge-m3 (한국어 강점, 1024 차원). Vertex AI Embedding API or 자체 ONNX
- **청킹**:
  - 유사 케이스: 신청 1건 = 1 청크. 메타 = decision/segment/amount/product
  - 정책/규제: 조항 단위(제N조 정규식). 메타 = 출처·발효일·매트릭스 좌표(product×seg)
  - FAQ: Q/A 쌍 = 1 청크
- **검색 전략**: **Hybrid Search**
  - 벡터 유사도(코사인) + PostgreSQL FTS(Nori 토크나이저) BM25 가중합
  - top-k=5, similarity threshold 0.7
- **재순위(rerank)**: 1차 50개 → bge-reranker-v2 로 top-5 (Track 3 한정)
- **메타 필터링**: Track 3 케이스 검색 시 (product, segment) 일치 우선
- **Grounding 강제**: LLM 리포트의 모든 인용·근거는 RAG 결과 id 포함 필수. 누락 시 리포트 거절 → 템플릿 fallback

### 6.4 호출 패턴 (Spring AI Tool)

- `retrieve_similar_cases(applicant_meta, k=5) → List<CaseChunk>`
- `retrieve_policy(question, filter_matrix_coord=true, k=3) → List<PolicyChunk>`
- `retrieve_faq(question, k=3) → List<FaqChunk>`

LLM agent 가 system prompt 의 Tool 명세 보고 트랙·상황에 따라 자율 호출. 단, Track 1·2 는 호출 횟수 cap 1회로 제한(비용 통제).

### 6.5 정책 매트릭스 자체도 RAG 코퍼스

`kb_policy_regulation` 안에 정책 매트릭스 cell 별 정책 문서가 들어감. 예:

```yaml
tblId: pd_threshold_matrix
product: MORT_001
segment: regular
threshold: 0.15
doc: "주담대 정상 직장인 PD 임계치는 자행 신용정책서 §3.2.1 에 따라 0.15.
      근거: 1금융권 평균 연체율 0.7% × 약 20배 안전여유"
```

→ 거절·승인 리포트의 정책 인용이 자동 매칭.

---

## 7. 책임 분담 — 트리아지 파이프라인 9 Step × 5 주체

### Step 별 책임 매트릭스

| Step | 작업 | 백엔드(결정론) | Rule Engine | ML (PD) | LLM+RAG | 사람 심사원 |
|------|------|:------:|:----:|:----:|:------:|:---------:|
| 1 | 서류 완비성·진위·일관성 | ✅ (OCR+정규식) | △ (룰 조회) | | | |
| 2 | NICE/KCB·거래내역 조회 | ✅ (API) | | | | |
| 3 | DSR/LTV/소득안정성 계산 | ✅ (수식) | | | | |
| 4 | 정책 룰 매칭 (hard constraint, 매트릭스) | | **✅** | | | |
| 5 | PD 산출 (LightGBM) + SHAP | | | **✅** | | |
| 6 | 트랙 분기 (1/2/3) | | ✅ (Rule + PD) | | | |
| 7 | 신청 사유 분석·진술 불일치 | | | | ✅ | |
| 8 | LLM 리포트 + RAG 인용 | | | | ✅ | |
| 9 | audit_log·DB 적재 | ✅ | | | | |
| 10 | 트랙별 sign-off / 심층 검토 | | | | | ✅ |
| 11 | sample audit (Track 1·2 무작위 5%) | | | | | ✅ |
| 12 | 매트릭스·정책 변경 결정 | | | | | ✅ (정책위) |

### 핵심 원칙

1. **ML 은 의사결정 안 함**. PD 확률만 산출. 어디서 cut 할지·한도·금리는 모두 정책서.
2. **LLM 도 의사결정 안 함**. 출력은 (a) feature 로 ML 보강 또는 (b) 리포트로 심사원 정보 제공.
3. **트랙 분기 결정권**도 Rule Engine 에 있음 — ML 출력을 정책 매트릭스 임계치와 비교하는 결정론적 로직.
4. **임계치 변경 = 정책 파일 갱신**. 모델 재학습 아님. 신용정책위원회 분기 리뷰로 변경.
5. **AI 가 하는 일**: (a) 정책서를 빠르게·일관되게 자동 적용, (b) 데이터 패턴 기반 PD 보강, (c) 근거 정리된 리포트로 심사원 시간 절약.
6. **사람의 일**: (a) Track 3 심층 판단, (b) Track 1·2 sign-off·audit, (c) 정책·매트릭스 변경.

### 트랙별 사람 vs 자동 비율 (목표)

| 트랙 | 자동 결정 | 사람 시간/건 |
|------|---------|------------|
| Track 1 | 결정 100% 자동, sign-off 만 사람 | 5분 |
| Track 2 | 결정 100% 자동, sign-off + 사유 검토 사람 | 3분 |
| Track 3 | 결정 0% 자동, 분석 결과만 자동 제공 | 30분~수시간 |
| sample audit | (Track 1·2 의 5%) | 별도, 정기 |

### 응답 시간 SLA

| 단계 | 목표 | 비고 |
|------|------|------|
| Step 1~6 (트랙 분기까지) | p99 < 1.5s | 동기 응답. 심사원 보드 즉시 표시 |
| Step 7~8 (LLM 보강·리포트) | p99 < 8s | 비동기 push. 카드에 "리포트 생성 중" 후 갱신 |
| 전체 (신청 → 트랙별 처리) | p95 < 10s | end-to-end |

---

## 8. 의사결정 흐름 (Sequence)

1. 신청 접수 (loan-service)
2. ai-service `/evaluate` 호출 (REST, sync)
3. 사전처리 Step 1~6 실행 → 트랙 결정 + 1차 응답
4. 결과를 loan-service 에 반환, 심사원 대시보드에 카드 표시
5. 비동기로 Step 7~8 실행 (LLM 보강·리포트)
6. 완료 시 `LOAN_REVIEW` UPDATE + 심사원 UI push (WebSocket 또는 SSE)
7. 심사원이 트랙별 처리:
   - Track 1: 카드 보고 sign-off → APPROVE
   - Track 2: 사유 확인 후 sign-off → REJECT (LLM 통보 문구 그대로 또는 수정)
   - Track 3: 모든 자동 분석 + RAG + 리포트 보고 심층 판단 → 의견 작성 → 결정
8. 최종 결정 `LOAN_REVIEW` 신규 행 (`reviewer_id=<심사원>, review_round=1`)
9. 트랙 1·2 결정은 정기 sample audit (5%) 대상

---

## 9. 신뢰성·감사성

- **audit_log**: 모든 자동 결정의 입력/출력/모델버전/프롬프트해시 기록
- **Shadow 모드**: 운영 첫 2개월, 자동심사는 표시만 결정권 없음 — 사람 심사 일치율 측정
- **킬스위치**: `bank.ai.auto-underwriting.enabled` 플래그 + 사유별 force-human 매트릭스
- **Drift 감지**: feature PSI 매일, 0.2 초과 시 알람
- **공정성 정기 리포트**: 주간, 4/5ths rule 위반 검출
- **모델 카드**: `docs/ai/MODEL_CARDS.md` — 각 모델 버전의 학습 데이터·평가·한계 명시
- **프롬프트 레지스트리**: `docs/ai/PROMPT_REGISTRY.md` — 모든 시스템 프롬프트의 버전 추적
- **재현성**: 합성 데이터 seed 고정, 모델 ONNX export, RAG 인덱스 버전 pin

---

## 10. 단계별 마일스톤

> 상세 실행 계획: [next-phase-roadmap.md](next-phase-roadmap.md) (인덱스)
> Phase B 상세: [phase-b-operational.md](phase-b-operational.md)
> Phase C 상세: [phase-c-ml-pipeline.md](phase-c-ml-pipeline.md)
> Phase E 상세: [phase-e-frontend.md](phase-e-frontend.md)

### 완료된 Phase

| Phase | 내용 | 상태 | 상세 문서 |
|-------|------|------|----------|
| 0 | 공통 인프라 (ai-service, pgvector, PII, ai-db) | 부분 완료 | — |
| 1.1 | 합성 데이터 4 Layer (HMDA + Home Credit 한국화) | ✅ 완료¹ | hmda-localization.md, pd-label-acquisition.md |
| 1.5 | LLM 보강 — 신청 사유 plausibility 분석 | ✅ 완료 | llm-pipeline.md |
| 1.6 | LLM 리포트 생성기 (트랙별 톤) | ✅ 완료 | llm-pipeline.md |
| 1.6.1 | Pre-Review Agent (A4~A9) — Track 3 시뮬 + Track 2 거절 초안 | ✅ 완료 | pre-review-agent-plan.md |
| 1.7 | RAG 구축 (3 코퍼스) | ⏸ 보류 | rag-corpora.md |
| 1.8 | `/evaluate` API 통합 + RuleEngine 트랙 분기 | ✅ 완료 | pre-review-agent-plan.md |

> ¹ Phase 1.1 검증 결함 3개(delinquency λ 과소, credit_score 단조성 위반, 편향 pool 버그) → Phase C1에서 수정 예정

### 진행 예정 Phase

| Phase | 내용 | 기간 | 상태 | 상세 문서 |
|-------|------|------|------|----------|
| **B** | **운영 준비** — Audit Log · Observability · Shadow Mode · PSI Drift · Admin | ~4주 | ⬜ 예정 | [phase-b-operational.md](phase-b-operational.md) |
| B1 | Audit Log 영구 보존 (`ai_audit_log`, 5년 보존, 여신전문금융업법) | 1주 | ⬜ | 〃 §B1 |
| B2 | Observability — 14종 Micrometer 메트릭 + Grafana 대시보드 | 0.5주 | ⬜ | 〃 §B2 |
| B3 | Shadow Mode — `@Async` 분리 실행, 일치율 배치 집계 | 1주 | ⬜ | 〃 §B3 |
| B4 | PSI Drift 감지 + 4/5ths Rule 공정성 리포트 | 0.5주 | ⬜ | 〃 §B4 |
| B5 | Admin Endpoint — 재생성·kill-switch·상태조회 (RBAC) | 0.5주 | ⬜ | 〃 §B5 |
| **C** | **ML 파이프라인 완성** — 합성 데이터 수정 · ONNX 학습 · SHAP · inference-server | ~6주 | ⬜ 예정 | [phase-c-ml-pipeline.md](phase-c-ml-pipeline.md) |
| C1 | 합성 데이터 검증 결함 3개 수정 | 0.5주 | ⬜ | 〃 §C1 |
| C2 | hmda_v1 Decision Score LightGBM 학습 + ONNX export | 1.5주 | ⬜ | 〃 §C2 |
| C3 | homecredit_kr_v1 PD 모델 학습 + ONNX export + 캘리브레이션 | 1.5주 | ⬜ | 〃 §C3 |
| C4 | inference-server FastAPI 리팩터링 (onnxruntime dual model) | 1주 | ⬜ | 〃 §C4 |
| C5 | Java InferenceClient SHAP 필드 확장 | 0.5주 | ⬜ | 〃 §C5 |
| C6 | FeatureMapper (AutoReviewRequest → feature vector) | 0.5주 | ⬜ | 〃 §C6 |
| C7 | ML 회귀 검증 — Track 분포 · AUC/KS CI · 4/5ths Rule CI | 0.5주 | ⬜ | 〃 §C7 |
| **D** | **RAG 구축** (보류 해제 시) — pgvector + bge-m3 + 3 코퍼스 | ~4주 | ⏸ 보류 | rag-corpora.md |
| **E** | **심사원 대시보드** — React 18 + SSE + 트랙별 카드 + Admin 패널 | ~5주 | ⬜ 예정 | [phase-e-frontend.md](phase-e-frontend.md) |
| E1 | 프로젝트 설정 (Vite + shadcn/ui + TanStack Query + Zustand) | 0.5주 | ⬜ | 〃 §E1 |
| E2 | SSE 실시간 Push (SseEmitter + ReviewerSseRegistry) | 1주 | ⬜ | 〃 §E2 |
| E3 | 심사 대기 목록 큐 보드 | 0.5주 | ⬜ | 〃 §E3 |
| E4–E6 | Track 1/2/3 심사 카드 + SHAP 차트 + 시뮬레이션 카드 | 1.5주 | ⬜ | 〃 §E4~E6 |
| E7 | 관리자 패널 (Shadow · PSI · 공정성 · RPD/RPM) | 1주 | ⬜ | 〃 §E7 |
| E8 | 접근성 + 보안 (PII 이중 마스킹, RBAC) | 0.5주 | ⬜ | 〃 §E8 |
| **F** | **Phase 2 에이전트** — 2nd Opinion + 계약서 리스크 | 별도 로드맵 | ⬜ 후순위 | next-phase-roadmap.md §F |

### MVP 완성 기준

```
Phase B (완료) + Phase C (완료)
  → 실제 ONNX 모델 서빙 + 운영 감사 로그 + Shadow Mode 배포
  → 심사원에게 Shadow로 시범 노출, 일치율 ≥ 85% 4주 연속 달성
  → Phase E 완료 후 Sign-off UI → 정식 운영
```

---

## 부록 A. 용어 정리

| 약어 | 풀네임 | 의미 |
|------|--------|------|
| PD | Probability of Default | 12개월 내 디폴트 확률 |
| LGD | Loss Given Default | 디폴트 시 손실률 |
| EAD | Exposure At Default | 디폴트 시점 익스포저 |
| DSR | Debt Service Ratio | 부채상환비율 (연원리금/연소득) |
| LTV | Loan-to-Value | 담보인정비율 (대출액/담보가치) |
| CB | Credit Bureau | 신용평가사 (NICE/KCB) |
| KS | Kolmogorov-Smirnov | 분포 분리도 통계량 |
| SHAP | SHapley Additive exPlanations | 모델 해석 도구 |
| PSI | Population Stability Index | 분포 안정성 지표 |
| MRM | Model Risk Management | 모델 리스크 관리 |

## 부록 B. 본 문서 사용법

- 신규 합류자에게 5분 안에 시스템 의도 전달
- 의사결정 시 "이게 ML 일인지 LLM 일인지" 빠르게 판단
- 포트폴리오 발표 시 시스템 아키텍처 슬라이드 base
- 후속 phase 작성 시 본 문서가 source of truth
