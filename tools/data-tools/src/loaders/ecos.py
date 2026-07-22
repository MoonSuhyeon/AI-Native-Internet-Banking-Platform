"""한국은행 ECOS Open API 로더.

API 명세: https://ecos.bok.or.kr/api/
URL 패턴:
  https://ecos.bok.or.kr/api/StatisticSearch/{KEY}/json/kr/{START_IDX}/{END_IDX}/{STAT_CODE}/{CYCLE}/{START_PERIOD}/{END_PERIOD}

CYCLE: A(연), S(반기), Q(분기), M(월), SM(반월), D(일)
"""

from __future__ import annotations

import logging
from pathlib import Path

import pandas as pd

from .base import http_get_json, save_parquet
from .config import KEYS

log = logging.getLogger(__name__)

BASE = "https://ecos.bok.or.kr/api/StatisticSearch"
PAGE_SIZE = 1000


def fetch_statistic(stat_code: str, cycle: str, start: str, end: str) -> pd.DataFrame:
    """ECOS 통계 시계열을 수집한다. 페이지네이션으로 전체 수집.

    Args:
        stat_code: 통계표 코드 (예: '151Y005' 가계신용, '104Y014' 예금취급기관 가계대출)
        cycle: 'A'/'Q'/'M'/'D' 등
        start: 시작 기간 (예: '2020', '2020Q1', '202001', '20200101')
        end: 종료 기간

    Returns:
        TIME / DATA_VALUE / ITEM_NAME1 등 컬럼을 갖는 DataFrame
    """
    key = KEYS.require("ecos")
    rows: list[dict] = []
    start_idx = 1
    while True:
        end_idx = start_idx + PAGE_SIZE - 1
        url = f"{BASE}/{key}/json/kr/{start_idx}/{end_idx}/{stat_code}/{cycle}/{start}/{end}"
        payload = http_get_json(url)

        # ECOS 응답 형식: {"StatisticSearch": {"list_total_count": N, "row": [...]}}
        # 에러 형식: {"RESULT": {"CODE": "INFO-200", "MESSAGE": "..."}}
        if "RESULT" in payload:
            msg = payload["RESULT"].get("MESSAGE", "unknown error")
            code = payload["RESULT"].get("CODE", "")
            if "INFO-200" in code:  # 데이터 없음
                log.warning("ECOS no data for %s: %s", stat_code, msg)
                break
            raise RuntimeError(f"ECOS error {code}: {msg}")

        node = payload.get("StatisticSearch", {})
        batch = node.get("row", []) or []
        rows.extend(batch)
        total = int(node.get("list_total_count", 0) or 0)
        if not batch or len(rows) >= total:
            break
        start_idx += PAGE_SIZE

    df = pd.DataFrame(rows)
    if not df.empty and "DATA_VALUE" in df.columns:
        df["DATA_VALUE"] = pd.to_numeric(df["DATA_VALUE"], errors="coerce")
    return df


def fetch_default_bundle() -> list[Path]:
    """자동심사에 쓸 기본 macro feature 시계열 일괄 수집."""
    targets = [
        # (stat_code, cycle, start, end, friendly_name)
        ("151Y005", "Q", "2018Q1", "2026Q4", "household_credit"),
        ("104Y014", "M", "201801", "202612", "deposit_inst_household_loan"),
        ("722Y001", "M", "201801", "202612", "policy_rate"),
    ]
    out: list[Path] = []
    for stat_code, cycle, start, end, name in targets:
        try:
            df = fetch_statistic(stat_code, cycle, start, end)
            if df.empty:
                log.warning("ECOS %s empty, skipping", stat_code)
                continue
            out.append(save_parquet(df, "ecos", f"{stat_code}__{name}"))
        except Exception as e:
            log.error("ECOS fetch failed for %s: %s", stat_code, e)
    return out
