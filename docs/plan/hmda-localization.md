# HMDA → 한국 자동심사 학습 데이터 변환 큰 그림

> Last updated: 2026-05-20
> Status: design draft (페르소나 풀 폐기 + HMDA 도입 결정 후 1차 안)
> 선행 문서: `docs/plan/banking-review-llm.md`

본 문서는 미국 HMDA(Home Mortgage Disclosure Act) 공개 데이터를 한국 자동심사 시스템의
학습·평가 데이터로 변환하기 위한 매핑 전략. 진짜 심사 결정 라벨(`action_taken`) 을 얻되,
한국 도메인(상품/지역/언어/정책)에 맞게 갈아끼우는 게 목적.

---

## 0. 전제·원칙

1. **HMDA 는 부동산 담보 대출만**. 카드론·사업자대출·자동차대출 제외 → MVP 학습은 주담대 중심.
2. **HMDA 의 진짜 라벨(action_taken)** 을 그대로 쓴다. oracle 시뮬레이션 라벨 폐기.
3. **한국 정책 매트릭스는 그대로** (DSR 40 / LTV 70 / NICE 600). HMDA 의 임계는 미국 CFPB 기준이라 무시.
4. **race/ethnicity 폐기**. 한국 도메인에 없고 개인정보보호법 충돌 + 학습 입력 부적합.
5. **누락된 한국 변수는 KOSIS + 합성**. 기존 한국 macro 로더는 살린다.

---

## 1. 컬럼 매핑 4분류

### 1.1 직역 가능 (의미 동일, 이름·단위만 변환)

| HMDA | 한국 컬럼 | 변환 |
|------|----------|------|
| `income` ($k 단위) | `annual_income_kw` | × 130 (만원 환산, 환율 1300원) |
| `loan_amount` | `requested_amount_kw` | × 130 |
| `property_value` | `property_value_kw` | × 130 |
| `debt_to_income_ratio` (구간 코드) | `dsr` (실수) | `<20%`→0.15, `20-<30%`→0.25 … 중앙값 매핑 |
| `combined_loan_to_value_ratio` | `ltv` | 그대로 (이미 0~1 또는 %) |
| `loan_term` | `requested_period_mo` | 월 단위, 30년 모기지 → 360 |
| `interest_rate` (%) | `interest_rate_bps` | × 100 |
| `applicant_age` (구간) | `age_band` 또는 `age` | 구간 중앙값 (e.g. `25-34`→30) |
| `derived_sex` (Male/Female) | `sex` | 남자/여자 |
| `action_taken` (1~8) | `decision_label` | §3 매핑 |

### 1.2 의미 매핑 (변환 필요)

| HMDA | 한국 | 변환 룰 |
|------|------|--------|
| `loan_type` (Conventional/FHA/VA/RHS) | `product_code` | Conventional 만 사용 → `MORT_001`. FHA/VA/RHS 는 미국 정부보증이라 한국 대응 없음, 행 제외 |
| `loan_purpose` (1/2/31/32/4/5) | `purpose_cd` | 1(Home purchase)→`HOUSING`, 2(Improvement)→`HOUSING`(주택개량), 31(Refi)→`DEBT_CONS`, 32(Cash-out)→`DEBT_CONS`, 4(Other)→`OTHER`, 5(N/A)→제외 |
| `state_code` + `county_code` (FIPS) | `province` + `district` | 직접 매핑 X. **KOSIS 인구 분포로 한국 17 광역시 × 250 시군구에 재샘플링** (FIPS 행 인덱스 무시) |
| `occupancy_type` (1/2/3) | `occupancy_cd` (신규) | 본인거주/2주거/투자. LTV 정책 차등에 사용. 한국 도메인에 추가 |
| `applicant_credit_score_type` (FICO/Equifax/…) | (사용 안 함) | 점수 자체는 HMDA 비공개. 점수 type 만 알려줘 입력 가치 낮음 |
| `lien_status` (1순위/2순위) | `lien_priority_cd` (신규) | 한국도 동일 개념 |

### 1.3 HMDA 만 있고 한국 도메인엔 없음 (드롭)

| HMDA | 드롭 사유 |
|------|----------|
| `derived_race`, `derived_ethnicity`, `co_applicant_race/ethnicity` | 한국 race 변수 부재. 개인정보보호법 충돌. 학습 입력 제외 |
| `hoepa_status`, `rate_spread` | 미국 고비용 모기지(HOEPA) 규제. 한국 대응 없음 |
| `reverse_mortgage` | 한국 도입 미미 |
| `construction_method` (Manufactured home) | 한국에 사실상 없음 |
| `census_tract` | 한국 동·읍·면 수준 단위 없음. `district` 로 흡수 |
| `total_points_and_fees`, `discount_points`, `lender_credits`, `origination_charges` | 미국식 모기지 수수료 구조 |
| `balloon_payment`, `interest_only_payment`, `negative_amortization` | 한국 모기지엔 거의 없음 |
| `multifamily_affordable_units` | 다세대 주택 정책 (미국 HUD) |
| `applicant_age_above_62` | age 로 충분 |

### 1.4 한국엔 있는데 HMDA 엔 없음 (합성 또는 KOSIS enrich)

| 한국 필수 컬럼 | 채우는 방법 |
|---------------|------------|
| `credit_score_proxy` (NICE/KCB 300~950) | DTI·소득·연체확률에서 합성 함수. action_taken 와 약한 상관 부여 |
| `delinquency_history_24m` | DTI > 임계인 행 일부에 베르누이 부여 (확률 ∝ DTI·소득역수) |
| `monthly_cashflow_mean_kw`, `monthly_cashflow_std_kw` | `income / 12` + `std = mean × Uniform(0.05, 0.35)` |
| `collateral_debt_kw` | `property_value × CLTV` |
| `credit_debt_kw` | `income × Uniform(0.2, 1.5)` (한국 신용대출 분포 근사) |
| `total_asset_kw`, `total_debt_kw` | `property_value + income × 5`, `collateral + credit` |
| `occupation`, `education_level`, `marital_status`, `family_type`, `housing_type`, `bachelors_field`, `military_status` | **KOSIS 가계금융복지조사 분포에서 income_quintile 별 샘플링** (기존 페르소나 sampler 의 역할 일부 재활용) |
| `applicant_segment` (regular/young/senior/precarious/self_employed) | age·occupation·income 으로 도출 (기존 `_applicant_segment` 함수 유지) |
| `purpose_text` (한국어 자유 텍스트) | `purpose_cd` 기반 한국어 템플릿 (기존 `application.py` 의 `_purpose_text` 재활용) |
| `purpose_red_flag` | 베르누이 10% (기존과 동일) |
| `income_quintile` | KOSIS 분위 경계로 `annual_income_kw` 매핑 |

→ **KOSIS 로더는 살아남음**. 한국 인구학 분포 sampling 에 필수.

---

## 2. 상품 매핑

HMDA 의 `loan_type × loan_purpose` 조합 중 **한국 도메인에 매핑 가능한 행만 사용**:

| HMDA 조합 | 한국 상품 | 학습 사용 |
|----------|----------|----------|
| Conventional + Home purchase | `MORT_001` (주담대 신규) | ✓ |
| Conventional + Refinancing | 대환 (신상품 추가 필요) | ✓ |
| Conventional + Home improvement | `MORT_001` 변형 또는 `OTHER` | ✓ |
| Conventional + Cash-out refinancing | `DEBT_CONS` 또는 대환 | ✓ |
| FHA / VA / RHS / FSA | (미국 정부보증, 한국 대응 X) | ✗ 제외 |
| Construction-Manufactured | (한국 대응 X) | ✗ 제외 |

→ **결과적으로 사용 가능한 행: HMDA 전체의 약 60~70%** (Conventional 비율 기준).

한국 자체 상품(`CRED_001` 신용대출 / `BIZ_001` 사업자 / `CARD_001` 카드론 / `EMER_001` 긴급생계 / `HOME_001` 전세) 은 HMDA 학습 외. 후속 단계에서 별도 처리.

---

## 3. 라벨 매핑

### 3.1 이진 PD 라벨 (`default_within_12m`) — **불가**

HMDA 는 **신청·심사 단계만** 다룸. 대출 실행 후 12개월 디폴트 추적 데이터 없음.
→ PD 모델 학습 데이터로는 HMDA 부적합. PD 라벨은 후속 단계에서 별도 (Home Credit 등) 또는 합성.

### 3.2 심사 결정 라벨 (`decision_label`) — HMDA 의 강점

| HMDA action_taken | 한국 라벨 | 학습 사용 |
|------------------|----------|----------|
| 1 (Loan originated) | `APPROVE` | ✓ |
| 2 (Approved not accepted) | `APPROVE` | ✓ |
| 3 (Denied) | `REJECT` | ✓ |
| 4 (Withdrawn) | — | ✗ 제외 (고객 사정, 모델 학습 부적합) |
| 5 (File closed for incompleteness) | — | ✗ 제외 |
| 6 (Purchased loan) | — | ✗ 제외 (다른 기관 매입) |
| 7 (Preapproval denied) | `REJECT` | △ 별도 학습 가능 |
| 8 (Preapproval not accepted) | — | ✗ 제외 |

→ **이진 라벨**: `APPROVE` (1+2) / `REJECT` (3). 약 80% 표본 사용 가능.

**거절 사유 부산물 (denial_reason_1~4)** 는 SHAP 검증·LLM 거절문구 학습에 활용:
- DTI / Employment / Credit history / Collateral / Insufficient cash / Unverifiable info / Incomplete / MI denied / Other

### 3.3 CONDITIONAL 라벨

HMDA 에는 CONDITIONAL 개념 없음. 두 옵션:
- **A**: 이진(APPROVE/REJECT) 만 학습. 3-class 모델 폐기
- **B**: 모델 PD 출력의 회색 구간(0.3~0.7) 을 RuleEngine 이 CONDITIONAL 트랙으로 분기

**추천: B**. 모델은 이진 PD 만 학습, 트랙 분기는 RuleEngine 책임 (banking-review-llm §4·7 정합).

---

## 4. 정책 매트릭스 한국화

| 항목 | 미국 (CFPB QM rule) | 한국 (자행 정책서) |
|------|---------------------|-------------------|
| DTI 임계 | 43% | DSR 40% |
| CLTV 임계 | 80% (LMI 없으면) | LTV 70% (주담대), 80%(생애최초) |
| 최소 신용점수 | FICO 620 | NICE 600 |
| 최대 대출/소득 비 | DTI 통과 필요 | 연소득 × 3 한도 |
| 연체이력 | DTI 흡수 | 24m 무연체 |

→ HMDA 가 가진 DTI/CLTV 값을 **한국 임계와 비교**. 미국 임계는 무시.
→ `synthesize/oracle.py` 의 룰 그대로 보존 (라벨 시뮬레이션은 폐기되지만 RuleEngine 입력으로 재사용).

---

## 5. 인구학·공정성 변수

### 5.1 폐기 (학습 입력에서 제외)

`derived_race`, `derived_ethnicity`, `co_applicant_*` — 한국 도메인 부적합.

### 5.2 유지

- `sex` — 4/5ths rule 분석
- `age` / `age_band` — 청년/장년/노년 공정성
- `applicant_segment` (한국 합성) — regular/young/senior/precarious/self_employed

### 5.3 4/5ths Rule 적용

미국 ECOA 의 80% 룰을 한국 도메인 그룹에 적용:
- `min(group_approve_rate) / max(group_approve_rate) ≥ 0.80`
- 그룹: sex × age_band × applicant_segment

---

## 6. 변환 파이프라인 (Layer 재정의)

```
[HMDA modified LAR CSV]  ← CFPB FFIEC 매년 발행
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 1. HMDA Loader & Filter                            │
│  - loan_type=Conventional 만                              │
│  - loan_purpose ∈ {Home purchase, Refi, Improvement,     │
│    Cash-out}                                              │
│  - action_taken ∈ {1, 2, 3} (학습 가능 라벨)             │
│  - 미국 특화 컬럼 드롭                                     │
└─────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 2. 직역·환산                                        │
│  - income($k) → annual_income_kw (만원)                  │
│  - DTI band → dsr 실수 중앙값                             │
│  - loan_purpose → purpose_cd                              │
│  - action_taken → decision_label (APPROVE/REJECT)        │
└─────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 3. KOSIS Enrichment                                │
│  - income_quintile 결정 → KOSIS 분위 인구학 분포에서      │
│    occupation/education/marital/family/housing 샘플링    │
│  - province/district 를 KOSIS 인구 비율로 재할당          │
└─────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 4. 한국 합성 보강                                   │
│  - credit_score_proxy (NICE 300~950) — 합성              │
│  - delinquency_history_24m — 합성                        │
│  - monthly_cashflow_mean/std — income/12 기반            │
│  - collateral/credit_debt_kw — property_value, income 기반│
│  - purpose_text (한국어 템플릿) — 기존 application.py 재사용│
│  - applicant_segment — age/occupation/income 도출         │
└─────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 5. Split & Persist                                 │
│  - train / valid / holdout (71/14.5/14.5)                │
│  - parquet 저장: data/synthetic/applications/             │
│    hmda_kr_<version>.parquet                              │
│  - DB 적재: ai_db.synthetic_application                  │
└─────────────────────────────────────────────────────────┘
        ↓
[기존 training/ 파이프라인 그대로 사용]
```

---

## 7. 영향받는 코드·데이터·문서

### 7.1 폐기 / 비활성

- `synthetic-data-generator/src/synthesize/financial_profile.py` — KOSIS 분위 합성 재무 (일부는 Layer 4 로 흡수)
- `synthetic-data-generator/src/synthesize/oracle.py` — 라벨 시뮬레이션 (룰만 RuleEngine 으로 이전, 라벨 생성 부분 폐기)
- `data/synthetic/applications/synthetic_application_v1.parquet` — 기존 합성 학습 데이터
- `data/models/auto_review_v1/` — 기존 XGBoost 3-class 모델

**보존(자동심사 학습엔 미사용, 후속 자산관리 시나리오용)**:
- `synthetic-data-generator/src/synthesize/persona_sampler.py` — Nemotron 페르소나 sampler
- `data/synthetic/personas/` — Nemotron slim parquet
  → 향후 자산관리·세그먼트 시뮬레이션 등 별도 도메인에서 재사용

### 7.2 보존 (재활용)

- `synthetic-data-generator/src/loaders/ecos.py`, `kosis.py` — KOSIS Enrichment 에 필수
- `synthetic-data-generator/src/synthesize/application.py` — `purpose_text` 한국어 템플릿 부분만
- `synthetic-data-generator/src/training/`, `src/evaluation/` — 학습·평가 골격 (feature schema 만 갈아끼움)
- `inference-server/`, `agents/auto-loan-review/` — 게이트웨이·서빙 (DTO 필드명만 변경)

### 7.3 신규

- `synthetic-data-generator/src/loaders/hmda.py` — CFPB Data Browser API 또는 modified LAR 다운로드
- `synthetic-data-generator/src/synthesize/hmda_to_kr.py` — Layer 1~4 변환 모듈
- `synthetic-data-generator/scripts/build_hmda_kr.py` — CLI
- `docs/ai/DATASETS.md` 갱신 — Nemotron 항목 deprecate, HMDA 항목 추가

### 7.4 갱신

- `docs/plan/banking-review-llm.md` — Phase 1.1 학습 데이터 부분, ML 학습 라벨, "합성 14만" → "HMDA + KOSIS enrich"
- `docs/ai/DATASETS.md` — HMDA 추가, Nemotron deprecate

---

## 8. 출력 데이터 스키마 (1차 안)

기존 `synthetic_application` 테이블 스키마와 거의 동일 유지 (training 코드 호환):

```sql
-- Layer 1 (HMDA 직역 + KOSIS enrich)
uuid                            text PRIMARY KEY     -- HMDA loan_application_id
sex                             text                 -- HMDA derived_sex
age                             smallint             -- HMDA applicant_age 구간 중앙값
marital_status                  text                 -- KOSIS 합성
military_status                 text                 -- KOSIS 합성 (남자만)
family_type                     text                 -- KOSIS 합성
housing_type                    text                 -- KOSIS 합성
education_level                 text                 -- KOSIS 합성
bachelors_field                 text                 -- KOSIS 합성
occupation                      text                 -- KOSIS 합성
district                        text                 -- KOSIS 분포 재할당
province                        text                 -- KOSIS 분포 재할당
applicant_segment               text                 -- age/occupation/income 도출

-- Layer 2 (HMDA 직역 + 한국 합성)
income_quintile                 smallint
annual_income_kw                bigint               -- HMDA income × 130
total_asset_kw                  bigint               -- 합성
total_debt_kw                   bigint               -- 합성
collateral_debt_kw              bigint               -- HMDA property_value × CLTV
credit_debt_kw                  bigint               -- 합성
dsr                             double precision     -- HMDA DTI band 중앙값
ltv                             double precision     -- HMDA CLTV
monthly_cashflow_mean_kw        bigint               -- income/12
monthly_cashflow_std_kw         bigint               -- 합성
delinquency_history_24m         smallint             -- 합성
credit_score_proxy              integer              -- 합성 (DTI/income 기반)

-- Layer 3 (HMDA 신청 정보)
product_code                    text                 -- Conventional → MORT_001 등
requested_amount_kw             bigint               -- HMDA loan_amount × 130
requested_period_mo             smallint             -- HMDA loan_term
purpose_cd                      text                 -- HMDA loan_purpose 매핑
purpose_text                    text                 -- 한국어 템플릿
purpose_red_flag                boolean              -- 베르누이 10%

-- Layer 4 (라벨 — 진짜 HMDA 결정)
decision_label                  text                 -- APPROVE/REJECT (action_taken 1/2 vs 3)
denial_reason_primary           text                 -- HMDA denial_reason_1 (REJECT만)

-- meta
split                           text                 -- train/valid/holdout
hmda_year                       smallint             -- HMDA 출처 연도
generated_at                    timestamptz
```

→ training/features.py 의 LABEL_CLASSES 변경: `["APPROVE", "REJECT"]` (이진).

---

## 9. 진행 단계

| 단계 | 작업 | 산출물 |
|------|------|--------|
| H1 | HMDA modified LAR 다운로드 (1년치 1주, ~10MB 샘플) | `data/external/credit/hmda/lar_2024_w1.csv` |
| H2 | `loaders/hmda.py` — 컬럼 필터 + 환산 | Layer 1~2 모듈 |
| H3 | `synthesize/hmda_to_kr.py` — KOSIS enrich + 한국 합성 보강 | Layer 3~4 모듈 |
| H4 | `scripts/build_hmda_kr.py` — n=10000 첫 빌드 | `synthetic_application_hmda_v1.parquet` |
| H5 | training/features 이진 라벨로 변경 + 재학습 | `auto_review_hmda_v1/model.json` |
| H6 | evaluation 재실행 (sex/age/segment 4/5ths) | 평가 리포트 |
| H7 | banking-review-llm.md / DATASETS.md 동기화 | 문서 |
| H8 | 폐기 코드 정리 (persona_sampler 등) | 코드 슬림화 |

→ H1~H4 로 데이터 새로 만들고, H5~H6 로 모델 갱신. 도메인 그림 자체는 한국 IB 유지.

---

## 10. 한계·리스크

- **credit_score_proxy 합성** — 한국 NICE/KCB 분포와 일치 보장 안 됨. feature importance 에서 신용점수 의존도가 진짜보다 약하거나 강할 수 있음.
- **인구학 변수의 약한 상관** — occupation/education 이 income 에서 KOSIS 분포 sampling 이라 한 페르소나 안의 일관성은 잃음 (예: "사무 보조원 + 박사 학위" 가능).
- **상품 한정** — 주담대·대환·주택개량 중심. 신용대출/카드론/사업자/긴급생계 학습은 별도 트랙 필요.
- **법·문화 차이** — 미국 모기지 신청 절차(preapproval, escrow, PMI) 와 한국이 다름. 일부 케이스는 학습 X.
- **race 변수 폐기** — 미국 audit baseline 의 일부 인사이트는 잃음. 한국 4/5ths 에 영향 없음.
- **denial_reason 라벨링** — HMDA `denial_reason_*` 코드 9종을 LLM 거절문구 카테고리로 매핑하는 작업 필요.

---

## 11. 부록 — HMDA 다운로드 옵션

| 소스 | URL 패턴 | 규모 |
|------|---------|------|
| CFPB Modified LAR | `https://ffiec.cfpb.gov/data-publication/modified-lar/<year>` | 연 ~수 GB |
| CFPB Data Browser API | `https://ffiec.cfpb.gov/v2/data-browser-api/view/csv?...` | 필터링 가능 |
| CFPB Snapshot National | `https://ffiec.cfpb.gov/v2/data-browser-api/view/nationwide/csv?years=<y>` | 연 통합 |
| Urban Institute HLD | Longitudinal Dataset (학술) | 별도 신청 |

→ MVP 는 Data Browser API + 필터(`states=CA&actions_taken=1,2,3&loan_types=1`) 로 작은 샘플부터.
