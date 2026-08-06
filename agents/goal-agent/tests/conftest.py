"""goal-agent 테스트 공통 설정.

이 디렉터리가 생기기 전까지 goal-agent 에는 테스트가 한 개도 없었다. 감사 배선
(app/audit.py)은 손으로 한 번 돌려본 것이 전부였고, 누가 record_goal_turn 호출을
지워도 아무것도 깨지지 않는 상태였다.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
# 하네스 공유 계약(harness_core). 이미지 안에서는 /app/harness_core 로 들어가지만
# 로컬 테스트는 레포의 원본을 직접 본다 — 사본을 만들지 않기 위해서다.
sys.path.insert(0, str(ROOT.parent / "harness-core" / "python"))


class RecordingAuditLog:
    """테스트용 인메모리 감사 저장소. harness_core 저장소와 같은 모양만 갖춘다."""

    def __init__(self) -> None:
        self.entries: list = []

    def record(self, entry) -> None:
        self.entries.append(entry)

    def find_latest(self, subject_type: str, subject_id: str, decision_kind=None):
        for entry in reversed(self.entries):
            if entry.subject_type != subject_type or entry.subject_id != subject_id:
                continue
            if decision_kind is None or entry.decision_kind == decision_kind:
                return entry
        return None


@pytest.fixture(autouse=True)
def audit_spy(monkeypatch) -> RecordingAuditLog:
    """감사 저장소를 인메모리 spy 로 바꾼다.

    바꾸지 않으면 get_audit_log() 가 SessionLocal(=DATABASE_URL 이 가리키는 실제
    DB)에 붙은 저장소를 만든다. 테스트가 DB 를 요구하게 되고, 없으면 감사 실패가
    로그로만 남아 아무것도 검증되지 않는다.

    저장이 실제로 되는지(JSONB·INSERT-ONLY 트리거)는 SQLite 로 확인할 수 없다.
    그쪽은 harness-core 의 계약 테스트와 consultation 의 PostgreSQL 테스트가 본다.
    여기서 보는 것은 배선이다 — 호출이 붙어 있는지, 계약이 정한 자리에 도메인 값이
    제대로 들어가는지.
    """
    import app.audit as audit_mod

    spy = RecordingAuditLog()
    monkeypatch.setattr(audit_mod, "_audit_log", spy)
    return spy
