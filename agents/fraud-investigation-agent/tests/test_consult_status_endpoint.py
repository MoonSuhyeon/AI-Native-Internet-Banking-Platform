"""GET /api/consult/status — 고객이 자기 상담 접수 현황을 확인하는 화면의 계약.

내부 조사 어휘(시나리오·위험 신호)를 감추고 세 상태(대기·검토중·완료)로만
번역해 내보내는지, 그리고 남의 사건 번호로는 들여다볼 수 없는지를 본다.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient
from harness_core import AgentAuditEntry

from agent import api as api_mod
from agent.api import app
from agent.models import Alert, Case, TxContext
from conftest import GATEWAY_SECRET

CUSTOMER_HEADERS = {"X-Gateway-Auth": GATEWAY_SECRET, "X-Customer-Id": "9001"}


@pytest.fixture()
def client():
    return TestClient(app)


def _case(customer_id: str = "9001", case_id: str = "consult-9001-1") -> Case:
    return Case(
        name=case_id,
        description="테스트 상담 사건",
        alert=Alert(
            id=case_id,
            account="",
            customer_id=customer_id,
            tx_context=TxContext(
                amount=500_000, payee="000-11-222222",
                time=datetime(2026, 9, 7, 3, 0, tzinfo=timezone.utc), channel="WEB",
            ),
            anomaly_score=0.0,
        ),
        tool_responses={},
    )


def _entry(decision_kind: str, output_json: str = "{}") -> AgentAuditEntry:
    return AgentAuditEntry(
        agent_name="fraud-investigation",
        subject_type="FRAUD_CASE",
        subject_id="consult-9001-1",
        decision_kind=decision_kind,
        output_json=output_json,
    )


def test_게이트웨이_없이는_거절(client):
    resp = client.get("/api/consult/status", params={"case_id": "consult-9001-1"})
    assert resp.status_code == 403


def test_없는_사건은_404(client, monkeypatch):
    def _raise(_case_id):
        raise FileNotFoundError()
    monkeypatch.setattr(api_mod, "load_case", _raise)

    resp = client.get("/api/consult/status", params={"case_id": "nope"}, headers=CUSTOMER_HEADERS)
    assert resp.status_code == 404


def test_남의_사건도_404(client, monkeypatch):
    """존재 여부로 "남의 것"과 "없는 것"이 구별되면 그 자체로 정보 노출이다."""
    monkeypatch.setattr(api_mod, "load_case", lambda cid: _case(customer_id="9999"))

    resp = client.get("/api/consult/status", params={"case_id": "consult-9999-1"}, headers=CUSTOMER_HEADERS)
    assert resp.status_code == 404


def test_기록이_없으면_대기(client, monkeypatch):
    monkeypatch.setattr(api_mod, "load_case", lambda cid: _case())
    monkeypatch.setattr(api_mod, "list_recent_audit", lambda alert_id=None, limit=50: [])

    resp = client.get("/api/consult/status", params={"case_id": "consult-9001-1"}, headers=CUSTOMER_HEADERS)
    body = resp.json()
    assert body["status"] == "PENDING"
    assert body["amount"] == 500_000


def test_권고만_있으면_검토중(client, monkeypatch):
    monkeypatch.setattr(api_mod, "load_case", lambda cid: _case())
    monkeypatch.setattr(api_mod, "list_recent_audit",
                         lambda alert_id=None, limit=50: [_entry("RECOMMENDATION")])

    resp = client.get("/api/consult/status", params={"case_id": "consult-9001-1"}, headers=CUSTOMER_HEADERS)
    assert resp.json()["status"] == "UNDER_REVIEW"


def test_실행까지_있으면_제한으로_완료(client, monkeypatch):
    monkeypatch.setattr(api_mod, "load_case", lambda cid: _case())
    monkeypatch.setattr(api_mod, "list_recent_audit", lambda alert_id=None, limit=50: [
        _entry("ACTION_EXECUTION", output_json='{"executed_actions": ["실행(목): FREEZE_PAYMENT"]}'),
        _entry("RECOMMENDATION"),
    ])

    resp = client.get("/api/consult/status", params={"case_id": "consult-9001-1"}, headers=CUSTOMER_HEADERS)
    body = resp.json()
    assert body["status"] == "RESOLVED"
    assert body["resolution"] == "RESTRICTED"


def test_게이팅_동작이_거부되면_정상으로_완료(client, monkeypatch):
    monkeypatch.setattr(api_mod, "load_case", lambda cid: _case())
    monkeypatch.setattr(api_mod, "list_recent_audit", lambda alert_id=None, limit=50: [
        _entry("ACTION_EXECUTION", output_json='{"executed_actions": ["거부됨(RBAC): FREEZE_PAYMENT — 필요 역할 ..."]}'),
    ])

    resp = client.get("/api/consult/status", params={"case_id": "consult-9001-1"}, headers=CUSTOMER_HEADERS)
    body = resp.json()
    assert body["status"] == "RESOLVED"
    assert body["resolution"] == "CLEARED"
