"""모든 한국 공공 데이터 일괄 수집 진입점.

사용:
    python -m scripts.fetch_all
    python -m scripts.fetch_all --source ecos
    python -m scripts.fetch_all --source ecos,kosis
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

# sys.path 에 src 추가하여 'from loaders import ...' 가능하게
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from loaders import data_go_kr, ecos, fisis, kosis  # noqa: E402


SOURCES = {
    "ecos": ecos.fetch_default_bundle,
    "kosis": kosis.fetch_default_bundle,
    "data_go_kr": data_go_kr.fetch_default_bundle,
    "fisis": fisis.fetch_default_bundle,
}


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source",
        default="all",
        help="콤마 구분. 기본 all. 예: ecos,kosis",
    )
    args = parser.parse_args()

    if args.source == "all":
        selected = list(SOURCES.keys())
    else:
        selected = [s.strip() for s in args.source.split(",")]
        unknown = set(selected) - set(SOURCES.keys())
        if unknown:
            parser.error(f"unknown source(s): {unknown}. choices={list(SOURCES.keys())}")

    summary: dict[str, int] = {}
    for src in selected:
        logging.info("=== %s ===", src)
        try:
            paths = SOURCES[src]()
            summary[src] = len(paths)
        except Exception:
            logging.exception("%s failed", src)
            summary[src] = -1

    logging.info("done: %s", summary)
    return 0 if all(v >= 0 for v in summary.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
