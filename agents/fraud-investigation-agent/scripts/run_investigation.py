"""Fraud Investigation Agent — rich CLI 러너 (트레이스 시각화).

한 사건을 받아 §16-1 루프를 단계별로 돌리며 출력한다:
  매 루프  : 현재 분포(막대) → 선택 도구+이유 → 도구 결과 → 갱신 분포 → 게이트 결정
  종료 시  : 최종 가설·태그·근거사슬·책임등급·권고 + "분석가 승인 대기(HITL)"

이 러너는 graph.py 의 노드와 **동일한 빌딩블록**(hypotheses·planner·tools·recommend)을
같은 순서로 호출한다 — 단계별 렌더링을 위해 루프를 밖으로 펼친 것뿐, 로직은 동일.

사용법:
  python scripts/run_investigation.py --case case_h1
  python scripts/run_investigation.py --case case_h2 --step
  python scripts/run_investigation.py --compare        # h1 vs h2 나란히
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

# scripts/ 에서 바로 실행 가능하도록 src 를 경로에 추가
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))


def _load_dotenv(path: Path) -> None:
    """serve.py 와 동일 — 의존성 없이 .env 를 os.environ 에 주입(이미 설정된 OS env 보존).
    CLI 러너도 실연결 토글(TRIAGE_REAL_TOOLS 등)을 .env 로 읽게 한다."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip())


_load_dotenv(ROOT / ".env")

try:  # Windows 콘솔(cp949)에서 한글/막대 출력 안전
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from rich.console import Console  # noqa: E402
from rich.panel import Panel  # noqa: E402
from rich.table import Table  # noqa: E402
from rich.text import Text  # noqa: E402

from agent import hypotheses  # noqa: E402
from agent.graph import ACCOUNT_TOOLS, CLOSE_THRESHOLD, CONFIRM_THRESHOLD  # noqa: E402
from agent.llm import get_llm_client  # noqa: E402
from agent.models import AgentState, Case  # noqa: E402
from agent.planner import plan_next_tool  # noqa: E402
from agent.recommend import build_recommendation  # noqa: E402
from agent.tool_matrix import TOOL_MATRIX  # noqa: E402
from agent.tools import TOOLS, load_case  # noqa: E402


def _gate(state: AgentState) -> str:
    """graph.gate 와 동일한 §16-5 우선순위."""
    if state.decisive_fact:
        return "recommend"
    if state.scenarios and max(state.scenarios.values()) >= CONFIRM_THRESHOLD:
        return "recommend"
    if state.budget_left <= 0:
        return "recommend"
    return "plan"


def _bars(console: Console, title: str, scenarios: dict, closed: list) -> None:
    table = Table(title=title, show_header=False, box=None, pad_edge=False)
    top = max(scenarios, key=scenarios.get) if scenarios else None
    for s, v in sorted(scenarios.items(), key=lambda kv: -kv[1]):
        bar = "█" * round(v * 24)
        style = "green" if s is top else ("dim" if s in closed else "white")
        tag = " (닫힘)" if s in closed else ""
        table.add_row(
            Text(s.value, style=style),
            Text(f"{v:0.2f}", style=style),
            Text(bar + tag, style=style),
        )
    console.print(table)


def run_case(case: Case, console: Console, step: bool = False, render: bool = True):
    llm = get_llm_client()
    state = AgentState(alert=case.alert)

    if render:
        a = case.alert
        console.rule(f"[bold]사건 {a.id}[/] · {case.description or ''}")
        console.print(
            f"알림: {a.account} / {a.customer_id} · "
            f"{a.tx_context.amount:,}원 → {a.tx_context.payee} "
            f"({a.tx_context.channel}) · 이상도 {a.anomaly_score:g}\n"
        )

    # hypothesize
    state.scenarios = hypotheses.init_scenarios()
    state.tags = hypotheses.init_tags()
    if render:
        _bars(console, "초기 분포 (균등)", state.scenarios, state.closed_scenarios)

    loop = 0
    while True:
        loop += 1
        before = dict(state.scenarios)

        # plan (도구 선택 + 이유 로그, 예산 차감)
        tool = plan_next_tool(state, llm, TOOL_MATRIX)
        state.budget_left -= 1
        reason = state.tool_log[-1].reason

        # act (조회 도구 호출 → Evidence)
        fn = TOOLS[tool]
        ident = state.alert.account if tool in ACCOUNT_TOOLS else state.alert.customer_id
        result = fn(case, ident)
        state.evidence.append(result.to_evidence())
        if result.decisive_fact:
            state.decisive_fact = result.decisive_fact

        # observe (분포·태그 갱신, ≤0.15 후보 닫기)
        scenarios, tags = hypotheses.observe(state)
        state.scenarios, state.tags = scenarios, tags
        state.closed_scenarios = [s for s, v in scenarios.items() if v <= CLOSE_THRESHOLD]

        decision = _gate(state)

        if render:
            console.print()
            console.rule(f"[bold cyan]루프 {loop}[/] · 예산 {state.budget_left} 남음")
            console.print(f"[bold]선택 도구[/]: [yellow]{tool}[/]")
            console.print(f"[bold]이유[/]: {reason}")
            console.print(f"[bold]결과[/]: {result.signal}")
            if result.decisive_fact:
                console.print(
                    f"[bold red]결정적 사실[/]: {result.decisive_fact.kind.value} "
                    "→ fail-closed"
                )
            _bars(console, "갱신 분포", state.scenarios, state.closed_scenarios)
            gate_txt = "종료 → 권고" if decision == "recommend" else "경합 → 루프백(재계획)"
            console.print(f"[bold]게이트[/]: {gate_txt}")

        if decision == "recommend":
            break
        if step and render and sys.stdin.isatty():
            console.input("[dim]다음 루프 — Enter…[/]")

    # recommend
    rec = build_recommendation(state, llm.generate_recommendation(state))
    state.recommendation = rec
    if render:
        _render_recommendation(console, rec)
    return state


_DECISIVE_LABEL = {
    "DEATH": "사망계좌 · 권리자 적격성(L4)",
    "GUARDIANSHIP": "성년후견 · 단독거래 무효(L4)",
}


def _headline(rec) -> str:
    """fail-closed 면 경합 1위(미확정) 대신 결정적 사실을 헤드라인으로 (프론트와 동일)."""
    if rec.status.value == "FAIL_CLOSED" and rec.decisive_fact:
        k = rec.decisive_fact.kind.value
        return _DECISIVE_LABEL.get(k, k)
    return rec.scenario.value


def _render_recommendation(console: Console, rec) -> None:
    lines = [
        f"[bold]최종 가설[/]: [green]{_headline(rec)}[/]",
        f"[bold]종료 유형[/]: {rec.status.value}",
        f"[bold]태그[/]: {', '.join(t.value for t in rec.tags) or '—'}",
        f"[bold]책임 등급[/]: [magenta]{rec.liability_grade.value}[/]  (§12)",
        "",
        "[bold]근거 사슬[/]:",
    ]
    lines += [f"  {i}. {line}" for i, line in enumerate(rec.rationale_chain, 1)]
    lines += ["", "[bold]권고 동작[/] (제안 — 실행 아님):"]
    lines += [f"  · {a.type.value} — {a.reason}" for a in rec.actions]
    lines += ["", "[bold yellow]▶ 분석가 승인 대기 (HITL) — 에이전트는 권고까지만[/]"]
    console.print(Panel("\n".join(lines), title="권고", border_style="green"))


def compare(console: Console) -> None:
    """case_h1 vs case_h2 — 같은 구조, 다른 경로를 나란히."""
    console.rule("[bold]같은 알림 구조, 다른 경로 — 결과가 다음 행동을 바꾼다[/]")
    paths = {}
    for name in ("case_h1", "case_h2"):
        state = run_case(load_case(name), console, render=False)
        paths[name] = (
            [(e.tool, e.reason) for e in state.tool_log],
            state.recommendation,
        )

    table = Table(title="조사 경로 비교", show_lines=True)
    table.add_column("단계")
    table.add_column("case_h1 (평소기기→수취망→H1)", style="green")
    table.add_column("case_h2 (낯선기기→인증→H2)", style="cyan")
    h1, h2 = paths["case_h1"][0], paths["case_h2"][0]
    for i in range(max(len(h1), len(h2))):
        c1 = f"{h1[i][0]}\n[dim]{h1[i][1]}[/]" if i < len(h1) else ""
        c2 = f"{h2[i][0]}\n[dim]{h2[i][1]}[/]" if i < len(h2) else ""
        table.add_row(str(i + 1), c1, c2)
    r1, r2 = paths["case_h1"][1], paths["case_h2"][1]
    table.add_row(
        "결론",
        f"{r1.scenario.value} ({r1.status.value}, {r1.liability_grade.value})",
        f"{r2.scenario.value} ({r2.status.value}, {r2.liability_grade.value})",
    )
    console.print(table)


def main() -> None:
    parser = argparse.ArgumentParser(description="Fraud Investigation Agent 러너")
    parser.add_argument("--case", default="case_h1", help="data/cases/<name>.json")
    parser.add_argument("--step", action="store_true", help="한 루프씩 멈춤")
    parser.add_argument("--compare", action="store_true", help="case_h1 vs case_h2 나란히")
    args = parser.parse_args()

    console = Console()
    if args.compare:
        compare(console)
    else:
        run_case(load_case(args.case), console, step=args.step)


if __name__ == "__main__":
    main()
