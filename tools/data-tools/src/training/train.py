"""XGBoost multiclass 학습 + holdout 평가.

XGBoost 2.x 의 native categorical 지원을 사용한다(`enable_categorical=True`).
타겟 클래스: APPROVE / CONDITIONAL / REJECT.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

import numpy as np
import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    classification_report,
    confusion_matrix,
    f1_score,
)

from .dataset import Splits
from .features import FeatureSchema, fit_categories, prepare_features, prepare_labels

log = logging.getLogger(__name__)


@dataclass
class TrainConfig:
    n_estimators: int = 600
    max_depth: int = 6
    learning_rate: float = 0.08
    min_child_weight: float = 1.0
    subsample: float = 0.9
    colsample_bytree: float = 0.9
    reg_lambda: float = 1.0
    early_stopping_rounds: int = 50
    seed: int = 42

    def to_xgb_params(self) -> dict[str, Any]:
        return {
            "objective": "multi:softprob",
            "num_class": len(FeatureSchema().label_classes),
            "tree_method": "hist",
            "max_depth": self.max_depth,
            "learning_rate": self.learning_rate,
            "min_child_weight": self.min_child_weight,
            "subsample": self.subsample,
            "colsample_bytree": self.colsample_bytree,
            "reg_lambda": self.reg_lambda,
            "eval_metric": ["mlogloss", "merror"],
            "seed": self.seed,
        }


@dataclass
class TrainResult:
    booster: Any  # xgboost.Booster
    schema: FeatureSchema
    config: TrainConfig
    best_iteration: int
    holdout_metrics: dict[str, Any]
    valid_metrics: dict[str, Any] = field(default_factory=dict)


def _evaluate(booster, X: pd.DataFrame, y: pd.Series, schema: FeatureSchema) -> dict[str, Any]:
    import xgboost as xgb

    dmat = xgb.DMatrix(X, label=y, enable_categorical=True)
    proba = booster.predict(dmat, iteration_range=(0, booster.best_iteration + 1))
    pred = proba.argmax(axis=1)
    y_arr = y.to_numpy()

    # per-class one-vs-rest PR-AUC
    pr_auc = {}
    for i, cls in enumerate(schema.label_classes):
        y_bin = (y_arr == i).astype(int)
        if y_bin.sum() == 0 or y_bin.sum() == len(y_bin):
            pr_auc[cls] = None
            continue
        pr_auc[cls] = float(average_precision_score(y_bin, proba[:, i]))

    cm = confusion_matrix(y_arr, pred, labels=list(range(len(schema.label_classes))))

    return {
        "accuracy": float(accuracy_score(y_arr, pred)),
        "macro_f1": float(f1_score(y_arr, pred, average="macro")),
        "pr_auc_per_class": pr_auc,
        "confusion_matrix": cm.tolist(),
        "confusion_matrix_labels": list(schema.label_classes),
        "n_samples": int(len(y_arr)),
        "classification_report": classification_report(
            y_arr, pred,
            labels=list(range(len(schema.label_classes))),
            target_names=schema.label_classes,
            output_dict=True,
            zero_division=0,
        ),
    }


def train(splits: Splits, config: TrainConfig | None = None) -> TrainResult:
    import xgboost as xgb

    config = config or TrainConfig()
    # 학습 데이터에서 카테고리 순서 fix → 추론에서 동일 코드 보장
    schema = fit_categories(splits.train, FeatureSchema())
    log.info("fit_categories: %s",
             {k: len(v) for k, v in schema.category_codes.items()})

    X_train = prepare_features(splits.train, schema)
    y_train = prepare_labels(splits.train, schema)
    X_valid = prepare_features(splits.valid, schema)
    y_valid = prepare_labels(splits.valid, schema)
    X_holdout = prepare_features(splits.holdout, schema)
    y_holdout = prepare_labels(splits.holdout, schema)

    dtrain = xgb.DMatrix(X_train, label=y_train, enable_categorical=True)
    dvalid = xgb.DMatrix(X_valid, label=y_valid, enable_categorical=True)

    log.info(
        "training XGBoost: n_train=%d n_valid=%d n_features=%d",
        len(X_train), len(X_valid), X_train.shape[1],
    )

    evals_result: dict[str, dict[str, list[float]]] = {}
    booster = xgb.train(
        params=config.to_xgb_params(),
        dtrain=dtrain,
        num_boost_round=config.n_estimators,
        evals=[(dtrain, "train"), (dvalid, "valid")],
        early_stopping_rounds=config.early_stopping_rounds,
        evals_result=evals_result,
        verbose_eval=50,
    )

    log.info("best_iteration=%d best_mlogloss=%.4f",
             booster.best_iteration,
             evals_result["valid"]["mlogloss"][booster.best_iteration])

    valid_metrics = _evaluate(booster, X_valid, y_valid, schema)
    holdout_metrics = _evaluate(booster, X_holdout, y_holdout, schema)
    log.info("valid:   acc=%.4f macro_f1=%.4f", valid_metrics["accuracy"], valid_metrics["macro_f1"])
    log.info("holdout: acc=%.4f macro_f1=%.4f", holdout_metrics["accuracy"], holdout_metrics["macro_f1"])

    return TrainResult(
        booster=booster,
        schema=schema,
        config=config,
        best_iteration=int(booster.best_iteration),
        holdout_metrics=holdout_metrics,
        valid_metrics=valid_metrics,
    )
