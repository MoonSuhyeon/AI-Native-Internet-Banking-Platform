# Phase C — ML 파이프라인 완성 구현 계획

<!-- plan-status -->
> **상태: 📐 설계만 — 코드 없음** — 문서 스스로 `design draft`. PD 라벨이 선행인데 그것도 설계만이다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> Last updated: 2026-05-26
> Status: design draft
> 선행 문서: `docs/plan/pd-label-acquisition.md`, `docs/plan/hmda-localization.md`,
>            `docs/ai/MODEL_CARDS.md`, `docs/plan/banking-review-llm.md`

본 문서는 두 ML 모델(`hmda_v1` Decision Score, `homecredit_kr_v1` PD Score)을 실제 동작하는
ONNX 모델로 완성하고, inference-server 를 프로덕션 FastAPI 서버로 전환하며, Java 클라이언트를
SHAP 설명가능성까지 포함한 완전한 인터페이스로 확장하는 구현 계획.

---

## 0. Phase C 전체 지도

```
C1  합성 데이터 검증 결함 수정          (synthetic-data-generator)
 ↓
C2  hmda_v1 Decision Score 학습 + ONNX (synthetic-data-generator/training)
 ↓
C3  homecredit_kr_v1 PD 학습 + ONNX   (synthetic-data-generator/training)
 ↓
C4  inference-server FastAPI 구현      (inference-server/)
 ↑                                        |
C5  Java InferenceClient 확장          (agents/auto-loan-review)
 ↑
C6  Feature Engineering Pipeline      (auto-loan-review → inference-server)
 ↓
C7  ML 회귀 검증                       (CI / evaluation/)
```

완료 기준:
- `hmda_v1` holdout AUC-ROC ≥ 0.87
- `homecredit_kr_v1` holdout Gini ≥ 0.64, KS ≥ 0.41
- 4/5ths ratio ≥ 0.80 (sex × age_band × applicant_segment 교차)
- ONNX 추론 latency p99 ≤ 50ms (단건)
- CI 전체 green

---

## C1. 합성 데이터 검증 결함 수정

### 1. 목표

Phase 1.1 에서 보류된 oracle 편향 주입 3개 결함을 수정해 학습 데이터의
편향 자기 증폭(bias amplification) 을 제거하고, 데이터 분포 검증을 자동화한다.

알려진 결함:
- **D1**: `delinquency_history_24m` 분포가 항상 음이항 분포 미달 (Poisson λ 가 너무 작게 하드코딩됨)
- **D2**: `oracle.label()` 의 편향 주입이 precarious 직군 중 이미 REJECT 인 행을 풀에서
  제외하지 못해 실제 flip 수가 목표 대비 0에 수렴하는 버그 (→ `oracle.py` 에서 이미 수정 완료)
- **D3**: `financial_profile.synthesize()` 의 `credit_score_proxy` 가 income_quintile 에 단조
  상관이 없음 — EXT_SOURCE 의존 없이 uniform 으로 샘플링될 때 분위 간 점수 역전 발생

### 2. 파일 구조

```
synthetic-data-generator/
  src/
    synthesize/
      financial_profile.py          # _ext_to_credit_score() 수정 (D3)
      oracle.py                     # bias_extra_reject pool 제한 수정 (D2 — 이미 완료, 회귀 테스트만)
    validation/
      __init__.py                   # 신규
      distribution_checks.py        # 신규 — 분포 검증 함수 모음
      schema_checks.py              # 신규 — 스키마·범위 체크
  tests/
    test_distribution_checks.py     # 신규 pytest
    test_oracle_bias.py             # 기존 → 확장
```

#### `validation/distribution_checks.py` 핵심 함수

```python
def check_delinquency_distribution(
    df: pd.DataFrame,
    col: str = "delinquency_history_24m",
    expected_mean: float = 0.35,
    tolerance: float = 0.15,
) -> CheckResult:
    """
    delinquency_history_24m 의 Poisson λ 추정치가 [0.20, 0.50] 구간에 있는지 검사.
    mean 이 범위 밖이면 WARN 반환 (CI 는 WARN 에서도 PASS, 단 리포트에 기록).
    """

def check_credit_score_monotone(
    df: pd.DataFrame,
    quintile_col: str = "income_quintile",
    score_col: str = "credit_score_proxy",
) -> CheckResult:
    """
    income_quintile 1→5 로 갈수록 credit_score_proxy 의 분위별 median 이
    단조 비감소(monotone non-decreasing)인지 Spearman ρ ≥ 0.80 로 확인.
    """

def check_label_balance(
    df: pd.DataFrame,
    label_col: str = "oracle_decision",
    min_rare_rate: float = 0.05,
) -> CheckResult:
    """
    APPROVE/CONDITIONAL/REJECT 중 최소 클래스 비율이 min_rare_rate 이상인지 확인.
    HMDA 이진 라벨일 때는 label_col='decision_label' 로 교체해 APPROVE 최소 30%.
    """

def check_4_5ths_rule(
    df: pd.DataFrame,
    pred_col: str,
    group_cols: list[str],
    favorable_label: str = "APPROVE",
) -> CheckResult:
    """
    그룹별 승인률의 min/max ≥ 0.80 검사.
    group_cols=['sex'], ['age_band'], ['applicant_segment'] 각각 호출.
    """

@dataclass
class CheckResult:
    name: str
    passed: bool
    metric: float | None
    threshold: float | None
    message: str
```

#### `financial_profile.py` — D3 수정 내용

```python
def _ext_to_credit_score(
    ext_mean: np.ndarray,
    income_quintile: np.ndarray,
    rng: np.random.Generator,
    base_300: int = 300,
    range_650: int = 650,
) -> np.ndarray:
    """
    기존: EXT_SOURCE mean → 300 + (1 - raw) × 650  (단순 선형)
    수정:
      1. EXT_SOURCE 결측(Home Credit 미확보) 시 uniform 대신
         income_quintile 별 Beta(α, β) 에서 샘플링:
         Q1: Beta(2,5), Q2: Beta(3,4), Q3: Beta(4,3), Q4: Beta(5,2), Q5: Beta(6,1.5)
      2. EXT_SOURCE 가용 시 기존 선형 + quintile 잔차 ±50점 노이즈 추가
      3. clamp(300, 950) 보장
    """
    _BETA_PARAMS = {1:(2,5), 2:(3,4), 3:(4,3), 4:(5,2), 5:(6,1.5)}
    ...
```

### 3. 핵심 구현 내용

D3 수정의 핵심은 `credit_score_proxy` 가 income_quintile 과 **통계적으로 단조 상관**을 가지도록
Beta 분포 파라미터를 분위별로 다르게 설정하는 것. EXT_SOURCE 가용 시에는 기존 로직 유지(회귀 없음).

D1 수정: `oracle.py` 내의 `delinquency_history_24m` 합성 부분을
`Poisson(λ=0.35)` → `NegativeBinomial(n=2, p=0.85)` 로 교체해 분산을 현실적으로 늘림.

### 4. 테스트 전략

```
tests/test_distribution_checks.py
  test_delinquency_mean_within_range        — Poisson λ=0.35 데이터로 PASS
  test_delinquency_mean_out_of_range        — λ=0.01 데이터로 WARN
  test_credit_score_monotone_pass           — Q1<Q2<Q3<Q4<Q5 median 데이터로 PASS
  test_credit_score_monotone_fail           — 역전 데이터로 FAIL
  test_4_5ths_rule_pass                     — 승인률 균일 데이터로 PASS (ratio=1.0)
  test_4_5ths_rule_fail                     — 극단 격차 데이터로 FAIL (ratio=0.50)

tests/test_oracle_bias.py (기존 확장)
  test_bias_flip_count_within_target        — 목표 flip 수 ±20% 이내
  test_bias_pool_excludes_already_reject    — D2 회귀: 이미 REJECT 행이 후보에 없음
```

### 5. 커밋 테이블

| # | type | scope | 메시지 |
|---|------|-------|--------|
| C1-1 | feat | synthesize | credit_score_proxy Beta분포 분위별 파라미터화(D3) |
| C1-2 | feat | synthesize | delinquency NegativeBinomial 교체(D1) |
| C1-3 | feat | validation | distribution_checks 분포 검증 모듈 신규 |
| C1-4 | test | validation | distribution_checks 단위 테스트 |
| C1-5 | test | synthesize | oracle 편향 주입 회귀 테스트(D2) |

### 6. 완료 기준

- Spearman ρ(income_quintile, credit_score_proxy median) ≥ 0.80
- `delinquency_history_24m` 표본 mean ∈ [0.20, 0.50]
- pytest 전체 green (C1 테스트 5개 포함)

---

## C2. hmda_v1 Decision Score 모델 학습 + ONNX Export

### 1. 목표

HMDA 기반 이진 분류기(APPROVE/REJECT)를 LightGBM 으로 학습하고 ONNX 로 export.
AUC-ROC ≥ 0.87, 4/5ths ratio ≥ 0.80 달성.

### 2. 파일 구조

```
synthetic-data-generator/
  src/
    training/
      features.py                   # HMDA 이진 라벨 분기 추가
      train_hmda.py                 # 신규 — hmda_v1 학습 스크립트
      onnx_export.py                # 신규 — LightGBM → ONNX 변환
  scripts/
    train_hmda_v1.py                # 신규 CLI 엔트리포인트
data/
  models/
    hmda_v1/
      model.lgb                     # LightGBM native 포맷
      model.onnx                    # ONNX export
      feature_schema.json           # FeatureSchema 직렬화
      calibrator.json               # IsotonicCalibrator
      metadata.json                 # AUC/KS/threshold/best_iteration
      shap_background.parquet       # SHAP KernelExplainer 백그라운드 샘플 (500행)
```

#### `train_hmda.py` 함수 시그니처

```python
def load_hmda_dataset(
    parquet_path: Path,
    schema: HmdaFeatureSchema,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """train/valid/holdout split 로드. decision_label APPROVE=1, REJECT=0 인코딩."""

def build_lgbm_dataset(
    df: pd.DataFrame,
    schema: HmdaFeatureSchema,
    label_col: str = "decision_label",
) -> lgb.Dataset:
    """categorical_feature 자동 지정 포함."""

def train(
    train_ds: lgb.Dataset,
    valid_ds: lgb.Dataset,
    params: dict,
    num_boost_round: int = 1000,
    callbacks: list | None = None,
) -> lgb.Booster:
    """early_stopping_rounds=50, log_evaluation=100."""

def calibrate_isotonic(
    booster: lgb.Booster,
    valid_df: pd.DataFrame,
    schema: HmdaFeatureSchema,
) -> IsotonicRegression:
    """valid 셋에서 raw score → calibrated probability 매핑."""

def evaluate(
    booster: lgb.Booster,
    holdout_df: pd.DataFrame,
    schema: HmdaFeatureSchema,
    calibrator: IsotonicRegression,
) -> EvalResult:
    """AUC-ROC, KS, PR-AUC, Brier score, threshold@F1-max 반환."""
```

#### `HmdaFeatureSchema` (features.py 확장)

```python
# HMDA decision 모델 전용 — Layer 1~3 (Layer 4 PD 전용 제외)
HMDA_CATEGORICAL: list[str] = [
    "sex", "marital_status", "family_type", "housing_type",
    "education_level", "occupation", "province", "applicant_segment",
    "product_code", "purpose_cd",
]
HMDA_NUMERIC: list[str] = [
    "age", "income_quintile", "annual_income_kw", "total_asset_kw",
    "total_debt_kw", "collateral_debt_kw", "credit_debt_kw",
    "dsr", "ltv", "monthly_cashflow_mean_kw", "monthly_cashflow_std_kw",
    "delinquency_history_24m", "credit_score_proxy",
    "requested_amount_kw", "requested_period_mo",
]
HMDA_BOOLEAN: list[str] = ["purpose_red_flag"]
HMDA_LABEL_COL: str = "decision_label"          # "APPROVE" / "REJECT"
HMDA_POSITIVE_CLASS: str = "APPROVE"
```

### 3. 핵심 구현 내용

#### 알고리즘 선택 근거

XGBoost(현재 stub) → **LightGBM** 교체 이유:
- ONNX export 가 `lightgbm` built-in `model.to_onnx()` 로 단순화
- 카테고리 피처 native 지원 (레이블 인코딩 전처리 불필요)
- 동일 성능 대비 학습 속도 3~5배 (로컬 CPU 환경에서 중요)
- `shap.TreeExplainer` 가 LightGBM booster 를 직접 지원

#### Hyperparameter 탐색 범위

```python
PARAM_SPACE = {
    "objective": "binary",
    "metric": ["auc", "binary_logloss"],
    "num_leaves":      [63, 127, 255],       # 트리 복잡도
    "learning_rate":   [0.03, 0.05, 0.10],
    "min_child_samples": [20, 50, 100],
    "subsample":       [0.7, 0.8, 0.9],
    "colsample_bytree":[0.7, 0.8, 0.9],
    "reg_alpha":       [0, 0.1, 0.5],
    "reg_lambda":      [1, 5, 10],
    "class_weight":    ["balanced", None],   # APPROVE:REJECT 비율 보정
    "seed": 42,
    "n_jobs": -1,
    "verbose": -1,
}
# Optuna 20 trial, pruner=MedianPruner, sampler=TPESampler(seed=42)
```

#### ONNX Export (LightGBM built-in)

```python
# onnx_export.py
def export_lgbm_to_onnx(
    booster: lgb.Booster,
    schema: HmdaFeatureSchema,
    out_path: Path,
    initial_types: list | None = None,
) -> None:
    """
    lightgbm >= 4.0 의 booster.to_onnx() 활용.
    categorical 컬럼은 StringTensorType, numeric은 FloatTensorType.

    fallback: lightgbm < 4.0 이면 skl2onnx + LightGbmClassifierConverter 사용.
      from skl2onnx import convert_sklearn
      from skl2onnx.common.data_types import FloatTensorType
      # sklearn pipeline wrapper 로 감싸 변환

    export 후 onnxruntime.InferenceSession 으로 smoke 검증:
      - 학습 셋 100행 LightGBM vs ONNX 출력 비교 (max diff < 1e-4)
    """
```

#### SHAP 배경 샘플 생성

```python
def build_shap_background(
    train_df: pd.DataFrame,
    schema: HmdaFeatureSchema,
    n: int = 500,
    seed: int = 42,
) -> pd.DataFrame:
    """
    KMeans(n_clusters=10) 으로 대표 배경 샘플 선택 (shap.kmeans 동일 원리).
    ONNX 서버에서 shapiq.TabularExplainer 초기화에 사용.
    """
```

### 4. FastAPI 엔드포인트 스키마

`POST /predict` 요청/응답은 C4 에서 확장. hmda_v1 전용 변경:

응답 예시:
```json
{
  "model_version": "hmda_v1",
  "predictions": [
    {
      "decision": "APPROVE",
      "score": 0.821,
      "proba": {"APPROVE": 0.821, "REJECT": 0.179},
      "shap_top3": [
        {"feature": "credit_score_proxy", "value": 0.312},
        {"feature": "dsr",               "value": -0.185},
        {"feature": "delinquency_history_24m", "value": -0.094}
      ]
    }
  ]
}
```

### 5. 테스트 전략

```
tests/test_train_hmda.py
  test_dataset_load_split_ratio        — train:valid:holdout = 71:14.5:14.5 ±1%
  test_label_positive_rate             — APPROVE 비율 HMDA 기준 60~80% 범위
  test_train_returns_booster           — lgb.Booster 타입 확인
  test_evaluate_auc_above_threshold    — holdout AUC ≥ 0.87
  test_calibration_reduces_brier       — calibrated Brier < raw Brier
  test_onnx_export_smoke               — ONNX 100행 추론 max_diff < 1e-4
  test_onnx_latency_p99                — 1000행 배치 p99 ≤ 50ms
```

### 6. 커밋 테이블

| # | type | scope | 메시지 |
|---|------|-------|--------|
| C2-1 | feat | training | HmdaFeatureSchema + 이진 라벨 분기 |
| C2-2 | feat | training | hmda_v1 LightGBM 학습 + Optuna 튜닝 |
| C2-3 | feat | training | IsotonicCalibrator HMDA 적용 |
| C2-4 | feat | training | LightGBM→ONNX export + smoke 검증 |
| C2-5 | feat | training | SHAP 배경 샘플 생성 스크립트 |
| C2-6 | test | training | hmda_v1 학습·export 단위 테스트 |

### 7. 완료 기준

- holdout AUC-ROC ≥ 0.87
- holdout KS ≥ 0.38
- 4/5ths ratio(sex) ≥ 0.80, ratio(age_band) ≥ 0.80
- ONNX smoke: max_diff < 1e-4
- 모델 아티팩트 `data/models/hmda_v1/` 전체 커밋

---

## C3. homecredit_kr_v1 PD 모델 학습 + ONNX Export

### 1. 목표

Home Credit 기반 이진 디폴트 분류기(P(default_within_12m)) 를 LightGBM 으로 학습하고
ONNX export. Gini ≥ 0.64, KS ≥ 0.41, isotonic calibration 으로 base rate 보정.

### 2. 파일 구조

```
synthetic-data-generator/
  src/
    training/
      train_pd.py                   # 신규 — homecredit_kr_v1 학습 스크립트
      features_pd.py                # 신규 — PD 전용 피처 스키마
  scripts/
    train_pd_v1.py                  # 신규 CLI 엔트리포인트
data/
  models/
    homecredit_kr_v1/
      model.lgb
      model.onnx
      feature_schema.json
      calibrator.json               # base rate 보정 필수 (양성률 8% → 1~3%)
      metadata.json                 # Gini/KS/threshold/pd_threshold
      shap_background.parquet
```

#### `features_pd.py` — PD 전용 피처 스키마

```python
# Layer 1~4 전체 + Bureau 집계 포함
PD_CATEGORICAL: list[str] = [
    "sex", "marital_status", "housing_type", "education_level",
    "occupation", "industry_cd", "province", "applicant_segment",
    "product_code", "purpose_cd",
]
PD_NUMERIC: list[str] = [
    "age", "employment_years", "n_children",
    "income_quintile", "annual_income_kw", "total_asset_kw",
    "total_debt_kw", "collateral_debt_kw", "credit_debt_kw",
    "dsr", "ltv", "monthly_cashflow_mean_kw", "monthly_cashflow_std_kw",
    "delinquency_history_24m", "credit_score_proxy",
    "requested_amount_kw", "requested_period_mo",
    "region_risk_band",
    "bureau_n_active",            # bureau 집계
    "bureau_overdue_amt_kw",
    "bureau_max_status_24m",
]
PD_BOOLEAN: list[str] = [
    "purpose_red_flag",
    "bureau_has_record",          # is_bureau_present 플래그 (D§11 결측 오류 방지)
]
PD_LABEL_COL: str = "default_within_12m"   # bool → int8 (0/1)
PD_POSITIVE_CLASS: int = 1                  # 1 = default
```

#### `train_pd.py` 함수 시그니처

```python
def load_pd_dataset(
    parquet_path: Path,
    schema: PdFeatureSchema,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """stratified split by default_within_12m — 양성률 8% 보존."""

def apply_class_weight(
    train_df: pd.DataFrame,
    target_positive_rate: float = 0.08,
    desired_positive_rate: float = 0.03,
) -> pd.Series:
    """
    Kamiran-Calders sample reweighting.
    positive 클래스 가중치 = desired_rate / target_positive_rate 역수 비율 적용.
    훈련 데이터의 실효 base rate 를 한국 추정 디폴트율(1~3%)로 맞춤.
    """

def train_pd(
    train_ds: lgb.Dataset,
    valid_ds: lgb.Dataset,
    params: dict,
) -> lgb.Booster:
    """objective=binary, metric=[auc, binary_logloss, ks]. early_stopping_rounds=50."""

def calibrate_pd(
    booster: lgb.Booster,
    valid_df: pd.DataFrame,
    schema: PdFeatureSchema,
    target_base_rate: float = 0.02,
) -> IsotonicRegression:
    """
    valid 셋 isotonic regression.
    추가로 Platt scaling 과 비교해 Brier score 낮은 쪽 선택.
    """

def evaluate_pd(
    booster: lgb.Booster,
    holdout_df: pd.DataFrame,
    schema: PdFeatureSchema,
    calibrator: IsotonicRegression,
) -> PdEvalResult:
    """
    AUC-ROC, Gini = 2*AUC-1, KS statistic, Brier score.
    Lift decile 표 (상위 10% 디폴트 포획률).
    """
```

### 3. 핵심 구현 내용

#### KS Statistic 계산

```python
from scipy.stats import ks_2samp

def ks_statistic(y_true: np.ndarray, proba: np.ndarray) -> float:
    """
    pos = proba[y_true == 1], neg = proba[y_true == 0]
    KS = max |CDF_pos(t) - CDF_neg(t)|  = ks_2samp(pos, neg).statistic
    """
```

#### Hyperparameter 탐색 범위 (PD 특화)

```python
PD_PARAM_SPACE = {
    "objective": "binary",
    "metric": ["auc", "binary_logloss"],
    "num_leaves":        [31, 63, 127],
    "learning_rate":     [0.02, 0.05, 0.10],
    "min_child_samples": [50, 100, 200],     # 양성 클래스 희소로 min_child 키움
    "subsample":         [0.6, 0.7, 0.8],
    "colsample_bytree":  [0.6, 0.7, 0.8],
    "scale_pos_weight":  [1.0, 2.0, 5.0],   # 양성:음성 = 1:12 비율 조정
    "reg_alpha":         [0, 0.5, 1.0],
    "reg_lambda":        [5, 10, 20],
    "seed": 42,
    "verbose": -1,
}
# Optuna 25 trial (AUC 최대화 + KS 0.38 미달 시 trial 제거)
```

#### Base Rate 보정 원리

Home Credit TARGET 양성률 8.07% vs 한국 자행 추정 1~3%.
보정 방법:
1. `scale_pos_weight = 0.02 / 0.0807 ≈ 0.25` (음성 클래스를 더 중시)
2. 학습 후 isotonic calibration 으로 출력 확률 분포를 [0.02 median] 근방으로 이동
3. `metadata.json` 에 `pd_threshold` 를 calibrated 분포의 80th percentile 로 저장

#### ONNX Export 방법

```python
# train_pd.py
def export_pd_onnx(
    booster: lgb.Booster,
    schema: PdFeatureSchema,
    out_path: Path,
) -> None:
    """
    LightGBM >= 4.0: booster.to_onnx(num_class=1) → binary probability 출력.
    출력 노드: variable_output_probability (shape: [N, 2], [:, 1] = P(default))
    
    onnxruntime 검증:
      sess = ort.InferenceSession(str(out_path))
      ort_out = sess.run(None, ort_inputs)[1][:, 1]  # P(default)
      lgb_out = booster.predict(X_valid)
      assert np.max(np.abs(ort_out - lgb_out)) < 1e-4
    """
```

### 4. 테스트 전략

```
tests/test_train_pd.py
  test_pd_dataset_positive_rate         — holdout 양성률 ≈ 8.07% ±1%
  test_class_weight_reduces_base_rate   — reweighted train 실효 rate ≈ 2~4%
  test_train_pd_returns_booster         — lgb.Booster 타입 확인
  test_evaluate_gini_above_threshold    — Gini ≥ 0.64
  test_evaluate_ks_above_threshold      — KS ≥ 0.41
  test_lift_decile1_highest             — 상위 10% 포획률 > 전체 양성률 × 3
  test_calibration_reduces_brier        — calibrated Brier < raw Brier
  test_onnx_pd_smoke                    — 100행 max_diff < 1e-4
  test_pd_threshold_persisted           — metadata.json 에 pd_threshold 키 존재
```

### 5. 커밋 테이블

| # | type | scope | 메시지 |
|---|------|-------|--------|
| C3-1 | feat | training | PdFeatureSchema + PD 이진 라벨 분기 |
| C3-2 | feat | training | homecredit_kr_v1 LightGBM 학습 + Kamiran reweight |
| C3-3 | feat | training | PD isotonic calibration + base rate 보정 |
| C3-4 | feat | training | PD 모델 ONNX export + onnxruntime 검증 |
| C3-5 | test | training | PD 학습·calibration·export 단위 테스트 |

### 6. 완료 기준

- holdout Gini ≥ 0.64 (AUC-ROC ≥ 0.82)
- holdout KS ≥ 0.41
- Brier score(calibrated) < Brier score(raw)
- 4/5ths ratio(sex) ≥ 0.80 (PD threshold 기준 HIGH/LOW 비율)
- ONNX smoke PASS

---

## C4. inference-server FastAPI 구현 (Dual Model + SHAP)

### 1. 목표

현재 xgboost stub 기반 서버를 LightGBM ONNX 기반 프로덕션 서버로 전환.
`/predict` (hmda_v1), `/predict/pd` (homecredit_kr_v1) 양쪽에 SHAP 설명가능성 추가.
ONNX Runtime 으로 추론 — Python LightGBM 의존성 제거.

### 2. 파일 구조

```
inference-server/
  app/
    main.py                    # 기존 → 대폭 리팩터링
    model_bundle.py            # 신규 — OnnxModelBundle 클래스
    feature_prep.py            # 신규 — dict → ONNX numpy array 변환
    shap_explainer.py          # 신규 — shapiq/shap ONNX wrapper
    schemas.py                 # 신규 — Pydantic 모델 전체
    config.py                  # 신규 — 환경변수 설정
  tests/
    test_main.py               # 신규 pytest + httpx.AsyncClient
    test_onnx_bundle.py        # 신규
    test_shap_explainer.py     # 신규
  requirements.txt             # 갱신 (onnxruntime, shapiq 추가)
```

#### `model_bundle.py` 핵심 클래스

```python
class OnnxModelBundle:
    """
    ONNX 모델 + feature_schema.json + calibrator.json + shap_background.parquet 를 묶음.
    xgb.Booster 의존성 완전 제거.
    """
    def __init__(self, model_dir: Path) -> None:
        self.sess = ort.InferenceSession(
            str(model_dir / "model.onnx"),
            providers=["CPUExecutionProvider"],
        )
        self.schema: FeatureSchema = ...      # feature_schema.json 로드
        self.calibrator: IsotonicCalibrator | None = ...
        self.background_df: pd.DataFrame = pd.read_parquet(
            model_dir / "shap_background.parquet"
        )
        self.metadata: dict = ...
        self.model_id: str = ...              # metadata["model_id"]

    def predict_proba(self, df: pd.DataFrame) -> np.ndarray:
        """shape (N, n_classes). ONNX 출력 노드에서 probability map 추출."""

    def predict_pd_proba(self, df: pd.DataFrame) -> np.ndarray:
        """shape (N,). binary:logistic 출력 → calibrated."""

    def warm_up(self, n_rows: int = 10) -> None:
        """서버 시작 시 더미 데이터로 JIT 컴파일 유도."""
```

#### `shap_explainer.py`

```python
class OnnxShapExplainer:
    """
    shapiq.TabularExplainer 를 ONNX 모델 함수로 초기화.
    KernelSHAP (Kernel SHAP approximate) — TreeSHAP 은 ONNX 에서 직접 불가.
    
    성능 트레이드오프:
      - top-k=3 이면 단건 ~15ms (배경 n=500)
      - top-k=0 (SHAP 비활성) 이면 추가 비용 0
    """
    def __init__(
        self,
        bundle: OnnxModelBundle,
        top_k: int = 3,
    ) -> None:
        self._bundle = bundle
        self._top_k = top_k
        self._explainer = shapiq.TabularExplainer(
            model=self._predict_fn,
            data=self._prepare_background(),
            index="k-SII",
            max_order=1,
        )

    def explain_top_k(
        self,
        df: pd.DataFrame,
    ) -> list[list[dict[str, float]]]:
        """
        반환: 행별 top-k SHAP 값 [{feature, shap_value}, ...].
        top_k=0 이면 빈 리스트 반환.
        """

    def _predict_fn(self, X: np.ndarray) -> np.ndarray:
        """ONNX 세션 호출 래퍼 (shapiq 요구 인터페이스)."""
```

### 3. FastAPI 엔드포인트 스키마

#### `POST /predict` — Decision Score (hmda_v1)

Request:
```json
{
  "features": [
    {
      "sex": "남자",
      "age": 35,
      "credit_score_proxy": 720,
      "dsr": 0.28,
      "delinquency_history_24m": 0,
      "requested_amount_kw": 30000,
      "product_code": "MORT_001"
    }
  ],
  "explain": true
}
```

Response:
```json
{
  "model_version": "hmda_v1",
  "predictions": [
    {
      "decision": "APPROVE",
      "score": 0.821,
      "proba": {"APPROVE": 0.821, "REJECT": 0.179},
      "shap_top3": [
        {"feature": "credit_score_proxy", "shap_value": 0.312},
        {"feature": "dsr",               "shap_value": -0.185},
        {"feature": "delinquency_history_24m", "shap_value": -0.094}
      ]
    }
  ]
}
```

#### `POST /predict/pd` — PD Score (homecredit_kr_v1)

Request: 동일 구조, Layer 4 필드 추가 가능.

Response:
```json
{
  "model_version": "homecredit_kr_v1",
  "threshold": 0.06,
  "calibrated": true,
  "predictions": [
    {
      "pd_score": 0.034,
      "decision": "LOW",
      "shap_top3": [
        {"feature": "delinquency_history_24m", "shap_value": 0.011},
        {"feature": "credit_score_proxy",       "shap_value": -0.008},
        {"feature": "bureau_max_status_24m",    "shap_value": 0.006}
      ]
    }
  ]
}
```

#### `GET /health`

Response:
```json
{
  "status": "UP",
  "models": {
    "hmda_v1":           {"loaded": true, "n_features": 28, "auc": 0.891},
    "homecredit_kr_v1":  {"loaded": true, "n_features": 32, "gini": 0.648, "ks": 0.413}
  }
}
```

### 4. 환경변수 (`config.py`)

```python
class Settings(BaseSettings):
    model_dir: Path = Field(default=..., env="MODEL_DIR")
    pd_model_dir: Path | None = Field(default=None, env="PD_MODEL_DIR")
    shap_top_k: int = Field(default=3, env="SHAP_TOP_K")        # 0 = 비활성
    shap_background_n: int = Field(default=500, env="SHAP_BG_N")
    pd_default_threshold: float = Field(default=0.06, env="PD_THRESHOLD")
    max_batch_size: int = Field(default=1000, env="MAX_BATCH_SIZE")
```

### 5. 테스트 전략

```
tests/test_main.py  (pytest + httpx.AsyncClient)
  test_health_returns_200                   — 모델 로드 후 200
  test_predict_decision_approve             — credit 높은 입력 → APPROVE
  test_predict_decision_reject              — DSR 0.6 입력 → REJECT
  test_predict_with_explain_has_shap        — explain=true → shap_top3 비어있지 않음
  test_predict_without_explain_no_shap      — explain=false → shap_top3=[]
  test_predict_pd_low_risk                  — credit 높은 → LOW
  test_predict_pd_high_risk                 — 연체 2 + DSR 0.5 → HIGH
  test_predict_pd_503_when_not_loaded       — PD 모델 없으면 503

tests/test_onnx_bundle.py
  test_bundle_load_success
  test_predict_proba_shape                  — (N, 2) 확인
  test_warmup_completes_without_error

tests/test_shap_explainer.py
  test_explain_returns_top_k_features
  test_explain_zero_top_k_returns_empty
  test_shap_values_sum_approx_to_pred       — SHAP 합 ≈ (pred - base value)
```

### 6. 커밋 테이블

| # | type | scope | 메시지 |
|---|------|-------|--------|
| C4-1 | feat | inference-server | OnnxModelBundle xgboost→onnxruntime 교체 |
| C4-2 | feat | inference-server | feature_prep ONNX numpy 변환 모듈 |
| C4-3 | feat | inference-server | OnnxShapExplainer shapiq KernelSHAP |
| C4-4 | feat | inference-server | /predict·/predict/pd SHAP 필드 확장 |
| C4-5 | feat | inference-server | /health dual model 상태 확장 |
| C4-6 | test | inference-server | FastAPI + ONNX + SHAP 통합 테스트 |

### 7. 완료 기준

- `/health` 양 모델 loaded: true
- ONNX 단건 latency p99 ≤ 50ms (SHAP 포함 ≤ 200ms)
- pytest 전체 green

---

## C5. Java InferenceClient 확장

### 1. 목표

SHAP 필드 수신, 오류 코드 세분화, PD 응답 DTO 확장.
`AutoReviewService` 가 SHAP 값을 `ReviewReport` 에 포함할 수 있도록 인터페이스 완성.

### 2. 파일 구조

```
agents/auto-loan-review/src/main/java/com/bank/ai/review/client/dto/
  InferenceResponse.java           # Prediction 에 shapTop3 필드 추가
  PdInferenceResponse.java         # Prediction 에 shapTop3 필드 추가
  ShapEntry.java                   # 신규 record
  InferenceRequest.java            # explain 필드 추가 (default true)
```

### 3. Java 코드 변경사항

#### `ShapEntry.java` (신규)

```java
package com.bank.ai.review.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * inference-server 의 shap_top3 배열 원소.
 * feature: 피처명 (snake_case), shapValue: SHAP 기여값 (양수=승인 기여, 음수=거절 기여).
 */
public record ShapEntry(
        String feature,
        @JsonProperty("shap_value") double shapValue
) {}
```

#### `InferenceResponse.java` — Prediction 확장

```java
public record InferenceResponse(
        @JsonProperty("model_version") String modelVersion,
        List<Prediction> predictions
) {
    public record Prediction(
            String decision,
            double score,
            Map<String, Double> proba,
            @JsonProperty("shap_top3") List<ShapEntry> shapTop3   // 신규 — nullable
    ) {
        /** SHAP 미제공 환경(explain=false)에서 null-safe 접근. */
        public List<ShapEntry> shapTop3OrEmpty() {
            return shapTop3 != null ? shapTop3 : List.of();
        }
    }
}
```

#### `PdInferenceResponse.java` — Prediction 확장

```java
public record PdInferenceResponse(
        @JsonProperty("model_version") String modelVersion,
        double threshold,
        boolean calibrated,
        List<Prediction> predictions
) {
    public record Prediction(
            @JsonProperty("pd_score") double pdScore,
            String decision,
            @JsonProperty("shap_top3") List<ShapEntry> shapTop3   // 신규
    ) {}
}
```

#### `InferenceRequest.java` — explain 필드 추가

```java
/**
 * inference-server POST /predict 입력.
 * explain=true 이면 서버가 SHAP top-k 계산 포함 (latency +150ms 예상).
 * 배치 처리나 캐시 사전 적재 시엔 explain=false 로 설정.
 */
public record InferenceRequest(
        List<Map<String, Object>> features,
        boolean explain                         // 신규, default true
) {
    public static InferenceRequest of(List<Map<String, Object>> features) {
        return new InferenceRequest(features, true);
    }
    public static InferenceRequest withoutShap(List<Map<String, Object>> features) {
        return new InferenceRequest(features, false);
    }
}
```

#### `InferenceClient.java` — 오류 코드 세분화

```java
// 기존: 모든 4xx/5xx → INFERENCE_FAILED
// 변경: 상태코드별 세분화

.onStatus(status -> status.value() == 503, (req, res) ->
    { throw new BusinessException(AiErrorCode.INFERENCE_UNAVAILABLE); })
.onStatus(status -> status.value() == 422, (req, res) ->
    { throw new BusinessException(AiErrorCode.INFERENCE_INVALID_FEATURE); })
.onStatus(HttpStatusCode::isError, (req, res) ->
    { throw new BusinessException(AiErrorCode.INFERENCE_FAILED); })
```

`AiErrorCode` 에 `INFERENCE_INVALID_FEATURE` 추가.

### 4. 테스트 전략

```
agents/auto-loan-review/src/test/java/com/bank/ai/review/client/
  InferenceClientShapTest.java
    shouldDeserializeShapTop3FromResponse      — shap_top3 JSON 역직렬화
    shouldReturnEmptyShapWhenNull              — null shap_top3 → shapTop3OrEmpty() = []
    shouldThrowInvalidFeatureOn422             — 422 → INFERENCE_INVALID_FEATURE
    shouldThrowUnavailableOn503               — 503 → INFERENCE_UNAVAILABLE
    shouldIncludeExplainFieldInRequest        — explain=true 직렬화 확인
```

### 5. 커밋 테이블

| # | type | scope | 메시지 |
|---|------|-------|--------|
| C5-1 | feat | client | ShapEntry record + InferenceResponse shapTop3 확장 |
| C5-2 | feat | client | PdInferenceResponse shapTop3 확장 |
| C5-3 | feat | client | InferenceRequest explain 필드 + factory 메서드 |
| C5-4 | feat | client | InferenceClient 오류 코드 세분화(422/503) |
| C5-5 | test | client | SHAP 역직렬화·오류코드 단위 테스트 |

### 6. 완료 기준

- SHAP JSON 역직렬화 단위 테스트 PASS
- `ReviewReport` 에 SHAP top-3 포함 가능 (서비스 레이어는 C5 완료 후 별도 작업)

---

## C6. Feature Engineering Pipeline

### 1. 목표

`AutoReviewRequest` (Java 59 필드 record) → inference-server 가 요구하는
snake_case feature map 변환을 단일 컴포넌트로 캡슐화.
누락 필드 처리, 타입 캐스팅, PD 전용 필드 조건부 포함 로직을 중앙화.

### 2. 파일 구조

```
agents/auto-loan-review/src/main/java/com/bank/ai/review/
  client/
    FeatureMapper.java             # 신규 — AutoReviewRequest → Map<String, Object>
    FeatureMapperTest.java         # (test 디렉터리)
```

### 3. Java 코드 변경사항

#### `FeatureMapper.java`

```java
package com.bank.ai.review.client;

import com.bank.ai.review.dto.AutoReviewRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AutoReviewRequest → inference-server snake_case feature map 변환.
 *
 * 규칙:
 *   - camelCase → snake_case (Java 필드명 규칙 기준)
 *   - null 필드는 key 를 map 에 포함하되 value=null (서버 측 NaN imputation)
 *   - boolean → Integer (0/1) — ONNX 입력 타입 요구사항
 *   - Layer 4 PD 전용 필드는 includePdFeatures=true 일 때만 포함
 */
@Component
public class FeatureMapper {

    /**
     * Decision 모델 입력 생성 (Layer 1~3).
     */
    public Map<String, Object> toDecisionFeatures(AutoReviewRequest req) {
        var m = new LinkedHashMap<String, Object>();
        // Layer 1
        m.put("sex",                     req.sex());
        m.put("age",                     req.age());
        m.put("marital_status",          req.maritalStatus());
        m.put("military_status",         req.militaryStatus());
        m.put("family_type",             req.familyType());
        m.put("housing_type",            req.housingType());
        m.put("education_level",         req.educationLevel());
        m.put("bachelors_field",         req.bachelorsField());
        m.put("occupation",              req.occupation());
        m.put("district",                req.district());
        m.put("province",                req.province());
        m.put("applicant_segment",       req.applicantSegment());
        // Layer 2
        m.put("income_quintile",         req.incomeQuintile());
        m.put("annual_income_kw",        req.annualIncomeKw());
        m.put("total_asset_kw",          req.totalAssetKw());
        m.put("total_debt_kw",           req.totalDebtKw());
        m.put("collateral_debt_kw",      req.collateralDebtKw());
        m.put("credit_debt_kw",          req.creditDebtKw());
        m.put("dsr",                     req.dsr());
        m.put("ltv",                     req.ltv());
        m.put("monthly_cashflow_mean_kw",req.monthlyCashflowMeanKw());
        m.put("monthly_cashflow_std_kw", req.monthlyCashflowStdKw());
        m.put("delinquency_history_24m", req.delinquencyHistory24m());
        m.put("credit_score_proxy",      req.creditScoreProxy());
        // Layer 3
        m.put("product_code",            req.productCode());
        m.put("requested_amount_kw",     req.requestedAmountKw());
        m.put("requested_period_mo",     req.requestedPeriodMo());
        m.put("purpose_cd",              req.purposeCd());
        m.put("purpose_red_flag",        toBit(req.purposeRedFlag()));
        return m;
    }

    /**
     * PD 모델 입력 생성 (Layer 1~4 전체).
     */
    public Map<String, Object> toPdFeatures(AutoReviewRequest req) {
        var m = toDecisionFeatures(req);
        // Layer 4 PD 전용
        m.put("industry_cd",             req.industryCd());
        m.put("region_risk_band",        req.regionRiskBand());
        m.put("n_children",              req.nChildren());
        m.put("employment_years",        req.employmentYears());
        m.put("bureau_has_record",       toBit(req.bureauHasRecord()));
        m.put("bureau_n_active",         req.bureauNActive());
        m.put("bureau_max_status_24m",   req.bureauMaxStatus24m());
        return m;
    }

    private static Integer toBit(Boolean v) {
        if (v == null) return null;
        return v ? 1 : 0;
    }
}
```

### 4. 테스트 전략

```
src/test/java/com/bank/ai/review/client/FeatureMapperTest.java
  shouldMapAllLayer1To3Fields             — 29 key 포함 확인
  shouldMapLayer4InPdFeaturesOnly         — toDecisionFeatures에 industryCd 없음
  shouldConvertBooleanToInt               — purposeRedFlag=true → 1
  shouldIncludeNullValueForMissingFields  — null 필드도 key 포함 (NaN imputation)
  shouldNotMutateOriginalRequest          — map 수정이 req 에 영향 없음
```

### 5. 커밋 테이블

| # | type | scope | 메시지 |
|---|------|-------|--------|
| C6-1 | feat | client | FeatureMapper AutoReviewRequest → snake_case map |
| C6-2 | test | client | FeatureMapper 단위 테스트 |

### 6. 완료 기준

- 모든 `AutoReviewRequest` 필드가 snake_case map 에 매핑됨 확인
- boolean → int 변환, null 유지 테스트 PASS

---

## C7. ML 회귀 검증

### 1. 목표

모델 재학습 또는 데이터 변경 시 성능 지표·공정성 지표가 기준치 이상인지 자동 검증.
CI 파이프라인에서 `pytest -m ml_regression` 으로 실행.

### 2. 파일 구조

```
synthetic-data-generator/
  tests/
    test_ml_regression.py          # 신규 — 느린 테스트, pytest mark=ml_regression
  src/
    evaluation/
      regression.py                # 신규 — 회귀 검증 함수 + RegressionReport
```

#### `regression.py` 핵심 함수

```python
@dataclass
class RegressionReport:
    model_id: str
    auc_roc: float
    gini: float
    ks: float
    brier: float
    fourfiths_sex: float
    fourfiths_age_band: float
    fourfiths_segment: float
    lift_decile1: float
    passed: bool

    def to_json(self, path: Path) -> None: ...

def run_regression_check(
    model_dir: Path,
    holdout_parquet: Path,
    schema: FeatureSchema | PdFeatureSchema,
    thresholds: dict[str, float],
) -> RegressionReport:
    """
    1. ONNX 모델 로드 (onnxruntime)
    2. holdout 추론
    3. AUC, Gini, KS, Brier, 4/5ths, lift 계산
    4. thresholds 와 비교 → passed 결정
    5. RegressionReport 반환 + evaluation/<model_id>_regression_<date>.json 저장
    """

def track_distribution(
    current_df: pd.DataFrame,
    reference_parquet: Path,
    feature_cols: list[str],
    psi_threshold: float = 0.20,
) -> dict[str, float]:
    """
    Population Stability Index (PSI) per feature.
    PSI > 0.20 이면 drift 경보.
    PSI = sum((actual_% - expected_%) * ln(actual_% / expected_%)) across bins.
    bin 수 = max(10, sqrt(n)) 동일 빈도 분위 구간.
    """
```

### 3. Track Distribution 검사

**PSI 계산식**:
```
PSI_i = (actual_rate_i - expected_rate_i) × ln(actual_rate_i / expected_rate_i)
PSI = Σ PSI_i   (bins: 10 구간)

PSI < 0.10: 분포 안정 (변화 무시)
0.10 ≤ PSI < 0.20: 경미한 변화 (관찰)
PSI ≥ 0.20: 유의미한 drift → 재학습 트리거
```

`reference_parquet` = 최초 학습 시 train 셋 스냅샷 (`data/models/<id>/train_reference.parquet`).
매 배포 시 `/predict` 통과한 실시간 피처와 비교.

### 4. 4/5ths Rule 자동화

```python
def check_approval_parity(
    y_pred: np.ndarray,                # 0/1 이진 (APPROVE=1)
    group_df: pd.DataFrame,            # sex, age_band, applicant_segment 포함
    min_ratio: float = 0.80,
) -> dict[str, FourFifthsResult]:
    """
    그룹 교차(sex × age_band × segment) 별 approval rate 계산.
    min_rate / max_rate < min_ratio 인 그룹 쌍을 FAIL 로 표기.
    """
```

테스트 데이터 `age_band` 도출:
```python
def to_age_band(age: int) -> str:
    if age < 30: return "20s"
    if age < 40: return "30s"
    if age < 50: return "40s"
    if age < 60: return "50s"
    return "60+"
```

### 5. AUC/KS 신뢰구간

```python
def auc_ci_bootstrap(
    y_true: np.ndarray,
    proba: np.ndarray,
    n_boot: int = 1000,
    alpha: float = 0.05,
    seed: int = 42,
) -> tuple[float, float]:
    """
    bootstrap n_boot 번 resample → AUC 분포 → alpha/2 ~ 1-alpha/2 백분위 반환.
    CI 하한이 threshold 이상이면 통계적으로 유의.
    """

def ks_ci_bootstrap(
    y_true: np.ndarray,
    proba: np.ndarray,
    n_boot: int = 1000,
    alpha: float = 0.05,
    seed: int = 42,
) -> tuple[float, float]:
    """동일 구조. KS statistic bootstrap."""
```

### 6. 테스트 전략

```
tests/test_ml_regression.py  (pytest -m ml_regression)
  # hmda_v1 회귀
  test_hmda_v1_auc_above_threshold
    — data/models/hmda_v1/model.onnx 로 holdout 추론 → AUC ≥ 0.87
  test_hmda_v1_auc_ci_lower_bound
    — bootstrap 95% CI 하한 ≥ 0.85
  test_hmda_v1_4_5ths_sex
    — sex 기준 approval parity ratio ≥ 0.80
  test_hmda_v1_4_5ths_age_band
    — age_band 기준 ratio ≥ 0.80
  test_hmda_v1_4_5ths_segment
    — applicant_segment 기준 ratio ≥ 0.80
  test_hmda_v1_psi_stable
    — train_reference vs holdout 모든 피처 PSI < 0.20

  # homecredit_kr_v1 회귀
  test_pd_v1_gini_above_threshold
    — Gini ≥ 0.64
  test_pd_v1_ks_above_threshold
    — KS ≥ 0.41
  test_pd_v1_ks_ci_lower_bound
    — bootstrap 95% CI 하한 ≥ 0.38
  test_pd_v1_lift_decile1
    — 상위 10% capture rate ≥ 전체 양성률 × 2.5 (최소 2.5x lift)
  test_pd_v1_4_5ths_sex
    — HIGH/LOW 기준 sex ratio ≥ 0.80
  test_pd_v1_psi_stable
    — train_reference vs holdout PSI < 0.20

  # 공통
  test_regression_report_persisted
    — evaluation/*.json 파일 생성 확인
```

### 7. 커밋 테이블

| # | type | scope | 메시지 |
|---|------|-------|--------|
| C7-1 | feat | evaluation | PSI track_distribution + AUC/KS CI bootstrap |
| C7-2 | feat | evaluation | 4/5ths rule check_approval_parity 자동화 |
| C7-3 | feat | evaluation | RegressionReport + run_regression_check |
| C7-4 | test | evaluation | hmda_v1·homecredit_kr_v1 ML 회귀 테스트 |

### 8. 완료 기준

- `pytest -m ml_regression` 전체 green
- `data/models/hmda_v1/regression_<date>.json` 생성 확인
- `data/models/homecredit_kr_v1/regression_<date>.json` 생성 확인
- PSI 모든 피처 < 0.20

---

## 전체 커밋 순서 요약

```
C1: 데이터 검증 결함 수정   (C1-1 ~ C1-5,  5커밋)
C2: hmda_v1 학습            (C2-1 ~ C2-6,  6커밋)
C3: PD 모델 학습            (C3-1 ~ C3-5,  5커밋)
C4: inference-server        (C4-1 ~ C4-6,  6커밋)
C5: Java client 확장        (C5-1 ~ C5-5,  5커밋)
C6: FeatureMapper           (C6-1 ~ C6-2,  2커밋)
C7: ML 회귀 검증            (C7-1 ~ C7-4,  4커밋)
────────────────────────────────────────────────
합계: 33 커밋 (feat 22 + test 11)
```

각 서브아이템은 **feat 커밋 완료 → test 커밋 완료 → 보고** 순서로 진행.
C1 완료 전에 C2·C3 학습 시작 금지 (학습 데이터 분포 보장 선행 필요).
C4 완료 전에 C5 Java 통합 테스트 실행 금지 (서버 스펙 확정 선행).

---

## 의존성 추가 정리

### `synthetic-data-generator/requirements.txt` 추가

```
lightgbm>=4.0
onnxruntime>=1.17
optuna>=3.5
shapiq>=0.1
scipy>=1.11
```

### `inference-server/requirements.txt` 변경

```
# 제거
xgboost

# 추가
onnxruntime>=1.17
shapiq>=0.1
```

### `agents/auto-loan-review/build.gradle` — 변경 없음

Java 측은 HTTP JSON 통신이라 ONNX/SHAP 라이브러리 의존 없음.

---

## 한계·리스크

| 항목 | 내용 | 완화 방법 |
|------|------|----------|
| ONNX LightGBM categorical | LightGBM ONNX 의 categorical 처리가 버전마다 다름. 4.0 미만은 skl2onnx 우회 필요 | `onnx_export.py` 에 버전 감지 + fallback 구현 |
| shapiq KernelSHAP 속도 | 배경 n=500, top-k=3 기준 단건 ~15ms. 배치 100건 → 1.5초 | explain=false 옵션 제공. 배치는 기본 비활성 |
| Home Credit 양성률 보정 과다 | scale_pos_weight 로 너무 낮추면 KS↓ (음성 분리 능력 저하) | valid AUC 최대화 기준 Optuna 자동 튜닝으로 보완 |
| 합성 데이터 한계 | Home Credit 동유럽 분포 → 한국 도메인 shift 완전 해소 불가 | shadow 모드 4주 후 실데이터 cutover 계획 |
| 4/5ths 합성 데이터 bias | 합성 데이터에선 group 간 차이가 실데이터보다 작을 수 있음 | oracle 편향 주입(D2) + sample reweight mitigation 병행 |
