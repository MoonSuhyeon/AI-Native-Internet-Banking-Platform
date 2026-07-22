"""Layer 4 — 규칙 기반 oracle + 노이즈 + 의도적 편향 주입."""

from __future__ import annotations

import re

import numpy as np
import pandas as pd

# 임계치
DSR_MAX = 0.40
LTV_MAX = 0.70
CREDIT_MIN = 600
AMOUNT_TO_INCOME_MAX = 3.0  # 신청금액 ≤ 연소득×3

# 편향 주입 대상: 단순/일용 직업군에 +5% 추가 거절 (Layer 4 self-test 용)
_BIASED_OCC_PATTERN = re.compile(r"단순|일용|일당")


def _rule_score(row: pd.Series) -> int:
    """0~100 점수. 100 = 모든 규칙 통과."""
    score = 100
    if row["dsr"] > DSR_MAX:
        score -= 30
    if row["ltv"] > LTV_MAX:
        score -= 20
    if row["credit_score_proxy"] < CREDIT_MIN:
        score -= 25
    if row["delinquency_history_24m"] > 0:
        score -= 30
    if row["requested_amount_kw"] > row["annual_income_kw"] * AMOUNT_TO_INCOME_MAX:
        score -= 15
    if row["purpose_red_flag"]:
        score -= 5
    return max(score, 0)


def label(applications: pd.DataFrame, seed: int = 42, noise_rate: float = 0.07,
          bias_extra_reject: float = 0.05, inject_bias: bool = True) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    n = len(applications)

    scores = applications.apply(_rule_score, axis=1).to_numpy()

    # 결정: APPROVE if >=70, CONDITIONAL if >=50, REJECT else
    decisions = np.where(scores >= 70, "APPROVE",
                np.where(scores >= 50, "CONDITIONAL", "REJECT"))

    # 노이즈: noise_rate 로 label flip (인접 카테고리로)
    flip_mask = rng.random(n) < noise_rate
    for i in np.where(flip_mask)[0]:
        if decisions[i] == "APPROVE":
            decisions[i] = "CONDITIONAL"
        elif decisions[i] == "REJECT":
            decisions[i] = "CONDITIONAL"
        else:
            decisions[i] = rng.choice(["APPROVE", "REJECT"])

    # 편향 주입: 단순/일용 직업군 +5% 추가 거절
    bias_flag = np.zeros(n, dtype=bool)
    if inject_bias:
        occ = applications["occupation"].fillna("")
        biased_pool = occ.str.contains(_BIASED_OCC_PATTERN, regex=True, na=False).to_numpy()
        # 그 중 현재 APPROVE 인 행만 대상으로 5% 강제 reject
        target_idx = np.where(biased_pool & (decisions == "APPROVE"))[0]
        if len(target_idx) > 0:
            to_flip = rng.choice(target_idx,
                                 size=int(len(target_idx) * bias_extra_reject),
                                 replace=False)
            decisions[to_flip] = "REJECT"
            bias_flag[to_flip] = True

    # 승인 권장 한도/금리
    suggested_amount = np.where(
        decisions == "APPROVE", applications["requested_amount_kw"],
        np.where(decisions == "CONDITIONAL",
                 (applications["requested_amount_kw"] * 0.7).astype(int), 0)
    )
    base_rate = 350  # 3.50% in bps
    risk_premium = (100 - scores) * 5  # 점수 낮을수록 금리 ↑
    suggested_rate_bps = (base_rate + risk_premium).astype(int)

    return applications.assign(
        oracle_score=scores.astype(int),
        oracle_decision=decisions,
        oracle_suggested_amount_kw=suggested_amount.astype(int),
        oracle_suggested_rate_bps=suggested_rate_bps,
        oracle_noise_flipped=flip_mask,
        oracle_bias_injected=bias_flag,
    )
