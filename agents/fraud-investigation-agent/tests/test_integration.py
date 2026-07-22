"""통합 테스트 — 케이스별 최종 상태 스냅샷 단언 (목 LLM, end-to-end).

각 케이스가 그래프를 끝까지 돌았을 때의 결정적 산출물(도구 경로·우세 가설·종료 유형·
책임 등급·태그·결정적 사실·예산)을 한 번에 고정한다. 회귀 방지용 골든 스냅샷.
"""

from agent.graph import run_investigation
from agent.models import (
    AttackScenario,
    DecisiveFactKind,
    LiabilityGrade,
    RecommendationStatus,
    Tag,
)
from agent.tools import load_case


def _snapshot(case_name: str, budget=None) -> dict:
    state = run_investigation(load_case(case_name), budget=budget)
    rec = state.recommendation
    return {
        "tools": [e.tool for e in state.tool_log],
        "scenario": rec.scenario,
        "status": rec.status,
        "grade": rec.liability_grade,
        "tags": sorted(t.value for t in rec.tags),
        "decisive": state.decisive_fact.kind if state.decisive_fact else None,
        "budget_left": state.budget_left,
        "executed": state.executed_actions,  # HITL 보류 — 비어 있어야
        "evidence_n": len(state.evidence),
    }


def test_case_h1_snapshot():
    snap = _snapshot("case_h1")
    assert snap["tools"] == ["get_device_fingerprint", "get_related_accounts"]
    assert snap["scenario"] == AttackScenario.H1_VOICE_PHISHING
    assert snap["status"] == RecommendationStatus.CONFIRMED
    assert snap["grade"] == LiabilityGrade.L3
    assert snap["tags"] == [Tag.T1_MULE.value, Tag.T2_ORGANIZED.value]
    assert snap["decisive"] is None
    assert snap["executed"] == []          # 에이전트는 권고까지만
    assert snap["evidence_n"] == 2


def test_case_h2_snapshot():
    snap = _snapshot("case_h2")
    assert snap["tools"] == ["get_device_fingerprint", "get_auth_events"]
    assert snap["scenario"] == AttackScenario.H2_ACCOUNT_TAKEOVER
    assert snap["status"] == RecommendationStatus.CONFIRMED
    assert snap["grade"] == LiabilityGrade.L2
    assert snap["decisive"] is None


def test_case_h5_snapshot():
    snap = _snapshot("case_h5")
    assert snap["scenario"] == AttackScenario.H5_BENIGN
    assert snap["status"] == RecommendationStatus.BENIGN
    assert snap["grade"] == LiabilityGrade.L0
    assert snap["tools"][0] == "get_device_fingerprint"


def test_case_death_snapshot():
    snap = _snapshot("case_death")
    assert snap["tools"] == ["get_party"]          # 첫 도구에서 끝
    assert snap["status"] == RecommendationStatus.FAIL_CLOSED
    assert snap["grade"] == LiabilityGrade.L4
    assert snap["decisive"] == DecisiveFactKind.DEATH
    assert snap["budget_left"] > 0                  # 예산 남아도 즉시 종료


def test_case_h2_budget1_provisional_snapshot():
    snap = _snapshot("case_h2", budget=1)
    assert snap["status"] == RecommendationStatus.PROVISIONAL
    assert snap["budget_left"] == 0
    assert snap["evidence_n"] == 1                  # 부분결과(빈손 아님)
