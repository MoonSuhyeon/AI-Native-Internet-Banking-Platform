"""행동 검증 — 에이전트가 (1) 결과에 따라 경로를 바꾸고, (2) 모든 선택에 이유를 남기며,
(3) 결정적 사실엔 예산·가설과 무관하게 즉시 fail-closed 하는지.
"""

from agent.graph import run_investigation
from agent.models import DecisiveFactKind
from agent.tools import load_case


def _tools(case_name: str) -> list[str]:
    return [e.tool for e in run_investigation(load_case(case_name)).tool_log]


def test_path_diverges():
    """같은 구조, 다른 경로 — device 결과가 다음 행동을 바꾼다."""
    h1 = _tools("case_h1")
    h2 = _tools("case_h2")
    # 두 사건의 도구 순서가 다르다
    assert h1 != h2
    assert h1[1] == "get_related_accounts"   # 평소기기 → 수취망 조사(H1)
    assert h2[1] == "get_auth_events"        # 낯선기기 → 인증 조사(H2)

    # case_h1 에서 device 결과만 '낯선 기기'로 뒤집으면 2번째 도구가 auth_events 로 바뀐다
    flipped = _tools("case_h1_flipped")
    assert flipped[0] == "get_device_fingerprint"
    assert flipped[1] == "get_auth_events"


def test_every_choice_logged():
    """모든 도구 선택에 10자 이상 이유가 tool_log 에 남아야 한다(설명가능성)."""
    for name in ("case_h1", "case_h2", "case_h5", "case_h1_flipped", "case_deceased"):
        state = run_investigation(load_case(name))
        assert state.tool_log, f"{name}: tool_log 비어있음"
        for entry in state.tool_log:
            assert len(entry.reason.strip()) >= 10, (name, entry.tool, entry.reason)


def test_deceased_fail_closed():
    """get_party 가 사망 반환 시 예산 남고 가설 max<0.75 라도 즉시 fail-closed 종료."""
    state = run_investigation(load_case("case_deceased"))

    termination = state.recommendation.status.value.lower()
    assert termination == "fail_closed"
    assert state.decisive_fact is not None
    assert state.decisive_fact.kind == DecisiveFactKind.DEATH
    assert state.budget_left > 0                       # 예산 남았는데도
    assert max(state.scenarios.values()) < 0.75        # 가설은 확정 아님인데도
