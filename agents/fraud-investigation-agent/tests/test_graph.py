"""Stage graph 테스트 — end-to-end 루프.

핵심: **같은 구조, 다른 경로.** 동일 그래프가 케이스에 따라 도구 순서를 동적으로
정한다. case_h1 → device→related (보이스피싱 확정), case_h2 → device→auth (탈취).
각 선택에 이유가 붙고, 2회 루프 후 확정되는지 확인.
"""

import sys

from agent.graph import run_investigation
from agent.models import AttackScenario, LiabilityGrade
from agent.tools import load_case

# Windows 콘솔(cp949)에서 -s 로 한글/대시 출력 시 인코딩 오류 방지
try:  # pragma: no cover
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def test_case_h1_confirms_h1_in_two_loops():
    state = run_investigation(load_case("case_h1"))

    tools = [e.tool for e in state.tool_log]
    print("case_h1 tool_log:", [(e.tool, e.reason) for e in state.tool_log])

    # 도구 순서가 device → related 로 동적으로 정해졌다
    assert tools[0] == "get_device_fingerprint"
    assert tools[1] == "get_related_accounts"
    # 2회 루프(plan/act/observe)로 종료
    assert len(tools) == 2
    # H1 확정
    assert state.recommendation is not None
    assert state.recommendation.scenario == AttackScenario.H1_VOICE_PHISHING
    assert state.scenarios[AttackScenario.H1_VOICE_PHISHING] >= 0.75
    # 각 선택에 이유가 붙어 있다 (설명가능성)
    assert all(e.reason for e in state.tool_log)


def test_case_h2_takes_different_path_device_then_auth():
    state = run_investigation(load_case("case_h2"))

    tools = [e.tool for e in state.tool_log]
    print("case_h2 tool_log:", [(e.tool, e.reason) for e in state.tool_log])

    # 같은 그래프인데 경로가 다르다: device → auth
    assert tools[0] == "get_device_fingerprint"
    assert tools[1] == "get_auth_events"
    assert state.recommendation is not None
    assert state.recommendation.scenario == AttackScenario.H2_ACCOUNT_TAKEOVER


def test_recommendation_has_rationale_and_grade():
    state = run_investigation(load_case("case_h1"))
    rec = state.recommendation
    # 근거 사슬이 도구선택 이유 + 증거로 엮였는지
    assert any("선택:" in line for line in rec.rationale_chain)
    assert any("증거:" in line for line in rec.rationale_chain)
    # 책임 등급(§12) 부여 — H1 = L3
    assert rec.liability_grade == LiabilityGrade.L3
    assert rec.actions  # 권고 동작(제안) 존재


def test_benign_case_terminates_without_high_risk():
    state = run_investigation(load_case("case_h5"))
    print("case_h5 tool_log:", [e.tool for e in state.tool_log])
    assert state.recommendation is not None
    # 정상 케이스는 고위험으로 확정되지 않는다
    assert state.recommendation.scenario != AttackScenario.H2_ACCOUNT_TAKEOVER
