"""금감원 FISIS Open API 로더.

API 명세: https://fisis.fss.or.kr/openapi/
공식 endpoint 는 키 발급 후 활용신청 단계에서 제공됨. ECOS·data.go.kr 와
중복되는 통계가 많아 우선순위는 낮음. 골격만 유지.
"""

from __future__ import annotations

import logging
from pathlib import Path

import pandas as pd

from .base import http_get_json, save_parquet
from .config import KEYS

log = logging.getLogger(__name__)


def fetch_default_bundle() -> list[Path]:
    """현재 placeholder. 실제 endpoint 와 통계 ID 가 확정되면 채울 것."""
    try:
        KEYS.require("fisis")
    except RuntimeError as e:
        log.info("FISIS skipped: %s", e)
        return []
    log.info("FISIS loader: not implemented yet (ECOS/data.go.kr 와 중복되어 보류)")
    return []
