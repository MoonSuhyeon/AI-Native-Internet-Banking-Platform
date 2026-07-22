"""KOSIS '통계검색' API 로 가계금융복지조사 통계표 ID 를 동적 탐색."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from loaders.base import http_get_json  # noqa: E402
from loaders.config import KEYS  # noqa: E402

SEARCH = "https://kosis.kr/openapi/statisticsSearch.do"


def search(name: str, page: int = 1, size: int = 30) -> list[dict]:
    key = KEYS.require("kosis")
    params = {
        "method": "getList",
        "apiKey": key,
        "format": "json",
        "jsonVD": "Y",
        "searchNm": name,
        "pageNo": str(page),
        "numOfRows": str(size),
    }
    payload = http_get_json(SEARCH, params=params)
    if isinstance(payload, dict):
        # 에러 응답
        print(f"  search error: {payload}")
        return []
    return payload or []


def main():
    for q in ("가계금융복지조사", "가구당 부채", "처분가능소득"):
        print(f"\n=== query: {q} ===")
        rows = search(q, page=1, size=20)
        if not rows:
            print("  (no rows)")
            continue
        for r in rows[:20]:
            print(
                f"  orgId={r.get('ORG_ID')} tblId={r.get('TBL_ID')} "
                f"name={r.get('TBL_NM')[:60] if r.get('TBL_NM') else '-'}"
            )


if __name__ == "__main__":
    main()
