"""목표 에이전트 감사 기록 배선.

consultation 과 같은 계약을 쓴다 (docs/decisions/agent-harness-consolidation.md 2-d).
여기 있는 것은 배선뿐이다 — 계약과 저장 구현은 harness_core 가 갖고 있다.

목표 에이전트는 고객에게 **금액과 기간을 권한다.** "월 80만원씩 24개월"
같은 권고가 남지 않으면, 나중에 고객이 그대로 했는데 목표에 못 미쳤을 때
무엇을 근거로 그렇게 권했는지 확인할 방법이 없다.
"""

from __future__ import annotations

import logging

from app.config import settings
from app.database import SessionLocal
from harness_core import AgentAuditEntry, NoOpAgentAuditLog
from harness_core.audit_sqlalchemy import SqlAlchemyAgentAuditLog

logger = logging.getLogger(__name__)

AGENT_NAME = "goal-agent"

#: 무엇에 대한 판단인가. 도메인 식별자는 하네스가 아니라 이쪽이 정한다.
SUBJECT_TYPE = "GOAL_SESSION"

_audit_log = None


def get_audit_log():
    """감사 저장소. 설정에 따라 실제 저장소 또는 NoOp.

    **끄더라도 None 을 돌려주지 않는다.** 기능을 끄는 것이 부르는 쪽을 깨뜨리면 안 된다.
    """
    global _audit_log
    if _audit_log is None:
        if settings.harness_audit_enabled:
            _audit_log = SqlAlchemyAgentAuditLog(SessionLocal, agent_name=AGENT_NAME)
        else:
            logger.warning("하네스 감사 기록이 꺼져 있다 — 에이전트 권고가 남지 않는다")
            _audit_log = NoOpAgentAuditLog()
    return _audit_log


def record_goal_turn(
    *,
    customer_id: str,
    message: str,
    result: dict,
    llm_used: bool,
    trace_id: str | None = None,
) -> None:
    """목표 상담 한 번을 감사에 남긴다.

    ``tool_calls_json`` 에 ``agent_steps`` 를 그대로 담는다. 어떤 도구를 어떤 순서로
    불러 그 결론에 이르렀는지가 사후 조사에서 결론 자체만큼 중요하다.

    ``llm_used`` 가 False 면 API 키가 없어 mock 으로 답한 것이다. 이것을
    ``fallback_reason`` 으로 남기지 않으면 나중에 기록을 봤을 때 모델의 판단과
    고정 응답을 구별할 수 없다.
    """
    entry = AgentAuditEntry(
        agent_name=AGENT_NAME,
        subject_type=SUBJECT_TYPE,
        subject_id=str(customer_id),
        trace_id=trace_id,
        request_json=AgentAuditEntry.json_of({"message": message}),
        output_json=AgentAuditEntry.json_of({
            "agent_type": result.get("agent_type"),
            "need_more_info": result.get("need_more_info"),
            "follow_up_question": result.get("follow_up_question"),
            "feasibility": result.get("feasibility"),
            "strategies": result.get("strategies"),
            "monthly_plan": result.get("monthly_plan"),
            "warning": result.get("warning"),
        }),
        tool_calls_json=AgentAuditEntry.json_of(result.get("agent_steps", [])),
        fallback_reason=None if llm_used else "LLM_KEY_ABSENT_MOCK",
    )
    get_audit_log().record(entry)
