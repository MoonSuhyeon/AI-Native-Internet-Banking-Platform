import asyncio

import pytest
from sqlalchemy import select

from app.models import ChatConsultation, ChatMessageHistory, ChatbotConsultation, ChatbotIntent
from app.schemas import ChatbotFeatureExecuteRequest


def start(service, customer_no: str = "CUST001"):
    return asyncio.run(service.start(customer_no, "HOME", "1.0.0"))


def send(service, chatbot_consultation_id: int, message: str = "", button_value: str | None = None):
    return asyncio.run(service.handle_message(chatbot_consultation_id, message, button_value))


def published_events(service) -> list[tuple[str, dict]]:
    return [(call.args[0], call.args[1]) for call in service.events.publish.call_args_list]


class TestScenarioButtonFlow:
    def test_start_returns_default_buttons(self, service):
        service.seed_default_scenario()

        response = start(service)

        assert {button.value for button in response.buttons} == {
            "PRODUCT_ADVICE",
            "USER_FINANCE",
            "STAFF_SUPPORT",
            "AGENT",
        }

    @pytest.mark.parametrize("button_value", ["PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT"])
    def test_menu_buttons_follow_scenario_flow(self, service, button_value):
        service.seed_default_scenario()
        session = start(service)

        response = send(service, session.chatbot_consultation_id, button_value=button_value)

        assert response.process_method == "SCENARIO"
        assert response.agent_transfer_required is False

    def test_agent_button_requests_transfer(self, service):
        service.seed_default_scenario()
        session = start(service)

        response = send(service, session.chatbot_consultation_id, button_value="AGENT")

        assert response.process_method == "STAFF_REQUEST"
        assert response.agent_transfer_required is True


class TestFreeTextIntentClassification:
    @pytest.mark.parametrize(
        ("message", "expected_method"),
        [
            ("정기예금 금리 알려줘", "FEATURE_RATE_GUIDE"),
            ("이자율이 얼마인가요?", "FEATURE_RATE_GUIDE"),
            ("가입 조건 알려줘", "FEATURE_JOIN_CONDITION"),
            ("가입할 수 있는 조건이 뭐야?", "FEATURE_JOIN_CONDITION"),
            ("예금이랑 적금 비교해줘", "FEATURE_PRODUCT_COMPARE"),
            ("상품 차이가 뭐예요?", "FEATURE_PRODUCT_COMPARE"),
            ("중도해지하면 어떻게 되나요?", "FEATURE_TERMS_RAG"),
            ("수수료 약관 보여줘", "FEATURE_TERMS_RAG"),
            ("상품 추천해줘", "FEATURE_PRODUCT_GUIDE"),
            ("어떤 예금 상품 있어?", "FEATURE_PRODUCT_GUIDE"),
            ("FAQ 알려줘", "FEATURE_FAQ"),
        ],
    )
    def test_free_text_routes_to_feature_method(self, service, message, expected_method):
        service.seed_default_scenario()
        session = start(service)

        response = send(service, session.chatbot_consultation_id, message=message)

        assert response.process_method == expected_method
        assert response.agent_transfer_required is False

    def test_unknown_button_value_falls_back_to_intent_classification(self, service):
        service.seed_default_scenario()
        session = start(service)

        response = send(
            service,
            session.chatbot_consultation_id,
            message="금리 알려줘",
            button_value="UNKNOWN_BUTTON",
        )

        assert response.process_method == "FEATURE_RATE_GUIDE"

    def test_delivery_app_message_routes_to_spending_pattern(self, service):
        service.seed_default_scenario()
        session = start(service)

        response = send(service, session.chatbot_consultation_id, message="지금 요즘 배달앱만 계속 써")

        assert response.process_method == "FEATURE_SPENDING_PATTERN"
        assert response.agent_transfer_required is False
        assert "배달" in response.message
        assert "절약" in response.message


class TestLlmFallback:
    def test_unclassified_message_uses_llm_when_adapter_exists(self, llm_service):
        llm_service.seed_default_scenario()
        session = start(llm_service)

        response = send(llm_service, session.chatbot_consultation_id, message="오늘 날씨는 어때?")

        assert response.process_method == "BP003_GPT"
        assert response.agent_transfer_required is False
        assert response.message

    def test_unclassified_message_requests_agent_when_llm_adapter_missing(self, service):
        service.seed_default_scenario()
        session = start(service)

        response = send(service, session.chatbot_consultation_id, message="분류되지 않는 임의 문장")

        assert response.process_method == "STAFF_REQUEST"
        assert response.agent_transfer_required is True


class TestAgentTransfer:
    def test_agent_transfer_creates_chat_consultation(self, service, db):
        service.seed_default_scenario()
        session = start(service)

        send(service, session.chatbot_consultation_id, button_value="AGENT")

        chat = db.scalars(
            select(ChatConsultation).where(
                ChatConsultation.chatbot_consultation_id == session.chatbot_consultation_id
            )
        ).first()
        assert chat is not None
        assert chat.active_yn == "Y"

    def test_agent_transfer_marks_chatbot_as_connected(self, service, db):
        service.seed_default_scenario()
        session = start(service)

        send(service, session.chatbot_consultation_id, button_value="AGENT")

        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.agent_connected_yn == "Y"


class TestChatbotIntentPersistence:
    def test_default_intents_are_seeded_with_scenario(self, service, db):
        service.seed_default_scenario()

        intent_names = {intent.intent_name for intent in db.scalars(select(ChatbotIntent)).all()}

        assert {"RATE_GUIDE", "LLM_FALLBACK", "STAFF_REQUEST", "STAFF_ERROR_FALLBACK"}.issubset(intent_names)

    def test_classified_intent_id_is_saved(self, service, db):
        service.seed_default_scenario()
        session = start(service)

        send(service, session.chatbot_consultation_id, message="가입 조건 알려줘")

        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        intent = db.get(ChatbotIntent, chatbot.intent_id)
        assert intent.intent_name == "JOIN_CONDITION"

    def test_llm_fallback_intent_id_is_saved(self, llm_service, db):
        llm_service.seed_default_scenario()
        session = start(llm_service)

        send(llm_service, session.chatbot_consultation_id, message="분류되지 않는 질문")

        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        intent = db.get(ChatbotIntent, chatbot.intent_id)
        assert intent.intent_name == "LLM_FALLBACK"


class TestKafkaEvents:
    def test_start_publishes_consultation_started(self, service):
        service.seed_default_scenario()

        session = start(service, customer_no="KAFKA_CUST")
        events = published_events(service)

        started = next(event for event in events if event[0] == "ChatbotConsultationStarted")
        assert started[1]["customerNo"] == "KAFKA_CUST"
        assert started[1]["consultationId"] == session.consultation_id
        assert started[1]["chatbotConsultationId"] == session.chatbot_consultation_id

    def test_feature_message_publishes_handled_event(self, service):
        service.seed_default_scenario()
        session = start(service)
        service.events.publish.reset_mock()

        response = send(service, session.chatbot_consultation_id, message="금리 알려줘")
        handled = next(event for event in published_events(service) if event[0] == "ChatbotMessageHandled")

        assert handled[1]["processMethod"] == response.process_method
        assert handled[1]["processMethod"] == "FEATURE_RATE_GUIDE"
        assert handled[1]["agentTransferRequired"] is False

    def test_agent_transfer_publishes_transfer_event(self, service):
        service.seed_default_scenario()
        session = start(service)
        service.events.publish.reset_mock()

        response = send(service, session.chatbot_consultation_id, button_value="AGENT")
        names = [event[0] for event in published_events(service)]

        assert response.agent_transfer_required is True
        assert "ChatbotMessageHandled" in names
        assert "ChatbotAgentTransferRequested" in names

    def test_llm_fallback_does_not_publish_agent_transfer(self, llm_service):
        llm_service.seed_default_scenario()
        session = start(llm_service)
        llm_service.events.publish.reset_mock()

        response = send(llm_service, session.chatbot_consultation_id, message="분류되지 않는 질문")
        names = [event[0] for event in published_events(llm_service)]

        assert response.process_method == "BP003_GPT"
        assert "ChatbotAgentTransferRequested" not in names


class TestResponseBody:
    def test_feature_response_has_expected_shape(self, service):
        service.seed_default_scenario()
        session = start(service)

        response = send(service, session.chatbot_consultation_id, message="금리 알려줘")

        assert response.consultation_id == session.consultation_id
        assert response.chatbot_consultation_id == session.chatbot_consultation_id
        assert response.node_id is not None
        assert response.message
        assert isinstance(response.buttons, list)

    def test_messages_are_recorded_for_turn(self, service, db):
        service.seed_default_scenario()
        session = start(service)

        send(service, session.chatbot_consultation_id, message="상품 추천해줘")

        messages = db.scalars(
            select(ChatMessageHistory).where(
                ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id
            )
        ).all()
        assert len(messages) >= 3

    def test_total_turn_count_increments(self, service, db):
        service.seed_default_scenario()
        session = start(service)

        send(service, session.chatbot_consultation_id, message="금리 알려줘")
        send(service, session.chatbot_consultation_id, message="가입 조건 알려줘")

        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.total_turn_count == 2


class TestFeatureExecution:
    def test_product_feature_returns_data_from_rich_db(self, rich_service):
        result = rich_service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())

        assert result.status == "OK"
        assert result.data

    def test_empty_db_feature_returns_empty_status(self, empty_service):
        result = empty_service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())

        assert result.status == "EMPTY"
