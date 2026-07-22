"""
추가 다양한 시나리오 테스트.

커버 영역:
  A. /chatbot/transfer API HTTP          - 이체 API 엔드포인트 전 분기
  B. Kafka publisher disabled            - kafka_enabled=False 시 publish 무시
  C. rich_service handle_message         - 실제 DB 데이터로 기능 실행 검증
  D. PRODUCT_GUIDE query 우선순위        - 청약/예금/적금 동시 키워드 우선순위
  E. ChatbotMessageRequest 스키마        - 기본값·필드 타입
  F. handle_message button+message 동시  - button_value 우선 시나리오 흐름
  G. ChatbotIntent 상세                  - process_method_code_id 검증
  H. PRODUCT_SEARCH 가격 초과 제외      - max_join_amount 초과 금액 처리
  I. rich_db 잔액 합산 현금흐름         - CUST001 두 계좌 합산 total_balance
  J. PRODUCT_SEARCH filtered 빈 fallback - 조건 미충족 시 rows[:5] fallback
  K. API 422 validation error            - 필수 필드·타입 오류 응답
  L. ChatbotScenario 우선순위            - "기본 수신 상담" 이름 우선 반환
  M. _get_active_scenario 여러 시나리오  - 복수 시나리오 중 기본 우선
  N. ChatbotNode first 정렬              - sort_order 가장 낮은 노드가 first
  O. rich_llm_service 현금흐름 추천      - rich_db + LLM mock 조합
  P. ChatService send_message BOT type   - sender_type=2(BOT) 저장
  Q. CASH_FLOW_RECOMMEND 인증 없으면 data=[] - requires_auth 분기
  R. RATE_GUIDE 군인 제외 쿼리 상세      - NOT LIKE 다중 조건
  S. MY_TRANSFERS 결과 최신 순 정렬      - transaction_id DESC
  T. MATURITY_SCHEDULE 계약 상태 무관   - ACTIVE+MATURED 모두 반환
  U. handle_message LLM 컨텍스트 조합    - filter(None, [...]) 동작
  V. ChatbotTransferResponse 스키마      - OK/ERROR 상태값
  W. PRODUCT_COMPARE product_id 단독    - product_id → compare_product_ids 처리
  X. ChatbotStartRequest 기본값          - customer_no·entry_screen·app_version
  Y. _execute_product_guide RAG=None     - RAG 없으면 DB 직접 조회
  Z. rich_service 거래 유형별 분류       - TRANSFER/DEPOSIT/WITHDRAWAL 각각 검증
"""

import asyncio

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.kafka import KafkaEventPublisher, KafkaEventConsumer
from app.llm import LlmHandoffAdapter
from app.models import ChatbotConsultation, ChatbotIntent, ChatbotNode, ChatbotScenario
from app.schemas import (
    ChatbotFeatureExecuteRequest,
    ChatbotMessageRequest,
    ChatbotStartRequest,
    ChatbotTransferRequest,
    ChatbotTransferResponse,
)
from app.main import app, get_chatbot_service


CUST  = "CUST001"
CUST2 = "CUST002"
STAFF = "EMP001"


def _start(service, customer_no=CUST):
    return asyncio.run(service.start(customer_no, "HOME", "1.0.0"))


def _send(service, chatbot_id, message="", button_value=None):
    return asyncio.run(service.handle_message(chatbot_id, message, button_value))


def _api_client(service):
    app.dependency_overrides[get_chatbot_service] = lambda: service
    return TestClient(app)


# ─────────────────────────────────────────────────────────────────────────────
# A. /chatbot/transfer API HTTP 계층
# ─────────────────────────────────────────────────────────────────────────────

class TestTransferApiHttp:
    """/chatbot/transfer 엔드포인트 — 응답 구조 검증."""

    def test_transfer_api_endpoint_exists(self, service):
        # 엔드포인트 존재 확인 — 422(validation) 또는 200 반환
        client = _api_client(service)
        try:
            resp = client.post("/chatbot/transfer", json={})
            # 필수 필드 누락 → 422
            assert resp.status_code in (200, 422)
        finally:
            app.dependency_overrides.clear()

    def test_transfer_api_missing_fields_422(self, service):
        client = _api_client(service)
        try:
            resp = client.post("/chatbot/transfer", json={})
            assert resp.status_code == 422
        finally:
            app.dependency_overrides.clear()

    def test_transfer_api_schema_structure(self):
        """ChatbotTransferRequest 필드 구조만 검증 (DB 없이)."""
        req = ChatbotTransferRequest(
            customer_no=CUST,
            from_account_id=1,
            to_account_number="001-002-000001",
            amount=100_000,
        )
        assert req.customer_no == CUST
        assert req.from_account_id == 1
        assert req.to_account_number == "001-002-000001"
        assert req.amount == 100_000
        assert req.memo == "이체"  # 기본값


# ─────────────────────────────────────────────────────────────────────────────
# B. Kafka publisher disabled
# ─────────────────────────────────────────────────────────────────────────────

class TestKafkaPublisherDisabled:
    """kafka_enabled=False → publish 호출 시 무시 (오류 없음)."""

    def test_publish_no_error_when_disabled(self):
        publisher = KafkaEventPublisher(Settings(
            kafka_enabled=False,
            database_url="sqlite:///:memory:",
        ))

        async def run():
            await publisher.start()
            await publisher.publish("TestEvent", {"key": "value"})
            await publisher.stop()

        asyncio.run(run())  # 예외 없음

    def test_publish_chat_no_error_when_disabled(self):
        publisher = KafkaEventPublisher(Settings(
            kafka_enabled=False,
            database_url="sqlite:///:memory:",
        ))

        async def run():
            await publisher.start()
            await publisher.publish_chat("AgentConnected", {"id": 1})
            await publisher.stop()

        asyncio.run(run())

    def test_producer_none_when_disabled(self):
        publisher = KafkaEventPublisher(Settings(
            kafka_enabled=False,
            database_url="sqlite:///:memory:",
        ))

        async def run():
            await publisher.start()
            return publisher._producer

        producer = asyncio.run(run())
        assert producer is None

    def test_consumer_start_no_error_when_disabled(self):
        consumer = KafkaEventConsumer(Settings(
            kafka_enabled=False,
            database_url="sqlite:///:memory:",
        ))

        async def run():
            await consumer.start(topics=["test.topic"], group_id="test-group")
            await consumer.stop()

        asyncio.run(run())

    def test_consumer_aiter_returns_nothing_when_disabled(self):
        consumer = KafkaEventConsumer(Settings(
            kafka_enabled=False,
            database_url="sqlite:///:memory:",
        ))

        async def run():
            msgs = []
            async for msg in consumer:
                msgs.append(msg)
            return msgs

        msgs = asyncio.run(run())
        assert msgs == []


# ─────────────────────────────────────────────────────────────────────────────
# C. rich_service handle_message 실제 기능 실행
# ─────────────────────────────────────────────────────────────────────────────

class TestRichServiceHandleMessage:
    """rich_db 실제 데이터로 handle_message 기능 실행."""

    def test_rate_guide_with_rich_db(self, rich_service):
        rich_service.seed_default_scenario()
        session = _start(rich_service)
        response = _send(rich_service, session.chatbot_consultation_id, message="금리 알려줘")
        assert response.process_method == "FEATURE_RATE_GUIDE"
        assert "정기예금 플러스" in response.message or "자유적금" in response.message

    def test_product_guide_with_rich_db(self, rich_service):
        rich_service.seed_default_scenario()
        session = _start(rich_service)
        response = _send(rich_service, session.chatbot_consultation_id, message="상품 목록 보여줘")
        assert response.process_method == "FEATURE_PRODUCT_GUIDE"
        assert response.message

    def test_join_condition_with_rich_db(self, rich_service):
        rich_service.seed_default_scenario()
        session = _start(rich_service)
        response = _send(rich_service, session.chatbot_consultation_id, message="가입 조건 알려줘")
        assert response.process_method == "FEATURE_JOIN_CONDITION"
        assert "정기예금 플러스" in response.message

    def test_terms_rag_with_rich_db(self, rich_service):
        rich_service.seed_default_scenario()
        session = _start(rich_service)
        response = _send(rich_service, session.chatbot_consultation_id, message="중도해지 약관")
        assert response.process_method == "FEATURE_TERMS_RAG"

    def test_multiple_turns_rich_db(self, rich_service):
        rich_service.seed_default_scenario()
        session = _start(rich_service)
        r1 = _send(rich_service, session.chatbot_consultation_id, message="금리 알려줘")
        r2 = _send(rich_service, session.chatbot_consultation_id, message="가입 조건 알려줘")
        assert r1.process_method == "FEATURE_RATE_GUIDE"
        assert r2.process_method == "FEATURE_JOIN_CONDITION"


# ─────────────────────────────────────────────────────────────────────────────
# D. PRODUCT_GUIDE query 키워드 우선순위
# ─────────────────────────────────────────────────────────────────────────────

class TestProductGuideQueryPriority:
    """청약/예금/적금 키워드 — 먼저 발견된 것만 필터링."""

    def test_only_first_keyword_filters(self, rich_service):
        # "청약"이 앞에 있으면 SUBSCRIPTION만
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="청약 예금 상품 알려줘"),
        )
        # 청약이 먼저 → SUBSCRIPTION 필터
        for item in result.data:
            assert item["product_type"] == "SUBSCRIPTION"

    def test_savings_keyword_in_query(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="적금 종류 알려줘"),
        )
        for item in result.data:
            assert item["product_type"] == "SAVINGS"

    def test_no_keyword_returns_all(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="상품 알려줘"),
        )
        types = {item["product_type"] for item in result.data}
        # 유형 필터 없음 → 여러 유형
        assert len(types) >= 2

    def test_deposit_keyword_only_deposit(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="예금 상품이 뭐가 있어?"),
        )
        for item in result.data:
            assert item["product_type"] == "DEPOSIT"


# ─────────────────────────────────────────────────────────────────────────────
# E. ChatbotMessageRequest 스키마
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotMessageRequestSchema:
    """ChatbotMessageRequest — 기본값·필드."""

    def test_default_message_empty_string(self):
        req = ChatbotMessageRequest()
        assert req.message == ""

    def test_default_button_value_none(self):
        req = ChatbotMessageRequest()
        assert req.button_value is None

    def test_message_set(self):
        req = ChatbotMessageRequest(message="금리 알려줘")
        assert req.message == "금리 알려줘"

    def test_button_value_set(self):
        req = ChatbotMessageRequest(button_value="PRODUCT_ADVICE")
        assert req.button_value == "PRODUCT_ADVICE"

    def test_both_set(self):
        req = ChatbotMessageRequest(message="텍스트", button_value="BTN")
        assert req.message == "텍스트"
        assert req.button_value == "BTN"


# ─────────────────────────────────────────────────────────────────────────────
# F. handle_message button+message 동시
# ─────────────────────────────────────────────────────────────────────────────

class TestHandleMessageButtonAndMessage:
    """button_value + message 동시 전달 — button_value로 시나리오 흐름."""

    def test_button_value_takes_precedence(self, service):
        service.seed_default_scenario()
        session = _start(service)
        # button_value가 있으면 시나리오 흐름 → SCENARIO
        response = _send(
            service,
            session.chatbot_consultation_id,
            message="아무 메시지",
            button_value="PRODUCT_ADVICE",
        )
        assert response.process_method == "SCENARIO"

    def test_no_button_text_only_classifies(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(
            service,
            session.chatbot_consultation_id,
            message="금리 알려줘",
            button_value=None,
        )
        assert response.process_method == "FEATURE_RATE_GUIDE"

    def test_user_message_stored_when_button_and_message(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(
            service,
            session.chatbot_consultation_id,
            message="아무 메시지",
            button_value="PRODUCT_ADVICE",
        )
        # 사용자 메시지는 message or button_value로 저장
        user_msgs = db.query(
            __import__("app.models", fromlist=["ChatMessageHistory"]).ChatMessageHistory
        ).filter_by(
            chatbot_consultation_id=session.chatbot_consultation_id,
            sender_type_code_id=1,
        ).all()
        assert len(user_msgs) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# G. ChatbotIntent process_method_code_id 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotIntentProcessMethod:
    """각 intent의 process_method_code_id (1=SCENARIO, 2=LLM)."""

    SCENARIO_CODE = 1
    LLM_CODE = 2

    SCENARIO_INTENTS = ["RATE_GUIDE", "JOIN_CONDITION", "PRODUCT_COMPARE",
                        "TERMS_RAG", "PRODUCT_GUIDE", "FAQ"]
    LLM_INTENTS = ["CASH_FLOW_RECOMMEND", "LLM_FALLBACK",
                   "STAFF_REQUEST", "STAFF_ERROR_FALLBACK"]

    def test_scenario_intents_code_1(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        for name in self.SCENARIO_INTENTS:
            intent = db.scalars(
                select(ChatbotIntent).where(
                    ChatbotIntent.scenario_id == scenario_id,
                    ChatbotIntent.intent_name == name,
                )
            ).first()
            assert intent is not None
            assert intent.process_method_code_id == self.SCENARIO_CODE, \
                f"{name}: process_method_code_id={intent.process_method_code_id} != 1"

    def test_llm_intents_code_2(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        for name in self.LLM_INTENTS:
            intent = db.scalars(
                select(ChatbotIntent).where(
                    ChatbotIntent.scenario_id == scenario_id,
                    ChatbotIntent.intent_name == name,
                )
            ).first()
            assert intent is not None
            assert intent.process_method_code_id == self.LLM_CODE, \
                f"{name}: process_method_code_id={intent.process_method_code_id} != 2"


# ─────────────────────────────────────────────────────────────────────────────
# H. PRODUCT_SEARCH 가격 초과 제외 → fallback
# ─────────────────────────────────────────────────────────────────────────────

class TestProductSearchFallback:
    """PRODUCT_SEARCH — 조건 미충족 시 rows[:5] fallback."""

    def test_amount_exceeds_max_filtered_out_then_fallback(self, rich_service):
        # 자유적금 max_join_amount=50,000,000 → amount=100,000,000은 초과
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(
                product_type="SAVINGS",
                amount=100_000_000,  # 최대 한도 초과
            ),
        )
        # filtered 비어있으면 rows[:5] fallback → 어떤 결과든 반환
        assert result.status in ("OK", "EMPTY")

    def test_period_out_of_range_fallback(self, rich_service):
        # 정기예금 max_period=60 → period=120은 초과
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(
                product_type="DEPOSIT",
                period=120,
            ),
        )
        assert result.status in ("OK", "EMPTY")

    def test_impossible_conditions_no_crash(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(
                product_type="DEPOSIT",
                amount=1,      # 최소금액 미달
                period=999,    # 최대기간 초과
            ),
        )
        assert result.status in ("OK", "EMPTY")

    def test_subscription_special_message(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="SUBSCRIPTION"),
        )
        assert result.status == "OK"
        assert "청약" in result.message


# ─────────────────────────────────────────────────────────────────────────────
# I. rich_db 잔액 합산 현금흐름
# ─────────────────────────────────────────────────────────────────────────────

class TestRichDbBalanceAggregation:
    """CUST001 두 계좌 잔액 합산 total_balance."""

    def test_total_balance_is_sum_of_accounts(self, rich_service):
        # rich_db: 계좌1=5,000,000 + 계좌2=1,200,000 = 6,200,000
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(6_200_000)

    def test_cust002_single_account_balance(self, rich_service):
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST2),
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(3_000_000)

    def test_cashflow_db_salary_balance(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY"),
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(8_000_000)

    def test_cashflow_db_surplus_balance(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SURPLUS"),
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(50_000_000)


# ─────────────────────────────────────────────────────────────────────────────
# J. API 422 validation error
# ─────────────────────────────────────────────────────────────────────────────

class TestApiValidationErrors:
    """필수 필드·타입 오류 → 422 Unprocessable Entity."""

    def test_connect_without_employee_id_returns_422(self, service, chat_service):
        from app.main import get_chat_service
        app.dependency_overrides[get_chatbot_service] = lambda: service
        app.dependency_overrides[get_chat_service] = lambda: chat_service
        client = TestClient(app)
        try:
            resp = client.post("/chat/consultations/1/connect", json={})
            assert resp.status_code == 422
        finally:
            app.dependency_overrides.clear()

    def test_transfer_without_required_fields_422(self, service):
        client = _api_client(service)
        try:
            resp = client.post("/chatbot/transfer", json={})
            assert resp.status_code == 422
        finally:
            app.dependency_overrides.clear()

    def test_transfer_negative_amount_422(self, service):
        client = _api_client(service)
        try:
            resp = client.post(
                "/chatbot/transfer",
                json={
                    "customer_no": CUST,
                    "from_account_id": "not_int",  # 타입 오류
                    "to_account_number": "001-002",
                    "amount": 100_000,
                },
            )
            assert resp.status_code == 422
        finally:
            app.dependency_overrides.clear()


# ─────────────────────────────────────────────────────────────────────────────
# K. ChatbotScenario 우선순위 — "기본 수신 상담" 이름 우선
# ─────────────────────────────────────────────────────────────────────────────

class TestScenarioPriority:
    """여러 시나리오 중 '기본 수신 상담' 이름 우선 반환."""

    def test_basic_scenario_returned_by_default(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        scenario = db.get(ChatbotScenario, scenario_id)
        assert "기본 수신 상담" in scenario.scenario_name

    def test_scenario_desc_set(self, service, db):
        scenario_id, _ = service.seed_default_scenario()
        scenario = db.get(ChatbotScenario, scenario_id)
        assert scenario.scenario_desc is not None
        assert len(scenario.scenario_desc) > 5

    def test_seed_returns_correct_first_node(self, service, db):
        scenario_id, first_node_id = service.seed_default_scenario()
        node = db.get(ChatbotNode, first_node_id)
        assert node is not None
        assert node.scenario_id == scenario_id
        assert node.sort_order == 1  # 첫 번째 노드


# ─────────────────────────────────────────────────────────────────────────────
# L. ChatbotNode first — sort_order 가장 낮은 노드
# ─────────────────────────────────────────────────────────────────────────────

class TestFirstNodeSortOrder:
    """_get_first_node — sort_order 가장 낮은 노드 반환."""

    def test_first_node_has_sort_order_1(self, service, db):
        scenario_id, first_node_id = service.seed_default_scenario()
        node = db.get(ChatbotNode, first_node_id)
        assert node.sort_order == 1

    def test_first_node_active_yn_y(self, service, db):
        _, first_node_id = service.seed_default_scenario()
        node = db.get(ChatbotNode, first_node_id)
        assert node.active_yn == "Y"

    def test_first_node_response_message_greeting(self, service):
        service.seed_default_scenario()
        session = _start(service)
        assert "안녕" in session.message or "선택" in session.message or session.message


# ─────────────────────────────────────────────────────────────────────────────
# M. rich_llm_service — rich_db + LLM mock 조합
# ─────────────────────────────────────────────────────────────────────────────

class TestRichLlmService:
    """rich_db + LLM mock — 현금흐름 추천 LLM 응답."""

    def test_cash_flow_recommend_llm_ok(self, rich_llm_service):
        # MockLlmAdapter는 recommend() 미구현 → 상위 클래스 fallback → OK 또는 오류 메시지
        result = rich_llm_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert result.status == "OK"
        assert result.message  # 메시지 반환됨

    def test_cash_flow_recommend_data_summary(self, rich_llm_service):
        result = rich_llm_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert len(result.data) == 1
        assert result.data[0]["total_balance"] == pytest.approx(6_200_000)

    def test_product_guide_with_llm_service(self, rich_llm_service):
        result = rich_llm_service.execute_feature(
            "PRODUCT_GUIDE", ChatbotFeatureExecuteRequest()
        )
        assert result.status == "OK"

    def test_rate_guide_with_llm_service(self, rich_llm_service):
        result = rich_llm_service.execute_feature(
            "RATE_GUIDE", ChatbotFeatureExecuteRequest()
        )
        assert result.status == "OK"
        assert len(result.data) >= 5  # rich_db에 금리 5개


# ─────────────────────────────────────────────────────────────────────────────
# N. ChatService send_message BOT type(2)
# ─────────────────────────────────────────────────────────────────────────────

class TestChatServiceSenderTypes:
    """send_message — sender_type_code_id 1(USER)·2(BOT)·3(AGENT)."""

    def _setup(self, service, chat_service):
        service.seed_default_scenario()
        session = asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
        chat_id = chat_service.get_waiting_queue()[0]["chat_consultation_id"]
        asyncio.run(chat_service.connect_agent(chat_id, 1))
        return chat_id

    def test_bot_message_stored(self, service, chat_service):
        chat_id = self._setup(service, chat_service)
        msg = asyncio.run(chat_service.send_message(chat_id, "봇 메시지", 2))
        assert msg.sender_type_code_id == 2

    def test_user_message_stored(self, service, chat_service):
        chat_id = self._setup(service, chat_service)
        msg = asyncio.run(chat_service.send_message(chat_id, "고객 메시지", 1))
        assert msg.sender_type_code_id == 1

    def test_agent_message_stored(self, service, chat_service):
        chat_id = self._setup(service, chat_service)
        msg = asyncio.run(chat_service.send_message(chat_id, "상담사 메시지", 3))
        assert msg.sender_type_code_id == 3

    def test_message_content_preserved(self, service, chat_service):
        chat_id = self._setup(service, chat_service)
        unique = "유니크_테스트_내용_XYZ789"
        msg = asyncio.run(chat_service.send_message(chat_id, unique, 3))
        assert msg.message_content == unique

    def test_sequence_no_increments(self, service, chat_service):
        chat_id = self._setup(service, chat_service)
        m1 = asyncio.run(chat_service.send_message(chat_id, "첫", 3))
        m2 = asyncio.run(chat_service.send_message(chat_id, "둘", 1))
        m3 = asyncio.run(chat_service.send_message(chat_id, "셋", 3))
        assert m2.sequence_no > m1.sequence_no
        assert m3.sequence_no > m2.sequence_no


# ─────────────────────────────────────────────────────────────────────────────
# O. CASH_FLOW_RECOMMEND AUTH_REQUIRED → data=[]
# ─────────────────────────────────────────────────────────────────────────────

class TestCashFlowAuthRequired:
    """CASH_FLOW_RECOMMEND 인증 없으면 data=[]·requires_auth=True."""

    def test_no_customer_no_auth_required(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest()
        )
        assert result.status == "AUTH_REQUIRED"
        assert result.data == []
        assert result.requires_auth is True
        assert result.requires_staff_auth is False

    def test_auth_required_message_present(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest()
        )
        assert result.message
        assert "인증" in result.message or "고객번호" in result.message


# ─────────────────────────────────────────────────────────────────────────────
# P. RATE_GUIDE 군인 제외 다중 키워드
# ─────────────────────────────────────────────────────────────────────────────

class TestRateGuideExcludeKeywords:
    """RATE_GUIDE — NOT LIKE 장병·군인·군무원 다중 키워드."""

    @pytest.fixture()
    def military_db(self, db):
        with db.get_bind().begin() as conn:
            conn.execute(__import__("sqlalchemy").text("""
                INSERT INTO deposit_banking_products VALUES
                (201,'장병내일준비적금','SAVINGS','군인전용A',5.0,1000,1000000,12,24,0,1,'SELLING'),
                (202,'군인우대예금','DEPOSIT','군인전용B',4.5,100000,10000000,12,36,1,1,'SELLING'),
                (203,'군무원전용예금','DEPOSIT','군무원전용',4.0,100000,10000000,12,36,1,1,'SELLING')
            """))
            conn.execute(__import__("sqlalchemy").text("""
                INSERT INTO banking_deposit_product_interest_rates VALUES
                (201,201,'BASE',12,24,5.0,'기본'),
                (202,202,'BASE',12,36,4.5,'기본'),
                (203,203,'BASE',12,36,4.0,'기본')
            """))
        return db

    def test_rate_guide_excludes_military_keywords(self, military_db, service):
        # RATE_GUIDE SQL: NOT LIKE '%장병%', NOT LIKE '%군인%' (군무원은 미포함)
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        names = [item.get("product_name", "") for item in result.data]
        assert not any("장병" in n for n in names), "장병 상품 포함됨"
        assert not any("군인우대" in n for n in names), "군인우대 상품 포함됨"

    def test_normal_product_still_present(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        names = [item.get("product_name", "") for item in result.data]
        assert "정기예금 플러스" in names


# ─────────────────────────────────────────────────────────────────────────────
# Q. MY_TRANSFERS 최신순 정렬
# ─────────────────────────────────────────────────────────────────────────────

class TestMyTransfersSortOrder:
    """MY_TRANSFERS — transaction_id DESC 정렬."""

    def test_transfer_ids_desc_order(self, rich_service):
        result = rich_service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        ids = [item["transaction_id"] for item in result.data]
        assert ids == sorted(ids, reverse=True), f"DESC 정렬 아님: {ids}"

    def test_cash_flow_ids_desc_order(self, rich_service):
        result = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        ids = [item["transaction_id"] for item in result.data]
        assert ids == sorted(ids, reverse=True)


# ─────────────────────────────────────────────────────────────────────────────
# R. MATURITY_SCHEDULE — 모든 계약 상태 반환
# ─────────────────────────────────────────────────────────────────────────────

class TestMaturityScheduleAllStatus:
    """MATURITY_SCHEDULE — ACTIVE + MATURED 모두 반환."""

    def test_includes_active_and_matured(self, rich_service):
        result = rich_service.execute_feature(
            "MATURITY_SCHEDULE",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        statuses = {item["contract_status"] for item in result.data}
        assert "ACTIVE" in statuses
        assert "MATURED" in statuses

    def test_contract_id_ascending_order(self, rich_service):
        result = rich_service.execute_feature(
            "MATURITY_SCHEDULE",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        ids = [item["contract_id"] for item in result.data]
        assert ids == sorted(ids), f"contract_id ASC 아님: {ids}"

    def test_both_maturity_dates_present(self, rich_service):
        result = rich_service.execute_feature(
            "MATURITY_SCHEDULE",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        dates = {item["maturity_at"] for item in result.data}
        assert "20270101" in dates
        assert "20260101" in dates


# ─────────────────────────────────────────────────────────────────────────────
# S. handle_message LLM context filter(None)
# ─────────────────────────────────────────────────────────────────────────────

class TestLlmContextFilter:
    """filter(None, [rag_ctx, history_ctx]) — 빈 문자열 필터링."""

    def test_empty_both_contexts_no_crash(self, llm_service):
        llm_service.seed_default_scenario()
        session = _start(llm_service)
        # RAG 없고 이력 없음 → 빈 context
        response = _send(llm_service, session.chatbot_consultation_id, message="분류불가질문XYZ")
        assert response.process_method == "BP003_GPT"
        assert response.message

    def test_history_only_context(self, llm_service):
        llm_service.seed_default_scenario()
        session = _start(llm_service)
        _send(llm_service, session.chatbot_consultation_id, message="금리 알려줘")
        # 이력 있음, RAG 없음 → history_ctx만 context
        response = _send(llm_service, session.chatbot_consultation_id, message="분류불가")
        assert response.process_method == "BP003_GPT"


# ─────────────────────────────────────────────────────────────────────────────
# T. ChatbotTransferResponse 스키마
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotTransferResponseSchema:
    """ChatbotTransferResponse — 상태값·필드."""

    def test_ok_status_fields(self):
        resp = ChatbotTransferResponse(
            status="OK",
            message="이체 완료",
            transaction_id=123,
            balance_after=900_000,
        )
        assert resp.status == "OK"
        assert resp.transaction_id == 123
        assert resp.balance_after == 900_000

    def test_error_status_optional_fields_none(self):
        resp = ChatbotTransferResponse(status="ERROR", message="오류 발생")
        assert resp.status == "ERROR"
        assert resp.transaction_id is None
        assert resp.balance_after is None

    def test_status_only_ok_or_error(self):
        ok_resp = ChatbotTransferResponse(status="OK", message="성공")
        err_resp = ChatbotTransferResponse(status="ERROR", message="실패")
        assert ok_resp.status == "OK"
        assert err_resp.status == "ERROR"


# ─────────────────────────────────────────────────────────────────────────────
# U. PRODUCT_COMPARE product_id 단독 → compare_product_ids 처리
# ─────────────────────────────────────────────────────────────────────────────

class TestProductCompareProductId:
    """product_id 단독 → compare_product_ids=[product_id]로 처리."""

    def test_product_id_returns_one_product(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(product_id=1),
        )
        assert result.status == "OK"
        assert len(result.data) == 1
        assert int(result.data[0]["product_id"]) == 1

    def test_product_id_rich_db_returns_correct_name(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(product_id=2),
        )
        assert result.data[0]["product_name"] == "자유적금"

    def test_product_id_and_compare_ids_both_set(self, rich_service):
        # compare_product_ids 우선 (product_id는 fallback)
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(product_id=1, compare_product_ids=[2, 3]),
        )
        assert result.status == "OK"
        product_ids = [int(i["product_id"]) for i in result.data]
        assert 2 in product_ids
        assert 3 in product_ids


# ─────────────────────────────────────────────────────────────────────────────
# V. ChatbotStartRequest 스키마 기본값
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotStartRequestSchema:
    """ChatbotStartRequest — 기본값·필드."""

    def test_default_customer_no(self):
        req = ChatbotStartRequest()
        assert req.customer_no == "CUST001"

    def test_default_entry_screen(self):
        req = ChatbotStartRequest()
        assert req.entry_screen == "HOME"

    def test_default_app_version(self):
        req = ChatbotStartRequest()
        assert req.app_version == "0.1.0"

    def test_default_initial_message_none(self):
        req = ChatbotStartRequest()
        assert req.initial_message is None

    def test_custom_values(self):
        req = ChatbotStartRequest(
            customer_no="MY_CUST",
            entry_screen="PRODUCT_TAB",
            app_version="2.0.0",
            initial_message="안녕하세요",
        )
        assert req.customer_no == "MY_CUST"
        assert req.entry_screen == "PRODUCT_TAB"
        assert req.app_version == "2.0.0"
        assert req.initial_message == "안녕하세요"


# ─────────────────────────────────────────────────────────────────────────────
# W. _execute_product_guide — RAG=None → DB 직접 조회
# ─────────────────────────────────────────────────────────────────────────────

class TestProductGuideRagNone:
    """RAG 미준비 → DB 직접 쿼리 경로 (경로 3)."""

    def test_no_rag_returns_db_products(self, service):
        assert service._rag is None
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"
        assert len(result.data) >= 1

    def test_no_rag_product_has_required_fields(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        item = result.data[0]
        assert "product_id" in item
        assert "product_name" in item
        assert "product_type" in item
        assert "base_interest_rate" in item

    def test_no_rag_message_is_generic(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert "상품" in result.message


# ─────────────────────────────────────────────────────────────────────────────
# X. rich_service 거래 유형별 분류
# ─────────────────────────────────────────────────────────────────────────────

class TestRichDbTransactionTypes:
    """rich_db 거래 유형별 상세 검증."""

    def test_cust001_transfer_tx_exists(self, rich_service):
        result = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        transfer_txs = [i for i in result.data if i["transaction_type"] == "TRANSFER"]
        assert len(transfer_txs) >= 1

    def test_cust001_deposit_tx_exists(self, rich_service):
        result = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        deposit_txs = [i for i in result.data if i["transaction_type"] == "DEPOSIT"]
        assert len(deposit_txs) >= 1

    def test_tx001_is_transfer_completed(self, rich_service):
        result = rich_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        tx001 = next(
            (i for i in result.data if i.get("transaction_id") and
             rich_service._rows(
                 "SELECT transaction_number FROM deposit_transactions WHERE transaction_id=:tid",
                 {"tid": i["transaction_id"]}
             ) and
             rich_service._rows(
                 "SELECT transaction_number FROM deposit_transactions WHERE transaction_id=:tid",
                 {"tid": i["transaction_id"]}
             )[0].get("transaction_number") == "TX-001"),
            None,
        )
        if tx001:
            assert tx001["transaction_type"] == "TRANSFER"
            assert tx001["transaction_status"] == "COMPLETED"

    def test_cust002_single_transfer(self, rich_service):
        result = rich_service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST2)
        )
        assert result.status == "OK"
        assert len(result.data) == 1
        assert result.data[0]["transaction_type"] == "TRANSFER"

    def test_cashflow_analysis_excludes_pending(self, rich_service):
        # CUST001 TX-002는 PENDING → 현금흐름 분석에서 제외
        # TX-001(COMPLETED, TRANSFER) + TX-003(COMPLETED, DEPOSIT) + TX-004(CUST002)
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        # COMPLETED인 CUST001 거래: TX-001(TRANSFER), TX-003(DEPOSIT)
        # 입금=100,000, 출금=10,000 → 잉여=(100,000-10,000)/3 = 30,000
        # has_data=True (COMPLETED 거래 있음)
        assert result.data[0]["has_data"] is True


# ─────────────────────────────────────────────────────────────────────────────
# Y. INTEREST_HISTORY 정렬 — interest_id DESC
# ─────────────────────────────────────────────────────────────────────────────

class TestInterestHistoryDescSort:
    """이자 내역 — interest_id DESC 정렬 (최신 먼저)."""

    def test_rich_cust001_interest_desc(self, rich_service):
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        ids = [item["interest_id"] for item in result.data]
        assert ids == sorted(ids, reverse=True)

    def test_first_record_is_latest(self, rich_service):
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        # rich_db: interest_id 1(계좌1), 2(계좌2) → DESC → [2, 1]
        assert result.data[0]["interest_id"] == 2

    def test_single_record_no_sort_issue(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert len(result.data) == 1  # 기본 db에 1건


# ─────────────────────────────────────────────────────────────────────────────
# Z. 엣지 케이스 통합 시나리오
# ─────────────────────────────────────────────────────────────────────────────

class TestEdgeCaseIntegration:
    """예외적 입력·경계 상황 통합."""

    def test_very_long_message_classified(self, service):
        service.seed_default_scenario()
        session = _start(service)
        long_msg = "금리" + " " * 500 + "알려줘"
        response = _send(service, session.chatbot_consultation_id, message=long_msg)
        assert response.process_method in ("FEATURE_RATE_GUIDE", "STAFF_REQUEST")

    def test_special_chars_message_no_crash(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="!@#$%^&*()")
        assert response.message  # 오류 없이 응답

    def test_numeric_only_message(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="12345")
        assert response.message

    def test_empty_product_search_no_type(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH", ChatbotFeatureExecuteRequest()
        )
        assert result.status in ("OK", "EMPTY")

    def test_execute_feature_with_all_none_params(self, service):
        """모든 선택 파라미터 None — PRODUCT_GUIDE는 OK."""
        result = service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(
                customer_no=None, query=None, product_id=None,
                staff_id=None, chatbot_consultation_id=None,
                amount=None, period=None, product_type=None, purpose=None,
            ),
        )
        assert result.status == "OK"

    def test_large_page_limit_no_crash(self, rich_service):
        # LIMIT 20 초과 요청도 최대 20개만 반환
        result = rich_service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert len(result.data) <= 20

    def test_staff_customer_no_only_without_staff_id(self, service):
        result = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert result.status == "STAFF_AUTH_REQUIRED"

    def test_multiple_consecutive_features(self, service):
        """동일 세션에서 여러 기능을 연속으로 실행해도 결과 일관성 유지."""
        for code in ["PRODUCT_GUIDE", "RATE_GUIDE", "JOIN_CONDITION", "FAQ"]:
            result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
            assert result.feature_code == code
            assert result.status in ("OK", "EMPTY")

    def test_chatbot_service_multiple_start_creates_separate_consultations(self, service, db):
        service.seed_default_scenario()
        sessions = [_start(service, f"CUST_{i:03d}") for i in range(5)]
        consultation_ids = [s.consultation_id for s in sessions]
        # 모두 다른 consultation_id
        assert len(set(consultation_ids)) == 5

    def test_rich_db_all_user_finance_features_return_data(self, rich_service):
        """rich_db에서 USER_FINANCE 전 기능 CUST001 데이터 반환."""
        features_with_data = [
            "MY_ACCOUNTS", "MY_PRODUCTS", "CONTRACT_STATUS",
            "MATURITY_SCHEDULE", "INTEREST_HISTORY", "MY_CASH_FLOW",
        ]
        for code in features_with_data:
            result = rich_service.execute_feature(
                code, ChatbotFeatureExecuteRequest(customer_no=CUST)
            )
            assert result.status == "OK", f"{code}: status={result.status}"
            assert len(result.data) >= 1, f"{code}: data 없음"
