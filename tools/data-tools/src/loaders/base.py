"""공통 HTTP / 저장 헬퍼."""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any

import pandas as pd
import requests
from tenacity import retry, stop_after_attempt, wait_exponential

from .config import DATA_DIR

log = logging.getLogger(__name__)

# 로그에서 API 키 등 시크릿 마스킹. ECOS 는 URL path 에 키가 박힘.
_SECRET_KEY_NAMES = {"servicekey", "apikey", "service_key", "api_key", "key"}


def _mask_url(url: str) -> str:
    # /api/StatisticSearch/<KEY>/... 패턴에서 KEY 부분만 가림
    return re.sub(r"(/api/[A-Za-z]+/)[A-Za-z0-9]+(/)", r"\1***\2", url)


def _mask_params(params: dict[str, Any] | None) -> dict[str, Any] | None:
    if not params:
        return params
    return {
        k: ("***" if k.lower() in _SECRET_KEY_NAMES else v)
        for k, v in params.items()
    }


@retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=1, max=10),
    reraise=True,
)
def http_get_json(url: str, params: dict[str, Any] | None = None, timeout: int = 30) -> dict:
    """JSON GET 요청. 3회 지수 백오프 재시도."""
    log.info("GET %s params=%s", _mask_url(url), _mask_params(params))
    resp = requests.get(url, params=params, timeout=timeout)
    resp.raise_for_status()
    return resp.json()


@retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=1, max=10),
    reraise=True,
)
def http_get_text(url: str, params: dict[str, Any] | None = None, timeout: int = 30) -> str:
    log.info("GET %s params=%s", _mask_url(url), _mask_params(params))
    resp = requests.get(url, params=params, timeout=timeout)
    resp.raise_for_status()
    return resp.text


def save_parquet(df: pd.DataFrame, source: str, name: str) -> Path:
    """data/external/korean/<source>/<name>.parquet 으로 저장."""
    out_dir = DATA_DIR / source
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{name}.parquet"
    df.to_parquet(out, compression="zstd", index=False)
    log.info("saved %s rows=%d size=%dKB", out, len(df), out.stat().st_size // 1024)
    return out
