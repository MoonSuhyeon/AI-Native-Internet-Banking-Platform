"""Stage 5 테스트 — §16-5 게이트 종료 유형 + HITL interrupt/RBAC."""

from agent.graph import approve_and_execute, investigate, run_investigation
from agent.models import AttackScenario, LiabilityGrade, RecommendationStatus
from agent.tools import load_case


def test_h1_confirmed():
    state = run_investigation(load_case("case_h1"))
    rec = state.recommendation
    assert rec.status == RecommendationStatus.CONFIRMED
    assert rec.scenario == AttackScenario.H1_VOICE_PHISHING


def test_h5_benign_conclusion():
    state = run_investigation(load_case("case_h5"))
    rec = state.recommendation
    assert rec.scenario == AttackScenario.H5_BENIGN
    assert rec.status == RecommendationStatus.BENIGN  # 오탐 결론


def test_budget_exhaustion_is_provisional_not_empty():
    # 예산 1 로 강제 소진 → 잠정 권고(부분결과). 빈손 아님.
    state = run_investigation(load_case("case_h2"), budget=1)
    rec = state.recommendation
    assert rec.status == RecommendationStatus.PROVISIONAL
    assert state.budget_left == 0
    # 부분결과: 수집 증거·근거 사슬·미확인 경합이 들어있다
    assert state.evidence, "증거가 비어있으면 안 됨"
    assert any("잠정" in line for line in rec.rationale_chain)
    assert any("미확인 경합" in line for line in rec.rationale_chain)


def test_death_triggers_fail_closed_even_with_budget_left():
    state = run_investigation(load_case("case_death"))
    rec = state.recommendation
    assert state.decisive_fact is not None          # 조사 중 사망 확인
    assert rec.status == RecommendationStatus.FAIL_CLOSED
    assert rec.liability_grade == LiabilityGrade.L4  # 즉시 차단 권고
    assert state.budget_left > 0                      # 예산 남아도 즉시 종료


def test_hitl_holds_until_approval_then_rbac_gates_execution():
    # 권고까지만: interrupt 로 멈춰 동작 미실행
    graph, config, state = investigate(load_case("case_h1"), thread_id="hitl-1")
    assert state.recommendation is not None
    assert state.executed_actions == []  # 에이전트는 권고까지만

    # 승인 + 올바른 RBAC → 동작(목) 실행
    final = approve_and_execute(graph, config, actor_roles=["FRAUD_OFFICER"])
    assert any("실행(목)" in x for x in final.executed_actions)


def test_hitl_rbac_denies_without_role():
    graph, config, _ = investigate(load_case("case_h1"), thread_id="hitl-2")
    denied = approve_and_execute(graph, config, actor_roles=["TELLER"])
    assert any("거부됨(RBAC)" in x for x in denied.executed_actions)


def test_hitl_no_approval_no_execution():
    graph, config, _ = investigate(load_case("case_h1"), thread_id="hitl-3")
    result = approve_and_execute(graph, config, actor_roles=["FRAUD_OFFICER"], approved=False)
    assert any("HITL 미승인" in x for x in result.executed_actions)
