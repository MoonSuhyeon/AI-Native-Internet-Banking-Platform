"""학습 데이터 로드 — parquet 우선, DB(synthetic_application) fallback.

split 컬럼 기반으로 train/valid/holdout 분할을 그대로 사용한다.
"""

from __future__ import annotations

import glob
import logging
from dataclasses import dataclass
from pathlib import Path

import pandas as pd

from loaders.config import PROJECT_ROOT

log = logging.getLogger(__name__)

DEFAULT_PARQUET_DIR = PROJECT_ROOT / "data" / "synthetic" / "applications"


@dataclass
class Splits:
    train: pd.DataFrame
    valid: pd.DataFrame
    holdout: pd.DataFrame

    def summary(self) -> dict[str, int]:
        return {
            "train": len(self.train),
            "valid": len(self.valid),
            "holdout": len(self.holdout),
        }


def _load_parquet(version: str) -> pd.DataFrame:
    target = DEFAULT_PARQUET_DIR / f"synthetic_application_{version}.parquet"
    if not target.exists():
        # version 미지정/오타 대응: 사용 가능한 파일 안내
        existing = sorted(glob.glob(str(DEFAULT_PARQUET_DIR / "synthetic_application_*.parquet")))
        raise FileNotFoundError(
            f"parquet not found: {target}\n"
            f"available: {[Path(p).name for p in existing] or 'none'}\n"
            f"먼저 `python -m scripts.build_synthetic --version {version}` 실행 필요"
        )
    df = pd.read_parquet(target)
    log.info("loaded parquet %s: %d rows × %d cols", target.name, len(df), df.shape[1])
    return df


def _load_db() -> pd.DataFrame:
    """ai_db.synthetic_application 전체 로드. parquet 가 없을 때만 사용."""
    from synthesize.db import _conn  # 내부 헬퍼 재사용
    with _conn() as c:
        df = pd.read_sql("SELECT * FROM synthetic_application", c)
    log.info("loaded DB synthetic_application: %d rows × %d cols", len(df), df.shape[1])
    return df


def load_dataset(source: str = "parquet", version: str = "v1") -> pd.DataFrame:
    if source == "parquet":
        return _load_parquet(version)
    if source == "db":
        return _load_db()
    raise ValueError(f"unknown source: {source} (parquet|db)")


def split(df: pd.DataFrame) -> Splits:
    """split 컬럼 값으로 3-way 분할."""
    if "split" not in df.columns:
        raise KeyError("dataset 에 'split' 컬럼이 없음 — build_synthetic 재실행 필요")
    train = df[df["split"] == "train"].reset_index(drop=True)
    valid = df[df["split"] == "valid"].reset_index(drop=True)
    holdout = df[df["split"] == "holdout"].reset_index(drop=True)
    if min(len(train), len(valid), len(holdout)) == 0:
        raise ValueError(
            f"분할 결과에 빈 split 존재: {{'train': {len(train)}, "
            f"'valid': {len(valid)}, 'holdout': {len(holdout)}}}"
        )
    return Splits(train=train, valid=valid, holdout=holdout)
