"""
ChatService 단위 테스트.

흐름: 챗봇 시작 → AGENT 버튼으로 상담사 이관 →
      대기열 확인 → 상담사 연결 → 메시지 교환 → 상담 종료
"""

import asyncio

import pytest

from app.services import ChatService


# ── 헬퍼 ────────────────────────────────────────────────────────────────────

def _trigger_agent_transfer(service, chat_service) -> int:
    """챗봇에서 상담사 이관을 유발하고 chat_consultation_id 반환."""
    service.seed_default_scenario()
    started = asyncio.run(service.start("CUST001", "HOME", "0.1.0"))
    # "AGENT" 버튼 → '상담사 연결' 노드 → agent_transfer_required=True
    asyncio.run(
        service.handle_message(started.chatbot_consultation_id, "상담사 연결", "AGENT")
    )
    queue = chat_service.get_waiting_queue()
    assert queue, "상담사 이관 후 대기열이 비어 있습니다."
    return queue[0]["chat_consultation_id"]


# ── 대기열 ───────────────────────────────────────────────────────────────────

class TestWaitingQueue:
    def test_queue_initially_empty(self, chat_service: ChatService):
        assert chat_service.get_waiting_queue() == []

    def test_queue_populated_after_agent_transfer(self, service, chat_service: ChatService):
        _trigger_agent_transfer(service, chat_service)
        queue = chat_service.get_waiting_queue()
        assert len(queue) >= 1
        row = queue[0]
        assert "chat_consultation_id" in row
        assert "customer_no" in row
        assert "waiting_since" in row

    def test_queue_disappears_after_connect(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        # 연결 수락 후 대기열에서 제거됨
        queue = chat_service.get_waiting_queue()
        ids = [r["chat_consultation_id"] for r in queue]
        assert chat_id not in ids


# ── 상담사 연결 ──────────────────────────────────────────────────────────────

class TestConnectAgent:
    def test_connect_sets_employee_and_timestamps(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        chat = asyncio.run(chat_service.connect_agent(chat_id, employee_id=42))

        assert chat.employee_id == 42
        assert chat.agent_connected_at is not None
        assert chat.chat_started_at is not None
        assert chat.waiting_seconds is not None

    def test_connect_already_connected_raises(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))

        with pytest.raises(ValueError, match="이미"):
            asyncio.run(chat_service.connect_agent(chat_id, employee_id=2))

    def test_get_consultation_not_found_raises(self, chat_service: ChatService):
        with pytest.raises(ValueError, match="찾을 수 없습니다"):
            chat_service.get_consultation(99999)


# ── 메시지 전송 ──────────────────────────────────────────────────────────────

class TestSendMessage:
    def test_agent_message_stored(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))

        msg = asyncio.run(chat_service.send_message(chat_id, "안녕하세요, 상담사입니다.", 3))

        assert msg.message_content == "안녕하세요, 상담사입니다."
        assert msg.sender_type_code_id == 3  # AGENT
        assert msg.chat_consultation_id == chat_id

    def test_user_message_stored(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))

        msg = asyncio.run(chat_service.send_message(chat_id, "도움이 필요합니다.", 1))

        assert msg.sender_type_code_id == 1  # USER

    def test_sequence_no_increments(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))

        msg1 = asyncio.run(chat_service.send_message(chat_id, "첫 번째", 3))
        msg2 = asyncio.run(chat_service.send_message(chat_id, "두 번째", 1))

        assert msg2.sequence_no > msg1.sequence_no

    def test_send_message_after_end_raises(self, service, chat_service: ChatService):
        """종료된 상담에 메시지 전송 시 ValueError가 발생한다."""
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        asyncio.run(chat_service.end_chat(chat_id))

        with pytest.raises(ValueError, match="종료"):
            asyncio.run(chat_service.send_message(chat_id, "종료 후 메시지", 1))

    def test_message_count_not_increase_after_end(self, service, chat_service: ChatService):
        """종료 후 send_message가 거부되어 메시지 수가 늘지 않는다."""
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        asyncio.run(chat_service.send_message(chat_id, "정상 메시지", 3))
        asyncio.run(chat_service.end_chat(chat_id))

        count_before = len(chat_service.get_messages(chat_id))

        with pytest.raises(ValueError):
            asyncio.run(chat_service.send_message(chat_id, "종료 후 메시지", 1))

        count_after = len(chat_service.get_messages(chat_id))
        assert count_after == count_before


# ── 메시지 이력 조회 ──────────────────────────────────────────────────────────

class TestGetMessages:
    def test_includes_chatbot_messages(self, service, chat_service: ChatService):
        """get_messages 는 챗봇 메시지도 포함해야 한다."""
        chat_id = _trigger_agent_transfer(service, chat_service)
        messages = chat_service.get_messages(chat_id)
        # 챗봇이 최소 1개 이상 메시지를 기록함
        assert len(messages) >= 1

    def test_includes_agent_message_after_connect(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        asyncio.run(chat_service.send_message(chat_id, "상담사 답변입니다.", 3))

        messages = chat_service.get_messages(chat_id)
        contents = [m.message_content for m in messages]
        assert "상담사 답변입니다." in contents


# ── 상담 종료 ────────────────────────────────────────────────────────────────

class TestEndChat:
    def test_end_sets_timestamps_and_inactive(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        ended = asyncio.run(chat_service.end_chat(chat_id, satisfaction_score=5))

        assert ended.active_yn == "N"
        assert ended.chat_ended_at is not None
        assert ended.satisfaction_score == 5
        assert ended.chat_seconds is not None

    def test_end_without_satisfaction_score(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        ended = asyncio.run(chat_service.end_chat(chat_id))

        assert ended.active_yn == "N"
        assert ended.satisfaction_score is None

    def test_end_already_ended_raises(self, service, chat_service: ChatService):
        chat_id = _trigger_agent_transfer(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        asyncio.run(chat_service.end_chat(chat_id))

        with pytest.raises(ValueError, match="이미"):
            asyncio.run(chat_service.end_chat(chat_id))


# ── 전체 흐름 통합 ────────────────────────────────────────────────────────────

class TestFullFlow:
    def test_chatbot_to_agent_to_end(self, service, chat_service: ChatService):
        """챗봇 → 상담사 이관 → 연결 → 메시지 교환 → 종료 전체 흐름."""
        # 1. 챗봇에서 상담사 이관
        chat_id = _trigger_agent_transfer(service, chat_service)

        # 2. 대기열에 존재
        queue = chat_service.get_waiting_queue()
        assert any(r["chat_consultation_id"] == chat_id for r in queue)

        # 3. 상담사 연결
        chat = asyncio.run(chat_service.connect_agent(chat_id, employee_id=999))
        assert chat.employee_id == 999

        # 4. 메시지 교환
        asyncio.run(chat_service.send_message(chat_id, "무엇을 도와드릴까요?", 3))
        asyncio.run(chat_service.send_message(chat_id, "예금 해지 방법 알고 싶어요.", 1))
        asyncio.run(chat_service.send_message(chat_id, "알겠습니다. 안내해 드리겠습니다.", 3))

        # 5. 메시지 이력 확인
        messages = chat_service.get_messages(chat_id)
        agent_msgs = [m for m in messages if m.sender_type_code_id == 3]
        user_msgs = [m for m in messages if m.sender_type_code_id == 1]
        assert len(agent_msgs) >= 2
        assert len(user_msgs) >= 1

        # 6. 상담 종료
        ended = asyncio.run(chat_service.end_chat(chat_id, satisfaction_score=4))
        assert ended.active_yn == "N"
        assert ended.satisfaction_score == 4
