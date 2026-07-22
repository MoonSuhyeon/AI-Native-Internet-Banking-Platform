"""Layer 1 — Nemotron 페르소나 샘플링 + 신청자 분포로 reweighting."""

from __future__ import annotations

import glob
import logging
import re

import numpy as np
import pandas as pd

from loaders.config import PROJECT_ROOT

log = logging.getLogger(__name__)

PERSONA_DIR = PROJECT_ROOT / "data" / "synthetic" / "personas" / "slim"

# 직업 키워드별 신청자 분포 보정 가중치 (1.0 = 인구 분포 그대로).
# KDI 가계부채 보고서 등에서 청년·자영업 over-representation 확인.
_OCC_WEIGHT_RULES: list[tuple[str, float]] = [
    (r"자영|소상공|개인사업|자영업", 1.5),
    (r"단순|일용|일당|비정규", 0.8),
    (r"무직|학생|전업주부", 0.4),
]


def _occupation_weight(occ: str | None) -> float:
    if not occ:
        return 1.0
    for pat, w in _OCC_WEIGHT_RULES:
        if re.search(pat, occ):
            return w
    return 1.0


def _age_weight(age: int) -> float:
    # 청년(20~30대) +30%, 60대+ -20%
    if age < 20:
        return 0.0  # 19세 컷
    if 20 <= age < 40:
        return 1.3
    if 40 <= age < 60:
        return 1.0
    if 60 <= age < 70:
        return 0.8
    return 0.5  # 70+


def _applicant_segment(row: pd.Series) -> str:
    occ = row.get("occupation") or ""
    age = int(row.get("age") or 0)
    if re.search(r"자영|소상공|개인사업", occ):
        return "self_employed"
    if age < 30:
        return "young"
    if age >= 60:
        return "senior"
    if re.search(r"단순|일용|비정규", occ):
        return "precarious"
    return "regular"


def sample(n: int, seed: int = 42) -> pd.DataFrame:
    """페르소나 풀에서 n 개 샘플링.

    reweighting: 청년/자영업자 over-sampling, 60+ under-sampling.
    """
    files = sorted(glob.glob(str(PERSONA_DIR / "*.parquet")))
    if not files:
        raise FileNotFoundError(f"persona slim not found at {PERSONA_DIR}")
    df = pd.concat([pd.read_parquet(f) for f in files], ignore_index=True)
    log.info("loaded persona pool: %d rows", len(df))

    occ_w = df["occupation"].map(_occupation_weight)
    age_w = df["age"].astype(int).map(_age_weight)
    weights = (occ_w * age_w).to_numpy()
    weights = np.where(weights > 0, weights, 0)
    if weights.sum() == 0:
        raise RuntimeError("all reweighting yielded 0; check rules")

    rng = np.random.default_rng(seed)
    probs = weights / weights.sum()
    idx = rng.choice(len(df), size=n, replace=False, p=probs)
    sampled = df.iloc[idx].reset_index(drop=True).copy()
    sampled["sample_weight"] = weights[idx]
    sampled["applicant_segment"] = sampled.apply(_applicant_segment, axis=1)
    log.info("sampled %d personas (segments: %s)",
             n, sampled["applicant_segment"].value_counts().to_dict())
    return sampled
