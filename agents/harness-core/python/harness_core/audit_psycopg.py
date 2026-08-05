"""psycopg 기반 감사 저장소 — ORM 없이 쓰는 에이전트용.

``audit_sqlalchemy`` 와 같은 계약을 구현한다. 둘로 나뉜 이유는 5단계에서 드러난 것이다:
``fraud-investigation-agent`` 는 상태를 갖지 않는 사이드카라 ORM 도 세션 팩토리도 없다.
SQLAlchemy 구현을 쓰라고 하면 감사를 붙이기 위해 ORM 전체를 얹어야 하고,
그러면 무거워서 안 붙이게 된다 — 감사가 없는 에이전트가 생기는 것이 애초의 문제였다.

**계약이 같고 구현이 둘인 것은 계약이 깨진 것이 아니다.** 저장 방식은 계약이 아니다.
어떤 필드를 어떤 테이블에 남기는가가 계약이고, 그것은 양쪽이 동일하다.
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
) VALUES (%s, %s, %s, %s, %s::jsonb, %s::jsonb, %s::jsonb, %s, %s, %s, %s,
          %s, %s, %s::jsonb)
"""

_SELECT_COLUMNS = """
SELECT agent_name, subject_type, subject_id, trace_id,
       request_json::text, output_json::text, tool_calls_json::text,
       raw_llm_response, pii_masked, fallback_reason, recorded_at,
       decision_kind, actor_id, actor_roles::text
  FROM harness_audit_log
"""

_LATEST_SQL = _SELECT_COLUMNS + """
 WHERE subject_type = %s AND subject_id = %s
 ORDER BY recorded_at DESC, id DESC
 LIMIT 1
"""

_LATEST_BY_KIND_SQL = _SELECT_COLUMNS + """
 WHERE subject_type = %s AND subject_id = %s AND decision_kind = %s
 ORDER BY recorded_at DESC, id DESC
 LIMIT 1
"""


class PsycopgAgentAuditLog:
    """``harness_audit_log`` 에 psycopg 로 직접 쓰는 구현.

    커넥션을 들고 있지 않고 기록할 때마다 연다. 사이드카는 요청이 드물어
    풀을 유지하는 비용이 이득보다 크고, 무엇보다 감사가 끊긴 커넥션 때문에
    조사 응답을 막으면 안 된다.

    Args:
        dsn: libpq 접속 문자열
        agent_name: 이 에이전트 이름
    """

    def __init__(self, dsn: str, agent_name: str) -> None:
        self._dsn = dsn
        self._agent_name = agent_name

    def record(self, entry: AgentAuditEntry) -> None:
        import psycopg  # 지연 import — 계약만 쓰는 쪽에 부담을 주지 않는다

        params = (
            entry.agent_name or self._agent_name,
            entry.subject_type,
            entry.subject_id,
            entry.trace_id,
            entry.request_json,
            entry.output_json,
            entry.tool_calls_json,
            entry.raw_llm_response,
            entry.pii_masked,
            entry.fallback_reason,
            entry.recorded_at,
            entry.decision_kind,
            entry.actor_id,
            entry.actor_roles,
        )
        try:
            with psycopg.connect(self._dsn) as conn:
                with conn.cursor() as cur:
                    cur.execute(_INSERT_SQL, params)
        except Exception:
            # 감사 실패가 조사를 막으면 안 된다. 그러나 조용히 넘어가서도 안 된다 —
            # 기록이 빠진 사실 자체가 사후 조사에서 드러나야 한다.
            logger.exception(
                "감사 기록 실패 agent=%s subject=%s/%s trace=%s",
                params[0], entry.subject_type, entry.subject_id, entry.trace_id,
            )

    def find_latest(
        self, subject_type: str, subject_id: str, decision_kind: str | None = None
    ) -> AgentAuditEntry | None:
        import psycopg

        if decision_kind is None:
            sql, params = _LATEST_SQL, (subject_type, subject_id)
        else:
            sql, params = _LATEST_BY_KIND_SQL, (subject_type, subject_id, decision_kind)

        with psycopg.connect(self._dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                row = cur.fetchone()
        if row is None:
            return None
        return AgentAuditEntry(*row)
