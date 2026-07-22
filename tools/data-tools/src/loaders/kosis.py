"""KOSIS Open API 로더.

API 명세: https://kosis.kr/openapi/devGuide/devGuide_0101List.do
URL 패턴 (통계자료 - 파라미터 방식):
  https://kosis.kr/openapi/Param/statisticsParameterData.do
  ?method=getList
  &apiKey={KEY}
  &orgId={ORG_ID}        # 통계청 등 기관 ID
  &tblId={TBL_ID}        # 통계표 ID
  &itmId=...             # 항목 코드
  &objL1=...             # 분류 코드 (자동 ALL=가능)
  &format=json
  &jsonVD=Y              # 값을 숫자로
  &prdSe={CYCLE}         # A/Q/M
  &startPrdDe=...
  &endPrdDe=...

통계표 ID는 KOSIS 사이트에서 확인 필요. 가계금융복지조사는 보통 'DT_1HDLF*' 시리즈.
"""

from __future__ import annotations

import logging
from pathlib import Path

import pandas as pd

from .base import http_get_json, save_parquet
from .config import KEYS

log = logging.getLogger(__name__)

BASE = "https://kosis.kr/openapi/Param/statisticsParameterData.do"


def _call_with_depth(base_params: dict, obj_depth: int) -> dict | list:
    params = dict(base_params)
    for i in range(1, obj_depth + 1):
        params[f"objL{i}"] = "ALL"
    return http_get_json(BASE, params=params)


def fetch_table(
    org_id: str,
    tbl_id: str,
    itm_id: str = "ALL",
    prd_se: str = "Y",
    start: str = "2018",
    end: str = "2026",
) -> pd.DataFrame:
    """KOSIS 통계표 데이터 수집.

    KOSIS 는 통계표마다 분류 차원 수(objL1..objL8) 가 다르고 초과 전달도 거부.
    err 20(누락) / err 21(잘못된 변수) 응답을 기준으로 1~6 깊이를 자동 탐색.
    """
    key = KEYS.require("kosis")
    base = {
        "method": "getList",
        "apiKey": key,
        "format": "json",
        "jsonVD": "Y",
        "orgId": org_id,
        "tblId": tbl_id,
        "itmId": itm_id,
        "prdSe": prd_se,
        "startPrdDe": start,
        "endPrdDe": end,
    }

    last_err: str | None = None
    for depth in range(1, 7):
        payload = _call_with_depth(base, depth)
        if isinstance(payload, dict) and ("err" in payload or "errMsg" in payload):
            err_code = str(payload.get("err", ""))
            last_err = str(payload)
            # err 20: 분류 부족 -> 더 깊이, err 21: 분류 초과 -> 중단
            if err_code == "20":
                continue
            if err_code == "21":
                break
            # 그 외 에러는 즉시 raise
            raise RuntimeError(f"KOSIS error: {payload}")
        if not isinstance(payload, list):
            raise RuntimeError(f"KOSIS unexpected response shape: {type(payload).__name__}")
        if not payload:
            return pd.DataFrame()
        df = pd.DataFrame(payload)
        if "DT" in df.columns:
            df["DT"] = pd.to_numeric(df["DT"], errors="coerce")
        log.info("KOSIS %s/%s: depth=%d rows=%d", org_id, tbl_id, depth, len(df))
        return df

    raise RuntimeError(f"KOSIS no valid depth for {org_id}/{tbl_id}. last: {last_err}")


def fetch_default_bundle() -> list[Path]:
    """자동심사 Layer 2 시드용 기본 통계표.

    가계금융복지조사(통계청 orgId=101) prefix DT_1HDAA*.
    자산↔부채↔소득의 결합 분포를 받아 합성 데이터의 reweighting 시드로 사용.
    """
    targets = [
        # (org_id, tbl_id, prd_se, start, end, friendly_name)

        # --- 자산·부채·소득 결합 (분위/소속별) ---
        ("101", "DT_1HDAAA10", "Y", "2018", "2025", "income_quintile_asset_debt"),
        ("101", "DT_1HDAAA22", "Y", "2018", "2025", "income_decile_asset_debt"),
        ("101", "DT_1HDAAA09", "Y", "2018", "2025", "employment_status_asset_debt"),
        ("101", "DT_1HDAAA14", "Y", "2018", "2025", "income_x_asset_quintile_asset_debt"),

        # --- 가구주 특성별 자산·부채·소득 (피처 분포) ---
        ("101", "DT_1HDAAA05", "Y", "2018", "2025", "gender_asset_debt"),
        ("101", "DT_1HDAAA06", "Y", "2018", "2025", "age_group_asset_debt"),
        ("101", "DT_1HDAAA07", "Y", "2018", "2025", "marital_status_asset_debt"),
        ("101", "DT_1HDAAA08", "Y", "2018", "2025", "education_asset_debt"),
        ("101", "DT_1HDAAA03", "Y", "2018", "2025", "housing_tenure_asset_debt"),
        ("101", "DT_1HDAAA02", "Y", "2018", "2025", "housing_type_asset_debt"),
        ("101", "DT_1HDAAA04", "Y", "2018", "2025", "household_size_asset_debt"),
        ("101", "DT_1HDAAB01", "Y", "2018", "2025", "income_source_decomposition"),

        # --- 재무건전성 (DSR / 부채상환능력 등) ---
        ("101", "DT_1HDAAA17", "Y", "2018", "2025", "employment_status_financial_health"),
        ("101", "DT_1HDAAA16", "Y", "2018", "2025", "age_group_financial_health"),
        ("101", "DT_1HDAAA18", "Y", "2018", "2025", "income_quintile_financial_health"),
        ("101", "DT_1HDAAA19", "Y", "2018", "2025", "asset_quintile_financial_health"),
        ("101", "DT_1HDAAA20", "Y", "2018", "2025", "net_asset_quintile_financial_health"),

        # --- 담보부채 비율 (LTV proxy) ---
        ("101", "DT_1HDAAC03", "Y", "2018", "2025", "income_quintile_collateral_debt_ratio"),
        ("101", "DT_1HDAAC01", "Y", "2018", "2025", "age_group_collateral_debt_ratio"),
        ("101", "DT_1HDAAC04", "Y", "2018", "2025", "asset_quintile_collateral_debt_ratio"),

        # --- 신용부채 비율 (무담보 부담 proxy) ---
        ("101", "DT_1HDAAC08", "Y", "2018", "2025", "income_quintile_credit_debt_ratio"),
        ("101", "DT_1HDAAC06", "Y", "2018", "2025", "age_group_credit_debt_ratio"),
        ("101", "DT_1HDAAC07", "Y", "2018", "2025", "employment_status_credit_debt_ratio"),
    ]
    out: list[Path] = []
    for org_id, tbl_id, prd_se, start, end, name in targets:
        try:
            df = fetch_table(org_id, tbl_id, prd_se=prd_se, start=start, end=end)
            if df.empty:
                log.warning("KOSIS %s/%s empty", org_id, tbl_id)
                continue
            out.append(save_parquet(df, "kosis", f"{tbl_id}__{name}"))
        except Exception as e:
            log.error("KOSIS fetch failed %s/%s: %s", org_id, tbl_id, e)
    return out
