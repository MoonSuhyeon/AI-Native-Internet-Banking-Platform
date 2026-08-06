"""유실된 감사 기록을 되돌릴 수 있는가.

저장이 실패하면 fail-soft 로 넘어간다. 그래서 DB 가 잠깐 흔들리면 그 사이의 판단
기록은 영구 유실이었다 — 지표와 알람은 유실을 알려줄 뿐 되돌려주지 못한다
(docs/decisions/agent-harness-consolidation.md 다음 순서 3).
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from harness_core import (
    AgentAuditEntry,
    FileAuditSpool,
    add_audit_failure_listener,
    clear_audit_failure_listeners,
)
from harness_core.audit_sqlalchemy import SqlAlchemyAgentAuditLog


@pytest.fixture(autouse=True)
def _isolate_listeners():
    clear_audit_failure_listeners()
    yield
    clear_audit_failure_listeners()


@pytest.fixture()
def spool(tmp_path) -> FileAuditSpool:
    return FileAuditSpool(tmp_path / "audit-spool.jsonl")


def _entry(**over) -> AgentAuditEntry:
    base = dict(
        agent_name="test-agent",
        subject_type="TEST_SUBJECT",
        subject_id="1",
        request_json='{"message": "안녕"}',
        output_json='{"answer": "네"}',
    )
    base.update(over)
    return AgentAuditEntry(**base)


class _FlakyLog:
    """켰다 껐다 할 수 있는 저장소. 실패 시 실제 저장소처럼 알림을 낸다."""

    def __init__(self, working: bool = True) -> None:
        self.working = working
        self.recorded: list[AgentAuditEntry] = []

    def record(self, entry: AgentAuditEntry) -> None:
        from harness_core import notify_audit_failure

        if self.working:
            self.recorded.append(entry)
            return
        notify_audit_failure(entry, RuntimeError("DB 없음"))


# ─────────────────────────────────────────────────────────────────────────────
# 쌓기
# ─────────────────────────────────────────────────────────────────────────────

class TestSpoolCollects:
    def test_failure_is_written(self, spool):
        add_audit_failure_listener(spool.on_failure)
        _FlakyLog(working=False).record(_entry(subject_id="7"))

        pending = spool.pending()
        assert [e.subject_id for e in pending] == ["7"]

    def test_payload_survives_round_trip(self, spool):
        recorded_at = datetime(2026, 3, 1, 12, 30, tzinfo=timezone.utc)
        original = _entry(
            trace_id="trace-1",
            tool_calls_json='[{"tool": "get_accounts"}]',
            fallback_reason="LLM_KEY_ABSENT_MOCK",
            decision_kind="ACTION_EXECUTION",
            actor_id="emp-9001",
            actor_roles='["FRAUD_OFFICER"]',
            recorded_at=recorded_at,
            pii_masked=False,
        )
        spool.append(original)

        restored = spool.pending()[0]
        assert restored == original

    def test_recorded_at_is_not_rewritten(self, spool):
        """복구 시각으로 덮으면 기록은 남아도 타임라인이 거짓이 된다."""
        long_ago = datetime.now(timezone.utc) - timedelta(days=3)
        spool.append(_entry(recorded_at=long_ago))

        assert spool.pending()[0].recorded_at == long_ago

    def test_broken_line_is_skipped(self, spool):
        spool.append(_entry(subject_id="1"))
        with spool.path.open("a", encoding="utf-8") as fp:
            fp.write("{망가진 줄\n")
        spool.append(_entry(subject_id="2"))

        # 깨진 줄 하나 때문에 나머지를 못 되돌리면 복구 장치의 의미가 없다.
        assert [e.subject_id for e in spool.pending()] == ["1", "2"]

    def test_missing_file_is_empty(self, spool):
        assert spool.pending() == []


# ─────────────────────────────────────────────────────────────────────────────
# 되돌리기
# ─────────────────────────────────────────────────────────────────────────────

class TestReplay:
    def test_restores_into_storage(self, spool):
        add_audit_failure_listener(spool.on_failure)
        log = _FlakyLog(working=False)
        log.record(_entry(subject_id="1"))
        log.record(_entry(subject_id="2"))

        log.working = True
        restored, remaining = spool.replay(log)

        assert (restored, remaining) == (2, 0)
        assert [e.subject_id for e in log.recorded] == ["1", "2"]

    def test_spool_is_emptied_after_success(self, spool):
        spool.append(_entry())
        spool.replay(_FlakyLog(working=True))

        assert spool.pending() == []
        assert not spool.path.exists()

    def test_still_failing_entries_stay(self, spool):
        add_audit_failure_listener(spool.on_failure)
        spool.append(_entry(subject_id="1"))

        restored, remaining = spool.replay(_FlakyLog(working=False))

        assert (restored, remaining) == (0, 1)
        assert [e.subject_id for e in spool.pending()] == ["1"]

    def test_replay_failure_does_not_duplicate(self, spool):
        """되돌리는 중에 난 실패를 다시 쌓으면 복구했을 때 중복 행이 생긴다."""
        add_audit_failure_listener(spool.on_failure)
        spool.append(_entry(subject_id="1"))

        spool.replay(_FlakyLog(working=False))
        spool.replay(_FlakyLog(working=False))

        assert len(spool.pending()) == 1

    def test_replay_is_repeatable(self, spool):
        log = _FlakyLog(working=True)
        spool.append(_entry(subject_id="1"))

        spool.replay(log)
        restored, remaining = spool.replay(log)

        # 두 번째 호출은 할 일이 없다 — 성공한 것을 또 넣으면 감사에 중복이 생긴다.
        assert (restored, remaining) == (0, 0)
        assert [e.subject_id for e in log.recorded] == ["1"]

    def test_listener_is_detached_after_replay(self, spool):
        """되돌리기용 임시 리스너가 남아 있으면 이후 실패를 잘못 센다."""
        import harness_core.audit_failures as failures

        spool.append(_entry())
        before = len(failures._listeners)
        spool.replay(_FlakyLog(working=True))

        assert len(failures._listeners) == before


class TestRealStorageIntegration:
    """실제 저장 구현(SQLAlchemy)의 실패가 스풀로 들어가는가."""

    def test_sqlalchemy_failure_is_spooled(self, spool):
        class _ExplodingSession:
            def execute(self, *a, **k):
                raise RuntimeError("relation does not exist")

            def commit(self):  # pragma: no cover
                raise AssertionError("실패 경로에서 commit 이 불리면 안 된다")

            def rollback(self):
                return None

            def close(self):
                return None

        add_audit_failure_listener(spool.on_failure)
        log = SqlAlchemyAgentAuditLog(lambda: _ExplodingSession(), agent_name="test-agent")
        log.record(_entry(subject_id="99"))

        assert [e.subject_id for e in spool.pending()] == ["99"]
