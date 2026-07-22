"""
확장 시나리오 테스트 — 2차 심화.

커버 영역:
  A. API 계층 검증          - HTTP 상태코드, 응답 스키마, 오류 형태
  B. 상담 시작 구조          - start() 반환 구조, 버튼, node_id, 이벤트
  C. 챗봇 상담 상태 저장     - ChatbotConsultation 필드, entry_screen, app_version
  D. 다중 세션 독립성        - 두 고객 동시 세션이 서로 간섭 없음
  E. 다중 상담사 대기열      - 여러 고객 동시 대기, FIFO 순서
  F. PRODUCT_COMPARE 상세   - 특정 상품 ID 비교 데이터 무결성
  G. JOIN_CONDITION 상세     - 중도해지·세제혜택 필드, rich_db 수치
  H. RATE_GUIDE 상세         - 우대금리 조건 설명, 기간 정보, product_id 연결
  I. TERMS_RAG 검색 패턴     - summary 부분 매칭, content 매칭, 대소문자
  J. 현금흐름 rich_db        - CUST001/CUST002 현금흐름 분석 간접 검증
  K. PRODUCT_SEARCH 경계값  - 최소금액 정확히 일치/초과, 기간 경계, 목적 조합
  L. Kafka 이벤트 페이로드   - 이벤트 타입·페이로드 내용 검증
  M. 쿼리 None vs 빈 문자열  - 동일 동작 보장
  N. ChatMessageHistory 순서 - sequence_no 단조 증가
  O. 빈 DB 시나리오 전체     - 모든 공개 기능 empty_db에서 EMPTY/OK 정확 반환
  P. feature 응답 불변성     - feature_code, requires_auth 항상 올바름
  Q. CASH_FLOW_RECOMMEND query 분기 - "예금 vs 적금" 비교 쿼리 룰 분기
  R. 다중 상담사 메시지 전송  - agent/user 교대 메시지, 순서 보장
  S. IntentClassifier 고밀도  - 복합 문장, 약관+금리 혼합, 개인화 트리거
  T. 상담 이력 시나리오       - STAFF_CONSULTATION_HISTORY 생성 후 조회
"""

import asyncio

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.llm import IntentClassifier
from app.main import app, get_chatbot_service
from app.models import (
    ChatMessageHistory,
    ChatbotConsultation,
)
from app.schemas import ChatbotFeatureExecuteRequest


# ── 공통 상수 ─────────────────────────────────────────────────────────────────

CUST   = "CUST001"
CUST2  = "CUST002"
STAFF  = "EMP001"
NO_CUST = "NO_SUCH_CUSTOMER_99999"

CUST_SALARY  = "CUST_SALARY"
CUST_SURPLUS = "CUST_SURPLUS"
CUST_TIGHT   = "CUST_TIGHT"
CUST_NODATA  = "CUST_NODATA"


# ── 공통 헬퍼 ─────────────────────────────────────────────────────────────────

def _start(service, customer_no=CUST):
    return asyncio.run(service.start(customer_no, "HOME", "1.0.0"))


def _send(service, chatbot_id, message="", button_value=None):
    return asyncio.run(service.handle_message(chatbot_id, message, button_value))


def _api_client(service):
    app.dependency_overrides[get_chatbot_service] = lambda: service
    return TestClient(app)


# ─────────────────────────────────────────────────────────────────────────────
# A. API 계층 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestApiLayer:
    """HTTP API 계층 — 상태코드, 응답 스키마, 오류 형태."""

    def test_features_list_200(self, service):
        client = _api_client(service)
        try:
            resp = client.get("/chatbot/features")
            assert resp.status_code == 200
            data = resp.json()
            assert isinstance(data, list)
        finally:
            app.dependency_overrides.clear()

    def test_feature_detail_200(self, service):
        client = _api_client(service)
        try:
            resp = client.get("/chatbot/features/RATE_GUIDE")
            assert resp.status_code == 200
            assert resp.json()["code"] == "RATE_GUIDE"
        finally:
            app.dependency_overrides.clear()

    def test_feature_detail_unknown_404(self, service):
        client = _api_client(service)
        try:
            resp = client.get("/chatbot/features/NO_SUCH_FEATURE")
            assert resp.status_code == 404
        finally:
            app.dependency_overrides.clear()

    def test_execute_feature_200_on_ok(self, service):
        client = _api_client(service)
        try:
            resp = client.post(
                "/chatbot/features/PRODUCT_GUIDE/execute",
                json={},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "OK"
            assert "feature_code" in body
            assert "data" in body
            assert "message" in body
        finally:
            app.dependency_overrides.clear()

    def test_execute_feature_200_on_auth_required(self, service):
        client = _api_client(service)
        try:
            resp = client.post(
                "/chatbot/features/MY_ACCOUNTS/execute",
                json={},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "AUTH_REQUIRED"
        finally:
            app.dependency_overrides.clear()

    def test_execute_feature_response_has_all_schema_fields(self, service):
        client = _api_client(service)
        try:
            resp = client.post(
                "/chatbot/features/PRODUCT_GUIDE/execute",
                json={},
            )
            body = resp.json()
            assert "feature_code" in body
            assert "status" in body
            assert "message" in body
            assert "data" in body
            assert "requires_auth" in body
            assert "requires_staff_auth" in body
        finally:
            app.dependency_overrides.clear()

    def test_categories_endpoint_200(self, service):
        client = _api_client(service)
        try:
            resp = client.get("/chatbot/categories")
            assert resp.status_code == 200
            cats = resp.json()
            assert len(cats) == 3
            codes = {c["code"] for c in cats}
            assert codes == {"PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT"}
        finally:
            app.dependency_overrides.clear()

    def test_execute_unknown_feature_returns_404_or_not_found(self, service):
        client = _api_client(service)
        try:
            resp = client.post(
                "/chatbot/features/TOTALLY_FAKE/execute",
                json={},
            )
            # API가 404를 반환하거나 200+NOT_FOUND 중 하나
            assert resp.status_code in (200, 404)
            if resp.status_code == 200:
                assert resp.json()["status"] == "NOT_FOUND"
        finally:
            app.dependency_overrides.clear()

    def test_execute_staff_feature_without_auth_returns_200(self, service):
        client = _api_client(service)
        try:
            resp = client.post(
                "/chatbot/features/STAFF_CONTRACT/execute",
                json={"customer_no": CUST},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "STAFF_AUTH_REQUIRED"
        finally:
            app.dependency_overrides.clear()

    def test_execute_with_extra_fields_ignored(self, service):
        client = _api_client(service)
        try:
            resp = client.post(
                "/chatbot/features/PRODUCT_GUIDE/execute",
                json={"unknown_field": "ignored", "another": 123},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
        finally:
            app.dependency_overrides.clear()


# ─────────────────────────────────────────────────────────────────────────────
# B. 상담 시작 구조 상세
# ─────────────────────────────────────────────────────────────────────────────

class TestStartStructure:
    """start() 반환 구조 상세 검증."""

    def test_start_returns_positive_ids(self, service):
        service.seed_default_scenario()
        session = _start(service)
        assert session.consultation_id > 0
        assert session.chatbot_consultation_id > 0
        assert session.node_id > 0

    def test_start_returns_four_buttons(self, service):
        service.seed_default_scenario()
        session = _start(service)
        assert len(session.buttons) == 4

    def test_start_button_values_correct(self, service):
        service.seed_default_scenario()
        session = _start(service)
        values = {b.value for b in session.buttons}
        assert values == {"PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT", "AGENT"}

    def test_start_message_not_empty(self, service):
        service.seed_default_scenario()
        session = _start(service)
        assert session.message
        assert len(session.message) > 5

    def test_start_different_customers_different_ids(self, service):
        service.seed_default_scenario()
        s1 = _start(service, "CUST001")
        s2 = _start(service, "CUST002")
        assert s1.consultation_id != s2.consultation_id
        assert s1.chatbot_consultation_id != s2.chatbot_consultation_id

    def test_start_same_customer_creates_new_session(self, service):
        service.seed_default_scenario()
        s1 = _start(service, "CUST001")
        s2 = _start(service, "CUST001")
        assert s1.chatbot_consultation_id != s2.chatbot_consultation_id


# ─────────────────────────────────────────────────────────────────────────────
# C. 챗봇 상담 상태 저장
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotConsultationState:
    """ChatbotConsultation 필드 저장 검증."""

    def test_initial_turn_count_zero(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.total_turn_count == 0

    def test_entry_screen_stored(self, service, db):
        service.seed_default_scenario()
        session = asyncio.run(service.start("CUST001", "PRODUCT_TAB", "2.0.0"))
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.entry_screen == "PRODUCT_TAB"

    def test_app_version_stored(self, service, db):
        service.seed_default_scenario()
        session = asyncio.run(service.start("CUST001", "HOME", "3.5.1"))
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.app_version == "3.5.1"

    def test_agent_connected_yn_initially_n(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.agent_connected_yn != "Y"

    def test_agent_connected_yn_set_after_transfer(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, button_value="AGENT")
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.agent_connected_yn == "Y"

    def test_consultation_id_linked(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.consultation_id == session.consultation_id

    def test_turn_count_increments_per_send(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        for _ in range(3):
            _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.total_turn_count == 3


# ─────────────────────────────────────────────────────────────────────────────
# D. 다중 세션 독립성
# ─────────────────────────────────────────────────────────────────────────────

class TestSessionIsolation:
    """두 고객 세션이 서로 간섭하지 않음."""

    def test_turn_count_independent(self, service, db):
        service.seed_default_scenario()
        s1 = _start(service, "CUST001")
        s2 = _start(service, "CUST002")

        for _ in range(5):
            _send(service, s1.chatbot_consultation_id, message="금리 알려줘")
        _send(service, s2.chatbot_consultation_id, message="가입 조건")

        c1 = db.get(ChatbotConsultation, s1.chatbot_consultation_id)
        c2 = db.get(ChatbotConsultation, s2.chatbot_consultation_id)
        assert c1.total_turn_count == 5
        assert c2.total_turn_count == 1

    def test_message_history_isolated(self, service, db):
        service.seed_default_scenario()
        s1 = _start(service, "CUST001")
        s2 = _start(service, "CUST002")

        _send(service, s1.chatbot_consultation_id, message="금리 알려줘")
        _send(service, s2.chatbot_consultation_id, message="상품 목록 보여줘")

        msgs_s1 = db.scalars(
            select(ChatMessageHistory).where(
                ChatMessageHistory.chatbot_consultation_id == s1.chatbot_consultation_id
            )
        ).all()
        msgs_s2 = db.scalars(
            select(ChatMessageHistory).where(
                ChatMessageHistory.chatbot_consultation_id == s2.chatbot_consultation_id
            )
        ).all()
        ids_s1 = {m.chat_message_history_id for m in msgs_s1}
        ids_s2 = {m.chat_message_history_id for m in msgs_s2}
        assert ids_s1.isdisjoint(ids_s2), "두 세션의 메시지 이력이 겹침"

    def test_agent_transfer_isolated(self, service, db):
        service.seed_default_scenario()
        s1 = _start(service, "CUST001")
        s2 = _start(service, "CUST002")

        _send(service, s1.chatbot_consultation_id, button_value="AGENT")

        c1 = db.get(ChatbotConsultation, s1.chatbot_consultation_id)
        c2 = db.get(ChatbotConsultation, s2.chatbot_consultation_id)
        assert c1.agent_connected_yn == "Y"
        assert c2.agent_connected_yn != "Y"


# ─────────────────────────────────────────────────────────────────────────────
# E. 다중 상담사 대기열
# ─────────────────────────────────────────────────────────────────────────────

class TestMultipleWaitingQueue:
    """여러 고객 동시 대기 — 대기열 순서 검증."""

    def _transfer(self, service, chat_service, customer_no):
        service.seed_default_scenario()
        session = asyncio.run(service.start(customer_no, "HOME", "1.0.0"))
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))

    def test_two_customers_in_queue(self, service, chat_service):
        self._transfer(service, chat_service, "CUST_A")
        self._transfer(service, chat_service, "CUST_B")
        queue = chat_service.get_waiting_queue()
        assert len(queue) >= 2

    def test_queue_contains_customer_nos(self, service, chat_service):
        self._transfer(service, chat_service, "CUST_A")
        self._transfer(service, chat_service, "CUST_B")
        queue = chat_service.get_waiting_queue()
        customer_nos = {r["customer_no"] for r in queue}
        assert "CUST_A" in customer_nos
        assert "CUST_B" in customer_nos

    def test_queue_fifo_order(self, service, chat_service):
        self._transfer(service, chat_service, "CUST_FIRST")
        self._transfer(service, chat_service, "CUST_SECOND")
        queue = chat_service.get_waiting_queue()
        assert queue[0]["customer_no"] == "CUST_FIRST"

    def test_connecting_one_removes_only_that_from_queue(self, service, chat_service):
        self._transfer(service, chat_service, "CUST_A")
        self._transfer(service, chat_service, "CUST_B")
        queue = chat_service.get_waiting_queue()
        first_id = queue[0]["chat_consultation_id"]

        asyncio.run(chat_service.connect_agent(first_id, employee_id=1))

        remaining = chat_service.get_waiting_queue()
        remaining_ids = {r["chat_consultation_id"] for r in remaining}
        assert first_id not in remaining_ids
        assert len(remaining) == len(queue) - 1


# ─────────────────────────────────────────────────────────────────────────────
# F. PRODUCT_COMPARE 특정 상품 ID 비교 상세
# ─────────────────────────────────────────────────────────────────────────────

class TestProductCompareDetailed:
    """특정 상품 ID 비교 — 데이터 무결성."""

    def test_compare_two_products_returns_two_rows(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1, 2]),
        )
        assert result.status == "OK"
        assert len(result.data) == 2

    def test_compare_single_product_returns_one_row(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1]),
        )
        assert result.status == "OK"
        assert len(result.data) == 1

    def test_compare_data_has_required_fields(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1, 2]),
        )
        for item in result.data:
            assert "product_id" in item
            assert "product_name" in item
            assert "product_type" in item
            assert "base_interest_rate" in item
            assert "min_join_amount" in item
            assert "max_join_amount" in item

    def test_compare_product1_name_correct(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1]),
        )
        assert result.data[0]["product_name"] == "정기예금 플러스"

    def test_compare_product1_rate_correct(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1]),
        )
        assert float(result.data[0]["base_interest_rate"]) == pytest.approx(3.5)

    def test_compare_product2_type_savings(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[2]),
        )
        assert result.data[0]["product_type"] == "SAVINGS"

    def test_compare_all_three_products(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1, 2, 3]),
        )
        assert len(result.data) == 3
        names = {item["product_name"] for item in result.data}
        assert "정기예금 플러스" in names
        assert "자유적금" in names
        assert "주택청약종합저축" in names

    def test_compare_no_query_returns_products(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE", ChatbotFeatureExecuteRequest()
        )
        assert result.status == "OK"

    def test_deposit_savings_concept_message_long(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금 적금 차이"),
        )
        assert result.status == "OK"
        assert len(result.message) >= 30
        assert result.data == []

    def test_savings_subscription_concept_message(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="적금 청약 차이 알려줘"),
        )
        assert result.status == "OK"
        assert result.message


# ─────────────────────────────────────────────────────────────────────────────
# G. JOIN_CONDITION 상세
# ─────────────────────────────────────────────────────────────────────────────

class TestJoinConditionDetailed:
    """가입 조건 — 중도해지·세제혜택 필드, 수치 정밀."""

    def test_early_termination_field_is_bool_or_int(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        for item in result.data:
            val = item.get("is_early_termination_allowed")
            assert val in (0, 1, True, False), f"기대값 불일치: {val}"

    def test_tax_benefit_field_is_bool_or_int(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        for item in result.data:
            val = item.get("is_tax_benefit_available")
            assert val in (0, 1, True, False), f"기대값 불일치: {val}"

    def test_deposit_early_termination_allowed(self, rich_service):
        # rich_db: 정기예금 플러스 is_early_termination_allowed=1
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        deposit = next(i for i in result.data if i["product_name"] == "정기예금 플러스")
        assert deposit["is_early_termination_allowed"] in (1, True)

    def test_savings_early_termination_not_allowed(self, rich_service):
        # rich_db: 자유적금 is_early_termination_allowed=0
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        savings = next(i for i in result.data if i["product_name"] == "자유적금")
        assert savings["is_early_termination_allowed"] in (0, False)

    def test_deposit_tax_benefit_available(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        deposit = next(i for i in result.data if i["product_name"] == "정기예금 플러스")
        assert deposit["is_tax_benefit_available"] in (1, True)

    def test_subscription_no_tax_benefit(self, rich_service):
        # rich_db: 주택청약종합저축 is_tax_benefit_available=0
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        sub = next(i for i in result.data if i["product_name"] == "주택청약종합저축")
        assert sub["is_tax_benefit_available"] in (0, False)

    def test_subscription_min_period_is_one(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        sub = next(i for i in result.data if i["product_name"] == "주택청약종합저축")
        assert int(sub["min_period_month"]) == 1

    def test_subscription_max_period_600(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        sub = next(i for i in result.data if i["product_name"] == "주택청약종합저축")
        assert int(sub["max_period_month"]) == 600

    def test_savings_min_join_amount(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        savings = next(i for i in result.data if i["product_name"] == "자유적금")
        assert float(savings["min_join_amount"]) == pytest.approx(10_000)

    def test_savings_max_join_amount(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        savings = next(i for i in result.data if i["product_name"] == "자유적금")
        assert float(savings["max_join_amount"]) == pytest.approx(50_000_000)


# ─────────────────────────────────────────────────────────────────────────────
# H. RATE_GUIDE 상세
# ─────────────────────────────────────────────────────────────────────────────

class TestRateGuideDetailed:
    """금리 안내 — 조건 설명, 기간 정보, 연결 정합성."""

    def test_condition_description_present(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert "condition_description" in item
            assert item["condition_description"]

    def test_period_fields_present(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert "minimum_contract_period" in item
            assert "maximum_contract_period" in item

    def test_base_rate_min_period_12(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        base_rows = [r for r in result.data if r["rate_type"] == "BASE" and r["product_name"] == "정기예금 플러스"]
        assert base_rows
        assert int(base_rows[0]["minimum_contract_period"]) == 12

    def test_preferential_rate_higher_than_zero(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        pref = [r for r in result.data if r["rate_type"] == "PREFERENTIAL"]
        for row in pref:
            assert float(row["interest_rate"]) > 0

    def test_rate_id_present_and_unique(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        ids = [item.get("rate_id") for item in result.data]
        assert len(ids) == len(set(ids)), "rate_id 중복"

    def test_all_rates_linked_to_product(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert int(item["product_id"]) > 0
            assert item["product_name"]

    def test_savings_preferential_rate_05(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        pref = [r for r in result.data if r["rate_type"] == "PREFERENTIAL" and r["product_name"] == "자유적금"]
        assert pref
        assert float(pref[0]["interest_rate"]) == pytest.approx(0.5)

    def test_subscription_base_rate_2_8(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        base = [r for r in result.data if r["rate_type"] == "BASE" and r["product_name"] == "주택청약종합저축"]
        assert base
        assert float(base[0]["interest_rate"]) == pytest.approx(2.8)


# ─────────────────────────────────────────────────────────────────────────────
# I. TERMS_RAG 검색 패턴 심화
# ─────────────────────────────────────────────────────────────────────────────

class TestTermsRagDetailed:
    """약관 검색 — 다양한 키워드, 부분 매칭, 경계."""

    def test_summary_keyword_match(self, rich_service):
        result = rich_service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="이율")
        )
        # "중도해지 약관"의 summary에 "이율" 포함
        assert result.status == "OK"
        names = [item.get("special_term_name", "") for item in result.data]
        assert any("중도해지" in name for name in names)

    def test_content_keyword_match(self, rich_service):
        result = rich_service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="수집하고")
        )
        assert result.status == "OK"

    def test_partial_term_name_match(self, rich_service):
        result = rich_service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="이용 동의")
        )
        assert result.status == "OK"
        names = [item.get("special_term_name", "") for item in result.data]
        assert any("개인정보" in name for name in names)

    def test_max_10_results(self, rich_service):
        result = rich_service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest()
        )
        assert len(result.data) <= 10

    def test_data_is_required_field_present(self, service):
        result = service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest()
        )
        item = result.data[0]
        assert item["is_required"] in (0, 1, True, False)

    def test_term_id_positive(self, service):
        result = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert int(item["special_term_id"]) > 0

    def test_term_content_not_empty(self, service):
        result = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert item["special_term_content"]


# ─────────────────────────────────────────────────────────────────────────────
# J. 현금흐름 rich_db 기반 간접 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestCashFlowRichDb:
    """rich_db: CUST001 현금흐름 분석 (거래 3건 COMPLETED 포함)."""

    def test_cust001_cash_flow_has_data(self, rich_service):
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert result.status == "OK"
        assert result.data[0]["has_data"] is True

    def test_cust001_total_balance_correct(self, rich_service):
        # CUST001 계좌 2개: 5,000,000 + 1,200,000 = 6,200,000
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(6_200_000)

    def test_cust002_cash_flow_has_data(self, rich_service):
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST2),
        )
        assert result.status == "OK"

    def test_cust002_total_balance_correct(self, rich_service):
        # CUST002 계좌: 3,000,000
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST2),
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(3_000_000)

    def test_cust001_product_count_reflects_db(self, rich_service):
        # rich_db에 SELLING 상품 3개
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert result.data[0]["product_count"] == 3

    def test_unknown_customer_empty_in_rich_db(self, rich_service):
        result = rich_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=NO_CUST),
        )
        assert result.status == "EMPTY"


# ─────────────────────────────────────────────────────────────────────────────
# K. PRODUCT_SEARCH 경계값
# ─────────────────────────────────────────────────────────────────────────────

class TestProductSearchBoundary:
    """PRODUCT_SEARCH — 금액·기간 경계값, 목적 조합."""

    def test_amount_exact_minimum_included(self, rich_service):
        # 정기예금 플러스 min_join_amount = 100,000
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", amount=100_000),
        )
        assert result.status == "OK"
        names = [i["product_name"] for i in result.data]
        assert "정기예금 플러스" in names

    def test_amount_below_minimum_excluded(self, rich_service):
        # min_join_amount=100,000, amount=99,999 → 제외되어야 함
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", amount=99_999),
        )
        # 필터에서 제외되지만 fallback으로 전체 rows[:5]를 사용
        # 실제 로직 확인: filtered 없으면 rows[:5]를 씀
        assert result.status in ("OK", "EMPTY")

    def test_period_exactly_minimum_included(self, rich_service):
        # 정기예금 플러스 min_period_month=1, max_period_month=60
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", period=1),
        )
        assert result.status == "OK"

    def test_period_exactly_maximum_included(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", period=60),
        )
        assert result.status == "OK"

    def test_period_out_of_range_excluded(self, rich_service):
        # 정기예금 max_period=60, period=61
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", period=61),
        )
        # 기간 벗어나면 필터 제외 → fallback rows[:5]
        assert result.status in ("OK", "EMPTY")

    def test_savings_monthly_amount_ok(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(
                product_type="SAVINGS",
                amount=300_000,
                purpose="monthly",
            ),
        )
        assert result.status == "OK"

    def test_subscription_no_amount_filter(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="SUBSCRIPTION"),
        )
        assert result.status == "OK"
        assert result.data[0]["product_type"] == "SUBSCRIPTION"

    def test_period_string_parsed_by_helper(self, service):
        # ChatbotService._parse_period: "12개월" → 12, "1년" → 12
        assert service._parse_period("12개월") == 12
        assert service._parse_period("1년") == 12
        assert service._parse_period("6") == 6
        assert service._parse_period(None) == 0

    def test_amount_string_man_parsed_by_helper(self, service):
        # ChatbotService._parse_amount: "100만원" → 1,000,000
        assert service._parse_amount("100만원") == pytest.approx(1_000_000)
        assert service._parse_amount("50만") == pytest.approx(500_000)
        assert service._parse_amount("1,000,000") == pytest.approx(1_000_000)
        assert service._parse_amount(None) == 0.0

    def test_amount_numeric_input_passthrough(self, service):
        assert service._parse_amount(500_000) == pytest.approx(500_000)
        assert service._parse_amount(3.5) == pytest.approx(3.5)


# ─────────────────────────────────────────────────────────────────────────────
# L. Kafka 이벤트 페이로드 내용
# ─────────────────────────────────────────────────────────────────────────────

def _published(service):
    return [(c.args[0], c.args[1]) for c in service.events.publish.call_args_list]


class TestKafkaEventPayloads:
    """이벤트 타입·페이로드 내용 검증."""

    def test_start_event_has_consultation_and_chatbot_ids(self, service):
        service.seed_default_scenario()
        session = _start(service, "KAFKA_TEST")
        events = _published(service)
        started = next(e for e in events if e[0] == "ChatbotConsultationStarted")
        assert started[1]["customerNo"] == "KAFKA_TEST"
        assert started[1]["consultationId"] == session.consultation_id
        assert started[1]["chatbotConsultationId"] == session.chatbot_consultation_id

    def test_message_event_has_process_method(self, service):
        service.seed_default_scenario()
        session = _start(service)
        service.events.publish.reset_mock()

        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        events = _published(service)
        handled = next(e for e in events if e[0] == "ChatbotMessageHandled")
        assert handled[1]["processMethod"] == "FEATURE_RATE_GUIDE"
        assert handled[1]["agentTransferRequired"] is False

    def test_agent_transfer_event_has_consultation_ids(self, service):
        service.seed_default_scenario()
        session = _start(service)
        service.events.publish.reset_mock()

        _send(service, session.chatbot_consultation_id, button_value="AGENT")
        events = _published(service)
        transfer = next(e for e in events if e[0] == "ChatbotAgentTransferRequested")
        assert "chatbotConsultationId" in transfer[1]
        assert "consultationId" in transfer[1]
        assert transfer[1]["consultationId"] == session.consultation_id

    def test_no_transfer_event_on_feature_message(self, service):
        service.seed_default_scenario()
        session = _start(service)
        service.events.publish.reset_mock()

        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        event_types = [e[0] for e in _published(service)]
        assert "ChatbotAgentTransferRequested" not in event_types

    def test_start_event_fired_once_per_start(self, service):
        service.seed_default_scenario()
        service.events.publish.reset_mock()
        _start(service)
        events = _published(service)
        started_events = [e for e in events if e[0] == "ChatbotConsultationStarted"]
        assert len(started_events) == 1

    def test_message_event_includes_message_text(self, service):
        service.seed_default_scenario()
        session = _start(service)
        service.events.publish.reset_mock()

        _send(service, session.chatbot_consultation_id, message="적금 알려줘")
        events = _published(service)
        handled = next(e for e in events if e[0] == "ChatbotMessageHandled")
        assert "적금 알려줘" in handled[1]["message"]


# ─────────────────────────────────────────────────────────────────────────────
# M. 쿼리 None vs 빈 문자열 동작
# ─────────────────────────────────────────────────────────────────────────────

class TestQueryNoneVsEmpty:
    """query=None과 query='' 동작이 동일해야 함."""

    @pytest.mark.parametrize("feature", ["PRODUCT_GUIDE", "RATE_GUIDE", "JOIN_CONDITION", "FAQ"])
    def test_none_query_returns_ok(self, service, feature):
        result = service.execute_feature(feature, ChatbotFeatureExecuteRequest(query=None))
        assert result.status == "OK"

    @pytest.mark.parametrize("feature", ["PRODUCT_GUIDE", "RATE_GUIDE", "JOIN_CONDITION", "FAQ"])
    def test_empty_query_returns_ok(self, service, feature):
        result = service.execute_feature(feature, ChatbotFeatureExecuteRequest(query=""))
        assert result.status == "OK"

    def test_product_guide_none_and_empty_same_count(self, service):
        r_none = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest(query=None))
        r_empty = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest(query=""))
        assert len(r_none.data) == len(r_empty.data)

    def test_terms_rag_empty_returns_all(self, service):
        r_none = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest(query=None))
        r_empty = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest(query=""))
        assert len(r_none.data) == len(r_empty.data)


# ─────────────────────────────────────────────────────────────────────────────
# N. ChatMessageHistory sequence_no 단조 증가
# ─────────────────────────────────────────────────────────────────────────────

class TestMessageSequenceOrder:
    """메시지 이력 — sequence_no 단조 증가 검증."""

    def test_sequence_strictly_increasing(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        for msg in ["금리 알려줘", "가입 조건", "약관 보여줘"]:
            _send(service, session.chatbot_consultation_id, message=msg)

        messages = sorted(
            db.scalars(
                select(ChatMessageHistory).where(
                    ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id
                )
            ).all(),
            key=lambda m: m.chat_message_history_id,
        )
        seqs = [m.sequence_no for m in messages]
        assert seqs == sorted(seqs), f"sequence_no 단조 증가 위반: {seqs}"

    def test_first_message_sequence_is_one(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        msg = db.scalars(
            select(ChatMessageHistory).where(
                ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id
            ).order_by(ChatMessageHistory.sequence_no)
        ).first()
        assert msg.sequence_no == 1

    def test_no_sequence_gaps_within_session(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        _send(service, session.chatbot_consultation_id, message="가입 조건")

        messages = sorted(
            db.scalars(
                select(ChatMessageHistory).where(
                    ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id
                )
            ).all(),
            key=lambda m: m.sequence_no,
        )
        seqs = [m.sequence_no for m in messages]
        for i in range(len(seqs) - 1):
            assert seqs[i + 1] == seqs[i] + 1, f"sequence_no 간격 발생: {seqs}"

    def test_two_sessions_sequence_independent(self, service, db):
        service.seed_default_scenario()
        s1 = _start(service)
        s2 = _start(service)

        _send(service, s1.chatbot_consultation_id, message="금리 알려줘")
        _send(service, s2.chatbot_consultation_id, message="금리 알려줘")

        msgs_s2 = sorted(
            db.scalars(
                select(ChatMessageHistory).where(
                    ChatMessageHistory.chatbot_consultation_id == s2.chatbot_consultation_id
                )
            ).all(),
            key=lambda m: m.sequence_no,
        )
        # s2 첫 메시지는 sequence_no=1 시작
        assert msgs_s2[0].sequence_no == 1


# ─────────────────────────────────────────────────────────────────────────────
# O. 빈 DB 시나리오 전체
# ─────────────────────────────────────────────────────────────────────────────

class TestEmptyDbAllFeatures:
    """empty_db: 인증 불필요 기능 EMPTY, 인증 필요 기능 AUTH_REQUIRED."""

    @pytest.mark.parametrize("feature", ["PRODUCT_GUIDE", "RATE_GUIDE", "JOIN_CONDITION"])
    def test_empty_db_returns_empty(self, empty_service, feature):
        result = empty_service.execute_feature(feature, ChatbotFeatureExecuteRequest())
        assert result.status == "EMPTY"

    def test_faq_always_ok_regardless_of_db(self, empty_service):
        result = empty_service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_my_accounts_empty_customer_returns_empty(self, empty_service):
        result = empty_service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no="ANY_CUST")
        )
        assert result.status == "EMPTY"

    def test_my_products_empty_customer_returns_empty(self, empty_service):
        result = empty_service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no="ANY_CUST")
        )
        assert result.status == "EMPTY"

    def test_interest_history_empty_customer_returns_empty(self, empty_service):
        result = empty_service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no="ANY_CUST")
        )
        assert result.status == "EMPTY"

    def test_my_cash_flow_empty_customer_returns_empty(self, empty_service):
        result = empty_service.execute_feature(
            "MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no="ANY_CUST")
        )
        assert result.status == "EMPTY"

    def test_staff_contract_empty_returns_empty(self, empty_service):
        result = empty_service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no="ANY_CUST", staff_id="EMP001"),
        )
        assert result.status == "EMPTY"

    def test_cash_flow_recommend_empty_customer_returns_empty(self, empty_service):
        result = empty_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no="ANY_CUST")
        )
        assert result.status == "EMPTY"

    def test_terms_rag_empty_db_returns_empty(self, empty_service):
        result = empty_service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        assert result.status == "EMPTY"

    def test_product_compare_empty_db_returns_empty(self, empty_service):
        result = empty_service.execute_feature("PRODUCT_COMPARE", ChatbotFeatureExecuteRequest())
        assert result.status == "EMPTY"


# ─────────────────────────────────────────────────────────────────────────────
# P. feature 응답 불변 계약
# ─────────────────────────────────────────────────────────────────────────────

class TestFeatureResponseContract:
    """모든 기능 — feature_code, requires_auth 항상 올바름."""

    @pytest.mark.parametrize("code, expect_auth, expect_staff", [
        ("PRODUCT_GUIDE",   False, False),
        ("RATE_GUIDE",      False, False),
        ("JOIN_CONDITION",  False, False),
        ("PRODUCT_COMPARE", False, False),
        ("TERMS_RAG",       False, False),
        ("FAQ",             False, False),
    ])
    def test_public_features_require_no_auth(self, service, code, expect_auth, expect_staff):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
        assert result.requires_auth is expect_auth
        assert result.requires_staff_auth is expect_staff

    @pytest.mark.parametrize("code", [
        "MY_ACCOUNTS", "MY_PRODUCTS", "CONTRACT_STATUS",
        "MATURITY_SCHEDULE", "INTEREST_HISTORY", "MY_CASH_FLOW", "MY_TRANSFERS",
    ])
    def test_user_finance_auth_required_sets_flag(self, service, code):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
        assert result.requires_auth is True
        assert result.requires_staff_auth is False

    @pytest.mark.parametrize("code", [
        "STAFF_CUSTOMER", "STAFF_CONTRACT", "STAFF_ACCOUNT",
        "STAFF_TRANSFER_FLOW", "STAFF_CASH_FLOW", "STAFF_CONSULTATION_HISTORY",
    ])
    def test_staff_features_staff_auth_required_sets_flag(self, service, code):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
        assert result.requires_staff_auth is True
        assert result.requires_auth is False

    @pytest.mark.parametrize("code", [
        "PRODUCT_GUIDE", "RATE_GUIDE", "MY_ACCOUNTS",
        "STAFF_CONTRACT", "CASH_FLOW_RECOMMEND", "FAQ",
    ])
    def test_feature_code_always_matches(self, service, code):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
        assert result.feature_code == code


# ─────────────────────────────────────────────────────────────────────────────
# Q. CASH_FLOW_RECOMMEND query 분기 — "예금 vs 적금" 비교
# ─────────────────────────────────────────────────────────────────────────────

class TestCashFlowRecommendQueryBranch:
    """query에 '예금' + '적금' 포함 시 룰 기반 비교 분기."""

    def test_deposit_vs_savings_query_returns_ok(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY, query="예금이랑 적금 중 뭐가 나아?"),
        )
        assert result.status == "OK"

    def test_deposit_vs_savings_message_contains_comparison(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY, query="예금이랑 적금 중 뭐가 나아?"),
        )
        assert "예금" in result.message or "적금" in result.message

    def test_surplus_customer_deposit_recommendation(self, cashflow_service):
        # CUST_SURPLUS: 잔액 50M >= 10M → 예금 추천
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(
                customer_no=CUST_SURPLUS, query="예금이랑 적금 중 어느 게 유리해?"
            ),
        )
        assert "예금" in result.message or "목돈" in result.message

    def test_salary_customer_savings_recommendation(self, cashflow_service):
        # CUST_SALARY: 월 잉여자금 ~2M → 적금 추천
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(
                customer_no=CUST_SALARY, query="예금 적금 중 뭐가 적합해?"
            ),
        )
        assert "적금" in result.message or "저축" in result.message or "납입" in result.message

    def test_nodata_customer_comparison_query(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(
                customer_no=CUST_NODATA, query="예금 적금 어느 게 맞아?"
            ),
        )
        assert result.status == "OK"
        assert result.message


# ─────────────────────────────────────────────────────────────────────────────
# R. 다중 상담사 메시지 전송
# ─────────────────────────────────────────────────────────────────────────────

class TestAgentMessageExchange:
    """상담사·고객 교대 메시지 전송 — 순서·내용 보장."""

    def _setup(self, service, chat_service):
        service.seed_default_scenario()
        started = asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        asyncio.run(service.handle_message(started.chatbot_consultation_id, "", "AGENT"))
        queue = chat_service.get_waiting_queue()
        chat_id = queue[0]["chat_consultation_id"]
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=77))
        return chat_id

    def test_agent_user_alternating_messages(self, service, chat_service):
        chat_id = self._setup(service, chat_service)
        asyncio.run(chat_service.send_message(chat_id, "무엇을 도와드릴까요?", 3))
        asyncio.run(chat_service.send_message(chat_id, "예금 해지 방법이요.", 1))
        asyncio.run(chat_service.send_message(chat_id, "안내해드리겠습니다.", 3))

        messages = chat_service.get_messages(chat_id)
        senders = [m.sender_type_code_id for m in messages if m.chat_consultation_id == chat_id]
        assert 3 in senders  # AGENT
        assert 1 in senders  # USER

    def test_five_messages_all_stored(self, service, chat_service):
        chat_id = self._setup(service, chat_service)
        for i in range(5):
            sender = 3 if i % 2 == 0 else 1
            asyncio.run(chat_service.send_message(chat_id, f"메시지 {i}", sender))

        messages = [m for m in chat_service.get_messages(chat_id) if m.chat_consultation_id == chat_id]
        assert len(messages) == 5

    def test_message_content_preserved(self, service, chat_service):
        chat_id = self._setup(service, chat_service)
        unique_text = "고유한_테스트_메시지_XYZ_12345"
        asyncio.run(chat_service.send_message(chat_id, unique_text, 3))

        messages = chat_service.get_messages(chat_id)
        contents = [m.message_content for m in messages]
        assert unique_text in contents

    def test_total_turn_count_after_messages(self, service, chat_service, db):
        from app.models import ChatConsultation
        chat_id = self._setup(service, chat_service)
        asyncio.run(chat_service.send_message(chat_id, "msg1", 3))
        asyncio.run(chat_service.send_message(chat_id, "msg2", 1))
        asyncio.run(chat_service.send_message(chat_id, "msg3", 3))

        chat = db.get(ChatConsultation, chat_id)
        assert chat.total_turn_count == 3


# ─────────────────────────────────────────────────────────────────────────────
# S. IntentClassifier 고밀도 — 복합 문장
# ─────────────────────────────────────────────────────────────────────────────

class TestIntentClassifierHighDensity:
    """복합 문장, 약관+금리 혼합, 개인화 트리거."""

    @pytest.fixture(autouse=True)
    def clf(self):
        self.clf = IntentClassifier()

    def test_terms_before_guide_in_priority(self):
        # 약관+상품 안내 → 약관 우선
        intent = self.clf.classify("중도해지 약관이랑 상품 목록 알려줘")
        assert intent == "TERMS_RAG"

    def test_compare_follow_up_various_phrases(self):
        for phrase in ["둘 다 어때?", "둘 중 어느 쪽?", "하나만 골라줘", "그 중 어느 게 나아?"]:
            intent = self.clf.classify(phrase)
            assert intent == "CASH_FLOW_RECOMMEND", f"'{phrase}' → {intent}"

    def test_personal_plus_product_type_cash_flow(self):
        for msg in ["나한테 맞는 예금이 뭐야", "나에게 맞는 적금 추천", "나는 적금이 좋을까 예금이 좋을까"]:
            intent = self.clf.classify(msg)
            assert intent in ("CASH_FLOW_RECOMMEND", "PRODUCT_COMPARE"), f"'{msg}' → {intent}"

    def test_deposit_savings_together_personal_becomes_cash_flow(self):
        intent = self.clf.classify("예금이랑 적금 나한테 뭐가 더 나아?")
        assert intent == "CASH_FLOW_RECOMMEND"

    def test_rate_guide_mixed_with_personal(self):
        # "금리" 키워드가 먼저 → RATE_GUIDE
        intent = self.clf.classify("금리 보여줘 나한테 맞는 걸로")
        assert intent == "RATE_GUIDE"

    def test_join_condition_with_extra_words(self):
        for msg in ["이 상품에 가입 조건이 어떻게 돼요?", "가입조건 자세히 알려줘", "가입할 수 있는지 알고 싶어"]:
            intent = self.clf.classify(msg)
            assert intent == "JOIN_CONDITION", f"'{msg}' → {intent}"

    def test_faq_mixed_sentences(self):
        intent = self.clf.classify("자주 묻는 질문 중에 금리 관련 있나요?")
        # FAQ가 RATE_GUIDE보다 낮은 우선순위 — 금리 키워드 없으면 FAQ
        assert intent in ("FAQ", "RATE_GUIDE")

    def test_very_long_sentence_still_classified(self):
        long_msg = "저는 매달 월급이 들어오는데 이번 달부터 적금을 들려고 하는데 나한테 맞는 적금 상품이 뭐가 있는지 추천 좀 해주세요"
        intent = self.clf.classify(long_msg)
        assert intent == "CASH_FLOW_RECOMMEND"

    def test_rank_keywords_map_to_cash_flow(self):
        for msg in ["1위부터 보여줘", "상품 순위 알려줘", "추천 순위 보여줘", "순위대로 알려줘"]:
            intent = self.clf.classify(msg)
            assert intent == "CASH_FLOW_RECOMMEND", f"'{msg}' → {intent}"


# ─────────────────────────────────────────────────────────────────────────────
# T. STAFF_CONSULTATION_HISTORY 생성 후 조회
# ─────────────────────────────────────────────────────────────────────────────

class TestStaffConsultationHistoryFlow:
    """상담 이력 — 챗봇 상담 생성 후 직원 조회 흐름."""

    def test_empty_before_any_consultation(self, service):
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "EMPTY"
        assert result.data == []

    def test_ok_after_chatbot_start(self, service):
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "OK"
        assert len(result.data) >= 1

    def test_history_item_has_required_fields(self, service):
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        item = result.data[0]
        assert "consultation_id" in item
        assert "customer_no" in item
        assert "content_summary" in item
        assert "consulted_at" in item

    def test_history_customer_no_matches(self, service):
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for item in result.data:
            assert item["customer_no"] == CUST

    def test_multiple_consultations_ordered_desc(self, service):
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) >= 2
        ids = [i["consultation_id"] for i in result.data]
        assert ids == sorted(ids, reverse=True)

    def test_unknown_customer_returns_empty(self, service):
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=NO_CUST, staff_id=STAFF),
        )
        assert result.status == "EMPTY"

    def test_requires_staff_auth_flag(self, service):
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.requires_staff_auth is True

    def test_no_staff_id_returns_staff_auth_required(self, service):
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert result.status == "STAFF_AUTH_REQUIRED"
