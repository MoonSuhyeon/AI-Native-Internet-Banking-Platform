import asyncio

from sqlalchemy import inspect

from app.config import Settings, get_settings
from app.kafka import KafkaEventPublisher
from app.llm import LlmHandoffAdapter
from app.models import (
    ChatConsultation,
    ChatMessageHistory,
    ChatbotConsultation,
    ChatbotNode,
    ChatbotNodeButton,
    ChatbotNodeFlow,
    ChatbotScenario,
    Consultation,
)
from app.services import ChatService, ChatbotService


class RecordingEvents:
    def __init__(self):
        self.chatbot_events = []
        self.chat_events = []

    async def publish(self, event_type, payload):
        self.chatbot_events.append((event_type, payload))

    async def publish_chat(self, event_type, payload):
        self.chat_events.append((event_type, payload))


def test_settings_reads_consultation_env_prefix(monkeypatch):
    get_settings.cache_clear()
    monkeypatch.setenv("CONSULTATION_KAFKA_ENABLED", "true")
    monkeypatch.setenv("CONSULTATION_KAFKA_TOPIC_CHAT_EVENTS", "custom.chat.events")

    settings = get_settings()

    assert settings.kafka_enabled is True
    assert settings.kafka_topic_chat_events == "custom.chat.events"
    get_settings.cache_clear()


def test_llm_adapter_declares_bp002_and_returns_fallback_answer():
    adapter = LlmHandoffAdapter()

    assert adapter.process_method_code == "BP002"
    assert adapter.answer("unknown question")


def test_kafka_publisher_skips_all_methods_when_disabled():
    publisher = KafkaEventPublisher(Settings(kafka_enabled=False))

    async def run():
        await publisher.start()
        await publisher.publish("ChatbotEvent", {"id": 1})
        await publisher.publish_chat("ChatEvent", {"id": 2})
        await publisher.stop()

    asyncio.run(run())
    assert publisher._producer is None


def test_core_table_metadata_matches_consultation_erd(db):
    mapper = inspect(db.get_bind())
    expected_tables = {
        Consultation.__tablename__,
        ChatbotConsultation.__tablename__,
        ChatConsultation.__tablename__,
        ChatbotScenario.__tablename__,
        "chatbot_intent",
        ChatbotNode.__tablename__,
        ChatbotNodeButton.__tablename__,
        ChatbotNodeFlow.__tablename__,
        ChatMessageHistory.__tablename__,
    }

    assert expected_tables.issubset(set(mapper.get_table_names()))


def test_chatbot_node_flow_has_image_erd_columns(db):
    columns = {column["name"] for column in inspect(db.get_bind()).get_columns("chatbot_node_flow")}

    assert {
        "current_node_id",
        "next_node_id",
        "sort_order",
        "chatbot_flow_type_cd",
        "branch_criteria_cd",
        "branch_value",
        "active_yn",
    }.issubset(columns)


def test_start_publishes_started_event_and_records_first_bot_message(db):
    events = RecordingEvents()
    service = ChatbotService(db, events, LlmHandoffAdapter())
    service.seed_default_scenario()

    started = asyncio.run(service.start("CUST001", "HOME", "1.0.0"))
    messages = db.query(ChatMessageHistory).filter(
        ChatMessageHistory.chatbot_consultation_id == started.chatbot_consultation_id
    ).all()

    assert events.chatbot_events[0][0] == "ChatbotConsultationStarted"
    assert events.chatbot_events[0][1]["customerNo"] == "CUST001"
    assert len(messages) == 1
    assert messages[0].sequence_no == 1
    assert messages[0].node_id == started.node_id


def test_unknown_button_uses_llm_fallback_and_opens_agent_queue(db):
    events = RecordingEvents()
    service = ChatbotService(db, events, LlmHandoffAdapter())
    service.seed_default_scenario()
    started = asyncio.run(service.start("CUST001", "HOME", "1.0.0"))

    response = asyncio.run(
        service.handle_message(started.chatbot_consultation_id, "not mapped", "NO_MATCH")
    )
    queue = ChatService(db, events).get_waiting_queue()

    assert response.process_method == "STAFF_REQUEST"
    assert response.agent_transfer_required is True
    assert len(queue) == 1
    assert [event[0] for event in events.chatbot_events][-2:] == [
        "ChatbotMessageHandled",
        "ChatbotAgentTransferRequested",
    ]


def test_chat_service_publishes_connect_message_and_end_events(db):
    events = RecordingEvents()
    chatbot_service = ChatbotService(db, events, LlmHandoffAdapter())
    chat_service = ChatService(db, events)
    chatbot_service.seed_default_scenario()
    started = asyncio.run(chatbot_service.start("CUST001", "HOME", "1.0.0"))
    asyncio.run(chatbot_service.handle_message(started.chatbot_consultation_id, "agent", "AGENT"))
    chat_id = chat_service.get_waiting_queue()[0]["chat_consultation_id"]

    asyncio.run(chat_service.connect_agent(chat_id, 100))
    asyncio.run(chat_service.send_message(chat_id, "hello", 3))
    asyncio.run(chat_service.end_chat(chat_id, 5))

    assert [event[0] for event in events.chat_events] == [
        "AgentConnected",
        "ChatMessageSent",
        "ChatEnded",
    ]
    assert events.chat_events[1][1]["senderType"] == "AGENT"
    assert events.chat_events[2][1]["satisfactionScore"] == 5
