# PD 라벨 데이터 확보 — 큰 그림

<!-- plan-status -->
> **상태: 📐 설계만 — 코드 없음** — 문서 스스로 `design draft`. 12개월 디폴트 추적 데이터가 없다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> Last updated: 2026-05-22
> Status: design draft (HMDA 한계 — `action_taken` 만 보유, 12개월 디폴트 추적 X — 해소 위한 1차 안)
> 선행 문서: `docs/plan/banking-review-llm.md` §4·§10, `docs/plan/hmda-localization.md` §3.1·§10

본 문서는 진짜 PD (Probability of Default, 12개월 내 디폴트 확률) 라벨을 확보해 자동심사
시스템의 **변별력 신호**를 "심사 결정 분류기"에서 "실제 디폴트 분류기"로 격상하기 위한
데이터·모델·운영 계획. HMDA 변환과 같은 4 Layer (Loader → 직역 → KOSIS enrich → 한국
합성 → split) 파이프라인을 재사용해 한국 도메인에 매핑한다.

---

## 0. 전제·원칙

1. **PD = funded loans 의 12개월 디폴트 라벨**. 신청·심사 단계만 있는 HMDA 로는 산출 불가.
2. **현 `hmda_tx_v1` 폐기 X**. "심사 결정 분류기" 로 별도 보존 — 트랙 분기의 보조 신호로
   결합 사용 가능 (banking-review-llm §4 의 SHAP·explainability 자산 유지).
3. **한국 정책 매트릭스·RuleEngine 그대로**. PD 모델은 결정권 없음 — Rule Engine 이 PD 값을
   매트릭스 임계 τ 와 비교해 트랙 1/2/3 분기 (plan §7 책임 분담 유지).
4. **feature schema 사전 통일**. 다른 데이터셋·자행 실데이터로 swap 시 training/serving
   코드 수정 최소.
5. **두 트랙 병행**:
   - **단기 (P1~P8)**: 공개 funded-loans 데이터셋 도메인 매핑으로 cold-start PD 모델
   - **장기**: 자행 실데이터 확보 후 transfer learning, shadow 모드 4주 후 cutover
6. **race / co_applicant 같은 부적합 변수 폐기** (hmda-localization §5 와 동일 원칙).

---

## 1. 데이터셋 후보 비교

| 데이터셋 | 출처 | 규모 | 라벨 | 한국 매핑 적합도 | 라이선스 |
|---------|------|------|------|----------------|---------|
| **Home Credit Default Risk** | Kaggle (Home Credit Group, 동유럽) | application_train 307K + 6 보조 테이블 | `TARGET` (1=default, 8.07%) | 중-상 (체코·러시아·중앙아시아 신용시장. 한국 CRED_001 / CARD_001 와 분포 결 비슷) | Kaggle competition, 비상업·연구 가능 |
| **Lending Club** | LendingClub 공개 (archived, Kaggle 미러) | ~2.2M loans (2007~2018) | `loan_status` → fully_paid vs default/charged_off (~14%) | 중-상 (미국 P2P 무담보 신용대출, 한국 CRED_001 가까움) | 공개 |
| **Freddie Mac Single-Family** | freddiemac.com | 50M loans 1999~ origination + monthly performance | delinquency status sequence (D30/D60/D90/D180+) | 상 (주담대 long performance — HMDA 와 결합 가능) | 학술/비상업, 약관 동의 |
| **Fannie Mae Loan Performance** | fanniemae.com | 유사 규모 | 유사 | 상 | 학술 등록 |
| German Credit | UCI ML | 1000 rows × 20 features | 양호/불량 | 낮 (너무 작음) | 공개 |
| LendingClub × Polaris | Kaggle 미러 | 다양 | 동일 | 동일 | 공개 |

### 1.1 단계별 우선순위

| 단계 | 데이터셋 | 이유 |
|------|---------|------|
| **단기 PoC (P1~P8)** | Home Credit Default Risk | 등록 즉시 다운로드, 라이선스 명확, application + bureau + previous_application 만으로도 50+ feature, TARGET 비율 명확, Kaggle 노트북 풍부해 sanity check 용이 |
| **확장 1** | Lending Club | 한국 신용대출(CRED_001) 매핑 자연스러움. grade A~G → 자행 신용등급 매핑 직관적 |
| **확장 2** | Freddie Mac Single-Family | 주담대 long-tenor performance. HMDA TX 와 동일 시장이라 결합 시 신청→funded→성과 chain 완성 가능 |
| **장기 cutover** | 자행 실데이터 | transfer learning base. shadow 4주 후 cutover |

→ 본 문서 본문은 **Home Credit 단기 PoC** 에 집중. Lending Club / GSE 는 §9·10 에서 후속
   계획만 명시.

---

## 2. Home Credit Default Risk — 상세 매핑

### 2.1 데이터 구조

| 테이블 | 행 수 | 키 | 용도 |
|--------|------|----|------|
| application_train | 307,511 | SK_ID_CURR | 신청 시점 스냅샷 + TARGET (12개월 디폴트) |
| bureau | 1.7M | SK_ID_CURR, SK_ID_BUREAU | 타기관 CB 정보 (대출 잔액·연체·종결) |
| bureau_balance | 27M | SK_ID_BUREAU, MONTHS_BALANCE | bureau 월별 잔액 status |
| previous_application | 1.67M | SK_ID_CURR, SK_ID_PREV | Home Credit 자체 과거 신청 |
| installments_payments | 13.6M | SK_ID_PREV | 과거 약정 vs 실제 납입 비교 |
| credit_card_balance | 3.84M | SK_ID_PREV | 신용카드 월별 잔액 |
| POS_CASH_balance | 10M | SK_ID_PREV | POS / 현금대출 월별 status |

**MVP 학습 범위**: application_train + bureau (집계만) — 50~80 feature 산출. 보조 테이블
풀 활용은 P9+ 별도 phase.

### 2.2 컬럼 매핑 4분류 (HMDA 4분류 동일 구조)

#### 2.2.1 직역 가능 (의미 동일, 환산만)

| Home Credit | 한국 컬럼 | 변환 |
|-------------|----------|------|
| `SK_ID_CURR` | `uuid` (text) | 그대로 |
| `AMT_INCOME_TOTAL` (현지통화) | `annual_income_kw` | 분위 매핑 (환율 직접 X — 동유럽 PPP 차이 큼) |
| `AMT_CREDIT` | `requested_amount_kw` | 분위 매핑 |
| `AMT_ANNUITY` | `monthly_repayment_kw` | × 1/12 보정 후 분위 |
| `AMT_GOODS_PRICE` | `collateral_value_kw` | 분위 매핑 (주담대 한정) |
| `DAYS_BIRTH` (음수, 일) | `age` | `floor(-DAYS_BIRTH / 365.25)` |
| `DAYS_EMPLOYED` (음수, 일) | `employment_years` | `floor(-DAYS_EMPLOYED / 365.25)`. **365243 sentinel = 무직** → NULL |
| `CODE_GENDER` (M/F/XNA) | `sex` | 남자/여자. XNA → 제외 |
| `CNT_CHILDREN` | `n_children` | 그대로 |
| `NAME_CONTRACT_TYPE` (Cash/Revolving) | `product_code` | Cash → `CRED_001`, Revolving → `CARD_001` |
| `TARGET` (0/1) | `default_within_12m` | 그대로 (라벨) |

#### 2.2.2 의미 매핑 (변환 룰 필요)

| Home Credit | 한국 | 변환 룰 |
|-------------|------|--------|
| `NAME_FAMILY_STATUS` | `marital_status` | Married→기혼, Single/not married→미혼, Civil marriage→사실혼, Widow→사별, Separated→별거 |
| `NAME_EDUCATION_TYPE` | `education_level` | Higher education→학사이상, Secondary→고졸, Incomplete higher→대학중퇴, Lower secondary→중졸이하 |
| `NAME_HOUSING_TYPE` | `housing_type` | House/apartment→자가, With parents→부모동거, Rented→월세, Municipal→공공임대, Office→사택, Co-op→공공자가 |
| `OCCUPATION_TYPE` (18종) | `occupation` | KOSIS 한국표준직업분류 8 대분류 재매핑 (Laborers→단순노무, IT staff→사무전문, Managers→관리자 ...) |
| `ORGANIZATION_TYPE` (58종) | `industry_cd` | 한국표준산업분류 17 대분류 재매핑 |
| `EXT_SOURCE_1/2/3` (0~1 익명점수) | `credit_score_proxy` | 0.5 × (EXT_2 + EXT_3) 가중평균 → 300~950 매핑 (`raw → 300 + (1-raw)·650`). EXT_1 결측 60% 라 제외 |
| `REGION_RATING_CLIENT` (1~3) | `region_risk_band` (신규) | 그대로 (1=저위험 ~ 3=고위험) |

#### 2.2.3 Home Credit 만 있고 한국 도메인엔 없음 (드롭)

| Home Credit | 드롭 사유 |
|-------------|----------|
| `FLAG_OWN_CAR`, `FLAG_OWN_REALTY` | 한국 자행에선 자산 테이블 별도 — feature 로 안 씀 |
| `OWN_CAR_AGE` | 동일 |
| `REGION_POPULATION_RELATIVE` | KOSIS 인구밀도로 갈음 |
| `LIVE_REGION_NOT_WORK_REGION` 등 거주-근무 지역 일치 플래그 4종 | 한국 도메인 활용도 낮음 |
| `WEEKDAY_APPR_PROCESS_START`, `HOUR_APPR_PROCESS_START` | 신청 시각 — bias 위험, 제외 |
| `OBS_30_CNT_SOCIAL_CIRCLE` 등 사회망 변수 8종 | Home Credit 자체 social graph — 한국 대응 없음 |
| `FLAG_DOCUMENT_2~21` (20종) | 익명 서류 플래그 — 한국 서류 구조와 매칭 X |

#### 2.2.4 한국엔 있는데 Home Credit 엔 없음 (합성 또는 KOSIS enrich)

| 한국 필수 컬럼 | 채우는 방법 |
|---------------|------------|
| `nice_score` / `kcb_score` (300~950) | EXT_SOURCE_2/3 가중평균 → 300~950 선형 매핑 |
| `dsr` (DSR) | `(AMT_ANNUITY × 12) / AMT_INCOME_TOTAL` 직산 |
| `ltv` (LTV) | `AMT_CREDIT / AMT_GOODS_PRICE` (주담대 한정 — Cash/Revolving 은 NULL) |
| `delinquency_history_24m` | bureau 테이블 집계 — `STATUS IN ('1','2','3','4','5')` 최근 24개월 count |
| `monthly_cashflow_mean_kw`, `monthly_cashflow_std_kw` | `AMT_INCOME_TOTAL / 12` + std = mean × Uniform(0.05, 0.35) |
| `total_debt_kw` | bureau `AMT_CREDIT_SUM` 합 |
| `total_asset_kw` | property_value + income × 5 (HMDA 와 동일 합성식) |
| `province`, `district` | KOSIS 인구 분포로 한국 17 광역 × 250 시군구 재샘플링 (Home Credit 의 동유럽 지역 코드 폐기) |
| `purpose_cd`, `purpose_text` | `NAME_CONTRACT_TYPE` × `CNT_CHILDREN` × age 로 한국어 템플릿 (생활안정·자녀교육·전세자금 등) |
| `applicant_segment` | age × occupation × income 도출 (HMDA 와 동일 함수 재사용) |

→ **KOSIS 로더·`_purpose_text` 템플릿·segment 도출 함수 모두 재활용**. hmda_to_kr.py 의
   utility 분리 → home_credit_to_kr.py 에서 import 가능하도록 리팩터링 (Layer 3·4 공통 모듈).

---

## 3. 라벨 매핑

### 3.1 PD 라벨 (`default_within_12m`)

| Home Credit `TARGET` | 한국 라벨 | 비고 |
|---------------------|----------|------|
| 0 | `default_within_12m = false` | 12개월 내 디폴트 없음 |
| 1 | `default_within_12m = true` | client had late payment > X days on at least one of the first Y installments (Home Credit 정의) |

**양성률 8.07%** — 한국 자행 실제 디폴트율(0.7~3% 추정)보다 높음 → **확률 캘리브레이션 필수**:
- Platt scaling / isotonic regression 으로 운영 시 보정
- 또는 학습 시 class_weight + undersampling 으로 base rate 조정 후 캘리브레이션

### 3.2 심사 결정 라벨 (HMDA `decision_label`) 와의 관계

- 별도 모델로 분리 (옵션 A — §5 참조)
- inference-server 에서 두 score 모두 산출, RuleEngine 이 결합 로직 적용

---

## 4. 변환 파이프라인 (5 Layer, HMDA 와 동일 골격)

```
[Home Credit application_train.csv + bureau.csv]
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 1. Loader & Filter                                 │
│  - CODE_GENDER ∈ {M, F} 만 (XNA 제외)                    │
│  - DAYS_EMPLOYED 365243 sentinel → NULL                  │
│  - NAME_CONTRACT_TYPE ∈ {Cash loans, Revolving loans}    │
│  - bureau 집계 (per SK_ID_CURR) → 30+ feature            │
└─────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 2. 직역·환산                                        │
│  - AMT_INCOME_TOTAL 분위 → annual_income_kw 분위 매핑     │
│  - DAYS_BIRTH → age, DAYS_EMPLOYED → employment_years    │
│  - NAME_* / OCCUPATION_TYPE / ORGANIZATION_TYPE → 한국 직역│
│  - EXT_SOURCE_2/3 → nice_score                           │
│  - TARGET → default_within_12m                           │
└─────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 3. KOSIS Enrichment                                │
│  - income_quintile → KOSIS 분위 인구학 분포에서           │
│    occupation 재샘플링 (Home Credit 18종 매핑 보강)       │
│  - province/district 를 KOSIS 인구 비율로 재할당          │
│  - bachelors_field, military_status, family_type 합성    │
│  - (HMDA 와 동일 모듈 재사용)                              │
└─────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 4. 한국 합성 보강                                   │
│  - nice_score (EXT_SOURCE 기반)                          │
│  - dsr (AMT_ANNUITY × 12 / AMT_INCOME)                   │
│  - ltv (Cash/Revolving 은 NULL, 주담대만)                 │
│  - delinquency_history_24m (bureau 집계)                 │
│  - monthly_cashflow_mean/std                             │
│  - total_debt_kw, total_asset_kw                         │
│  - purpose_text (한국어 템플릿, NAME_CONTRACT_TYPE × age) │
│  - applicant_segment (age × occupation × income)         │
└─────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────┐
│ Layer 5. Split & Persist                                 │
│  - train / valid / holdout (71/14.5/14.5, stratified by  │
│    TARGET — 양성률 보존)                                  │
│  - parquet: data/synthetic/applications/                  │
│    homecredit_kr_<version>.parquet                        │
│  - DB 적재: ai_db.pd_training_application                │
└─────────────────────────────────────────────────────────┘
        ↓
[training/ 파이프라인 — features.py LABEL=default_within_12m 으로 swap]
```

---

## 5. 모델 구조 — 듀얼 모델 (decision + PD)

### 5.1 옵션 비교

| 옵션 | 설명 | 장점 | 단점 |
|------|------|------|------|
| **A. 별도 모델 2개** | hmda_tx_v1 (decision) + homecredit_kr_v1 (PD) 독립 학습·서빙 | 운영·MRM·재학습 단순. 라벨 출처 완전 분리 reflected. 한쪽 폐기·교체 자유 | 두 score 결합 로직을 RuleEngine 에서 정의 필요 |
| **B. Multi-task learning** | 공유 백본 (LightGBM 어려움 → DNN) + 2 헤드 | feature representation 공유 시 transfer 효과 | 한국 ML 운영 부담 ↑, LightGBM 자산 폐기, MRM 복잡 |

**추천: A**. 이유:
- 라벨 출처가 완전히 다른 데이터셋이라 multi-task 가정(공유 분포) 위배
- LightGBM + ONNX 자산 그대로 활용
- 한쪽만 재학습·shadow·롤백 가능

### 5.2 모델 명세 (homecredit_kr_v1)

- 알고리즘: LightGBM 이진 분류 (hmda_tx_v1 과 동일)
- 출력: `pd_score` (0~1, 12개월 디폴트 확률)
- 부산물: SHAP top-k
- feature: ~50개 (Home Credit application 30 + bureau 집계 20)
- 평가 지표 (banking-review-llm §4 와 동일):
  - AUC ≥ 0.75 (PD 모델은 decision 보다 일반적으로 낮음 — funded 만 다루고 cherry-pick 후 분포)
  - KS ≥ 0.35
  - Brier 낮을수록 (캘리브레이션 후)
  - lift decile-1 단조 감소
  - 4/5ths ratio ≥ 0.80 (sex × age_band × segment)
- mitigation: Kamiran-Calders sample weighting + segment 별 threshold (hmda_tx_v1 와 동일)

### 5.3 두 score 결합 (RuleEngine)

```
Hard fail (정책 룰 위반)                  → Track 2
decision_score ≥ 0.95 ∧ pd_score ≤ τ×0.3  → Track 1 (강한 자동승인)
pd_score ≤ τ (매트릭스 임계)              → Track 3 (사람 심사)
pd_score > τ ∨ decision_score ≤ 0.2       → Track 2 (자동반려)
```

→ τ·결합 비율 모두 정책 YAML 외부화. RuleEngine 의 PolicyMatrix 에 `pd_threshold`,
   `decision_strong_threshold` 추가.

---

## 6. 출력 스키마 (1차 안)

기존 `synthetic_application` 스키마와 호환 유지 + PD 라벨·관련 컬럼 추가:

```sql
-- 공통 (HMDA 스키마와 통일)
uuid                            text PRIMARY KEY
sex                             text
age                             smallint
marital_status                  text
family_type                     text
housing_type                    text
education_level                 text
occupation                      text
industry_cd                     text                 -- 신규 (ORGANIZATION_TYPE 매핑)
district                        text
province                        text
applicant_segment               text

income_quintile                 smallint
annual_income_kw                bigint
total_asset_kw                  bigint
total_debt_kw                   bigint
collateral_debt_kw              bigint
credit_debt_kw                  bigint
dsr                             double precision
ltv                             double precision     -- 주담대만, 그 외 NULL
monthly_cashflow_mean_kw        bigint
monthly_cashflow_std_kw         bigint
delinquency_history_24m         smallint
credit_score_proxy              integer

product_code                    text                 -- CRED_001 / CARD_001 / MORT_001
requested_amount_kw             bigint
requested_period_mo             smallint
purpose_cd                      text
purpose_text                    text
purpose_red_flag                boolean

-- PD 학습 전용 신규 컬럼
region_risk_band                smallint             -- REGION_RATING_CLIENT 1~3
n_children                      smallint
employment_years                smallint             -- DAYS_EMPLOYED 환산 (sentinel NULL)
bureau_active_loans_n           smallint             -- bureau 집계
bureau_overdue_amt_kw           bigint               -- bureau 집계
bureau_max_status_24m           smallint             -- bureau_balance 집계

-- 라벨
default_within_12m              boolean              -- TARGET
target_positive_rate            double precision     -- meta (split 별 양성률 기록)

-- meta
split                           text                 -- train/valid/holdout
source_dataset                  text                 -- "home_credit_v1"
generated_at                    timestamptz
```

→ `training/features.py` 의 `LABEL_CLASSES` / `LABEL_COL` 을 `default_within_12m` (이진) 으로
   변경. HMDA 학습용 분기는 `--dataset hmda` / `--dataset homecredit` 옵션으로 분리.

---

## 7. 영향받는 코드·데이터·문서

### 7.1 신규

- `synthetic-data-generator/src/loaders/home_credit.py` — Kaggle CSV 로더 + 컬럼 필터 + bureau 집계
- `synthetic-data-generator/src/synthesize/home_credit_to_kr.py` — Layer 2~4 변환
- `synthetic-data-generator/scripts/build_home_credit_kr.py` — CLI
- `synthetic-data-generator/src/synthesize/_kr_enrich_common.py` — Layer 3·4 공통 (hmda_to_kr 와 공유) **리팩터링**
- `data/external/credit/home_credit/` — application_train.csv, bureau.csv
- `data/synthetic/applications/homecredit_kr_v1.parquet`
- `data/models/auto_review_pd_homecredit_v1/` — LightGBM + ONNX + evaluation.md
- `inference-server/` PD 모델 endpoint 추가 — `POST /predict/pd` (decision 과 분리)

### 7.2 갱신

- `synthetic-data-generator/src/synthesize/hmda_to_kr.py` — `_kr_enrich_common` 으로 공통 로직 이관
- `synthetic-data-generator/src/training/features.py` — `--dataset` 옵션 + label/feature 분기
- `agents/auto-loan-review/src/main/resources/application.yml` — `ai.inference.pd-model-url`, `ai.rule-engine.pd-threshold`, `ai.rule-engine.decision-strong-threshold` 신규 키
- `com.bank.ai.rule.service.TrackClassifier` — 듀얼 score 결합 분기 추가 (단, decision_score 미가용 시 PD 단독 fallback)
- `com.bank.ai.rule.config.PolicyMatrix` — `decision_strong_threshold` 필드 추가
- `docs/ai/DATASETS.md` — Home Credit 항목 추가 (라이선스·출처·사용 범위)
- `docs/plan/banking-review-llm.md` — §1.4 PD 라벨 보류 → 완료 (homecredit_kr_v1) 갱신, §4 평가 표에 PD 모델 컬럼 추가, §10 Phase 1.4-PD 신규 마일스톤
- `docs/ai/MODEL_CARDS.md` (신규 작성) — homecredit_kr_v1 모델 카드

### 7.3 보존 / 미변경

- `auto_review_hmda_tx_v1` 모델 — decision 분류기로 유지
- HMDA loader / synthesize — 그대로
- RuleEngine YAML 의 기존 hard constraints, MORT_001 매트릭스 — 그대로
- loan-service 통합 (AutoReviewClient 등) — wire 호환 (응답 DTO 에 pd_score 추가만)

---

## 8. 진행 단계 (P1~P8)

| 단계 | 작업 | 산출물 |
|------|------|--------|
| P1 | Kaggle API 토큰 발급 + `home-credit-default-risk` 다운로드 (application_train + bureau 만, ~200MB) | `data/external/credit/home_credit/application_train.csv`, `bureau.csv` |
| P2 | `loaders/home_credit.py` — 컬럼 필터·결측·sentinel 처리·bureau 집계 | Layer 1~2 모듈, sanity unit test (양성률 8.07% 재현) |
| P3 | `synthesize/_kr_enrich_common.py` 리팩터링 — hmda_to_kr 의 KOSIS·합성 함수 추출 | 공통 모듈 + hmda_to_kr 회귀 테스트 PASS |
| P4 | `synthesize/home_credit_to_kr.py` — Layer 3~4 (KOSIS enrich + 한국 합성) | 변환 모듈 |
| P5 | `scripts/build_home_credit_kr.py` n=50K 첫 빌드 (stratified by TARGET) | `homecredit_kr_v1.parquet` |
| P6 | `training/features.py` 이진 라벨(default_within_12m) 변경 + LightGBM 학습 + 캘리브레이션 (isotonic) | `auto_review_pd_homecredit_v1/model.json`, `model.onnx` |
| P7 | 평가 (AUC/KS/Brier/lift/4-5ths) + mitigation 2종 (reweight + segment threshold) | `data/models/auto_review_pd_homecredit_v1/evaluation.md` |
| P8 | inference-server 듀얼 모델 서빙 (`/predict/decision`, `/predict/pd`) + RuleEngine 결합 분기 + e2e smoke 3 케이스 | 통합 PASS, 응답 DTO 에 `pd_score` 노출 |
| P9 | `banking-review-llm.md` / `DATASETS.md` / `MODEL_CARDS.md` 동기화 | docs |

**예상 기간**: 2~3주 (P1~P5 데이터·1주, P6~P8 모델·서빙·1주, P9 문서·반나절).

---

## 9. Lending Club 후속 (옵션, P9+)

신용대출(CRED_001) 전용 PD 모델 별도 학습:

| 컬럼 | 매핑 |
|------|------|
| `loan_status` | `Fully Paid` → 0, `Default` / `Charged Off` / `Late (>=120 days)` → 1 |
| `grade` (A~G) | 자행 신용등급 1~7 |
| `term` (36/60 months) | `requested_period_mo` |
| `int_rate` | `interest_rate_bps` |
| `dti` | `dsr` (단위 통일 후) |
| `fico_range_low/high` | 평균 → `credit_score_proxy` |
| `purpose` (debt_consolidation 등) | `purpose_cd` |
| `addr_state` | KOSIS 재할당 (HMDA·Home Credit 와 동일) |
| `emp_length` | `employment_years` |
| `home_ownership` | `housing_type` |

→ 학습 결과: `auto_review_pd_lendingclub_v1` — 신용대출 전용 PD.
→ inference-server 가 `product_code` 에 따라 PD 모델 라우팅 (MORT_001 → homecredit, CRED_001 → lendingclub).

---

## 10. 자행 실데이터 확보 후 transfer learning

| 단계 | 작업 |
|------|------|
| T1 | 자행 funded loans 6개월 이상 성숙 데이터 수령 (계약·납입·연체 chain) |
| T2 | feature schema 정합 점검 (본 문서 §6 스키마 vs 실데이터) — 불일치 컬럼만 추가 매핑 |
| T3 | `auto_review_pd_homecredit_v1` 을 base 로 fine-tuning (LightGBM `init_model` 또는 신규 학습 후 ensemble) |
| T4 | Shadow 모드 4주 — 자동심사는 표시만, 사람 결정 vs PD 일치율·캘리브레이션 측정 |
| T5 | 일치율·4/5ths PASS 시 cutover. `application.yml` `ai.rule-engine.pd-model-version` 키 갱신 |

→ shadow·kill switch 인프라는 Phase 1.9.2 에서 완성된 상태 (`bank.ai.auto-underwriting.enabled` /
   `shadow-mode` 플래그) — transfer learning 시 그대로 활용.

---

## 11. 한계·리스크

- **Home Credit 베이스 = 동유럽 신용시장** (러시아·체코·우크라이나·카자흐스탄). 한국 금융문화·
  소득분포·신용평가 관행과 차이. 분포 shift 보정용 캘리브레이션 필수.
- **`DAYS_EMPLOYED` 365243 sentinel** — 18% 행이 무직 표식. NULL 처리 시 employment_years
  결측 ↑, feature importance 분석에 영향.
- **TARGET 양성률 8.07%** — 한국 자행 실제 디폴트율 (0.7~3% 추정) 대비 4~10배. base rate 보정
  안 하면 PD score 가 일관되게 과대 추정.
- **`EXT_SOURCE_1/2/3` 익명 점수** — Home Credit 내부 모델 산출이라 한국 NICE/KCB 점수 분포와
  align 보장 없음. 가중평균 → 300~950 선형 매핑은 근사일 뿐, 실제 NICE 분포와 KL divergence
  높을 수 있음.
- **주담대 LTV 결측** — Home Credit `AMT_GOODS_PRICE` 는 Cash loans 만 일부 채워짐. LTV
  feature 는 `MORT_001` 행 학습엔 못 씀 → HMDA decision 모델과의 결합이 더 중요해짐.
- **`OCCUPATION_TYPE` 31% 결측** — 한국 occupation 매핑 시 결측 imputation 룰 필요 (segment
  기반 mode imputation 권고).
- **bureau 집계 효과** — bureau 미보유 행 (신규 거래 고객) 의 feature 가 모두 0 으로 채워짐 →
  모델이 "bureau 없음 = 안전" 으로 잘못 학습 가능. is_bureau_present 플래그 추가 필요.
- **Lending Club 데이터 종결** — 2018 년 이후 신규 issuance 중단. 시계열 freshness 부족 (매크로
  shift 보정 약함).
- **Freddie/Fannie 등록 절차** — 학술/비상업 약관 동의 필요. MVP 단계엔 후순위.
- **transfer learning 시점 외부 통제** — 자행 실데이터 확보 일정은 본 팀 통제 밖.

---

## 12. 부록 — 데이터 라이선스·다운로드

| 소스 | 라이선스 | 다운로드 방법 | 사용 가능성 |
|------|---------|--------------|------------|
| Kaggle / home-credit-default-risk | Kaggle competition rules — 비상업·연구 가능. 모델 가중치 재배포 시 출처 명시 | `kaggle competitions download -c home-credit-default-risk` (API 토큰 필요) | ✓ |
| LendingClub (archive.org / Kaggle 미러) | 공개 (원본 페이지 archived, 미러는 CC0 미상) | Kaggle `wordsforthewise/lending-club` | ✓ (출처 명시 권장) |
| Freddie Mac Single-Family | 학술/비상업, 약관 동의 + 이메일 등록 | freddiemac.com → Data → Single Family Loan-Level | △ (등록 시간 소요) |
| Fannie Mae Single-Family | 동일 | fanniemae.com 데이터 페이지 | △ |
| UCI German Credit | 공개 (UCI) | `archive.ics.uci.edu/dataset/144/statlog+german+credit+data` | ✓ (참고용) |

---

## 13. 의존성 — 본 phase 시작 전 점검

- [ ] Kaggle 계정 + API 토큰 (`~/.kaggle/kaggle.json`)
- [ ] `data/external/credit/home_credit/` 디스크 여유 1GB+ (압축 해제 후 ~2.7GB)
- [ ] `_kr_enrich_common` 리팩터링 영향 받는 hmda_to_kr 회귀 테스트 인프라 (P3 선행)
- [ ] inference-server 듀얼 endpoint 지원 가능 여부 점검 (현재는 단일 `/predict`)
- [ ] RuleEngine `PolicyMatrix` 스키마 확장에 대한 정책위 안내 (decision_strong_threshold 추가)
