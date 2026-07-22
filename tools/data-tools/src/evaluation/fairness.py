"""공정성 메트릭 + 의도 주입 편향 회수율."""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from training.features import FeatureSchema


def demographic_parity(
    pred: np.ndarray,
    groups: pd.Series,
    favorable_value: int,
) -> dict[str, Any]:
    """favorable=APPROVE 라고 가정. 그룹별 favorable rate 와 최대 격차(DPD).

    DPD (Demographic Parity Difference) = max(group_rate) - min(group_rate).
    0 에 가까울수록 인구통계적 균형.
    """
    rates = {}
    for g, mask in groups.groupby(groups):
        m = (groups == g).to_numpy()
        if m.sum() == 0:
            continue
        rates[g] = float((pred[m] == favorable_value).mean())
    if not rates:
        return {"per_group_rate": {}, "dpd": None}
    return {
        "per_group_rate": rates,
        "dpd": max(rates.values()) - min(rates.values()),
        "max_group": max(rates, key=rates.get),
        "min_group": min(rates, key=rates.get),
    }


def equalized_odds(
    y_true: np.ndarray,
    pred: np.ndarray,
    groups: pd.Series,
    favorable_value: int,
) -> dict[str, Any]:
    """그룹별 TPR/FPR + EOD = max diff in TPR + max diff in FPR.

    favorable 클래스에 한해 binary 로 환원.
    """
    yt = (y_true == favorable_value).astype(int)
    yp = (pred == favorable_value).astype(int)

    tpr, fpr = {}, {}
    for g, _ in groups.groupby(groups):
        m = (groups == g).to_numpy()
        if m.sum() == 0:
            continue
        pos = yt[m] == 1
        neg = yt[m] == 0
        tpr[g] = float(yp[m][pos].mean()) if pos.sum() > 0 else None
        fpr[g] = float(yp[m][neg].mean()) if neg.sum() > 0 else None

    def _spread(d: dict) -> float | None:
        vals = [v for v in d.values() if v is not None]
        return (max(vals) - min(vals)) if len(vals) >= 2 else None

    return {
        "per_group_tpr": tpr,
        "per_group_fpr": fpr,
        "tpr_spread": _spread(tpr),
        "fpr_spread": _spread(fpr),
    }


def bias_recovery(
    df: pd.DataFrame,
    pred: np.ndarray,
    schema: FeatureSchema,
) -> dict[str, Any]:
    """oracle_bias_injected=True 행을 모델이 얼마나 따라가는가.

    의도: Layer 4 가 precarious 일부를 강제로 REJECT 시켰을 때,
    모델이 학습 데이터의 그 편향을 그대로 학습해 같은 행을 REJECT 로 예측하는가.

    회수율이 높으면 → 모델이 편향까지 학습 (운영 전 mitigation 필요)
    낮으면 → 모델이 편향에 둔감 (의외로 견고)
    """
    if "oracle_bias_injected" not in df.columns:
        return {"available": False}
    mask = df["oracle_bias_injected"].fillna(False).astype(bool).to_numpy()
    reject_idx = schema.label_classes.index("REJECT")
    n_bias = int(mask.sum())
    if n_bias == 0:
        return {"available": True, "n_bias_rows": 0, "recovery_rate": None}
    recovered = int((pred[mask] == reject_idx).sum())
    return {
        "available": True,
        "n_bias_rows": n_bias,
        "n_predicted_reject": recovered,
        "recovery_rate": recovered / n_bias,
    }


def occupation_disparity(
    df: pd.DataFrame,
    pred: np.ndarray,
    schema: FeatureSchema,
    top_k: int = 10,
) -> dict[str, Any]:
    """직업별 REJECT 예측률 상·하위 top_k.

    precarious 그룹(단순/일용/일당/비정규)이 상위에 몰리는지 확인.
    """
    reject_idx = schema.label_classes.index("REJECT")
    occ = df["occupation"].fillna("(missing)")
    is_reject = (pred == reject_idx).astype(int)

    grouped = pd.DataFrame({"occupation": occ, "is_reject": is_reject})
    agg = (
        grouped.groupby("occupation")
        .agg(n=("is_reject", "size"), reject_rate=("is_reject", "mean"))
        .reset_index()
    )
    # n 이 너무 작은 직업은 통계 노이즈가 크다 → 최소 20건 필터
    eligible = agg[agg["n"] >= 20].sort_values("reject_rate", ascending=False)

    return {
        "min_support": 20,
        "top_reject": eligible.head(top_k).to_dict(orient="records"),
        "bottom_reject": eligible.tail(top_k).to_dict(orient="records"),
    }
