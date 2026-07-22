"""
최종 커버리지 테스트 — 3차 심화.

커버 영역:
  A. /health 엔드포인트              - 서버 생존 확인
  B. 채팅 API 전체 HTTP 계층         - /chat/queue, /connect, /messages, /end
  C. seed_default_scenario 멱등성    - 반복 호출해도 결과 일관성
  D. handle_message 비정상 ID        - 존재하지 않는 chatbot_consultation_id
  E. FeatureAnswerFormatter 단위     - 각 포맷터(_rate, _products, _join, _compare, _terms)
  F. _chat_status 함수               - WAITING/CONNECTED/ENDED 상태값 검증
  G. category name/description       - 카테고리 한국어 메타데이터 검증
  H. feature api_status 필드         - API_STATUS 값 전수 확인
  I. MY_PRODUCTS vs CONTRACT_STATUS  - 동일 _contract_rows 사용, 데이터 동일성
  J. PRODUCT_GUIDE 정렬              - base_interest_rate DESC 순서
  K. CUST002 이자내역                 - rich_db CUST002 이자 내역 분리 조회
  L. LLM 오류 → 상담사 이관         - LLM 오류 시 STAFF_ERROR_FALLBACK 처리
  M. PRODUCT_SEARCH 복합 조건        - amount+period+type+purpose 동시 사용
  N. ChatbotStartRequest 기본값      - 기본값 필드 동작 확인
  O. ChatConsultation waiting_seconds - 대기 시간 양수 검증
  P. CUST002 전체 기능 접근          - rich_db에서 CUST002 전체 조회
  Q. 시나리오 버튼 텍스트 검증       - 버튼 text 필드 내용
  R. 동일 시나리오 중복 seed 방지    - seed 후 재seed 시 노드 중복 없음
  S. ChatbotFeatureExecuteRequest 조합 - compare_product_ids + query 복합
  T. execute_feature 전체 기능 코드  - 등록된 모든 handler 정상 호출 확인
  U. 빈 button_value + 빈 message    - 버튼값/메시지 없이 handle_message 호출
  V. 이자 내역 복수 조회 정렬        - interest_id DESC 정렬 검증
  W. rich_db STAFF 기능 CUST002      - 직원이 CUST002 계약/계좌 조회
  X. features 전체 sample_questions  - 모든 기능 샘플 질문 비어있지 않음
"""

import asyncio
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.llm import FeatureAnswerFormatter, IntentClassifier, LlmAdapter, LlmHandoffAdapter
from app.main import app, get_chat_service, get_chatbot_service
from app.models import ChatMessageHistory, ChatbotConsultation, ChatbotNode
from app.schemas import ChatbotFeatureExecuteRequest
from app.services import ChatbotService, ChatService, _chat_status


# ── 공통 상수·헬퍼 ────────────────────────────────────────────────────────────

CUST  = "CUST001"
CUST2 = "CUST002"
STAFF = "EMP001"
NO_CUST = "NO_SUCH_XYZ"


def _api_client(service, chat_service=None):
    app.dependency_overrides[get_chatbot_service] = lambda: service
    if chat_service:
        app.dependency_overrides[get_chat_service] = lambda: chat_service
    return TestClient(app)


def _start(service, customer_no=CUST):
    return asyncio.run(service.start(customer_no, "HOME", "1.0.0"))


def _send(service, chatbot_id, message="", button_value=None):
    return asyncio.run(service.handle_message(chatbot_id, message, button_value))


def _setup_agent(service, chat_service, customer_no=CUST):
    service.seed_default_scenario()
    session = asyncio.run(service.start(customer_no, "HOME", "1.0.0"))
    asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
    queue = chat_service.get_waiting_queue()
    return queue[0]["chat_consultation_id"]


# ─────────────────────────────────────────────────────────────────────────────
# A. /health 엔드포인트
# ─────────────────────────────────────────────────────────────────────────────

class TestHealthEndpoint:
    def test_health_returns_200(self, service):
        client = _api_client(service)
        try:
            resp = client.get("/health")
            assert resp.status_code == 200
        finally:
            app.dependency_overrides.clear()

    def test_health_returns_up_status(self, service):
        client = _api_client(service)
        try:
            resp = client.get("/health")
            assert resp.json()["status"] == "UP"
        finally:
            app.dependency_overrides.clear()


# ─────────────────────────────────────────────────────────────────────────────
# B. 채팅 API 전체 HTTP 계층
# ─────────────────────────────────────────────────────────────────────────────

class TestChatApiHttp:
    """채팅 API — HTTP 계층 전체 엔드포인트 검증."""

    def test_queue_empty_initially(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            resp = client.get("/chat/queue")
            assert resp.status_code == 200
            assert resp.json() == []
        finally:
            app.dependency_overrides.clear()

    def test_queue_populated_after_transfer(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"message": "", "button_value": "AGENT"},
            )
            resp = client.get("/chat/queue")
            assert resp.status_code == 200
            queue = resp.json()
            assert len(queue) >= 1
            assert "chat_consultation_id" in queue[0]
            assert "customer_no" in queue[0]
        finally:
            app.dependency_overrides.clear()

    def test_connect_agent_api(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"message": "", "button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            resp = client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 42})
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "CONNECTED"
            assert body["employee_id"] == 42
        finally:
            app.dependency_overrides.clear()

    def test_connect_already_connected_returns_400(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"message": "", "button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            resp = client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 2})
            assert resp.status_code == 400
        finally:
            app.dependency_overrides.clear()

    def test_send_chat_message_agent(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})

            resp = client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "안녕하세요!", "sender_type": "AGENT"},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["message"] == "안녕하세요!"
            assert body["sender_type"] == "AGENT"
            assert "message_id" in body
        finally:
            app.dependency_overrides.clear()

    def test_send_chat_message_user(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})

            resp = client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "도움 주세요", "sender_type": "USER"},
            )
            assert resp.status_code == 200
            assert resp.json()["sender_type"] == "USER"
        finally:
            app.dependency_overrides.clear()

    def test_get_chat_messages_api(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "테스트 메시지", "sender_type": "AGENT"},
            )

            resp = client.get(f"/chat/consultations/{chat_id}/messages")
            assert resp.status_code == 200
            msgs = resp.json()
            assert isinstance(msgs, list)
            assert len(msgs) >= 1
            msg = msgs[-1]
            assert "message_id" in msg
            assert "sender_type" in msg
            assert "message" in msg
        finally:
            app.dependency_overrides.clear()

    def test_end_chat_api(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})

            resp = client.post(
                f"/chat/consultations/{chat_id}/end",
                json={"satisfaction_score": 5},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "ENDED"
            assert body["satisfaction_score"] == 5
            assert body["active_yn"] == "N"
        finally:
            app.dependency_overrides.clear()

    def test_end_chat_no_score_api(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})

            resp = client.post(f"/chat/consultations/{chat_id}/end", json={})
            assert resp.status_code == 200
            assert resp.json()["status"] == "ENDED"
            assert resp.json()["satisfaction_score"] is None
        finally:
            app.dependency_overrides.clear()

    def test_end_already_ended_returns_400(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            client.post(f"/chat/consultations/{chat_id}/end", json={})

            resp = client.post(f"/chat/consultations/{chat_id}/end", json={})
            assert resp.status_code == 400
        finally:
            app.dependency_overrides.clear()

    def test_send_to_ended_chat_returns_404(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"button_value": "AGENT"},
            )
            chat_id = client.get("/chat/queue").json()[0]["chat_consultation_id"]
            client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})
            client.post(f"/chat/consultations/{chat_id}/end", json={})

            resp = client.post(
                f"/chat/consultations/{chat_id}/messages",
                json={"message": "종료 후 메시지", "sender_type": "AGENT"},
            )
            assert resp.status_code == 404
        finally:
            app.dependency_overrides.clear()

    def test_chatbot_start_api(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            resp = client.post("/chatbot/consultations/start", json={"customer_no": CUST})
            assert resp.status_code == 200
            body = resp.json()
            assert body["consultation_id"] > 0
            assert body["chatbot_consultation_id"] > 0
            assert body["node_id"] > 0
            assert body["message"]
            assert isinstance(body["buttons"], list)
        finally:
            app.dependency_overrides.clear()

    def test_chatbot_message_api(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            start = client.post("/chatbot/consultations/start", json={"customer_no": CUST}).json()
            resp = client.post(
                f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
                json={"message": "금리 알려줘"},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["process_method"] == "FEATURE_RATE_GUIDE"
            assert body["agent_transfer_required"] is False
        finally:
            app.dependency_overrides.clear()

    def test_chatbot_message_invalid_id_returns_404(self, service, chat_service):
        client = _api_client(service, chat_service)
        try:
            client.post("/chatbot/scenarios/default")
            resp = client.post(
                "/chatbot/consultations/99999/messages",
                json={"message": "테스트"},
            )
            assert resp.status_code == 404
        finally:
            app.dependency_overrides.clear()


# ─────────────────────────────────────────────────────────────────────────────
# C. seed_default_scenario 멱등성
# ─────────────────────────────────────────────────────────────────────────────

class TestSeedIdempotent:
    """seed_default_scenario 반복 호출 — 노드/버튼 중복 없음."""

    def test_seed_twice_same_scenario_id(self, service):
        id1, _ = service.seed_default_scenario()
        id2, _ = service.seed_default_scenario()
        assert id1 == id2

    def test_seed_twice_same_first_node(self, service):
        _, node1 = service.seed_default_scenario()
        _, node2 = service.seed_default_scenario()
        assert node1 == node2

    def test_seed_ten_times_buttons_still_four(self, service):
        for _ in range(10):
            _, node_id = service.seed_default_scenario()
        buttons = service._button_responses(node_id)
        assert len(buttons) == 4

    def test_seed_button_values_unchanged(self, service):
        for _ in range(5):
            _, node_id = service.seed_default_scenario()
        values = {b.value for b in service._button_responses(node_id)}
        assert values == {"PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT", "AGENT"}

    def test_seed_creates_nodes_only_once(self, service, db):
        service.seed_default_scenario()
        count1 = db.query(ChatbotNode).count()
        service.seed_default_scenario()
        count2 = db.query(ChatbotNode).count()
        assert count1 == count2

    def test_seed_returns_valid_ids(self, service):
        scenario_id, node_id = service.seed_default_scenario()
        assert scenario_id > 0
        assert node_id > 0


# ─────────────────────────────────────────────────────────────────────────────
# D. handle_message 비정상 ID
# ─────────────────────────────────────────────────────────────────────────────

class TestHandleMessageInvalidId:
    """존재하지 않는 chatbot_consultation_id → ValueError."""

    def test_invalid_id_raises_value_error(self, service):
        service.seed_default_scenario()
        with pytest.raises(ValueError, match="찾을 수 없습니다"):
            asyncio.run(service.handle_message(99999, "금리 알려줘", None))

    def test_zero_id_raises_value_error(self, service):
        service.seed_default_scenario()
        with pytest.raises(ValueError):
            asyncio.run(service.handle_message(0, "테스트", None))


# ─────────────────────────────────────────────────────────────────────────────
# E. FeatureAnswerFormatter 단위 테스트
# ─────────────────────────────────────────────────────────────────────────────

class TestFeatureAnswerFormatter:
    """FeatureAnswerFormatter — 각 포맷터 직접 단위 테스트."""

    @pytest.fixture(autouse=True)
    def fmt(self):
        self.fmt = FeatureAnswerFormatter()

    def test_rate_empty_data_returns_fallback(self):
        result = self.fmt.format("RATE_GUIDE", [])
        assert result
        assert "죄송합니다" in result or "없습니다" in result

    def test_rate_single_product(self):
        data = [{
            "product_name": "정기예금 플러스",
            "rate_type": "BASE",
            "interest_rate": 3.5,
            "minimum_contract_period": 12,
            "maximum_contract_period": 24,
        }]
        result = self.fmt.format("RATE_GUIDE", data)
        assert "정기예금 플러스" in result
        assert "3.5" in result

    def test_rate_includes_preferential(self):
        data = [
            {"product_name": "A상품", "rate_type": "BASE", "interest_rate": 3.5,
             "minimum_contract_period": 12, "maximum_contract_period": 24},
            {"product_name": "A상품", "rate_type": "PREFERENTIAL", "interest_rate": 0.3,
             "minimum_contract_period": 12, "maximum_contract_period": 24},
        ]
        result = self.fmt.format("RATE_GUIDE", data)
        assert "우대금리" in result
        assert "0.3" in result

    def test_products_empty_returns_fallback(self):
        result = self.fmt.format("PRODUCT_GUIDE", [])
        assert result

    def test_products_single_item(self):
        data = [{"product_name": "자유적금", "product_type": "SAVINGS", "base_interest_rate": 4.0}]
        result = self.fmt.format("PRODUCT_GUIDE", data)
        assert "자유적금" in result
        assert "4.0" in result

    def test_products_header_normal(self):
        data = [{"product_name": "상품A", "product_type": "DEPOSIT", "base_interest_rate": 3.5}]
        result = self.fmt.format("PRODUCT_GUIDE", data)
        assert "판매 중인 상품 목록" in result

    def test_products_personalized_header(self):
        data = [{"product_name": "상품A", "product_type": "DEPOSIT",
                 "base_interest_rate": 3.5, "match_score": 90, "recommend_reason": "잔액 충분"}]
        result = self.fmt.format("PRODUCT_GUIDE", data)
        assert "맞춤 상품 추천" in result

    def test_join_condition_fields(self):
        data = [{
            "product_name": "정기예금 플러스",
            "min_join_amount": 100000,
            "max_join_amount": 100000000,
            "min_period_month": 1,
            "max_period_month": 60,
            "is_early_termination_allowed": True,
            "is_tax_benefit_available": True,
        }]
        result = self.fmt.format("JOIN_CONDITION", data)
        assert "정기예금 플러스" in result

    def test_compare_has_header(self):
        data = [
            {"product_name": "상품A", "product_type": "DEPOSIT", "base_interest_rate": 3.5,
             "min_period_month": 12, "max_period_month": 24},
            {"product_name": "상품B", "product_type": "SAVINGS", "base_interest_rate": 4.0,
             "min_period_month": 12, "max_period_month": 36},
        ]
        result = self.fmt.format("PRODUCT_COMPARE", data)
        assert "상품 비교" in result

    def test_terms_has_term_name(self):
        data = [{
            "special_term_name": "개인정보 수집 이용 동의",
            "special_term_summary": "개인정보를 수집하고 이용합니다.",
        }]
        result = self.fmt.format("TERMS_RAG", data)
        assert "개인정보 수집 이용 동의" in result

    def test_unknown_feature_code_fallback(self):
        data = [{"key": "value"}]
        result = self.fmt.format("UNKNOWN_FEATURE", data)
        assert "조회된 정보" in result or result

    def test_format_preserves_multiple_products(self):
        data = [
            {"product_name": f"상품{i}", "product_type": "DEPOSIT", "base_interest_rate": 3.0 + i * 0.1}
            for i in range(5)
        ]
        result = self.fmt.format("PRODUCT_GUIDE", data)
        assert "상품0" in result
        assert "상품4" in result


# ─────────────────────────────────────────────────────────────────────────────
# F. _chat_status 함수
# ─────────────────────────────────────────────────────────────────────────────

class TestChatStatusFunction:
    """_chat_status — WAITING/CONNECTED/ENDED 상태값 검증."""

    def test_waiting_when_no_agent_connected(self, service, chat_service):
        chat_id = _setup_agent(service, chat_service)
        chat = chat_service.get_consultation(chat_id)
        assert _chat_status(chat) == "WAITING"

    def test_connected_after_agent_connects(self, service, chat_service):
        chat_id = _setup_agent(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        chat = chat_service.get_consultation(chat_id)
        assert _chat_status(chat) == "CONNECTED"

    def test_ended_after_end_chat(self, service, chat_service):
        chat_id = _setup_agent(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        asyncio.run(chat_service.end_chat(chat_id))
        chat = chat_service.get_consultation(chat_id)
        assert _chat_status(chat) == "ENDED"

    def test_waiting_queue_only_shows_waiting(self, service, chat_service):
        chat_id = _setup_agent(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        queue = chat_service.get_waiting_queue()
        ids_in_queue = [r["chat_consultation_id"] for r in queue]
        assert chat_id not in ids_in_queue


# ─────────────────────────────────────────────────────────────────────────────
# G. category name/description 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestCategoryMetadata:
    """카테고리 한국어 메타데이터."""

    def test_product_advice_name(self, service):
        cats = service.categories()
        pa = next(c for c in cats if c.code == "PRODUCT_ADVICE")
        assert pa.name
        assert len(pa.name) > 2

    def test_user_finance_name(self, service):
        cats = service.categories()
        uf = next(c for c in cats if c.code == "USER_FINANCE")
        assert uf.name
        assert len(uf.name) > 2

    def test_staff_support_name(self, service):
        cats = service.categories()
        ss = next(c for c in cats if c.code == "STAFF_SUPPORT")
        assert ss.name
        assert len(ss.name) > 2

    def test_all_categories_have_description(self, service):
        for cat in service.categories():
            assert cat.description, f"{cat.code} 설명 없음"
            assert len(cat.description) > 5

    def test_category_names_all_korean(self, service):
        for cat in service.categories():
            # 이름에 한글 포함 확인 (유니코드 가나다 범위)
            has_korean = any("가" <= c <= "힣" for c in cat.name)
            assert has_korean, f"{cat.code} 이름에 한글 없음: {cat.name}"


# ─────────────────────────────────────────────────────────────────────────────
# H. feature api_status 필드 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestFeatureApiStatus:
    """feature api_status — 유효한 값만 사용."""

    VALID_STATUSES = {"MOCK_READY", "AUTH_REQUIRED", "STAFF_AUTH_REQUIRED", "RAG_PENDING", "LLM_REQUIRED"}

    def test_all_features_have_api_status(self, service):
        for f in service.features():
            assert f.api_status, f"{f.code} api_status 없음"

    def test_all_api_status_valid(self, service):
        for f in service.features():
            assert f.api_status in self.VALID_STATUSES, \
                f"{f.code}: 알 수 없는 api_status '{f.api_status}'"

    def test_user_finance_require_auth_status(self, service):
        for f in service.features():
            if f.category_code == "USER_FINANCE":
                assert f.api_status in ("AUTH_REQUIRED", "LLM_REQUIRED"), \
                    f"{f.code}: USER_FINANCE 기능의 api_status가 AUTH_REQUIRED/LLM_REQUIRED 아님"

    def test_staff_support_require_staff_auth_status(self, service):
        for f in service.features():
            if f.category_code == "STAFF_SUPPORT":
                assert f.api_status == "STAFF_AUTH_REQUIRED", \
                    f"{f.code}: api_status != STAFF_AUTH_REQUIRED"

    def test_product_advice_public_statuses(self, service):
        for f in service.features():
            if f.category_code == "PRODUCT_ADVICE":
                assert f.api_status in ("MOCK_READY", "RAG_PENDING"), \
                    f"{f.code}: PRODUCT_ADVICE 기능의 api_status 이상"


# ─────────────────────────────────────────────────────────────────────────────
# I. MY_PRODUCTS vs CONTRACT_STATUS — 동일 데이터
# ─────────────────────────────────────────────────────────────────────────────

class TestMyProductsVsContractStatus:
    """MY_PRODUCTS와 CONTRACT_STATUS 둘 다 _contract_rows 사용 → 동일 데이터."""

    def test_both_ok(self, service):
        r_mp = service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        r_cs = service.execute_feature("CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert r_mp.status == "OK"
        assert r_cs.status == "OK"

    def test_same_contract_count(self, service):
        r_mp = service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        r_cs = service.execute_feature("CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(r_mp.data) == len(r_cs.data)

    def test_same_contract_ids(self, service):
        r_mp = service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        r_cs = service.execute_feature("CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        ids_mp = {i["contract_id"] for i in r_mp.data}
        ids_cs = {i["contract_id"] for i in r_cs.data}
        assert ids_mp == ids_cs

    def test_same_maturity_dates(self, service):
        r_mp = service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        r_cs = service.execute_feature("CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        mats_mp = {i["maturity_at"] for i in r_mp.data}
        mats_cs = {i["maturity_at"] for i in r_cs.data}
        assert mats_mp == mats_cs

    def test_rich_db_same_count(self, rich_service):
        r_mp = rich_service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        r_cs = rich_service.execute_feature("CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(r_mp.data) == len(r_cs.data) == 2


# ─────────────────────────────────────────────────────────────────────────────
# J. PRODUCT_GUIDE 금리 정렬 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestProductGuideSort:
    """PRODUCT_GUIDE — 정렬 및 필터 동작."""

    def test_type_filter_sorted_by_rate_desc(self, rich_service):
        # 유형 필터 쿼리 시 base_interest_rate DESC 정렬
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE", ChatbotFeatureExecuteRequest(query="예금 상품 보여줘")
        )
        rates = [float(p["base_interest_rate"]) for p in result.data]
        assert rates == sorted(rates, reverse=True), f"예금 필터 금리 내림차순 아님: {rates}"

    def test_savings_filter_sorted_by_rate_desc(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE", ChatbotFeatureExecuteRequest(query="적금 상품 알려줘")
        )
        rates = [float(p["base_interest_rate"]) for p in result.data]
        assert rates == sorted(rates, reverse=True)

    def test_no_filter_returns_all_selling_ordered_by_id(self, rich_service):
        # 필터 없을 때 banking_product_id ASC 정렬
        result = rich_service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        ids = [int(p["product_id"]) for p in result.data]
        assert ids == sorted(ids), f"기본 정렬(product_id ASC) 아님: {ids}"

    def test_product_status_all_selling(self, rich_service):
        result = rich_service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert item["product_status"] == "SELLING"


# ─────────────────────────────────────────────────────────────────────────────
# K. CUST002 이자내역 (rich_db)
# ─────────────────────────────────────────────────────────────────────────────

class TestCust002InterestHistory:
    """rich_db CUST002 이자 내역 — CUST001과 독립 검증."""

    def test_cust002_interest_history_ok(self, rich_service):
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST2)
        )
        assert result.status == "OK"

    def test_cust002_has_one_interest_record(self, rich_service):
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST2)
        )
        assert len(result.data) == 1

    def test_cust002_interest_amount_correct(self, rich_service):
        # rich_db: CUST002 이자 105,000
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST2)
        )
        assert float(result.data[0]["interest_amount"]) == pytest.approx(105_000)

    def test_cust002_after_tax_correct(self, rich_service):
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST2)
        )
        assert float(result.data[0]["interest_after_tax_amount"]) == pytest.approx(88_830)

    def test_cust002_applied_rate(self, rich_service):
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST2)
        )
        assert float(result.data[0]["applied_interest_rate"]) == pytest.approx(3.5)

    def test_cust001_cust002_interest_independent(self, rich_service):
        r1 = rich_service.execute_feature("INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST))
        r2 = rich_service.execute_feature("INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        ids1 = {item["interest_id"] for item in r1.data}
        ids2 = {item["interest_id"] for item in r2.data}
        assert ids1.isdisjoint(ids2), "CUST001과 CUST002 이자내역이 겹침"


# ─────────────────────────────────────────────────────────────────────────────
# L. LLM 오류 → 상담사 이관
# ─────────────────────────────────────────────────────────────────────────────

class ErrorLlmAdapter(LlmAdapter):
    """항상 오류 응답을 반환하는 LLM mock."""
    def __init__(self):
        super().__init__(api_key="error-key")

    def answer(self, message: str, context: str = "") -> tuple[str, bool]:
        return "죄송합니다, 일시적인 오류가 발생했습니다. 상담사 연결을 원하시면 '상담사 연결'을 선택해 주세요.", True


class TestLlmErrorFallback:
    """LLM 오류 발생 시 상담사 이관 흐름."""

    @pytest.fixture()
    def error_service(self, db):
        return ChatbotService(db, AsyncMock(), LlmHandoffAdapter(), ErrorLlmAdapter())

    def test_llm_error_triggers_agent_transfer(self, error_service):
        error_service.seed_default_scenario()
        session = _start(error_service)
        response = _send(error_service, session.chatbot_consultation_id, message="분류되지 않는 질문")
        assert response.agent_transfer_required is True

    def test_llm_error_process_method(self, error_service):
        error_service.seed_default_scenario()
        session = _start(error_service)
        response = _send(error_service, session.chatbot_consultation_id, message="분류 안 되는 임의 문장")
        assert response.process_method == "STAFF_ERROR_FALLBACK"

    def test_llm_error_message_is_apology(self, error_service):
        error_service.seed_default_scenario()
        session = _start(error_service)
        response = _send(error_service, session.chatbot_consultation_id, message="아무말")
        assert "죄송" in response.message or "오류" in response.message

    def test_llm_error_creates_chat_consultation(self, error_service, db):
        from app.models import ChatConsultation
        error_service.seed_default_scenario()
        session = _start(error_service)
        _send(error_service, session.chatbot_consultation_id, message="분류 안 되는 문장")
        chat = db.scalars(
            select(ChatConsultation).where(
                ChatConsultation.chatbot_consultation_id == session.chatbot_consultation_id
            )
        ).first()
        assert chat is not None
        assert chat.active_yn == "Y"


# ─────────────────────────────────────────────────────────────────────────────
# M. PRODUCT_SEARCH 복합 조건 동시 사용
# ─────────────────────────────────────────────────────────────────────────────

class TestProductSearchComplex:
    """PRODUCT_SEARCH — amount+period+type+purpose 복합 조건."""

    def test_deposit_with_amount_and_period(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(
                product_type="DEPOSIT",
                amount=500_000,
                period=12,
            ),
        )
        assert result.status == "OK"

    def test_savings_with_monthly_purpose_and_amount(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(
                product_type="SAVINGS",
                amount=100_000,
                period=24,
                purpose="monthly",
            ),
        )
        assert result.status == "OK"

    def test_deposit_lump_sum_all_fields(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(
                product_type="DEPOSIT",
                amount=10_000_000,
                period=24,
                purpose="lump_sum",
            ),
        )
        assert result.status == "OK"
        assert len(result.data) <= 3

    def test_subscription_ignores_amount_period(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(
                product_type="SUBSCRIPTION",
                amount=50_000,
                period=600,
                purpose="subscription",
            ),
        )
        assert result.status == "OK"

    def test_result_data_has_reason_field(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", amount=100_000, period=12),
        )
        for item in result.data:
            assert "reason" in item

    def test_result_message_contains_type_label(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="SAVINGS"),
        )
        assert "적금" in result.message


# ─────────────────────────────────────────────────────────────────────────────
# N. ChatbotStartRequest 기본값 동작
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotStartRequestDefaults:
    """ChatbotStartRequest 기본값 동작."""

    def test_default_customer_no(self, service):
        service.seed_default_scenario()
        # 기본값 customer_no="CUST001"로 시작
        session = asyncio.run(service.start("CUST001", "HOME", "0.1.0"))
        assert session.consultation_id > 0

    def test_various_entry_screens(self, service):
        service.seed_default_scenario()
        for screen in ["HOME", "PRODUCT_LIST", "MY_PAGE", "TRANSFER"]:
            session = asyncio.run(service.start("CUST001", screen, "1.0.0"))
            assert session.chatbot_consultation_id > 0

    def test_various_app_versions(self, service, db):
        service.seed_default_scenario()
        for version in ["1.0.0", "2.3.1", "10.0.0-beta"]:
            session = asyncio.run(service.start("CUST001", "HOME", version))
            chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
            assert chatbot.app_version == version


# ─────────────────────────────────────────────────────────────────────────────
# O. ChatConsultation waiting_seconds 양수 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestWaitingSeconds:
    """connect_agent 후 waiting_seconds 양수."""

    def test_waiting_seconds_non_negative(self, service, chat_service):
        chat_id = _setup_agent(service, chat_service)
        chat = asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        assert chat.waiting_seconds is not None
        assert chat.waiting_seconds >= 0

    def test_chat_seconds_after_end(self, service, chat_service):
        chat_id = _setup_agent(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        chat = asyncio.run(chat_service.end_chat(chat_id))
        assert chat.chat_seconds is not None
        assert chat.chat_seconds >= 0


# ─────────────────────────────────────────────────────────────────────────────
# P. rich_db CUST002 전체 조회
# ─────────────────────────────────────────────────────────────────────────────

class TestCust002FullAccess:
    """rich_db CUST002 전체 기능 접근 검증."""

    def test_cust002_my_accounts_ok(self, rich_service):
        result = rich_service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        assert result.status == "OK"
        assert len(result.data) == 1

    def test_cust002_account_number(self, rich_service):
        result = rich_service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        assert result.data[0]["account_number"] == "001-002-000001"

    def test_cust002_my_products_ok(self, rich_service):
        result = rich_service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        assert result.status == "OK"
        assert len(result.data) == 1

    def test_cust002_contract_no(self, rich_service):
        result = rich_service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        assert result.data[0]["contract_no"] == "CTR-003"

    def test_cust002_maturity_schedule(self, rich_service):
        result = rich_service.execute_feature("MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        assert result.status == "OK"
        assert result.data[0]["maturity_at"] == "20270601"

    def test_cust002_staff_account(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        assert result.status == "OK"
        assert len(result.data) == 1

    def test_cust002_staff_transfer_flow(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        assert result.status == "OK"
        tx_nos = [item["transaction_number"] for item in result.data]
        assert "TX-004" in tx_nos

    def test_cust002_cash_flow_recommend_ok(self, rich_service):
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST2)
        )
        assert result.status == "OK"
        assert result.data[0]["total_balance"] == pytest.approx(3_000_000)


# ─────────────────────────────────────────────────────────────────────────────
# Q. 시나리오 버튼 텍스트 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestButtonText:
    """버튼 text 필드 — 내용 검증."""

    def test_buttons_have_text(self, service):
        service.seed_default_scenario()
        session = _start(service)
        for btn in session.buttons:
            assert btn.text, f"버튼 text 비어있음: {btn}"

    def test_button_texts_korean(self, service):
        service.seed_default_scenario()
        session = _start(service)
        for btn in session.buttons:
            has_korean = any("가" <= c <= "힣" for c in btn.text)
            assert has_korean, f"버튼 '{btn.text}'에 한글 없음"

    def test_agent_button_text_contains_agent(self, service):
        service.seed_default_scenario()
        session = _start(service)
        agent_btn = next((b for b in session.buttons if b.value == "AGENT"), None)
        assert agent_btn is not None
        assert "상담사" in agent_btn.text or "연결" in agent_btn.text

    def test_button_ids_unique(self, service):
        service.seed_default_scenario()
        session = _start(service)
        ids = [btn.id for btn in session.buttons]
        assert len(ids) == len(set(ids))

    def test_button_values_match_expected(self, service):
        service.seed_default_scenario()
        session = _start(service)
        values = {b.value for b in session.buttons}
        assert "PRODUCT_ADVICE" in values
        assert "USER_FINANCE" in values
        assert "STAFF_SUPPORT" in values
        assert "AGENT" in values


# ─────────────────────────────────────────────────────────────────────────────
# R. 이자 내역 복수 조회 정렬
# ─────────────────────────────────────────────────────────────────────────────

class TestInterestHistorySort:
    """interest_id DESC 정렬 — 최신 이자 내역 먼저."""

    def test_interest_ids_desc_order(self, rich_service):
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        ids = [item["interest_id"] for item in result.data]
        assert ids == sorted(ids, reverse=True), f"interest_id DESC 아님: {ids}"

    def test_multiple_records_all_same_customer(self, rich_service):
        result = rich_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        # account_id가 CUST001 소유인지 확인 (account_id 1, 2 중 하나)
        account_ids = {item["account_id"] for item in result.data}
        assert account_ids.issubset({1, 2}), f"CUST001 소유 계좌 외 데이터 포함: {account_ids}"


# ─────────────────────────────────────────────────────────────────────────────
# S. ChatbotFeatureExecuteRequest 복합 조합
# ─────────────────────────────────────────────────────────────────────────────

class TestExecuteRequestCombinations:
    """compare_product_ids + query 복합, product_id 단독 사용."""

    def test_product_id_single_compare(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(product_id=1),
        )
        assert result.status == "OK"
        assert len(result.data) == 1

    def test_compare_ids_override_query_type(self, rich_service):
        # compare_product_ids 제공 시 type filter 무시하고 ID 조회
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1, 3], query="예금 적금 차이"),
        )
        # ID [1,3]이 제공됐으므로 ID 기반 조회
        assert result.status == "OK"
        assert len(result.data) == 2

    def test_chatbot_consultation_id_in_cash_flow(self, cashflow_service):
        # chatbot_consultation_id 없이 CASH_FLOW_RECOMMEND 호출
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY", chatbot_consultation_id=None),
        )
        assert result.status == "OK"

    def test_all_none_fields_handled(self, service):
        # 모든 필드 None — PRODUCT_GUIDE는 인증 불필요이므로 OK
        result = service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(
                customer_no=None, query=None, product_id=None,
                compare_product_ids=[], staff_id=None,
            ),
        )
        assert result.status == "OK"


# ─────────────────────────────────────────────────────────────────────────────
# T. execute_feature 전체 handler 코드 호출 확인
# ─────────────────────────────────────────────────────────────────────────────

class TestAllHandlersCallable:
    """등록된 모든 feature handler가 오류 없이 호출 가능."""

    ALL_PUBLIC = ["PRODUCT_GUIDE", "RATE_GUIDE", "JOIN_CONDITION",
                  "PRODUCT_COMPARE", "TERMS_RAG", "FAQ", "PRODUCT_SEARCH"]
    ALL_USER   = ["MY_ACCOUNTS", "MY_PRODUCTS", "CONTRACT_STATUS",
                  "MATURITY_SCHEDULE", "INTEREST_HISTORY", "MY_CASH_FLOW",
                  "MY_TRANSFERS", "CASH_FLOW_RECOMMEND"]
    ALL_STAFF  = ["STAFF_CUSTOMER", "STAFF_CONTRACT", "STAFF_ACCOUNT",
                  "STAFF_TRANSFER_FLOW", "STAFF_CASH_FLOW",
                  "STAFF_CONSULTATION_HISTORY"]

    @pytest.mark.parametrize("code", ALL_PUBLIC)
    def test_public_feature_no_exception(self, service, code):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
        assert result.feature_code == code
        assert result.status in ("OK", "EMPTY", "NOT_FOUND")

    @pytest.mark.parametrize("code", ALL_USER)
    def test_user_feature_no_auth_returns_auth_required(self, service, code):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
        assert result.status == "AUTH_REQUIRED"

    @pytest.mark.parametrize("code", ALL_STAFF)
    def test_staff_feature_no_auth_returns_staff_auth_required(self, service, code):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
        assert result.status == "STAFF_AUTH_REQUIRED"

    @pytest.mark.parametrize("code", ALL_USER)
    def test_user_feature_with_customer_no_ok_or_empty(self, service, code):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert result.status in ("OK", "EMPTY")

    @pytest.mark.parametrize("code", ALL_STAFF)
    def test_staff_feature_with_both_params_ok_or_empty(self, service, code):
        result = service.execute_feature(
            code, ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF)
        )
        assert result.status in ("OK", "EMPTY")


# ─────────────────────────────────────────────────────────────────────────────
# U. 빈 button_value + 빈 message 처리
# ─────────────────────────────────────────────────────────────────────────────

class TestEmptyInputHandling:
    """버튼값/메시지 없이 handle_message 호출 — 상담사 이관."""

    def test_empty_message_and_button_goes_to_staff(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="", button_value=None)
        # 버튼값도 메시지도 없으면 intent 분류 안 됨 → STAFF_REQUEST
        assert response.process_method == "STAFF_REQUEST"
        assert response.agent_transfer_required is True

    def test_whitespace_message_not_classified(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="   ")
        # 공백만 있으면 분류 안 됨
        assert response.process_method == "STAFF_REQUEST"


# ─────────────────────────────────────────────────────────────────────────────
# V. PRODUCT_GUIDE 군인 상품 제외 로직
# ─────────────────────────────────────────────────────────────────────────────

class TestProductGuideExclusion:
    """RATE_GUIDE / PRODUCT_GUIDE — 특수 상품명 제외 필터."""

    def test_rate_guide_no_military_products(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            name = item.get("product_name", "")
            assert "장병" not in name
            assert "군인" not in name

    def test_cash_flow_recommend_no_military_products(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY"),
        )
        # message에 군인 상품 언급 없어야 함
        assert "장병" not in result.message
        assert "군무원" not in result.message


# ─────────────────────────────────────────────────────────────────────────────
# W. rich_db STAFF 기능 CUST002 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestStaffFeaturesCust002:
    """직원이 CUST002 계약·계좌 조회 — 데이터 정합성."""

    def test_staff_contract_cust002_ok(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        assert result.status == "OK"
        assert len(result.data) == 1

    def test_staff_contract_cust002_number(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        assert result.data[0]["contract_no"] == "CTR-003"

    def test_staff_account_cust002_ok(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        assert result.status == "OK"
        assert result.data[0]["account_number"] == "001-002-000001"

    def test_staff_customer_cust002_balance(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        assert float(result.data[0]["balance"]) == pytest.approx(3_000_000)

    def test_staff_cash_flow_cust002_ok(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        assert result.status == "OK"

    def test_staff_transfer_flow_cust002_tx_number(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        tx_nos = [item["transaction_number"] for item in result.data]
        assert "TX-004" in tx_nos


# ─────────────────────────────────────────────────────────────────────────────
# X. features 전체 sample_questions 비어있지 않음
# ─────────────────────────────────────────────────────────────────────────────

class TestFeatureSampleQuestions:
    """모든 기능 — sample_questions 비어있지 않고 한글 포함."""

    def test_all_have_at_least_one_question(self, service):
        for f in service.features():
            assert len(f.sample_questions) >= 1, f"{f.code}: sample_questions 없음"

    def test_all_questions_not_empty_string(self, service):
        for f in service.features():
            for q in f.sample_questions:
                assert q.strip(), f"{f.code}: 빈 sample_question"

    def test_all_questions_have_korean(self, service):
        for f in service.features():
            for q in f.sample_questions:
                has_korean = any("가" <= c <= "힣" for c in q)
                assert has_korean, f"{f.code}: '{q}'에 한글 없음"

    def test_question_min_length(self, service):
        for f in service.features():
            for q in f.sample_questions:
                assert len(q) >= 5, f"{f.code}: sample_question 너무 짧음: '{q}'"

    def test_total_sample_questions_count(self, service):
        total = sum(len(f.sample_questions) for f in service.features())
        assert total >= 30, f"전체 샘플 질문 수 너무 적음: {total}"
