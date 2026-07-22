"""공공데이터포털 (data.go.kr) 로더.

데이터셋마다 endpoint·파라미터·응답 포맷이 달라 단일 함수로 추상화 불가.
각 데이터셋용 fetch 함수를 개별로 정의.

공통 인증: query param `serviceKey={PUBLIC_DATA_API_KEY}` (URL-encoded form)
"""

from __future__ import annotations

import logging
import xml.etree.ElementTree as ET
from pathlib import Path

import pandas as pd

from .base import http_get_text, save_parquet
from .config import KEYS

log = logging.getLogger(__name__)


def _xml_to_records(xml_text: str, item_tag: str = "item") -> list[dict]:
    """XML 응답을 dict 리스트로. 각 item 의 자식 태그를 컬럼으로."""
    root = ET.fromstring(xml_text)
    records: list[dict] = []
    for item in root.iter(item_tag):
        rec = {child.tag: (child.text or "") for child in item}
        records.append(rec)
    return records


def fetch_kdic_savings_bank() -> Path | None:
    """예금보험공사 저축은행 주요 재무현황 (data id 15026726).

    실제 endpoint 는 데이터셋 페이지의 '활용가이드' 참조. 사용자 키 발급 후
    승인된 URL 로 교체 필요. 아래는 일반적인 KDIC OpenAPI 패턴 예시.
    """
    key = KEYS.require("data_go_kr")
    # 실제 endpoint 는 데이터셋 활용신청 후 발급되는 운영 URL 로 교체
    url = "https://apis.data.go.kr/B190001/savingsBankFinanceService/getSavingsBankFinanceList"
    params = {
        "serviceKey": key,
        "pageNo": "1",
        "numOfRows": "1000",
        "_type": "xml",
    }
    try:
        text = http_get_text(url, params=params)
        records = _xml_to_records(text)
        if not records:
            log.warning("KDIC savings bank: empty response")
            return None
        df = pd.DataFrame(records)
        return save_parquet(df, "data_go_kr", "15026726__kdic_savings_bank")
    except Exception as e:
        log.error("KDIC fetch failed: %s", e)
        return None


def fetch_fsc_credit_card() -> Path | None:
    """금융위원회 금융통계 신용카드사 (data id 15061308)."""
    key = KEYS.require("data_go_kr")
    url = "https://apis.data.go.kr/1160100/service/GetFinancialCardCoInfoService/getFinancialCardCoInfoList"
    params = {
        "serviceKey": key,
        "pageNo": "1",
        "numOfRows": "1000",
        "_type": "xml",
    }
    try:
        text = http_get_text(url, params=params)
        records = _xml_to_records(text)
        if not records:
            return None
        df = pd.DataFrame(records)
        return save_parquet(df, "data_go_kr", "15061308__fsc_credit_card")
    except Exception as e:
        log.error("FSC credit card fetch failed: %s", e)
        return None


def fetch_default_bundle() -> list[Path]:
    out: list[Path] = []
    for f in (fetch_kdic_savings_bank, fetch_fsc_credit_card):
        p = f()
        if p:
            out.append(p)
    return out
