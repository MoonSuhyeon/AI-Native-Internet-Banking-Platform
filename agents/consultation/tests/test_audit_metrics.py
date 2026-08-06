"""감사 실패가 Prometheus 지표로 올라가는가.

감사는 fail-soft 다 — 기록이 안 남았다고 상담을 막지 않는다. 그 대가로 실패가 로그
한 줄로만 남았고, 감사가 조용히 멈춰도 알아챌 방법이 없었다
(docs/decisions/agent-harness-consolidation.md 다음 순서 2).

여기서 지키는 것은 배선이다 — 저장이 실패하면 지표가 오르는가, 레이블이 알람이
기대하는 모양인가(infra/prometheus/alerts.yml 의 harness-audit 그룹).
"""
from __future__ import annotations

import pytest
from prometheus_client import REGISTRY

from app.audit import AGENT_NAME
from harness_core import AgentAuditEntry
from harness_core.audit_sqlalchemy import SqlAlchemyAgentAuditLog

METRIC = "harness_audit_failure_total"


def _count(agent: str, decision_kind: str) -> float:
    value = REGISTRY.get_sample_value(
        METRIC, {"agent": agent, "decision_kind": decision_kind}
    )
    return value or 0.0


class _ExplodingSession:
    def execute(self, *args, **kwargs):
        raise RuntimeError('relation "harness_audit_log" does not exist')

    def commit(self):  # pragma: no cover - 도달하지 않는다
        raise AssertionError("실패 경로에서 commit 이 불리면 안 된다")

    def rollback(self):
        return None

    def close(self):
        return None


@pytest.fixture()
def failing_log():
    """DB 없이 감사 실패를 만드는 저장소.

    app.audit 을 임포트하는 것만으로 리스너가 등록된다 — conftest 가 이미 임포트한다.
    """
    return SqlAlchemyAgentAuditLog(lambda: _ExplodingSession(), agent_name=AGENT_NAME)


def _entry(**over) -> AgentAuditEntry:
    base = dict(agent_name=AGENT_NAME, subject_type="CONSULT_SESSION", subject_id="1")
    base.update(over)
    return AgentAuditEntry(**base)


def test_failure_increments_counter(failing_log):
    before = _count(AGENT_NAME, "DECISION")
    failing_log.record(_entry())
    assert _count(AGENT_NAME, "DECISION") == before + 1


def test_action_execution_counted_separately(failing_log):
    """실행 확정이 안 남는 것은 판단 시도가 안 남는 것과 심각도가 다르다.

    알람이 decision_kind 로 갈라 보므로 레이블이 섞이면 안 된다.
    """
    before_decision = _count(AGENT_NAME, "DECISION")
    before_execution = _count(AGENT_NAME, "ACTION_EXECUTION")

    failing_log.record(_entry(decision_kind="ACTION_EXECUTION"))

    assert _count(AGENT_NAME, "ACTION_EXECUTION") == before_execution + 1
    assert _count(AGENT_NAME, "DECISION") == before_decision


def test_success_does_not_increment():
    class _OkSession:
        def execute(self, *a, **k):
            return None

        def commit(self):
            return None

        def rollback(self):  # pragma: no cover
            raise AssertionError("성공 경로에서 rollback 이 불리면 안 된다")

        def close(self):
            return None

    before = _count(AGENT_NAME, "DECISION")
    SqlAlchemyAgentAuditLog(lambda: _OkSession(), agent_name=AGENT_NAME).record(_entry())
    assert _count(AGENT_NAME, "DECISION") == before
