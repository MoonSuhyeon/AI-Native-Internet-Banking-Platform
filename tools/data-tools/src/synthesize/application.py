"""Layer 3 — 페르소나 + 재무 → 대출 신청서 메타 + purpose_text (템플릿)."""

from __future__ import annotations

import numpy as np
import pandas as pd

# 상품 코드 — 여신 ERD 의 LOAN_PRODUCT 가정
_PRODUCTS = [
    # (code, name, min_amt_kw, max_amt_kw, min_mo, max_mo, target_segment)
    ("MORT_001", "주택담보대출", 5000, 50000, 60, 360, "regular"),
    ("CRED_001", "신용대출",     500,  10000, 12,  60, "regular"),
    ("BIZ_001",  "사업자대출",  1000,  30000, 12, 120, "self_employed"),
    ("CARD_001", "카드론",       100,   3000,  6,  36, "young"),
    ("EMER_001", "긴급생계대출",  100,   2000,  6,  36, "precarious"),
    ("HOME_001", "전세자금대출", 2000,  20000, 24, 120, "regular"),
]

_PURPOSE_CODES = ["LIVING", "HOUSING", "BUSINESS", "EDUCATION", "MEDICAL", "DEBT_CONS", "OTHER"]

# 사유 텍스트 템플릿 (segment × purpose)
_PURPOSE_TEMPLATES = {
    "HOUSING":   ["{loc} 아파트 매매 자금 보전", "전세 보증금 마련", "{loc} 주택 구입 잔금"],
    "BUSINESS":  ["사업장 운영자금", "{loc} 매장 인테리어 비용", "사업 확장 자금 조달"],
    "LIVING":    ["생활비 부족분 보전", "긴급 생계자금", "월세·관리비 충당"],
    "EDUCATION": ["자녀 등록금", "본인 학자금", "학원·교재 비용"],
    "MEDICAL":   ["수술비 및 입원비", "가족 치료비 부담"],
    "DEBT_CONS": ["기존 대출 통합 상환", "고금리 카드대출 대환"],
    "OTHER":     ["혼례 비용", "차량 구입 자금", "이사 비용"],
}

# 의도적으로 빈약한 사유 (red flag — 10% 주입)
_WEAK_TEMPLATES = ["급해서요", "필요해서", "여러가지", "그냥 좀", "음...", "사정이 있어서요"]


def _pick_product(seg: str, rng: np.random.Generator) -> tuple:
    candidates = [p for p in _PRODUCTS if p[6] == seg]
    if not candidates:
        candidates = _PRODUCTS
    return candidates[rng.integers(0, len(candidates))]


def _purpose_text(purpose_cd: str, province: str, rng: np.random.Generator) -> str:
    templates = _PURPOSE_TEMPLATES.get(purpose_cd, _PURPOSE_TEMPLATES["OTHER"])
    base = templates[rng.integers(0, len(templates))]
    return base.replace("{loc}", province or "")


def synthesize(profiles: pd.DataFrame, seed: int = 42, red_flag_ratio: float = 0.10) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    n = len(profiles)

    rows = []
    for i in range(n):
        row = profiles.iloc[i]
        product = _pick_product(row["applicant_segment"], rng)
        prod_code, _, min_amt, max_amt, min_mo, max_mo, _ = product

        # 신청 금액: 연소득 0.5~3.0 배 범위에서 상품 한도에 맞춤
        income = int(row["annual_income_kw"])
        amt = int(rng.uniform(0.5, 3.0) * income)
        req_amount = int(np.clip(amt, min_amt, max_amt))
        req_period = int(rng.integers(min_mo, max_mo + 1))

        # 목적 코드 — 상품에 따라 비중 다름
        if prod_code.startswith("MORT") or prod_code == "HOME_001":
            purpose_cd = "HOUSING"
        elif prod_code.startswith("BIZ"):
            purpose_cd = "BUSINESS"
        elif prod_code == "EMER_001":
            purpose_cd = rng.choice(["LIVING", "MEDICAL"])
        else:
            purpose_cd = rng.choice(_PURPOSE_CODES, p=[0.3, 0.05, 0.1, 0.15, 0.1, 0.2, 0.1])

        rows.append({
            "product_code": prod_code,
            "requested_amount_kw": req_amount,
            "requested_period_mo": req_period,
            "purpose_cd": purpose_cd,
        })

    apps = pd.DataFrame(rows)

    # purpose_text 생성 (red flag 일부 주입)
    red_flag_mask = rng.random(n) < red_flag_ratio
    texts = []
    for i, row in apps.iterrows():
        if red_flag_mask[i]:
            texts.append(_WEAK_TEMPLATES[rng.integers(0, len(_WEAK_TEMPLATES))])
        else:
            texts.append(_purpose_text(row["purpose_cd"], profiles.iloc[i].get("province") or "", rng))
    apps["purpose_text"] = texts
    apps["purpose_red_flag"] = red_flag_mask

    return pd.concat([profiles.reset_index(drop=True), apps.reset_index(drop=True)], axis=1)
