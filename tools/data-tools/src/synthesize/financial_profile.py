"""Layer 2 — 페르소나 → 재무 프로파일 합성.

KOSIS 가계금융복지조사 분위별 분포 + Home Credit EXT_SOURCE 분포를 시드로 사용.
"""

from __future__ import annotations

import glob
import logging

import numpy as np
import pandas as pd

from loaders.config import PROJECT_ROOT

log = logging.getLogger(__name__)

KOSIS_DIR = PROJECT_ROOT / "data" / "external" / "korean" / "kosis"
HOME_CREDIT = PROJECT_ROOT / "data" / "external" / "credit" / "home-credit-default" / "application_train"

# 종사상지위 → 소득5분위 prior (대략, KOSIS 자료 빈도 기반)
_OCC_QUINTILE_PRIOR = {
    "self_employed": [0.15, 0.20, 0.25, 0.25, 0.15],
    "young":         [0.30, 0.30, 0.20, 0.15, 0.05],
    "senior":        [0.40, 0.25, 0.15, 0.10, 0.10],
    "precarious":    [0.50, 0.30, 0.15, 0.04, 0.01],
    "regular":       [0.10, 0.20, 0.25, 0.25, 0.20],
}

# 분위별 연소득 만원 (KOSIS 가구금융복지조사 통상값 근사, 2024 기준)
_INCOME_QUINTILE_MEAN = [1500, 3000, 4500, 6500, 12000]
_INCOME_QUINTILE_STD =  [400, 600, 900, 1200, 4000]

# 분위별 자산/부채 만원
_ASSET_QUINTILE_MEAN = [10000, 22000, 38000, 65000, 180000]
_ASSET_QUINTILE_STD =  [4000, 7000, 10000, 18000, 80000]
_DEBT_QUINTILE_MEAN =  [1500, 4500, 8500, 14000, 28000]
_DEBT_QUINTILE_STD =   [800, 1500, 2500, 4500, 12000]

# 담보부채 비중(=담보부채/총부채) — KOSIS DT_1HDAAC03 분위별 통상값 근사
_COLLATERAL_SHARE_BY_Q = [0.45, 0.55, 0.65, 0.72, 0.78]


def _load_ext_source_distribution() -> np.ndarray:
    """Home Credit EXT_SOURCE 평균을 0~1 분포로 추출, 결측 제거."""
    files = sorted(glob.glob(str(HOME_CREDIT / "*.parquet")))
    if not files:
        log.warning("home-credit not found; credit_score_proxy will fallback to uniform")
        return np.array([])
    df = pd.read_parquet(files[0], columns=["EXT_SOURCE_1", "EXT_SOURCE_2", "EXT_SOURCE_3"])
    means = df.mean(axis=1).dropna().to_numpy()
    log.info("loaded EXT_SOURCE distribution: %d valid samples", len(means))
    return means


def _ext_to_credit_score(values: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """EXT_SOURCE 0~1 → 한국 신용점수 300~950 매핑.

    EXT_SOURCE 가 작을수록 default 확률 높으므로 1-x 로 뒤집고 선형 매핑.
    """
    if values.size == 0:
        # fallback: 정규분포(평균 720, std 80)
        return np.clip(rng.normal(720, 80, size=10_000), 300, 950)
    inverted = 1.0 - values
    score = 300 + inverted * (950 - 300)
    return score


def synthesize(personas: pd.DataFrame, seed: int = 42) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    n = len(personas)

    # 1) 소득 분위 — segment prior 로 샘플
    quintile = np.empty(n, dtype=np.int8)
    for seg, prior in _OCC_QUINTILE_PRIOR.items():
        mask = (personas["applicant_segment"] == seg).to_numpy()
        if mask.any():
            quintile[mask] = rng.choice(5, size=mask.sum(), p=prior)
    # fallback: 분류 안 된 행
    miss = (quintile < 0) | (quintile > 4)
    if miss.any():
        quintile[miss] = rng.choice(5, size=miss.sum(), p=_OCC_QUINTILE_PRIOR["regular"])

    # 2) 소득/자산/부채 정규분포 샘플
    annual_income = np.maximum(rng.normal(
        loc=np.take(_INCOME_QUINTILE_MEAN, quintile),
        scale=np.take(_INCOME_QUINTILE_STD, quintile),
    ), 100).astype(int)
    total_asset = np.maximum(rng.normal(
        loc=np.take(_ASSET_QUINTILE_MEAN, quintile),
        scale=np.take(_ASSET_QUINTILE_STD, quintile),
    ), 0).astype(int)
    total_debt = np.maximum(rng.normal(
        loc=np.take(_DEBT_QUINTILE_MEAN, quintile),
        scale=np.take(_DEBT_QUINTILE_STD, quintile),
    ), 0).astype(int)

    # 3) 담보/신용 부채 분해
    collat_share = np.take(_COLLATERAL_SHARE_BY_Q, quintile)
    noise = rng.normal(0, 0.05, size=n)
    collat_share = np.clip(collat_share + noise, 0.0, 1.0)
    collateral_debt = (total_debt * collat_share).astype(int)
    credit_debt = total_debt - collateral_debt

    # 4) DSR, LTV 추정
    # DSR = 연원리금상환액 / 연소득. 부채를 5년 분할상환 가정.
    annual_repay = total_debt / 5 + total_debt * 0.05  # 원금 + 5% 이자
    dsr = annual_repay / np.maximum(annual_income, 1)
    # LTV = 담보부채 / 부동산자산. 부동산자산을 총자산의 60%로 가정.
    real_estate = total_asset * 0.6
    ltv = collateral_debt / np.maximum(real_estate, 1)

    # 5) 거래내역 통계 (월평균/변동성)
    monthly_cashflow_mean = annual_income / 12
    monthly_cashflow_std = monthly_cashflow_mean * rng.uniform(0.05, 0.35, size=n)

    # 6) 연체 이력 (분위가 낮을수록 확률 ↑)
    deli_rate = np.array([0.20, 0.12, 0.07, 0.04, 0.02])
    delinquency_history_24m = (rng.random(n) < np.take(deli_rate, quintile)).astype(int)

    # 7) credit_score_proxy — Home Credit EXT_SOURCE 분포 매핑
    ext = _load_ext_source_distribution()
    if ext.size > 0:
        # 분위별로 EXT_SOURCE 분포 슬라이스(낮은 분위는 낮은 EXT_SOURCE)
        sorted_ext = np.sort(ext)
        per_q = np.array_split(sorted_ext, 5)
        samples = np.empty(n)
        for q in range(5):
            mask = quintile == q
            if mask.any():
                samples[mask] = rng.choice(per_q[q], size=mask.sum(), replace=True)
        credit_score_proxy = _ext_to_credit_score(samples, rng).astype(int)
    else:
        credit_score_proxy = _ext_to_credit_score(np.array([]), rng)[:n].astype(int)

    return personas.assign(
        income_quintile=quintile + 1,  # 1~5
        annual_income_kw=annual_income,
        total_asset_kw=total_asset,
        total_debt_kw=total_debt,
        collateral_debt_kw=collateral_debt,
        credit_debt_kw=credit_debt,
        dsr=np.round(dsr, 4),
        ltv=np.round(ltv, 4),
        monthly_cashflow_mean_kw=monthly_cashflow_mean.astype(int),
        monthly_cashflow_std_kw=monthly_cashflow_std.astype(int),
        delinquency_history_24m=delinquency_history_24m,
        credit_score_proxy=credit_score_proxy,
    )
