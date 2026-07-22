"""자동심사 모델 추론 서버 (FastAPI).

학습 산출물(data/models/auto_review_<v>/) 을 로드해 HTTP /predict 엔드포인트로 노출한다.
ai-service(Java) 가 이 서버를 게이트웨이 패턴으로 호출.

엔드포인트
- GET  /health       헬스체크 (모델 로드 여부)
- POST /predict      단건 또는 배치 추론 (decision 모델)
- POST /predict/pd   단건 또는 배치 PD 추론 (binary:logistic + isotonic calibration)
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import xgboost as xgb
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

log = logging.getLogger("inference")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
)

MODEL_VERSION = os.getenv("MODEL_VERSION", "v1")
MODEL_DIR_OVERRIDE = os.getenv("MODEL_DIR")       # 절대 경로 직지정 (Docker 마운트용)
PD_MODEL_VERSION = os.getenv("PD_MODEL_VERSION", "pd_homecredit_v1")
PD_MODEL_DIR_OVERRIDE = os.getenv("PD_MODEL_DIR")


def _resolve_model_dir() -> Path:
    if MODEL_DIR_OVERRIDE:
        return Path(MODEL_DIR_OVERRIDE)
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "data" / "models" / f"auto_review_{MODEL_VERSION}"
        if candidate.exists():
            return candidate
    raise FileNotFoundError(
        f"model dir not found for version={MODEL_VERSION}; set MODEL_DIR env"
    )


def _resolve_pd_model_dir() -> Path | None:
    if PD_MODEL_DIR_OVERRIDE:
        p = Path(PD_MODEL_DIR_OVERRIDE)
        return p if p.exists() else None
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "data" / "models" / f"auto_review_{PD_MODEL_VERSION}"
        if candidate.exists():
            return candidate
    return None


class FeatureSchema:
    """학습 측 training.features.FeatureSchema 와 같은 구조의 경량 구현 (의존성 0)."""

    def __init__(self, d: dict[str, Any]) -> None:
        self.categorical: list[str] = list(d["categorical"])
        self.numeric: list[str] = list(d["numeric"])
        self.boolean: list[str] = list(d["boolean"])
        self.label_classes: list[str] = list(d["label_classes"])
        self.category_codes: dict[str, list[str]] = {
            k: list(v) for k, v in d.get("category_codes", {}).items()
        }

    @property
    def all_features(self) -> list[str]:
        return [*self.categorical, *self.numeric, *self.boolean]


class IsotonicCalibrator:
    """calibrator.json (sklearn IsotonicRegression 직렬화) 로더."""

    def __init__(self, d: dict[str, Any]) -> None:
        self.X_thresholds = np.array(d["X_thresholds"], dtype=float)
        self.y_thresholds = np.array(d["y_thresholds"], dtype=float)

    def predict(self, raw: np.ndarray) -> np.ndarray:
        return np.interp(raw, self.X_thresholds, self.y_thresholds)


class ModelBundle:
    def __init__(self, model_dir: Path) -> None:
        self.model_dir = model_dir
        schema_path = model_dir / "feature_schema.json"
        model_path = model_dir / "model.json"
        metadata_path = model_dir / "metadata.json"
        if not schema_path.exists() or not model_path.exists():
            raise FileNotFoundError(f"incomplete model bundle in {model_dir}")

        self.schema = FeatureSchema(json.loads(schema_path.read_text(encoding="utf-8")))
        self.metadata = (
            json.loads(metadata_path.read_text(encoding="utf-8"))
            if metadata_path.exists() else {}
        )
        self.booster = xgb.Booster()
        self.booster.load_model(str(model_path))
        self.best_iteration: int | None = self.metadata.get("best_iteration")

        calibrator_path = model_dir / "calibrator.json"
        self.calibrator: IsotonicCalibrator | None = None
        if calibrator_path.exists():
            self.calibrator = IsotonicCalibrator(
                json.loads(calibrator_path.read_text(encoding="utf-8"))
            )

        log.info("model loaded: %s (best_iter=%s, calibrated=%s)",
                 model_dir, self.best_iteration, self.calibrator is not None)

    def predict_proba(self, df: pd.DataFrame) -> list[list[float]]:
        X = self._prepare(df)
        dmat = xgb.DMatrix(X, enable_categorical=True)
        if self.best_iteration is not None:
            proba = self.booster.predict(dmat, iteration_range=(0, self.best_iteration + 1))
        else:
            proba = self.booster.predict(dmat)
        return proba.tolist()

    def predict_pd(self, df: pd.DataFrame) -> np.ndarray:
        """binary:logistic raw 확률 → isotonic calibration 적용 PD 반환."""
        X = self._prepare(df)
        dmat = xgb.DMatrix(X, enable_categorical=True)
        if self.best_iteration is not None:
            raw = self.booster.predict(dmat, iteration_range=(0, self.best_iteration + 1))
        else:
            raw = self.booster.predict(dmat)
        if self.calibrator is not None:
            return self.calibrator.predict(raw)
        return raw

    def _prepare(self, df: pd.DataFrame) -> pd.DataFrame:
        out = pd.DataFrame(index=df.index)
        for col in self.schema.categorical:
            series = df[col].astype("string") if col in df.columns else pd.Series([None] * len(df), dtype="string")
            codes = self.schema.category_codes.get(col)
            out[col] = pd.Categorical(series, categories=codes) if codes else series.astype("category")
        for col in self.schema.numeric:
            val = df[col] if col in df.columns else pd.Series([None] * len(df))
            out[col] = pd.to_numeric(val, errors="coerce").astype("float32")
        for col in self.schema.boolean:
            val = df[col] if col in df.columns else pd.Series([False] * len(df))
            out[col] = val.fillna(False).astype("int8")
        return out[self.schema.all_features]


# --- Pydantic IO ----------------------------------------------------------

class PredictRequest(BaseModel):
    """단건 또는 배치. features 는 raw 키-값 dict 의 리스트.

    누락 키는 NaN/None 처리.
    """
    features: list[dict[str, Any]] = Field(..., min_length=1, max_length=10_000)


class Prediction(BaseModel):
    decision: str
    score: float = Field(description="예측 결정 클래스의 확률(0~1)")
    proba: dict[str, float]


class PredictResponse(BaseModel):
    model_config = {"protected_namespaces": ()}
    model_version: str
    predictions: list[Prediction]


class PdPrediction(BaseModel):
    pd_score: float = Field(description="P(default_within_12m=1) — isotonic 보정 후")
    decision: str = Field(description="PD threshold 기준 HIGH/LOW")


class PdPredictResponse(BaseModel):
    model_config = {"protected_namespaces": ()}
    model_version: str
    threshold: float
    calibrated: bool
    predictions: list[PdPrediction]


class HealthResponse(BaseModel):
    model_config = {"protected_namespaces": ()}
    status: str
    model_version: str
    best_iteration: int | None
    holdout_accuracy: float | None
    n_features: int


# --- App lifecycle --------------------------------------------------------

_bundle: ModelBundle | None = None
_pd_bundle: ModelBundle | None = None
app = FastAPI(title="auto-review inference", version="1.0.0")

from app.ocr_router import router as ocr_router            # noqa: E402
from app.extract_router import router as extract_router    # noqa: E402
from app.forgery_router import router as forgery_router    # noqa: E402
app.include_router(ocr_router)
app.include_router(extract_router)
app.include_router(forgery_router)

_PD_DEFAULT_THRESHOLD = 0.12


@app.on_event("startup")
def _load() -> None:
    global _bundle, _pd_bundle
    _bundle = ModelBundle(_resolve_model_dir())
    pd_dir = _resolve_pd_model_dir()
    if pd_dir is not None:
        try:
            _pd_bundle = ModelBundle(pd_dir)
        except Exception as e:
            log.warning("PD model load failed (will return 503 on /predict/pd): %s", e)
    else:
        log.info("PD model dir not found — /predict/pd will return 503")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    if _bundle is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    holdout_acc = None
    try:
        holdout_acc = float(_bundle.metadata["holdout_metrics"]["accuracy"])
    except (KeyError, TypeError):
        pass
    return HealthResponse(
        status="UP",
        model_version=MODEL_VERSION,
        best_iteration=_bundle.best_iteration,
        holdout_accuracy=holdout_acc,
        n_features=len(_bundle.schema.all_features),
    )


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest) -> PredictResponse:
    if _bundle is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    df = pd.DataFrame(req.features)
    proba = _bundle.predict_proba(df)
    classes = _bundle.schema.label_classes

    preds: list[Prediction] = []
    for row in proba:
        top = max(range(len(row)), key=lambda i: row[i])
        preds.append(Prediction(
            decision=classes[top],
            score=float(row[top]),
            proba={cls: float(p) for cls, p in zip(classes, row)},
        ))
    return PredictResponse(model_version=MODEL_VERSION, predictions=preds)


@app.post("/predict/pd", response_model=PdPredictResponse)
def predict_pd(req: PredictRequest) -> PdPredictResponse:
    if _pd_bundle is None:
        raise HTTPException(status_code=503, detail="PD model not loaded")
    df = pd.DataFrame(req.features)
    pd_scores = _pd_bundle.predict_pd(df)
    calibrated = _pd_bundle.calibrator is not None
    threshold = float(
        _pd_bundle.metadata.get("pd_threshold", _PD_DEFAULT_THRESHOLD)
    )

    preds: list[PdPrediction] = []
    for score in pd_scores:
        preds.append(PdPrediction(
            pd_score=float(score),
            decision="HIGH" if float(score) >= threshold else "LOW",
        ))
    return PdPredictResponse(
        model_version=PD_MODEL_VERSION,
        threshold=threshold,
        calibrated=calibrated,
        predictions=preds,
    )
