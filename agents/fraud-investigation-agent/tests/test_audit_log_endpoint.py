"""GET /api/audit — 감사 로그 조회 화면의 계약.

기록(audit.py의 record_investigation/record_action_execution)은 이미
test_audit_trace_link.py 가 본다. 여기서는 **읽는 쪽**만 본다 — 저장된 기록이
화면이 쓸 수 있는 모양(JSON 필드가 파싱된 dict, 역할 배열)으로 나오는지,
그리고 alert_id 로 좁혀지는지.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient
from harness_core import AgentAuditEntry

from agent import audit as audit_mod
from agent.api import app
from conftest import GATEWAY_HEADERS


class _FakeAuditLog:
    """list_recent 만 흉내 내는 감사 저장소. record 는 이 테스트가 안 쓴다."""

    def __init__(self, entries: list[AgentAuditEntry]) -> None:
        self._entries = entries

    def record(self, entry: AgentAuditEntry) -> None:  # pragma: no cover - 미사용
        self._entries.append(entry)

    def list_recent(self, subject_type, subject_id=None, limit=100):
        rows = [e for e in self._entries if e.subject_type == subject_type]
        if subject_id is not None:
            rows = [e for e in rows if e.subject_id == subject_id]
        return rows[:limit]


@pytest.fixture()
def client():
    return TestClient(app)


def _entry(**overrides) -> AgentAuditEntry:
    base = dict(
        agent_name="fraud-investigation",
        subject_type="FRAUD_CASE",
        subject_id="ALERT-1",
        decision_kind="RECOMMENDATION",
        recorded_at=datetime(2026, 9, 7, 3, 0, tzinfo=timezone.utc),
        actor_id=None,
        actor_roles="[]",
        request_json='{"case": "case_h1"}',
        output_json='{"recommendation": {"status": "CONFIRMED"}}',
    )
    base.update(overrides)
    return AgentAuditEntry(**base)


def test_기록이_화면이_쓸_모양으로_파싱돼_나온다(client, monkeypatch):
    entries = [
        _entry(),
        _entry(
            decision_kind="ACTION_EXECUTION",
            actor_id="9001",
            actor_roles='["ROLE_BRANCH_MANAGER"]',
            output_json='{"approved": true, "executed_actions": ["실행(목): FREEZE_PAYMENT"]}',
        ),
    ]
    monkeypatch.setattr(audit_mod, "get_audit_log", lambda: _FakeAuditLog(entries))

    resp = client.get("/api/audit", headers=GATEWAY_HEADERS)

    assert resp.status_code == 200
    rows = resp.json()
    assert len(rows) == 2
    assert rows[0]["decision_kind"] == "RECOMMENDATION"
    assert rows[0]["request"] == {"case": "case_h1"}
    assert rows[1]["actor_roles"] == ["ROLE_BRANCH_MANAGER"]
    assert rows[1]["output"]["executed_actions"] == ["실행(목): FREEZE_PAYMENT"]


def test_alert_id로_좁혀진다(client, monkeypatch):
    entries = [_entry(subject_id="ALERT-1"), _entry(subject_id="ALERT-2")]
    monkeypatch.setattr(audit_mod, "get_audit_log", lambda: _FakeAuditLog(entries))

    resp = client.get("/api/audit", params={"alert_id": "ALERT-2"}, headers=GATEWAY_HEADERS)

    rows = resp.json()
    assert len(rows) == 1
    assert rows[0]["alert_id"] == "ALERT-2"
