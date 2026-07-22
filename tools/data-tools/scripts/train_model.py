"""자동심사 모델 학습 CLI.

기본 흐름:
1. synthetic_application 파quet/DB 로드
2. split 컬럼으로 train/valid/holdout 분할
3. XGBoost multiclass 학습 (early stopping)
4. holdout 평가 + 모델 저장

예시:
    python -m scripts.train_model                              # parquet v1, 모델 v1
    python -m scripts.train_model --source db --version v2     # DB 소스, 모델 v2
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from training.dataset import load_dataset, split  # noqa: E402
from training.io import save  # noqa: E402
from training.train import TrainConfig, train  # noqa: E402


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", choices=["parquet", "db"], default="parquet")
    parser.add_argument("--data-version", default="v1", help="synthetic_application 데이터 버전")
    parser.add_argument("--version", default="v1", help="저장될 모델 버전")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--n-estimators", type=int, default=600)
    parser.add_argument("--max-depth", type=int, default=6)
    parser.add_argument("--learning-rate", type=float, default=0.08)
    args = parser.parse_args()

    df = load_dataset(source=args.source, version=args.data_version)
    splits = split(df)
    logging.info("splits: %s", splits.summary())

    config = TrainConfig(
        n_estimators=args.n_estimators,
        max_depth=args.max_depth,
        learning_rate=args.learning_rate,
        seed=args.seed,
    )
    result = train(splits, config)
    out = save(result, version=args.version, data_version=args.data_version)

    logging.info("=== done ===")
    logging.info("model dir: %s", out)
    logging.info("holdout acc=%.4f macro_f1=%.4f",
                 result.holdout_metrics["accuracy"],
                 result.holdout_metrics["macro_f1"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
