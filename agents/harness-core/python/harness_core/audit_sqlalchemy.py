"""SQLAlchemy 기반 감사 저장소.

계약(``audit``)과 분리해 둔다. fraud-investigation-agent 에는 DB 의존성이 없어서
계약만 가져가고 이 모듈은 가져가지 않는다.

consultation·goal-agent 는 이미 SQLAlchemy 세션을 갖고 있으므로 그 세션을 받아 쓴다.
새 커넥션 풀을 여기서 만들지 않는 이유는, 감사가 자기 풀을 따로 들면
에이전트의 커넥션 상한 계산이 조용히 어긋나기 때문이다.
"""

from __future__ import annotations

import logging

from .audit import AgentAuditEntry

logger = logging.getLogger(__name__)

_INSERT_SQL = """
INSERT INTO harness_audit_log (
    agent_name, subject_type, subject_id, trace_id,
    request_json, output_json, tool_calls_json,
    raw_llm_response, pii_masked, fallback_reason, recorded_at,
    decision_kind, actor_id, actor_roles
) VALUES (
    :agent_name, :subject_type, :subject_id, :trace_id,
    CAST(:request_json AS JSONB), CAST(:output_json AS JSONB), CAST(:tool_calls_json AS JSONB),
    :raw_llm_response, :pii_masked, :fallback_reason, :recorded_at,
    :decision_kind, :actor_id, CAST(:actor_roles AS JSONB)
)
"""

_SELECT_COLUMNS = """
SELECT agent_name, subject_type, subject_id, trace_id,
       request_json::text AS request_json,
       output_json::text  AS output_json,
       tool_calls_json::text AS tool_calls_json,
       raw_llm_response, pii_masked, fallback_reason, recorded_at,
       decision_kind, actor_id, actor_roles::text AS actor_roles
  FROM harness_audit_log
"""

_LATEST_SQL = _SELECT_COLUMNS + """
 WHERE subject_type = :subject_type AND subject_id = :subject_id
 ORDER BY recorded_at DESC, id DESC
 LIMIT 1
"""

_LATEST_BY_KIND_SQL = _SELECT_COLUMNS + """
 WHERE subject_type = :subject_type AND subject_id = :subject_id
   AND decision_kind = :decision_kind
 ORDER BY recorded_at DESC, id DESC
 LIMIT 1
"""


class SqlAlchemyAgentAuditLog:
    """``harness_audit_log`` 에 쓰는 구현.

    Args:
        session_factory: 호출할 때마다 새 Session 을 주는 것 (``SessionLocal``).
            **호출자의 세션을 재사용하지 않는다.** 판단이 롤백되더라도
            "그런 판단을 시도했다"는 사실은 남아야 하기 때문이다.
        agent_name: 이 에이전트의 이름. 기록마다 넘기지 않도록 여기서 고정한다.
    """

    def __init__(self, session_factory, agent_name: str) -> None:
        self._session_factory = session_factory
        self._agent_name = agent_name

    def record(self, entry: AgentAuditEntry) -> None:
        from sqlalchemy import text  # 지연 import — 계약만 쓰는 쪽에 부담을 주지 않는다

        params = {
            "agent_name": entry.agent_name or self._agent_name,
            "subject_type": entry.subject_type,
            "subject_id": entry.subject_id,
            "trace_id": entry.trace_id,
            "request_json": entry.request_json,
            "output_json": entry.output_json,
            "tool_calls_json": entry.tool_calls_json,
            "raw_llm_response": entry.raw_llm_response,
            "pii_masked": entry.pii_masked,
            "fallback_reason": entry.fallback_reason,
            "recorded_at": entry.recorded_at,
            "decision_kind": entry.decision_kind,
            "actor_id": entry.actor_id,
            "actor_roles": entry.actor_roles,
        }
        session = self._session_factory()
        try:
            session.execute(text(_INSERT_SQL), params)
            session.commit()
        except Exception:
            session.rollback()
            # 감사 실패가 고객 응답을 막으면 안 된다. 그러나 조용히 넘어가서도 안 된다 —
            # 기록이 빠진 사실 자체가 사후 조사에서 드러나야 한다.
            logger.exception(
                "감사 기록 실패 agent=%s subject=%s/%s trace=%s",
                params["agent_name"], entry.subject_type, entry.subject_id, entry.trace_id,
            )
        finally:
            session.close()

    def find_latest(
        self, subject_type: str, subject_id: str, decision_kind: str | None = None
    ) -> AgentAuditEntry | None:
        from sqlalchemy import text

        params = {"subject_type": subject_type, "subject_id": subject_id}
        sql = _LATEST_SQL
        if decision_kind is not None:
            sql = _LATEST_BY_KIND_SQL
            params["decision_kind"] = decision_kind

        session = self._session_factory()
        try:
            row = session.execute(text(sql), params).mappings().first()
            if row is None:
                return None
            return AgentAuditEntry(**dict(row))
        finally:
            session.close()
