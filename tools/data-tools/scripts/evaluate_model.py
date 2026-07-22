"""저장된 모델을 holdout 으로 평가 + 공정성 리포트.

예시:
    python -m scripts.evaluate_model --version v1 --data-version v1
"""

from __future__ import annotations

import argparse
import logging
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import pandas as pd  # noqa: E402

from evaluation import fairness, metrics, report  # noqa: E402
from training.dataset import load_dataset, split  # noqa: E402
from training.features import prepare_features, prepare_labels  # noqa: E402
from training.io import load_booster, load_schema, model_dir  # noqa: E402


def _predict_proba(booster, X: pd.DataFrame):
    import xgboost as xgb
    dmat = xgb.DMatrix(X, enable_categorical=True)
    best_iter = getattr(booster, "best_iteration", None)
    if best_iter is not None:
        return booster.predict(dmat, iteration_range=(0, best_iter + 1))
    return booster.predict(dmat)


def _feature_importance(booster, schema) -> list[dict]:
    score = booster.get_score(importance_type="gain")
    items = sorted(score.items(), key=lambda kv: -kv[1])
    return [{"feature": k, "gain": float(v)} for k, v in items]


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", choices=["parquet", "db"], default="parquet")
    parser.add_argument("--data-version", default="v1")
    parser.add_argument("--version", default="v1", help="평가 대상 모델 버전")
    parser.add_argument("--out", default=None, help="리포트 출력 디렉토리 (기본: 모델 디렉토리)")
    args = parser.parse_args()

    booster = load_booster(args.version)
    schema = load_schema(args.version)

    df = load_dataset(source=args.source, version=args.data_version)
    splits = split(df)
    holdout = splits.holdout
    logging.info("holdout: %d rows", len(holdout))

    X = prepare_features(holdout, schema)
    y = prepare_labels(holdout, schema).to_numpy()
    proba = _predict_proba(booster, X)
    pred = proba.argmax(axis=1)

    overall = metrics.overall(y, proba, schema)
    by_seg = metrics.by_segment(y, proba, holdout["applicant_segment"], schema)

    approve_idx = schema.label_classes.index("APPROVE")
    dp = fairness.demographic_parity(pred, holdout["applicant_segment"], approve_idx)
    eo = fairness.equalized_odds(y, pred, holdout["applicant_segment"], approve_idx)
    br = fairness.bias_recovery(holdout, pred, schema)
    od = fairness.occupation_disparity(holdout, pred, schema)
    fi = _feature_importance(booster, schema)

    full_report = {
        "meta": {
            "model_version": args.version,
            "data_version": args.data_version,
            "evaluated_at": datetime.now(timezone.utc).isoformat(),
        },
        "overall": overall,
        "by_segment": by_seg,
        "demographic_parity_segment": dp,
        "equalized_odds_segment": eo,
        "bias_recovery": br,
        "occupation_disparity": od,
        "feature_importance": fi,
    }

    out_dir = Path(args.out) if args.out else model_dir(args.version)
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "evaluation.json"
    md_path = out_dir / "evaluation.md"
    report.to_json(full_report, json_path)
    md_path.write_text(report.to_markdown(full_report), encoding="utf-8")

    logging.info("evaluation.json + evaluation.md → %s", out_dir)
    logging.info("acc=%.4f macro_f1=%.4f DPD=%s bias_recovery=%s",
                 overall["accuracy"], overall["macro_f1"],
                 f"{dp.get('dpd'):.4f}" if dp.get("dpd") is not None else "—",
                 f"{br.get('recovery_rate'):.2%}" if br.get("recovery_rate") is not None else "—")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
