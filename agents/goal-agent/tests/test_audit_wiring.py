"""목표 에이전트의 권고가 감사에 남는가 — docs/decisions/agent-harness-consolidation.md 2-d.

목표 에이전트는 고객에게 **금액과 기간을 권한다.** "월 80만원씩 24개월" 같은 권고가
남지 않으면, 나중에 고객이 그대로 했는데 목표에 못 미쳤을 때 무엇을 근거로 그렇게
권했는지 확인할 방법이 없다.

배선은 손으로 한 번 확인한 것이 전부였다. 이 파일이 생기기 전까지 record_goal_turn
호출을 지워도 깨지는 테스트가 없었다.
"""
from __future__ import annotations

import json

import pytest

from app import agent_goal_chat
from app.audit import AGENT_NAME, SUBJECT_TYPE, record_goal_turn


CUSTOMER = "9001"

#: 에이전트가 돌려주는 결과의 축소판. 감사에 담겨야 하는 필드만 갖췄다.
PLAN_RESULT = {
    "agent_type": "GOAL_PLANNER",
    "need_more_info": False,
    "follow_up_question": None,
    "feasibility": {"achievable": True, "gap": 0},
    "strategies": [{"name": "적금 집중"}],
    "monthly_plan": [{"month": 1, "amount": 800000}],
    "warning": None,
    "agent_steps": [
        {"tool": "get_accounts", "result_summary": "계좌 2건"},
        {"tool": "simulate_savings", "result_summary": "24개월 시뮬레이션"},
    ],
    "message": "월 80만원씩 24개월 모으면 목표 금액에 닿습니다.",
}


def _only_entry(audit_spy):
    assert len(audit_spy.entries) == 1, f"감사 기록 {len(audit_spy.entries)}건"
    return audit_spy.entries[0]


# ─────────────────────────────────────────────────────────────────────────────
# 호출이 붙어 있는가
# ─────────────────────────────────────────────────────────────────────────────

class TestRunGoalAgentRecords:
    """run_goal_agent 는 두 경로(Claude·mock)가 합류하는 지점에서 한 번 남긴다."""

    @pytest.fixture()
    def mock_path(self, monkeypatch):
        """API 키 없음 → mock 경로. 계획 수립 자체는 이 테스트의 관심이 아니다."""
        monkeypatch.setattr(agent_goal_chat.settings, "anthropic_api_key", "")
        monkeypatch.setattr(
            agent_goal_chat, "run_goal_agent_mock",
            lambda db, customer_id, message: dict(PLAN_RESULT),
        )

    def test_records_once(self, audit_spy, mock_path):
        agent_goal_chat.run_goal_agent(None, CUSTOMER, "2년 안에 2천만원 모으고 싶어")
        _only_entry(audit_spy)

    def test_result_is_returned_unchanged(self, audit_spy, mock_path):
        """감사를 남기는 것이 응답을 바꾸지 않는다."""
        result = agent_goal_chat.run_goal_agent(None, CUSTOMER, "2천만원")
        assert result == PLAN_RESULT

    def test_subject_identifies_the_customer(self, audit_spy, mock_path):
        agent_goal_chat.run_goal_agent(None, CUSTOMER, "2천만원")
        entry = _only_entry(audit_spy)
        assert entry.agent_name == AGENT_NAME
        assert entry.subject_type == SUBJECT_TYPE
        assert entry.subject_id == CUSTOMER

    def test_claude_path_records_too(self, audit_spy, monkeypatch):
        """API 키가 있으면 Claude 경로로 가고, 그쪽도 똑같이 남는다."""
        monkeypatch.setattr(agent_goal_chat.settings, "anthropic_api_key", "sk-test")
        monkeypatch.setattr(
            agent_goal_chat, "_run_goal_agent_claude",
            lambda db, customer_id, message: dict(PLAN_RESULT),
        )
        agent_goal_chat.run_goal_agent(None, CUSTOMER, "2천만원")
        _only_entry(audit_spy)


# ─────────────────────────────────────────────────────────────────────────────
# 무엇이 남는가
# ─────────────────────────────────────────────────────────────────────────────

class TestRecordedContent:
    def test_input_message_is_kept(self, audit_spy):
        record_goal_turn(
            customer_id=CUSTOMER, message="2년 안에 2천만원",
            result=PLAN_RESULT, llm_used=True,
        )
        entry = _only_entry(audit_spy)
        assert json.loads(entry.request_json) == {"message": "2년 안에 2천만원"}

    def test_recommendation_is_kept(self, audit_spy):
        """고객에게 권한 내용이 그대로 남아야 한다 — 이 기록의 존재 이유다."""
        record_goal_turn(
            customer_id=CUSTOMER, message="2천만원", result=PLAN_RESULT, llm_used=True,
        )
        recorded = json.loads(_only_entry(audit_spy).output_json)
        assert recorded["monthly_plan"] == PLAN_RESULT["monthly_plan"]
        assert recorded["feasibility"] == PLAN_RESULT["feasibility"]
        assert recorded["strategies"] == PLAN_RESULT["strategies"]
        assert recorded["agent_type"] == "GOAL_PLANNER"

    def test_tool_calls_are_kept(self, audit_spy):
        """어떤 도구를 어떤 순서로 불러 그 결론에 이르렀는지가 결론만큼 중요하다."""
        record_goal_turn(
            customer_id=CUSTOMER, message="2천만원", result=PLAN_RESULT, llm_used=True,
        )
        recorded = json.loads(_only_entry(audit_spy).tool_calls_json)
        assert [step["tool"] for step in recorded] == ["get_accounts", "simulate_savings"]

    def test_follow_up_question_is_kept(self, audit_spy):
        """정보가 모자라 되물은 것도 판단의 일부다."""
        result = dict(PLAN_RESULT, need_more_info=True, follow_up_question="목표 금액이 얼마인가요?")
        record_goal_turn(customer_id=CUSTOMER, message="모으고 싶어", result=result, llm_used=True)
        recorded = json.loads(_only_entry(audit_spy).output_json)
        assert recorded["need_more_info"] is True
        assert recorded["follow_up_question"] == "목표 금액이 얼마인가요?"

    def test_empty_result_still_records(self, audit_spy):
        """결과가 비어도 기록은 남는다 — 판단은 성공했는데 기록만 사라지는 것이 가장 나쁘다."""
        record_goal_turn(customer_id=CUSTOMER, message="?", result={}, llm_used=True)
        entry = _only_entry(audit_spy)
        assert entry.output_json and entry.tool_calls_json == "[]"


# ─────────────────────────────────────────────────────────────────────────────
# 모델의 판단인가 고정 응답인가
# ─────────────────────────────────────────────────────────────────────────────

class TestFallbackReason:
    """llm_used=False 는 API 키가 없어 mock 으로 답했다는 뜻이다.

    이것을 남기지 않으면 나중에 기록을 봤을 때 모델의 판단과 고정 응답을 구별할 수
    없다. 규제 대응에서 "이 권고는 모델이 한 것인가"는 답할 수 있어야 하는 질문이다.
    """

    def test_mock_answer_is_marked(self, audit_spy):
        record_goal_turn(
            customer_id=CUSTOMER, message="2천만원", result=PLAN_RESULT, llm_used=False,
        )
        assert _only_entry(audit_spy).fallback_reason == "LLM_KEY_ABSENT_MOCK"

    def test_model_answer_has_no_fallback_reason(self, audit_spy):
        record_goal_turn(
            customer_id=CUSTOMER, message="2천만원", result=PLAN_RESULT, llm_used=True,
        )
        assert _only_entry(audit_spy).fallback_reason is None

    def test_run_goal_agent_marks_mock_path(self, audit_spy, monkeypatch):
        """키 유무가 fallback_reason 으로 이어지는 경로 전체를 확인한다."""
        monkeypatch.setattr(agent_goal_chat.settings, "anthropic_api_key", "")
        monkeypatch.setattr(
            agent_goal_chat, "run_goal_agent_mock",
            lambda db, customer_id, message: dict(PLAN_RESULT),
        )
        agent_goal_chat.run_goal_agent(None, CUSTOMER, "2천만원")
        assert _only_entry(audit_spy).fallback_reason == "LLM_KEY_ABSENT_MOCK"


# ─────────────────────────────────────────────────────────────────────────────
# 꺼도 부르는 쪽이 깨지지 않는가
# ─────────────────────────────────────────────────────────────────────────────

class TestAuditDisabled:
    """감사를 꺼도 None 이 아니라 NoOp 이 들어간다.

    Java 쪽에서 감사를 끄자 빈이 사라져 의존하는 쪽이 통째로 깨진 적이 있다.
    기능을 끄는 것이 부르는 쪽을 깨뜨리면 안 된다.
    """

    def test_returns_noop_not_none(self, monkeypatch):
        import app.audit as audit_mod
        from harness_core import NoOpAgentAuditLog

        monkeypatch.setattr(audit_mod, "_audit_log", None)
        monkeypatch.setattr(audit_mod.settings, "harness_audit_enabled", False)

        log = audit_mod.get_audit_log()
        assert isinstance(log, NoOpAgentAuditLog)

    def test_record_is_a_no_op_when_disabled(self, monkeypatch):
        import app.audit as audit_mod

        monkeypatch.setattr(audit_mod, "_audit_log", None)
        monkeypatch.setattr(audit_mod.settings, "harness_audit_enabled", False)

        # 예외 없이 지나가야 한다.
        record_goal_turn(
            customer_id=CUSTOMER, message="2천만원", result=PLAN_RESULT, llm_used=True,
        )
