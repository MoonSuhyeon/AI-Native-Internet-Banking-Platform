"""모델 + 피처 스키마 + 메타데이터 저장/로드.

XGBoost native JSON 형식으로 직렬화 → 언어 중립적(Python 외 ai-service 에서도 사용 가능).
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path

from loaders.config import PROJECT_ROOT

from .features import FeatureSchema
from .train import TrainResult

log = logging.getLogger(__name__)

DEFAULT_MODELS_DIR = PROJECT_ROOT / "data" / "models"


def model_dir(version: str) -> Path:
    return DEFAULT_MODELS_DIR / f"auto_review_{version}"


def save(result: TrainResult, version: str, data_version: str = "v1") -> Path:
    out = model_dir(version)
    out.mkdir(parents=True, exist_ok=True)

    model_path = out / "model.json"
    result.booster.save_model(str(model_path))

    schema_path = out / "feature_schema.json"
    schema_path.write_text(
        json.dumps(result.schema.to_dict(), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    metadata = {
        "model_version": version,
        "data_version": data_version,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "framework": "xgboost",
        "config": result.config.__dict__,
        "best_iteration": result.best_iteration,
        "valid_metrics": result.valid_metrics,
        "holdout_metrics": result.holdout_metrics,
    }
    metadata_path = out / "metadata.json"
    metadata_path.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    log.info("model saved to %s (3 files: model.json, feature_schema.json, metadata.json)", out)
    return out


def load_schema(version: str) -> FeatureSchema:
    path = model_dir(version) / "feature_schema.json"
    return FeatureSchema.from_dict(json.loads(path.read_text(encoding="utf-8")))


def load_booster(version: str):
    import xgboost as xgb
    booster = xgb.Booster()
    booster.load_model(str(model_dir(version) / "model.json"))
    return booster
