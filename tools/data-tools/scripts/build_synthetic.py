"""합성 신청서 생성 CLI.

기본은 PoC 1만 건. 운영 시 --n 140000 으로 확장.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from synthesize.build import build  # noqa: E402
from synthesize.db import bulk_load, save_parquet  # noqa: E402


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=10_000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--version", default="v1")
    parser.add_argument("--no-db", action="store_true", help="DB 적재 생략 (parquet 만)")
    args = parser.parse_args()

    df = build(n=args.n, seed=args.seed)
    save_parquet(df, version=args.version)

    if not args.no_db:
        try:
            bulk_load(df)
        except Exception:
            logging.exception("DB load failed; parquet 은 저장됨")
            return 1

    # 요약
    logging.info("=== summary ===")
    logging.info("decision: %s", df["oracle_decision"].value_counts().to_dict())
    logging.info("segment:  %s", df["applicant_segment"].value_counts().to_dict())
    logging.info("split:    %s", df["split"].value_counts().to_dict())
    logging.info("bias injected: %d  noise flipped: %d",
                 int(df["oracle_bias_injected"].sum()),
                 int(df["oracle_noise_flipped"].sum()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
