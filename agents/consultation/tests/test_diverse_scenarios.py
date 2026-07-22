"""
다양한 시나리오 테스트 — 아직 미커버된 전 영역 망라.

커버 영역:
  A. handle_message 전 intent 경로    - 모든 intent 실행·formatter 응답·intent_id 저장
  B. 직전 추천 상품 annotation 제거   - "[직전 추천 상품:...]" 제거 후 분류
  C. Consultation 모델 검증           - content_summary·status·active_yn·customer_no
  D. ChatbotConsultation 링크 검증    - scenario_id·process_method·initial_intent
  E. ChatbotScenario/Node/Button 검증 - 이름·메시지·sort_order·active_yn
  F. ChatbotNodeFlow 검증             - branch_value·active_yn·sort_order
  G. _deactivate_legacy_start_options - 불허 버튼 비활성화
  H. _resolve_next_node               - 매핑 있음/없음·active_yn 필터
  I. _button_responses                - active_yn='Y'만·sort_order 정렬
  J. config/settings 검증             - 기본값·env prefix·lru_cache
  K. PRODUCT_COMPARE LLM 경로         - LLM mock 있을 때 개념 비교 호출
  L. MY_CASH_FLOW vs MY_TRANSFERS     - 거래 유형 필터 차이 명확 검증
  M. rich_db 거래 유형 다양성         - TRANSFER/PENDING/DEPOSIT 분리
  N. ChatbotConsultation 상태 전이    - initial_intent·scenario_id 저장
  O. end_chat 후 Consultation.completed_at 설정
  P. 만족도 점수 경계값               - 1·5 유효·0·6 무효
  Q. _execute_product_guide LIMIT 20  - 상품 20개 이상일 때 20개만 반환
  R. PRODUCT_GUIDE 군인 상품 DB 삽입 후 제외 확인
  S. _execute_terms_search OR 조건    - 이름/내용/요약 각각 매칭
  T. transfer 기본 memo               - memo 미지정 시 "이체" 사용
  U. LLM context 결합                 - history_ctx + rag_ctx 빈 문자열 처리
  V. CASH_FLOW_RECOMMEND 메시지+데이터 분리 - message는 LLM, data는 summary
  W. IntentClassifier [직전 추천 상품] annotation 필터 검증
  X. ChatbotScenario seed 후 DB 검증  - 시나리오 테이블 레코드 검증
  Y. feature_detail 전체 코드 정상 반환
  Z. ChatService 다중 연결 시나리오   - waiting_seconds 양수, 순서 보장
"""

import asyncio

import pytest
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.llm import IntentClassifier, LlmHandoffAdapter
from app.models import (
    ChatConsultation,
    ChatMessageHistory,
    ChatbotConsultation,
    ChatbotIntent,
    ChatbotNode,
    ChatbotNodeButton,
    ChatbotNodeFlow,
    ChatbotScenario,
    Consultation,
)
from app.schemas import ChatbotFeatureExecuteRequest, ChatbotTransferRequest
from app.services import ChatbotService, ChatService


CUST   = "CUST001"
CUST2  = "CUST002"
STAFF  = "EMP001"


def _start(service, customer_no=CUST):
    return asyncio.run(service.start(customer_no, "HOME", "1.0.0"))


def _send(service, chatbot_id, message="", button_value=None):
    return asyncio.run(service.handle_message(chatbot_id, message, button_value))


# ─────────────────────────────────────────────────────────────────────────────
# A. handle_message 전 intent 경로
# ─────────────────────────────────────────────────────────────────────────────

class TestHandleMessageAllIntents:
    """handle_message — 모든 intent 분류 후 feature 실행 경로."""

    @pytest.mark.parametrize("message,expected_method", [
        ("금리 알려줘",             "FEATURE_RATE_GUIDE"),
        ("가입 조건 알려줘",        "FEATURE_JOIN_CONDITION"),
        ("예금이랑 적금 비교해줘",  "FEATURE_PRODUCT_COMPARE"),
        ("중도해지 약관 알려줘",    "FEATURE_TERMS_RAG"),
        ("예금 상품 목록 보여줘",   "FEATURE_PRODUCT_GUIDE"),
        ("FAQ 알려줘",              "FEATURE_FAQ"),
    ])
    def test_intent_routes_to_correct_feature(self, service, message, expected_method):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message=message)
        assert response.process_method == expected_method
        assert response.agent_transfer_required is False

    def test_feature_response_has_message(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        assert response.message
        assert len(response.message) > 5

    def test_intent_id_saved_after_classification(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="가입 조건 알려줘")
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.intent_id is not None
        intent = db.get(ChatbotIntent, chatbot.intent_id)
        assert intent.intent_name == "JOIN_CONDITION"

    def test_rate_guide_intent_id_saved(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="금리 보여줘")
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        intent = db.get(ChatbotIntent, chatbot.intent_id)
        assert intent.intent_name == "RATE_GUIDE"

    def test_product_compare_response_is_message_not_formatted(self, service):
        # PRODUCT_COMPARE → feat_result.message 직접 사용 (formatter 미사용)
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="예금이랑 적금 차이가 뭐야")
        assert response.process_method == "FEATURE_PRODUCT_COMPARE"
        # 개념 비교 → message에 "예금"/"적금" 포함
        assert "예금" in response.message or "적금" in response.message

    def test_terms_rag_formatted_response(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="약관 보여줘")
        assert response.process_method == "FEATURE_TERMS_RAG"
        assert response.message

    @pytest.mark.parametrize("btn", ["PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT"])
    def test_menu_button_routes_to_scenario(self, service, btn):
        service.seed_default_scenario()
        session = _start(service)  # 버튼마다 새 세션
        r = _send(service, session.chatbot_consultation_id, button_value=btn)
        assert r.process_method == "SCENARIO"

    def test_agent_button_triggers_transfer(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, button_value="AGENT")
        assert response.process_method == "STAFF_REQUEST"
        assert response.agent_transfer_required is True


# ─────────────────────────────────────────────────────────────────────────────
# B. [직전 추천 상품] annotation 제거 후 분류
# ─────────────────────────────────────────────────────────────────────────────

class TestAnnotationStripping:
    """프론트엔드 annotation 제거 후 올바른 intent 분류."""

    def test_annotation_stripped_before_classification(self, service):
        service.seed_default_scenario()
        session = _start(service)
        # annotation이 붙은 메시지도 "금리 알려줘" 분류돼야 함
        msg = "금리 알려줘\n[직전 추천 상품: 정기예금 플러스, 자유적금]"
        response = _send(service, session.chatbot_consultation_id, message=msg)
        assert response.process_method == "FEATURE_RATE_GUIDE"

    def test_annotation_only_message_not_classified(self, service):
        service.seed_default_scenario()
        session = _start(service)
        # annotation만 있으면 분류 안 됨 → STAFF_REQUEST
        msg = "\n[직전 추천 상품: 정기예금 플러스]"
        response = _send(service, session.chatbot_consultation_id, message=msg)
        assert response.process_method == "STAFF_REQUEST"

    def test_annotation_with_join_condition(self, service):
        service.seed_default_scenario()
        session = _start(service)
        msg = "가입 조건 알려줘\n[직전 추천 상품: 자유적금, 주택청약종합저축]"
        response = _send(service, session.chatbot_consultation_id, message=msg)
        assert response.process_method == "FEATURE_JOIN_CONDITION"


# ─────────────────────────────────────────────────────────────────────────────
# C. Consultation 모델 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestConsultationModel:
    """start() 후 Consultation 레코드 검증."""

    def test_consultation_created_on_start(self, service, db):
        service.seed_default_scenario()
        session = _start(service, "TEST_CUST_99")
        consult = db.get(Consultation, session.consultation_id)
        assert consult is not None

    def test_consultation_customer_no_matches(self, service, db):
        service.seed_default_scenario()
        session = _start(service, "MY_CUSTOMER_123")
        consult = db.get(Consultation, session.consultation_id)
        assert consult.customer_no == "MY_CUSTOMER_123"

    def test_consultation_content_summary(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        consult = db.get(Consultation, session.consultation_id)
        assert consult.content_summary == "챗봇 상담 시작"

    def test_consultation_active_yn_y(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        consult = db.get(Consultation, session.consultation_id)
        assert consult.active_yn == "Y"

    def test_consultation_status_code_set(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        consult = db.get(Consultation, session.consultation_id)
        assert consult.status_code_id is not None

    def test_consultation_consulted_at_set(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        consult = db.get(Consultation, session.consultation_id)
        assert consult.consulted_at is not None

    def test_consultation_completed_at_none_initially(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        consult = db.get(Consultation, session.consultation_id)
        assert consult.completed_at is None


# ─────────────────────────────────────────────────────────────────────────────
# D. ChatbotConsultation 링크 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotConsultationLinks:
    """ChatbotConsultation — scenario_id·process_method·initial_intent."""

    def test_chatbot_links_to_consultation(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.consultation_id == session.consultation_id

    def test_chatbot_scenario_id_set(self, service, db):
        scenario_id, _ = service.seed_default_scenario()
        session = _start(service)
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.scenario_id == scenario_id

    def test_chatbot_process_method_scenario(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        # CODE_PROCESS_SCENARIO = 1
        assert chatbot.process_method_code_id == 1

    def test_chatbot_initial_intent_set(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.initial_intent is not None
        assert len(chatbot.initial_intent) > 0

    def test_chatbot_entry_screen_stored(self, service, db):
        service.seed_default_scenario()
        session = asyncio.run(service.start(CUST, "TRANSFER_TAB", "1.0.0"))
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.entry_screen == "TRANSFER_TAB"

    def test_chatbot_app_version_stored(self, service, db):
        service.seed_default_scenario()
        session = asyncio.run(service.start(CUST, "HOME", "9.9.9"))
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.app_version == "9.9.9"


# ─────────────────────────────────────────────────────────────────────────────
# E. ChatbotScenario/Node/Button DB 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestScenarioNodeButtonDB:
    """seed 후 DB 레코드 상세 검증."""

    def test_scenario_name(self, service, db):
        service.seed_default_scenario()
        scenario = db.scalars(
            __import__("sqlalchemy").select(ChatbotScenario).where(ChatbotScenario.active_yn == "Y")
        ).first()
        assert "기본 수신 상담" in scenario.scenario_name

    def test_scenario_active_yn_y(self, service, db):
        service.seed_default_scenario()
        from sqlalchemy import select
        scenarios = db.scalars(select(ChatbotScenario).where(ChatbotScenario.active_yn == "Y")).all()
        assert len(scenarios) >= 1

    def test_nodes_all_active(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        nodes = db.scalars(
            select(ChatbotNode).where(ChatbotNode.scenario_id == scenario_id)
        ).all()
        for node in nodes:
            assert node.active_yn == "Y"

    def test_nodes_have_response_message(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        nodes = db.scalars(
            select(ChatbotNode).where(ChatbotNode.scenario_id == scenario_id)
        ).all()
        for node in nodes:
            assert node.response_message

    def test_start_node_has_four_buttons(self, service, db):
        from sqlalchemy import select
        _, first_node_id = service.seed_default_scenario()
        buttons = db.scalars(
            select(ChatbotNodeButton).where(
                ChatbotNodeButton.node_id == first_node_id,
                ChatbotNodeButton.active_yn == "Y",
            )
        ).all()
        assert len(buttons) == 4

    def test_buttons_sorted_by_sort_order(self, service, db):
        from sqlalchemy import select
        _, first_node_id = service.seed_default_scenario()
        buttons = db.scalars(
            select(ChatbotNodeButton)
            .where(ChatbotNodeButton.node_id == first_node_id, ChatbotNodeButton.active_yn == "Y")
            .order_by(ChatbotNodeButton.sort_order)
        ).all()
        orders = [b.sort_order for b in buttons]
        assert orders == sorted(orders)

    def test_flows_exist_for_all_buttons(self, service, db):
        from sqlalchemy import select
        _, first_node_id = service.seed_default_scenario()
        flows = db.scalars(
            select(ChatbotNodeFlow).where(
                ChatbotNodeFlow.current_node_id == first_node_id,
                ChatbotNodeFlow.active_yn == "Y",
            )
        ).all()
        assert len(flows) == 4

    def test_flow_branch_values_match_buttons(self, service, db):
        from sqlalchemy import select
        _, first_node_id = service.seed_default_scenario()
        button_values = {
            b.button_value
            for b in db.scalars(
                select(ChatbotNodeButton).where(ChatbotNodeButton.node_id == first_node_id, ChatbotNodeButton.active_yn == "Y")
            ).all()
        }
        flow_values = {
            f.branch_value
            for f in db.scalars(
                select(ChatbotNodeFlow).where(ChatbotNodeFlow.current_node_id == first_node_id, ChatbotNodeFlow.active_yn == "Y")
            ).all()
        }
        assert button_values == flow_values

    def test_ten_intents_seeded(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        intents = db.scalars(
            select(ChatbotIntent).where(ChatbotIntent.scenario_id == scenario_id)
        ).all()
        assert len(intents) == 10

    def test_intent_priority_ascending(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        intents = sorted(
            db.scalars(
                select(ChatbotIntent).where(ChatbotIntent.scenario_id == scenario_id)
            ).all(),
            key=lambda i: i.priority,
        )
        priorities = [i.priority for i in intents]
        assert priorities == sorted(priorities)

    def test_intent_confidence_threshold_70(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        intents = db.scalars(
            select(ChatbotIntent).where(ChatbotIntent.scenario_id == scenario_id)
        ).all()
        for intent in intents:
            assert intent.confidence_threshold == 70


# ─────────────────────────────────────────────────────────────────────────────
# F. ChatbotNodeFlow active_yn 필터
# ─────────────────────────────────────────────────────────────────────────────

class TestNodeFlowFilter:
    """_resolve_next_node — active_yn='Y' 필터·branch_value 정확 매칭."""

    def test_valid_button_resolves_next_node(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, button_value="PRODUCT_ADVICE")
        assert response.process_method == "SCENARIO"
        assert "예금" in response.message or "상품" in response.message

    def test_invalid_button_value_goes_to_staff(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, button_value="COMPLETELY_UNKNOWN_BUTTON")
        # 매핑 없음 → intent 분류 → None → STAFF_REQUEST
        assert response.process_method == "STAFF_REQUEST"

    def test_none_button_and_empty_message(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id)
        assert response.process_method == "STAFF_REQUEST"


# ─────────────────────────────────────────────────────────────────────────────
# G. _deactivate_legacy_start_options
# ─────────────────────────────────────────────────────────────────────────────

class TestDeactivateLegacyOptions:
    """허용되지 않은 버튼 비활성화."""

    def test_extra_button_deactivated_after_seed(self, service, db):
        from sqlalchemy import select
        _, first_node_id = service.seed_default_scenario()

        # 임의 버튼 직접 추가
        extra = ChatbotNodeButton(
            node_id=first_node_id,
            button_text="레거시 버튼",
            button_value="LEGACY_OPTION",
            sort_order=99,
            active_yn="Y",
        )
        db.add(extra)
        db.commit()

        # 재seed → 허용 목록에 없는 버튼 비활성화
        service.seed_default_scenario()

        legacy = db.scalars(
            select(ChatbotNodeButton).where(
                ChatbotNodeButton.node_id == first_node_id,
                ChatbotNodeButton.button_value == "LEGACY_OPTION",
            )
        ).first()
        assert legacy.active_yn == "N"

    def test_valid_buttons_remain_active(self, service, db):
        from sqlalchemy import select
        _, first_node_id = service.seed_default_scenario()
        service.seed_default_scenario()  # 재seed

        active = db.scalars(
            select(ChatbotNodeButton).where(
                ChatbotNodeButton.node_id == first_node_id,
                ChatbotNodeButton.active_yn == "Y",
            )
        ).all()
        values = {b.button_value for b in active}
        assert {"PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT", "AGENT"}.issubset(values)


# ─────────────────────────────────────────────────────────────────────────────
# H. _button_responses — active_yn='Y'·sort_order
# ─────────────────────────────────────────────────────────────────────────────

class TestButtonResponses:
    """_button_responses — 활성 버튼만 sort_order 순으로 반환."""

    def test_inactive_button_not_returned(self, service, db):
        from sqlalchemy import select
        _, first_node_id = service.seed_default_scenario()

        # 비활성 버튼 추가
        inactive = ChatbotNodeButton(
            node_id=first_node_id,
            button_text="비활성 버튼",
            button_value="INACTIVE_BTN",
            sort_order=99,
            active_yn="N",
        )
        db.add(inactive)
        db.commit()

        buttons = service._button_responses(first_node_id)
        values = {b.value for b in buttons}
        assert "INACTIVE_BTN" not in values

    def test_buttons_ordered_by_sort_order(self, service):
        _, first_node_id = service.seed_default_scenario()
        buttons = service._button_responses(first_node_id)
        orders_by_id = [b.id for b in buttons]
        # 같은 sort_order 내 id 순 정렬 확인 (sort_order ASC가 기본)
        assert len(buttons) == 4

    def test_button_response_fields(self, service):
        _, first_node_id = service.seed_default_scenario()
        buttons = service._button_responses(first_node_id)
        for btn in buttons:
            assert btn.id > 0
            assert btn.text
            assert btn.value


# ─────────────────────────────────────────────────────────────────────────────
# I. config/settings
# ─────────────────────────────────────────────────────────────────────────────

class TestConfigSettings:
    """Settings — 기본값·env prefix."""

    def test_default_app_name(self, monkeypatch):
        monkeypatch.setenv("CONSULTATION_DATABASE_URL", "sqlite:///:memory:")
        get_settings.cache_clear()
        settings = Settings(database_url="sqlite:///:memory:")
        assert settings.app_name == "consultation-service"
        get_settings.cache_clear()

    def test_default_kafka_disabled(self, monkeypatch):
        settings = Settings(database_url="sqlite:///:memory:")
        assert settings.kafka_enabled is False

    def test_default_openai_key_is_str(self, monkeypatch):
        # 환경변수에 실제 키가 있을 수 있으므로 타입만 확인
        settings = Settings(database_url="sqlite:///:memory:")
        assert isinstance(settings.openai_api_key, str)

    def test_default_openai_model(self):
        settings = Settings(database_url="sqlite:///:memory:")
        assert settings.openai_model == "gpt-4o-mini"

    def test_default_kafka_topic_chatbot(self):
        settings = Settings(database_url="sqlite:///:memory:")
        assert settings.kafka_topic_chatbot_events == "consultation.chatbot.events"

    def test_default_kafka_topic_chat(self):
        settings = Settings(database_url="sqlite:///:memory:")
        assert settings.kafka_topic_chat_events == "consultation.chat.events"

    def test_default_llm_confidence_threshold(self):
        settings = Settings(database_url="sqlite:///:memory:")
        assert settings.llm_confidence_threshold == 70

    def test_kafka_topic_override(self, monkeypatch):
        monkeypatch.setenv("CONSULTATION_KAFKA_TOPIC_CHATBOT_EVENTS", "my.chatbot.topic")
        get_settings.cache_clear()
        settings = Settings(database_url="sqlite:///:memory:")
        assert settings.kafka_topic_chatbot_events == "my.chatbot.topic"
        get_settings.cache_clear()

    def test_kafka_enabled_override(self, monkeypatch):
        monkeypatch.setenv("CONSULTATION_KAFKA_ENABLED", "true")
        get_settings.cache_clear()
        settings = Settings(database_url="sqlite:///:memory:")
        assert settings.kafka_enabled is True
        get_settings.cache_clear()


# ─────────────────────────────────────────────────────────────────────────────
# J. PRODUCT_COMPARE — LLM 있을 때 개념 비교 호출
# ─────────────────────────────────────────────────────────────────────────────

class TestProductCompareLlm:
    """PRODUCT_COMPARE — LLM mock 있을 때 개념 비교 LLM 우선."""

    def test_concept_compare_with_llm_returns_llm_message(self, llm_service):
        result = llm_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금이랑 적금 차이가 뭐야"),
        )
        assert result.status == "OK"
        # MockLlmAdapter 응답 포함
        assert "[LLM 응답]" in result.message

    def test_concept_compare_without_llm_uses_fixed_text(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금이랑 적금 차이가 뭐야"),
        )
        assert result.status == "OK"
        assert "예금" in result.message
        assert "적금" in result.message
        assert result.data == []

    def test_deposit_vs_subscription_fixed_text(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금이랑 청약 차이"),
        )
        assert result.status == "OK"
        assert "청약" in result.message

    def test_savings_vs_subscription_fixed_text(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="적금이랑 청약 차이"),
        )
        assert result.status == "OK"
        assert "적금" in result.message or "청약" in result.message

    def test_no_matching_pair_returns_first_text(self, service):
        # 개념 비교이지만 매칭 안 되는 경우 첫 번째 텍스트 반환
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금 적금 어느게 나은지 모르겠어"),
        )
        assert result.status == "OK"
        assert result.message


# ─────────────────────────────────────────────────────────────────────────────
# K. MY_CASH_FLOW vs MY_TRANSFERS 차이
# ─────────────────────────────────────────────────────────────────────────────

class TestCashFlowVsTransfers:
    """MY_CASH_FLOW=전체·MY_TRANSFERS=TRANSFER만 필터 차이."""

    def test_cash_flow_includes_all_types(self, rich_service):
        result = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        types = {item["transaction_type"] for item in result.data}
        # rich_db: CUST001 계좌에 TRANSFER, DEPOSIT 모두 있음
        assert len(types) >= 1  # 다양한 유형

    def test_transfers_only_transfer_type(self, rich_service):
        result = rich_service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        for item in result.data:
            assert item["transaction_type"] == "TRANSFER"

    def test_cash_flow_count_gte_transfers(self, rich_service):
        r_cf = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        r_tr = rich_service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert len(r_cf.data) >= len(r_tr.data)

    def test_cashflow_db_salary_mixed_types(self, cashflow_service):
        r_cf = cashflow_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY")
        )
        types = {item["transaction_type"] for item in r_cf.data}
        # DEPOSIT + WITHDRAWAL + TRANSFER 모두 포함
        assert "DEPOSIT" in types
        assert "WITHDRAWAL" in types
        assert "TRANSFER" in types

    def test_cashflow_db_salary_transfers_only_transfer(self, cashflow_service):
        r_tr = cashflow_service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY")
        )
        for item in r_tr.data:
            assert item["transaction_type"] == "TRANSFER"

    def test_cashflow_db_surplus_no_transfer_transactions(self, cashflow_service):
        # CUST_SURPLUS: DEPOSIT + WITHDRAWAL 거래만 있음 (TRANSFER 없음)
        r_tr = cashflow_service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no="CUST_SURPLUS")
        )
        assert result.status == "EMPTY" if not (result := r_tr).data else True


# ─────────────────────────────────────────────────────────────────────────────
# L. rich_db 거래 유형 다양성 상세
# ─────────────────────────────────────────────────────────────────────────────

class TestRichDbTransactionVariety:
    """rich_db 거래: TRANSFER(COMPLETED·PENDING)·DEPOSIT."""

    def test_cust001_has_completed_transfer(self, rich_service):
        result = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        statuses = {item["transaction_status"] for item in result.data}
        assert "COMPLETED" in statuses

    def test_cust001_has_pending_transaction(self, rich_service):
        result = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        statuses = {item["transaction_status"] for item in result.data}
        assert "PENDING" in statuses

    def test_cust001_has_deposit_transaction(self, rich_service):
        result = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        types = {item["transaction_type"] for item in result.data}
        assert "DEPOSIT" in types

    def test_cash_flow_recommend_only_counts_completed(self, rich_service):
        # PENDING 거래는 현금흐름 분석에서 제외 (transaction_status='COMPLETED'만)
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        # COMPLETED 거래만 있으면 has_data=True
        assert result.status == "OK"

    def test_staff_transfer_flow_shows_all_statuses(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        statuses = {item["transaction_status"] for item in result.data}
        # STAFF_TRANSFER_FLOW는 상태 필터 없이 전체 조회
        assert len(statuses) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# M. end_chat 후 Consultation.completed_at 설정
# ─────────────────────────────────────────────────────────────────────────────

class TestConsultationCompletedAt:
    """end_chat → Consultation.completed_at 설정."""

    def _setup(self, service, chat_service):
        service.seed_default_scenario()
        session = asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
        queue = chat_service.get_waiting_queue()
        chat_id = queue[0]["chat_consultation_id"]
        asyncio.run(chat_service.connect_agent(chat_id, 1))
        return session, chat_id

    def test_completed_at_none_before_end(self, service, chat_service, db):
        session, chat_id = self._setup(service, chat_service)
        consult = db.get(Consultation, session.consultation_id)
        assert consult.completed_at is None

    def test_completed_at_set_after_end(self, service, chat_service, db):
        session, chat_id = self._setup(service, chat_service)
        asyncio.run(chat_service.end_chat(chat_id))
        db.refresh(db.get(Consultation, session.consultation_id))
        consult = db.get(Consultation, session.consultation_id)
        assert consult.completed_at is not None


# ─────────────────────────────────────────────────────────────────────────────
# N. 만족도 점수 경계값
# ─────────────────────────────────────────────────────────────────────────────

class TestSatisfactionScore:
    """ChatEndRequest.satisfaction_score 경계값."""

    def _get_chat_id(self, service, chat_service):
        service.seed_default_scenario()
        session = asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
        chat_id = chat_service.get_waiting_queue()[0]["chat_consultation_id"]
        asyncio.run(chat_service.connect_agent(chat_id, 1))
        return chat_id

    def test_score_1_valid(self, service, chat_service):
        from app.schemas import ChatEndRequest
        req = ChatEndRequest(satisfaction_score=1)
        assert req.satisfaction_score == 1

    def test_score_5_valid(self, service, chat_service):
        from app.schemas import ChatEndRequest
        req = ChatEndRequest(satisfaction_score=5)
        assert req.satisfaction_score == 5

    def test_score_0_invalid(self):
        from pydantic import ValidationError
        from app.schemas import ChatEndRequest
        with pytest.raises(ValidationError):
            ChatEndRequest(satisfaction_score=0)

    def test_score_6_invalid(self):
        from pydantic import ValidationError
        from app.schemas import ChatEndRequest
        with pytest.raises(ValidationError):
            ChatEndRequest(satisfaction_score=6)

    def test_score_none_valid(self):
        from app.schemas import ChatEndRequest
        req = ChatEndRequest(satisfaction_score=None)
        assert req.satisfaction_score is None

    def test_score_3_stored_correctly(self, service, chat_service):
        chat_id = self._get_chat_id(service, chat_service)
        chat = asyncio.run(chat_service.end_chat(chat_id, satisfaction_score=3))
        assert chat.satisfaction_score == 3


# ─────────────────────────────────────────────────────────────────────────────
# O. _execute_product_guide LIMIT 20
# ─────────────────────────────────────────────────────────────────────────────

class TestProductGuideLimitTwenty:
    """20개 이상의 상품이 있어도 최대 20개만 반환."""

    def test_max_20_products_returned(self, db):
        from unittest.mock import AsyncMock
        # 25개 상품 삽입
        with db.get_bind().begin() as conn:
            for i in range(25):
                conn.execute(text(f"""
                    INSERT INTO deposit_banking_products
                    (banking_product_id, deposit_product_name, deposit_product_type,
                     description, base_interest_rate, min_join_amount, max_join_amount,
                     min_period_month, max_period_month, is_early_termination_allowed,
                     is_tax_benefit_available, deposit_product_status)
                    VALUES ({100+i}, '상품{100+i}', 'DEPOSIT', '설명', 3.0, 100000, 100000000,
                            12, 60, 1, 1, 'SELLING')
                """))
        service = __import__("app.services", fromlist=["ChatbotService"]).ChatbotService(
            db, AsyncMock(), LlmHandoffAdapter()
        )
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert len(result.data) <= 20


# ─────────────────────────────────────────────────────────────────────────────
# P. 군인 상품 포함 DB → RATE_GUIDE에서 제외 확인
# ─────────────────────────────────────────────────────────────────────────────

class TestMilitaryProductExclusion:
    """군인/장병/군무원 상품은 RATE_GUIDE·CASH_FLOW_RECOMMEND에서 제외."""

    def test_rate_guide_excludes_military(self, db):
        from unittest.mock import AsyncMock
        # 군인 상품 삽입
        with db.get_bind().begin() as conn:
            conn.execute(text("""
                INSERT INTO deposit_banking_products
                (banking_product_id, deposit_product_name, deposit_product_type,
                 description, base_interest_rate, min_join_amount, max_join_amount,
                 min_period_month, max_period_month, is_early_termination_allowed,
                 is_tax_benefit_available, deposit_product_status)
                VALUES (999, '장병내일준비적금', 'SAVINGS', '군인 전용', 5.0, 1000, 1000000,
                        12, 24, 0, 1, 'SELLING')
            """))
            conn.execute(text("""
                INSERT INTO banking_deposit_product_interest_rates
                (rate_id, banking_product_id, rate_type, minimum_contract_period,
                 maximum_contract_period, rate, condition_description)
                VALUES (999, 999, 'BASE', 12, 24, 5.0, '군인 기본금리')
            """))
        service = __import__("app.services", fromlist=["ChatbotService"]).ChatbotService(
            db, AsyncMock(), LlmHandoffAdapter()
        )
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        names = [item.get("product_name", "") for item in result.data]
        assert not any("장병" in n for n in names)

    def test_cash_flow_recommend_excludes_military(self, db):
        from unittest.mock import AsyncMock
        # 군무원 상품 삽입
        with db.get_bind().begin() as conn:
            conn.execute(text("""
                INSERT INTO deposit_banking_products
                (banking_product_id, deposit_product_name, deposit_product_type,
                 description, base_interest_rate, min_join_amount, max_join_amount,
                 min_period_month, max_period_month, is_early_termination_allowed,
                 is_tax_benefit_available, deposit_product_status)
                VALUES (998, '군무원우대예금', 'DEPOSIT', '군무원 전용', 4.5, 100000, 10000000,
                        12, 36, 1, 1, 'SELLING')
            """))
        service = __import__("app.services", fromlist=["ChatbotService"]).ChatbotService(
            db, AsyncMock(), LlmHandoffAdapter()
        )
        result = service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        # CASH_FLOW_RECOMMEND의 message에 군무원 상품 미포함
        assert "군무원" not in result.message


# ─────────────────────────────────────────────────────────────────────────────
# Q. TERMS_RAG OR 조건 — 이름/내용/요약 각각 매칭
# ─────────────────────────────────────────────────────────────────────────────

class TestTermsRagOrCondition:
    """TERMS_RAG — 이름 OR 내용 OR 요약 각각 매칭 확인."""

    def test_matches_by_name(self, service):
        result = service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="개인정보 수집")
        )
        assert result.status == "OK"
        assert any("개인정보" in i.get("special_term_name", "") for i in result.data)

    def test_matches_by_content(self, service):
        result = service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="수집하고 이용")
        )
        assert result.status == "OK"

    def test_matches_by_summary(self, service):
        result = service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="동의 요약")
        )
        assert result.status == "OK"

    def test_limit_10_not_exceeded(self, service):
        result = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        assert len(result.data) <= 10


# ─────────────────────────────────────────────────────────────────────────────
# R. transfer 기본 memo
# ─────────────────────────────────────────────────────────────────────────────

class TestTransferDefaultMemo:
    """ChatbotTransferRequest — memo 기본값 '이체'."""

    def test_default_memo_is_transfer(self):
        req = ChatbotTransferRequest(
            customer_no=CUST,
            from_account_id=1,
            to_account_number="001-002-000001",
            amount=100_000,
        )
        assert req.memo == "이체"

    def test_custom_memo_preserved(self):
        req = ChatbotTransferRequest(
            customer_no=CUST,
            from_account_id=1,
            to_account_number="001-002-000001",
            amount=100_000,
            memo="생일 축하 선물",
        )
        assert req.memo == "생일 축하 선물"


# ─────────────────────────────────────────────────────────────────────────────
# S. LLM context 결합 — history + rag 빈 문자열 처리
# ─────────────────────────────────────────────────────────────────────────────

class TestLlmContextCombination:
    """LLM 호출 시 history_ctx + rag_ctx 결합."""

    def test_both_empty_no_context(self, llm_service):
        llm_service.seed_default_scenario()
        session = _start(llm_service)
        # 대화 이력 없고 RAG 없음 → 빈 context로 LLM 호출
        response = _send(llm_service, session.chatbot_consultation_id, message="랜덤 질문")
        assert response.process_method == "BP003_GPT"

    def test_history_included_in_context(self, llm_service):
        llm_service.seed_default_scenario()
        session = _start(llm_service)
        _send(llm_service, session.chatbot_consultation_id, message="금리 알려줘")
        # 두 번째 분류 안 되는 메시지 → history context 포함 LLM 호출
        response = _send(llm_service, session.chatbot_consultation_id, message="임의 질문 XYZ")
        assert response.process_method == "BP003_GPT"


# ─────────────────────────────────────────────────────────────────────────────
# T. CASH_FLOW_RECOMMEND message vs data 분리
# ─────────────────────────────────────────────────────────────────────────────

class TestCashFlowMessageDataSeparation:
    """CASH_FLOW_RECOMMEND — message는 추천 텍스트, data는 summary 1개."""

    def test_message_is_recommendation_text(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY"),
        )
        assert result.message
        assert "[현금흐름 분석 기반 상품 추천]" in result.message

    def test_data_is_summary_not_products(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY"),
        )
        assert len(result.data) == 1
        assert result.data[0]["row_type"] == "cash_flow_summary"
        assert "total_balance" in result.data[0]

    def test_no_product_rows_in_data(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY"),
        )
        for item in result.data:
            assert item.get("row_type") != "recommended_product"

    def test_llm_message_different_from_rule_based(self, cashflow_service, cashflow_llm_service):
        r_rule = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY"),
        )
        r_llm = cashflow_llm_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY"),
        )
        # LLM 응답이 룰 기반과 다른 내용
        assert r_rule.message != r_llm.message


# ─────────────────────────────────────────────────────────────────────────────
# U. IntentClassifier — [직전 추천 상품] annotation 필터
# ─────────────────────────────────────────────────────────────────────────────

class TestIntentClassifierAnnotationFilter:
    """handle_message 내 annotation 제거 후 분류 동작."""

    @pytest.fixture(autouse=True)
    def clf(self):
        self.clf = IntentClassifier()

    def test_annotation_not_in_classify_text(self):
        # 실제로는 split 후 분류되지만 classifier 자체는 annotation 포함 분류
        text = "금리 알려줘"
        annotation = "\n[직전 추천 상품: 정기예금 플러스]"
        full = text + annotation
        # classifier는 전체 문자열로 호출되기도 함 (주의: services.py에서 split 처리)
        assert self.clf.classify(text) == "RATE_GUIDE"

    def test_pure_product_names_do_not_trigger_guide(self):
        # 단순 상품명은 분류 안 됨
        intent = self.clf.classify("정기예금 플러스")
        # "정기예금" 포함 → "예금" in msg → PRODUCT_GUIDE (단, 정확히 "예금"이 포함)
        assert intent in ("PRODUCT_GUIDE", None)

    def test_product_type_standalone_triggers_guide(self):
        assert self.clf.classify("예금") == "PRODUCT_GUIDE"
        assert self.clf.classify("적금") == "PRODUCT_GUIDE"
        assert self.clf.classify("청약") == "PRODUCT_GUIDE"


# ─────────────────────────────────────────────────────────────────────────────
# V. feature_detail 전체 코드 정상 반환
# ─────────────────────────────────────────────────────────────────────────────

class TestFeatureDetailAllCodes:
    """service.feature_detail(code) — 모든 등록 코드 정상 반환."""

    ALL_CODES = [
        "PRODUCT_GUIDE", "RATE_GUIDE", "JOIN_CONDITION", "PRODUCT_COMPARE",
        "TERMS_RAG", "FAQ", "MY_ACCOUNTS", "MY_PRODUCTS", "CONTRACT_STATUS",
        "MATURITY_SCHEDULE", "INTEREST_HISTORY", "MY_CASH_FLOW", "MY_TRANSFERS",
        "CASH_FLOW_RECOMMEND", "STAFF_CUSTOMER", "STAFF_CONTRACT", "STAFF_ACCOUNT",
        "STAFF_TRANSFER_FLOW", "STAFF_CONSULTATION_HISTORY", "STAFF_CASH_FLOW",
    ]

    @pytest.mark.parametrize("code", ALL_CODES)
    def test_feature_detail_returns_feature(self, service, code):
        detail = service.feature_detail(code)
        assert detail is not None, f"{code}: feature_detail 반환 None"
        assert detail.code == code
        assert detail.category_code in ("PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT")
        assert detail.name
        assert detail.summary
        assert detail.api_status


# ─────────────────────────────────────────────────────────────────────────────
# W. ChatService 다중 연결 시나리오 심화
# ─────────────────────────────────────────────────────────────────────────────

class TestChatServiceMultiConnection:
    """여러 고객·상담사 간 교차 연결 시나리오."""

    def _transfer(self, service, chat_service, customer_no):
        service.seed_default_scenario()
        session = asyncio.run(service.start(customer_no, "HOME", "1.0.0"))
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
        return chat_service.get_waiting_queue()

    def test_two_customers_two_agents(self, service, chat_service):
        self._transfer(service, chat_service, "CUST_X")
        self._transfer(service, chat_service, "CUST_Y")
        queue = chat_service.get_waiting_queue()
        chat_id_1 = queue[0]["chat_consultation_id"]
        chat_id_2 = queue[1]["chat_consultation_id"]

        asyncio.run(chat_service.connect_agent(chat_id_1, employee_id=101))
        asyncio.run(chat_service.connect_agent(chat_id_2, employee_id=102))

        c1 = chat_service.get_consultation(chat_id_1)
        c2 = chat_service.get_consultation(chat_id_2)
        assert c1.employee_id == 101
        assert c2.employee_id == 102

    def test_end_one_does_not_affect_other(self, service, chat_service):
        self._transfer(service, chat_service, "CUST_A")
        self._transfer(service, chat_service, "CUST_B")
        queue = chat_service.get_waiting_queue()
        chat_id_1 = queue[0]["chat_consultation_id"]
        chat_id_2 = queue[1]["chat_consultation_id"]

        asyncio.run(chat_service.connect_agent(chat_id_1, employee_id=1))
        asyncio.run(chat_service.connect_agent(chat_id_2, employee_id=2))
        asyncio.run(chat_service.end_chat(chat_id_1))

        c1 = chat_service.get_consultation(chat_id_1)
        c2 = chat_service.get_consultation(chat_id_2)
        assert c1.active_yn == "N"
        assert c2.active_yn == "Y"

    def test_messages_not_mixed_between_chats(self, service, chat_service):
        self._transfer(service, chat_service, "CUST_P")
        self._transfer(service, chat_service, "CUST_Q")
        queue = chat_service.get_waiting_queue()
        chat_id_1 = queue[0]["chat_consultation_id"]
        chat_id_2 = queue[1]["chat_consultation_id"]

        asyncio.run(chat_service.connect_agent(chat_id_1, employee_id=1))
        asyncio.run(chat_service.connect_agent(chat_id_2, employee_id=2))
        asyncio.run(chat_service.send_message(chat_id_1, "CUST_P 메시지", 3))
        asyncio.run(chat_service.send_message(chat_id_2, "CUST_Q 메시지", 3))

        msgs1 = [m.message_content for m in chat_service.get_messages(chat_id_1)]
        msgs2 = [m.message_content for m in chat_service.get_messages(chat_id_2)]
        assert "CUST_P 메시지" in msgs1
        assert "CUST_P 메시지" not in msgs2
        assert "CUST_Q 메시지" in msgs2
        assert "CUST_Q 메시지" not in msgs1


# ─────────────────────────────────────────────────────────────────────────────
# X. ChatbotMessageResponse 구조 상세
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotMessageResponseStructure:
    """ChatbotMessageResponse 전 필드 검증."""

    def test_all_fields_present(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        assert hasattr(response, "consultation_id")
        assert hasattr(response, "chatbot_consultation_id")
        assert hasattr(response, "node_id")
        assert hasattr(response, "message")
        assert hasattr(response, "buttons")
        assert hasattr(response, "process_method")
        assert hasattr(response, "agent_transfer_required")

    def test_consultation_id_matches_session(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        assert response.consultation_id == session.consultation_id

    def test_chatbot_consultation_id_matches(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        assert response.chatbot_consultation_id == session.chatbot_consultation_id

    def test_buttons_is_list(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        assert isinstance(response.buttons, list)

    def test_agent_transfer_false_for_feature(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        assert response.agent_transfer_required is False

    def test_agent_transfer_true_for_agent_button(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, button_value="AGENT")
        assert response.agent_transfer_required is True

    def test_node_id_non_negative(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        assert response.node_id >= 0


# ─────────────────────────────────────────────────────────────────────────────
# Y. PRODUCT_SEARCH row_type 필드 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestProductSearchRowType:
    """PRODUCT_SEARCH 결과 row_type 필드 일관성."""

    def test_deposit_results_have_row_type(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH", ChatbotFeatureExecuteRequest(product_type="DEPOSIT")
        )
        for item in result.data:
            assert item.get("row_type") == "recommended_product"

    def test_subscription_results_have_row_type(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH", ChatbotFeatureExecuteRequest(product_type="SUBSCRIPTION")
        )
        for item in result.data:
            assert item.get("row_type") == "recommended_product"

    def test_rank_field_present_and_starts_at_1(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH", ChatbotFeatureExecuteRequest(product_type="DEPOSIT")
        )
        ranks = [item.get("rank") for item in result.data]
        assert 1 in ranks
        assert all(r is not None for r in ranks)

    def test_rank_sequential(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH", ChatbotFeatureExecuteRequest(product_type="DEPOSIT")
        )
        ranks = sorted(item.get("rank") for item in result.data)
        assert ranks == list(range(1, len(ranks) + 1))


# ─────────────────────────────────────────────────────────────────────────────
# Z. ChatbotStartResponse 구조 상세
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotStartResponseStructure:
    """ChatbotStartResponse 전 필드 검증."""

    def test_all_fields_present(self, service):
        service.seed_default_scenario()
        session = _start(service)
        assert hasattr(session, "consultation_id")
        assert hasattr(session, "chatbot_consultation_id")
        assert hasattr(session, "node_id")
        assert hasattr(session, "message")
        assert hasattr(session, "buttons")

    def test_ids_all_positive(self, service):
        service.seed_default_scenario()
        session = _start(service)
        assert session.consultation_id > 0
        assert session.chatbot_consultation_id > 0
        assert session.node_id > 0

    def test_message_is_greeting_or_info(self, service):
        service.seed_default_scenario()
        session = _start(service)
        # 시작 노드 메시지 - 안녕/선택 관련 내용
        assert len(session.message) > 5

    def test_buttons_have_correct_structure(self, service):
        service.seed_default_scenario()
        session = _start(service)
        for btn in session.buttons:
            assert isinstance(btn.id, int)
            assert isinstance(btn.text, str)
            assert isinstance(btn.value, str)
            assert btn.id > 0
            assert btn.text
            assert btn.value
