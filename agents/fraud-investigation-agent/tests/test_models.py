"""Stage 1 스모크 테스트 — 모델·패키지 import 안정성과 AgentState 기본 동작 확인."""

from agent import models
from agent.models import (
    AgentState,
    Alert,
    AttackScenario,
    DecisiveFact,
    DecisiveFactKind,
    Tag,
    TxContext,
)


def _sample_alert() -> Alert:
    return Alert(
        id="ALT-001",
        account="110-222-333",
        customer_id="CUST-9001",
        tx_context=TxContext(amount=8_000_000, payee="안전계좌", channel="MOBILE"),
        anomaly_score=80.0,
    )


def test_all_submodules_import():
    # langgraph·LLM SDK 미설치 상태에서도 stub 모듈이 깨지지 않아야 한다 (원칙 5).
    import agent.graph  # noqa: F401
    import agent.hypotheses  # noqa: F401
    import agent.llm  # noqa: F401
    import agent.recommend  # noqa: F401
    import agent.tool_matrix  # noqa: F401
    import agent.tools  # noqa: F401

    assert models is not None


def test_agent_state_defaults():
    state = AgentState(alert=_sample_alert())
    assert state.budget_left == 6          # 예산 상한 기본값 (원칙 4)
    assert state.decisive_fact is None
    assert state.scenarios == {}
    assert state.tool_log == []


def test_two_axis_hypothesis_shape():
    state = AgentState(
        alert=_sample_alert(),
        scenarios={
            AttackScenario.H1_VOICE_PHISHING: 0.35,
            AttackScenario.H2_ACCOUNT_TAKEOVER: 0.25,
            AttackScenario.H3_LAUNDERING: 0.15,
            AttackScenario.H4_INSIDER: 0.05,
            AttackScenario.H5_BENIGN: 0.20,
        },
        tags={Tag.T1_MULE: True, Tag.T2_ORGANIZED: False},
    )
    # 축 1: 시나리오는 합 ≈ 1 로 경쟁
    assert abs(sum(state.scenarios.values()) - 1.0) < 1e-9
    # 축 2: 태그는 독립 불리언
    assert state.tags[Tag.T1_MULE] is True


def test_decisive_fact_can_be_set():
    state = AgentState(alert=_sample_alert())
    state.decisive_fact = DecisiveFact(
        kind=DecisiveFactKind.DEATH, source="get_party", detail="END_REASON=DEATH"
    )
    assert state.decisive_fact.kind is DecisiveFactKind.DEATH
