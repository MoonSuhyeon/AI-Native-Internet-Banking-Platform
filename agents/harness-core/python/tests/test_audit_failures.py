"""감사 기록이 실패했을 때 알림이 나가는가.

저장 구현은 감사 실패를 fail-soft 로 처리한다 — 기록이 안 남았다고 고객 응답이나
조사를 막으면 안 되기 때문이다. 그 대가로 실패가 로그 한 줄로만 남았고, 로그 한 줄은
아무도 보지 않는다. 셀 수 있게 알리는 지점을 두고, 그 지점이 실제로 불리는지 지킨다.
(docs/decisions/agent-harness-consolidation.md 다음 순서 2)
"""

from __future__ import annotations

import pytest

from harness_core import (
    AgentAuditEntry,
    add_audit_failure_listener,
    clear_audit_failure_listeners,
    notify_audit_failure,
)
from harness_core.audit_sqlalchemy import SqlAlchemyAgentAuditLog


@pytest.fixture(autouse=True)
def _isolate_listeners():
    """등록은 전역이다. 테스트끼리 새지 않게 앞뒤로 지운다."""
    clear_audit_failure_listeners()
    yield
    clear_audit_failure_listeners()


def _entry(**over) -> AgentAuditEntry:
    base = dict(
        agent_name="test-agent",
        subject_type="TEST_SUBJECT",
        subject_id="1",
    )
    base.update(over)
    return AgentAuditEntry(**base)


class _ExplodingSession:
    """execute 에서 터지는 세션. DB 없이 실패 경로를 만든다."""

    def __init__(self) -> None:
        self.rolled_back = False
        self.closed = False

    def execute(self, *args, **kwargs):
        raise RuntimeError("relation \"harness_audit_log\" does not exist")

    def commit(self) -> None:  # pragma: no cover - 도달하지 않는다
        raise AssertionError("실패 경로에서 commit 이 불리면 안 된다")

    def rollback(self) -> None:
        self.rolled_back = True

    def close(self) -> None:
        self.closed = True


class TestStorageNotifiesOnFailure:
    def test_listener_receives_entry_and_exception(self):
        seen = []
        add_audit_failure_listener(lambda entry, exc: seen.append((entry, exc)))

        session = _ExplodingSession()
        log = SqlAlchemyAgentAuditLog(lambda: session, agent_name="test-agent")
        log.record(_entry(subject_id="42"))

        assert len(seen) == 1
        entry, exc = seen[0]
        assert entry.subject_id == "42"
        assert isinstance(exc, RuntimeError)

    def test_record_still_fails_soft(self):
        """알림을 붙였다고 예외가 다시 밖으로 나가면 안 된다."""
        add_audit_failure_listener(lambda entry, exc: None)

        session = _ExplodingSession()
        log = SqlAlchemyAgentAuditLog(lambda: session, agent_name="test-agent")
        log.record(_entry())  # 예외가 나오면 이 줄에서 실패한다

        assert session.rolled_back
        assert session.closed

    def test_success_does_not_notify(self):
        seen = []
        add_audit_failure_listener(lambda entry, exc: seen.append(entry))

        class _OkSession:
            def execute(self, *a, **k):
                return None

            def commit(self):
                return None

            def rollback(self):  # pragma: no cover
                raise AssertionError("성공 경로에서 rollback 이 불리면 안 된다")

            def close(self):
                return None

        SqlAlchemyAgentAuditLog(lambda: _OkSession(), agent_name="test-agent").record(_entry())
        assert seen == []


class TestNotifyIsSafe:
    """실패를 세는 일이 실패해서 본래 흐름을 무너뜨리면 안 된다.

    감사를 fail-soft 로 만든 이유가 통째로 사라진다.
    """

    def test_listener_exception_does_not_escape(self):
        def _broken(entry, exc):
            raise ValueError("리스너가 터졌다")

        seen = []
        add_audit_failure_listener(_broken)
        add_audit_failure_listener(lambda entry, exc: seen.append(entry))

        notify_audit_failure(_entry(), RuntimeError("원래 실패"))

        # 앞 리스너가 터져도 뒤 리스너까지 간다.
        assert len(seen) == 1

    def test_same_listener_registered_once(self):
        """같은 실패가 두 번 세어지면 지표를 믿을 수 없다."""
        calls = []

        def _listener(entry, exc):
            calls.append(entry)

        add_audit_failure_listener(_listener)
        add_audit_failure_listener(_listener)

        notify_audit_failure(_entry(), RuntimeError("실패"))
        assert len(calls) == 1

    def test_no_listener_is_fine(self):
        notify_audit_failure(_entry(), RuntimeError("실패"))
