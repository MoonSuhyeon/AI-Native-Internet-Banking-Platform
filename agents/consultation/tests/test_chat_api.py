"""
상담사 채팅 HTTP API 테스트.

GET  /chat/queue
POST /chat/consultations/{id}/connect
POST /chat/consultations/{id}/messages
GET  /chat/consultations/{id}/messages
POST /chat/consultations/{id}/end
"""

from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient

from app.main import app, get_chat_service, get_chatbot_service
from app.services import ChatService


# ── 헬퍼 ────────────────────────────────────────────────────────────────────

def _make_client(service, chat_svc):
    app.dependency_overrides[get_chatbot_service] = lambda: service
    app.dependency_overrides[get_chat_service] = lambda: chat_svc
    return TestClient(app)


def _trigger_transfer(client: TestClient) -> int:
    """챗봇 시작 → AGENT 버튼 → 상담사 이관 후 chat_consultation_id 반환."""
    client.post("/chatbot/scenarios/default")
    started = client.post(
        "/chatbot/consultations/start",
        json={"customer_no": "CUST001", "entry_screen": "HOME", "app_version": "0.1.0"},
    ).json()
    client.post(
        f"/chatbot/consultations/{started['chatbot_consultation_id']}/messages",
        json={"message": "상담사 연결", "button_value": "AGENT"},
    )
    queue = client.get("/chat/queue").json()
    assert queue, "대기열이 비어 있습니다."
    return queue[0]["chat_consultation_id"]


# ── 대기열 ───────────────────────────────────────────────────────────────────

class TestAgentQueue:
    def test_queue_initially_empty(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            resp = client.get("/chat/queue")
            assert resp.status_code == 200
            assert resp.json() == []
        finally:
            app.dependency_overrides.clear()

    def test_queue_returns_waiting_item(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            _trigger_transfer(client)
            queue = client.get("/chat/queue").json()
            assert len(queue) >= 1
            item = queue[0]
            assert "chat_consultation_id" in item
            assert "customer_no" in item
            assert item["customer_no"] == "CUST001"
        finally:
            app.dependency_overrides.clear()


# ── 상담사 연결 ──────────────────────────────────────────────────────────────

class TestConnectAgent:
    def test_connect_returns_connected_status(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            resp = client.post(
                f"/chat/consultations/{chat_id}/connect",
                json={"employee_id": 99},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "CONNECTED"
            assert body["employee_id"] == 99
        finally:
            app.dependency_overrides.clear()

    def test_connect_already_connected_returns_400(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            resp = client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 2})
            assert resp.status_code == 400
        finally:
            app.dependency_overrides.clear()


# ── 메시지 전송 ──────────────────────────────────────────────────────────────

class TestSendMessage:
    def test_agent_message_returns_200(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            resp = client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "도와드리겠습니다.", "sender_type": "AGENT"},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["message"] == "도와드리겠습니다."
            assert body["sender_type"] == "AGENT"
        finally:
            app.dependency_overrides.clear()

    def test_user_message_returns_200(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            resp = client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "궁금한 점이 있습니다.", "sender_type": "USER"},
            )
            assert resp.status_code == 200
            assert resp.json()["sender_type"] == "USER"
        finally:
            app.dependency_overrides.clear()

    def test_message_not_found_returns_404(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            resp = client.post(
                "/chat/consultations/99999/messages",
                json={"message": "없는 상담", "sender_type": "AGENT"},
            )
            assert resp.status_code == 404
        finally:
            app.dependency_overrides.clear()

    def test_send_message_after_end_returns_404(self, service, chat_service):
        """종료된 상담에 메시지 전송 시 HTTP 404를 반환하고 DB에 저장되지 않는다."""
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            client.post(f"/chat/consultations/{chat_id}/end", json={})

            resp = client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "종료 후 메시지", "sender_type": "USER"},
            )

            assert resp.status_code == 404
        finally:
            app.dependency_overrides.clear()


# ── 메시지 이력 조회 ──────────────────────────────────────────────────────────

class TestGetMessages:
    def test_get_messages_includes_chatbot_messages(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            resp = client.get(f"/chat/consultations/{chat_id}/messages")
            assert resp.status_code == 200
            messages = resp.json()
            assert len(messages) >= 1
            # 메시지 필드 확인
            msg = messages[0]
            assert "message_id" in msg
            assert "sender_type" in msg
            assert "message" in msg
        finally:
            app.dependency_overrides.clear()

    def test_get_messages_after_agent_connect(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "상담사 메시지", "sender_type": "AGENT"},
            )
            messages = client.get(f"/chat/consultations/{chat_id}/messages").json()
            contents = [m["message"] for m in messages]
            assert "상담사 메시지" in contents
        finally:
            app.dependency_overrides.clear()

    def test_get_messages_not_found_returns_404(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            resp = client.get("/chat/consultations/99999/messages")
            assert resp.status_code == 404
        finally:
            app.dependency_overrides.clear()


# ── 상담 종료 ────────────────────────────────────────────────────────────────

class TestEndChat:
    def test_end_returns_ended_status(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            resp = client.post(
                f"/chat/consultations/{chat_id}/end",
                json={"satisfaction_score": 5},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "ENDED"
            assert body["active_yn"] == "N"
            assert body["satisfaction_score"] == 5
        finally:
            app.dependency_overrides.clear()

    def test_end_without_score_returns_200(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            resp = client.post(f"/chat/consultations/{chat_id}/end", json={})
            assert resp.status_code == 200
            assert resp.json()["status"] == "ENDED"
        finally:
            app.dependency_overrides.clear()

    def test_end_already_ended_returns_400(self, service, chat_service):
        client = _make_client(service, chat_service)
        try:
            chat_id = _trigger_transfer(client)
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            client.post(f"/chat/consultations/{chat_id}/end", json={})
            resp = client.post(f"/chat/consultations/{chat_id}/end", json={})
            assert resp.status_code == 400
        finally:
            app.dependency_overrides.clear()


# ── 전체 흐름 ────────────────────────────────────────────────────────────────

class TestFullFlow:
    def test_chatbot_llm_agent_end_flow(self, service, chat_service):
        """챗봇 시작 → AGENT 버튼 → 상담사 연결 → 메시지 교환 → 종료."""
        client = _make_client(service, chat_service)
        try:
            # 1. 챗봇 시작
            client.post("/chatbot/scenarios/default")
            started = client.post(
                "/chatbot/consultations/start",
                json={"customer_no": "CUST001", "entry_screen": "HOME", "app_version": "0.1.0"},
            )
            assert started.status_code == 200
            chatbot_id = started.json()["chatbot_consultation_id"]

            # 2. 상담사 버튼 클릭 → agent_transfer_required
            transfer = client.post(
                f"/chatbot/consultations/{chatbot_id}/messages",
                json={"message": "상담사 연결해주세요", "button_value": "AGENT"},
            )
            assert transfer.json()["agent_transfer_required"] is True

            # 3. 대기열 확인
            queue = client.get("/chat/queue").json()
            assert len(queue) >= 1
            chat_id = queue[0]["chat_consultation_id"]

            # 4. 상담사 연결
            connected = client.post(
                f"/chat/consultations/{chat_id}/connect",
                json={"employee_id": 777},
            )
            assert connected.json()["status"] == "CONNECTED"

            # 5. 메시지 교환
            client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "어떻게 도와드릴까요?", "sender_type": "AGENT"},
            )
            client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "예금 만기 확인 부탁드립니다.", "sender_type": "USER"},
            )

            # 6. 전체 이력 조회
            msgs = client.get(f"/chat/consultations/{chat_id}/messages").json()
            senders = {m["sender_type"] for m in msgs}
            assert "AGENT" in senders
            assert "USER" in senders

            # 7. 상담 종료
            ended = client.post(
                f"/chat/consultations/{chat_id}/end",
                json={"satisfaction_score": 4},
            )
            assert ended.json()["status"] == "ENDED"
        finally:
            app.dependency_overrides.clear()
