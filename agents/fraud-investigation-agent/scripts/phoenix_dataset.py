"""사건을 Phoenix Dataset 으로 올리고 채점 결과를 실험으로 남긴다.

    python scripts/phoenix_dataset.py                    # 올리고 1회 실행
    python scripts/phoenix_dataset.py --label 매트릭스개정  # 무엇을 바꾼 실행인지 적기

**`evaluate_cases.py` 와 무엇이 다른가.** 그쪽은 지금 성적을 보여 주고,
`tests/test_evaluation_baseline.py` 는 바뀌었는지를 빌드에서 막는다. 여기는
**이력**이다 — 실행마다 사건별 결과가 Phoenix 에 남아, 프롬프트·모델·도구 표를
바꿔 온 자취를 나란히 볼 수 있다. 세 개는 겹치지 않는다.

    evaluate_cases.py    지금 얼마인가       (사람이 본다)
    baseline 테스트      바뀌었나            (빌드가 막는다)
    여기                 어떻게 바뀌어 왔나   (이력이 쌓인다)

채점은 `evaluation.py` 가 하므로 여기서도 **LLM 심판을 쓰지 않는다.** 실험 결과가
결정적이어야 두 실행의 차이가 "바꾼 것 때문" 이라고 말할 수 있다.

Phoenix 가 안 떠 있으면 아무것도 하지 않고 안내만 남긴다 — 평가 도구가 없다고
조사 코드가 영향을 받지는 않는다.
"""

from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from agent.evaluation import ToolChoiceVerdict, score_investigation  # noqa: E402
from agent.graph import run_investigation  # noqa: E402
from agent.tools import CASES_DIR, load_case  # noqa: E402

DATASET_NAME = "fraud-investigation-cases"


def _client():
    """Phoenix 클라이언트. 미설치·미기동이면 ``None`` 과 이유를 돌려준다."""
    try:
        from phoenix.client import Client
    except ImportError:
        return None, (
            "phoenix.client 가 없다. 평가 도구라 런타임 의존성이 아니다:\n"
            "  pip install -r requirements-dev.txt"
        )
    base = os.getenv("PHOENIX_BASE_URL", "http://localhost:6006")
    try:
        client = Client(base_url=base)
        client.datasets.list()  # 실제로 닿는지 확인한다
        return client, None
    except Exception as exc:
        return None, f"Phoenix({base})에 닿지 않는다: {exc}\n  docker compose up -d phoenix"


def _run_all() -> list[dict]:
    """사건을 전부 돌려 채점 결과를 모은다."""
    rows = []
    for path in sorted(CASES_DIR.glob("*.json")):
        case = load_case(path.stem)
        state = run_investigation(case)
        score = score_investigation(state)
        rows.append(
            {
                "case": path.stem,
                "trace_id": state.trace_id,
                "budget_used": score.budget_used,
                "productive_ratio": round(score.productive_ratio, 3),
                "fail_closed_respected": score.fail_closed_respected,
                "verdicts": [v.value for v in score.verdicts],
                **{
                    f"n_{v.value.lower()}": score.count(v)
                    for v in ToolChoiceVerdict
                },
            }
        )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--label",
        default="",
        help="이 실행에서 무엇을 바꿨는지. 나중에 두 실행을 비교할 때 이것만 남는다.",
    )
    args = parser.parse_args()

    client, reason = _client()
    if client is None:
        print(reason)
        return 1

    rows = _run_all()
    planner = os.getenv("TRIAGE_LLM_PROVIDER") or "mock"
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    label = args.label or "라벨 없음"

    dataset = client.datasets.create_dataset(
        name=f"{DATASET_NAME}-{stamp}",
        dataset_description=f"플래너={planner} · {label}",
        inputs=[{"case": r["case"]} for r in rows],
        outputs=[
            {
                "productive_ratio": r["productive_ratio"],
                "budget_used": r["budget_used"],
                "fail_closed_respected": r["fail_closed_respected"],
                "verdicts": r["verdicts"],
            }
            for r in rows
        ],
        metadata=[
            # 추적 id 를 같이 올린다. 사건별 점수에서 그 실행의 도구 호출 순서로
            # 건너뛸 수 있어야 "왜 이 점수인가" 를 답할 수 있다.
            {"trace_id": r["trace_id"], "planner": planner, "label": label}
            for r in rows
        ],
    )

    total = sum(r["budget_used"] for r in rows)
    productive = sum(
        r["budget_used"] * r["productive_ratio"] for r in rows
    )
    print(f"올림: {dataset.name} · 사건 {len(rows)}건")
    print(f"플래너: {planner} · 라벨: {label}")
    print(f"전체 판별 비율: {productive / total:.0%} ({total}회 중)")
    print(f"확인: {os.getenv('PHOENIX_BASE_URL', 'http://localhost:6006')}/datasets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
