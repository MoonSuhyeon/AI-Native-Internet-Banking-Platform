"""저장된 사건 전부를 돌려 채점한다 — 회귀 비교의 기준선.

    python scripts/evaluate_cases.py

프롬프트·모델·도구 표를 바꾼 뒤 다시 돌려 앞의 출력과 비교하면 그것이 회귀 테스트다.
채점이 결정적이라 같은 입력이면 같은 표가 나온다 — LLM 심판이었다면 매번 달라져
비교 자체가 성립하지 않는다.

기본 LLM 은 목이라 실제 API 를 치지 않는다(CLAUDE.md 원칙 5).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from agent.evaluation import ToolChoiceVerdict, score_investigation  # noqa: E402
from agent.graph import run_investigation  # noqa: E402
from agent.tools import load_case  # noqa: E402

CASES_DIR = Path(__file__).resolve().parents[1] / "data" / "cases"


def main() -> int:
    rows = []
    for path in sorted(CASES_DIR.glob("*.json")):
        case = load_case(path.stem)
        state = run_investigation(case)
        rows.append((path.stem, score_investigation(state)))

    # 어느 플래너를 잰 것인지 밝힌다. 목과 실제 LLM 은 다른 값이 나오고, 그걸 안
    # 적으면 목의 점수를 에이전트의 점수로 읽게 된다.
    provider = os.getenv("TRIAGE_LLM_PROVIDER") or "mock (TRIAGE_LLM_PROVIDER 미설정)"
    print(f"플래너: {provider}")
    print()

    width = max(len(name) for name, _ in rows)
    print(f"{'사건':<{width}}  예산  판별   판정")
    print("-" * (width + 40))

    dirty = 0
    for name, score in rows:
        counts = {v: score.count(v) for v in ToolChoiceVerdict if score.count(v)}
        detail = " · ".join(f"{v.value}×{n}" for v, n in counts.items())
        flag = "" if score.fail_closed_respected else "  ⚠ fail-closed 위반"
        if not score.clean:
            dirty += 1
        print(
            f"{name:<{width}}  {score.budget_used:>4}"
            f"  {score.productive_ratio:>5.0%}   {detail}{flag}"
        )

    print("-" * (width + 40))
    print(f"사건 {len(rows)}건 · 흠 있는 조사 {dirty}건")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
