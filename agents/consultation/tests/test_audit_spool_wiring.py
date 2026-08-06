"""감사 유실을 되돌릴 수 있게 배선돼 있는가.

harness_core 쪽 스풀 자체는 harness-core/python/tests/test_audit_spool.py 가 본다.
여기서 보는 것은 상담 서비스의 배선이다 — 설정이 스풀을 붙이는가, 저장 실패가 파일에
남는가, 되돌리기 명령이 그것을 다시 넣는가.
"""
from __future__ import annotations

import pytest

import app.audit as audit_mod
from app import audit_replay
from harness_core import AgentAuditEntry, clear_audit_failure_listeners
from harness_core.audit_sqlalchemy import SqlAlchemyAgentAuditLog


@pytest.fixture()
def spool_path(tmp_path, monkeypatch):
    """스풀 경로를 임시 디렉터리로 돌리고 모듈 전역을 초기화한다."""
    path = tmp_path / "audit-spool.jsonl"
    monkeypatch.setattr(audit_mod, "_spool", None)
    monkeypatch.setattr(audit_mod, "_spool_resolved", False)
    monkeypatch.setattr(
        audit_mod.get_settings(), "harness_audit_spool_path", str(path)
    )
    yield path
    clear_audit_failure_listeners()


class _ExplodingSession:
    def execute(self, *a, **k):
        raise RuntimeError('relation "harness_audit_log" does not exist')

    def commit(self):  # pragma: no cover
        raise AssertionError("실패 경로에서 commit 이 불리면 안 된다")

    def rollback(self):
        return None

    def close(self):
        return None


def _entry(**over) -> AgentAuditEntry:
    base = dict(
        agent_name=audit_mod.AGENT_NAME,
        subject_type=audit_mod.SUBJECT_TYPE,
        subject_id="1",
        output_json='{"answer": "네"}',
    )
    base.update(over)
    return AgentAuditEntry(**base)


class TestSpoolWiring:
    def test_failure_lands_in_spool(self, spool_path):
        audit_mod.get_audit_spool()
        log = SqlAlchemyAgentAuditLog(
            lambda: _ExplodingSession(), agent_name=audit_mod.AGENT_NAME
        )

        log.record(_entry(subject_id="55"))

        assert [e.subject_id for e in audit_mod.get_audit_spool().pending()] == ["55"]

    def test_no_path_means_no_spool(self, tmp_path, monkeypatch):
        """경로를 비우면 예전처럼 영구 유실이다. 조용히가 아니라 경고와 함께."""
        monkeypatch.setattr(audit_mod, "_spool", None)
        monkeypatch.setattr(audit_mod, "_spool_resolved", False)
        monkeypatch.setattr(audit_mod.get_settings(), "harness_audit_spool_path", "")

        assert audit_mod.get_audit_spool() is None

    def test_resolution_is_cached(self, spool_path):
        first = audit_mod.get_audit_spool()
        assert audit_mod.get_audit_spool() is first


class TestReplayCommand:
    def test_dry_run_reports_without_restoring(self, spool_path, audit_spy, capsys):
        audit_mod.get_audit_spool().append(_entry(subject_id="1"))

        assert audit_replay.main(["--path", str(spool_path), "--dry-run"]) == 0
        assert "1건" in capsys.readouterr().out
        assert audit_spy.entries == []

    def test_replay_restores_into_audit_log(self, spool_path, audit_spy, capsys):
        audit_mod.get_audit_spool().append(_entry(subject_id="42"))

        exit_code = audit_replay.main(["--path", str(spool_path)])

        assert exit_code == 0
        assert [e.subject_id for e in audit_spy.entries] == ["42"]
        assert not spool_path.exists()

    def test_empty_spool_is_not_an_error(self, spool_path, capsys):
        assert audit_replay.main(["--path", str(spool_path)]) == 0
        assert "없다" in capsys.readouterr().out

    def test_missing_path_setting_exits_two(self, monkeypatch, capsys):
        monkeypatch.setattr(audit_mod, "_spool", None)
        monkeypatch.setattr(audit_mod, "_spool_resolved", False)
        monkeypatch.setattr(audit_mod.get_settings(), "harness_audit_spool_path", "")

        assert audit_replay.main([]) == 2
