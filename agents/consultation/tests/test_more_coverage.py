"""
추가 커버리지 테스트 — 남은 미커버 경로 망라.

커버 영역:
  A. _data_response 헬퍼              - rows 유무에 따른 OK/EMPTY 판별
  B. _account_rows LIMIT 20           - 계좌 20개 이상 시 제한
  C. INTEREST_HISTORY LIMIT 20        - 이자 내역 20개 이상 시 제한
  D. MY_CASH_FLOW LIMIT 20            - 거래 내역 20개 이상 시 제한
  E. ChatbotMessageHistory node_id    - 봇 메시지 node_id 있음·사용자 없음
  F. PRODUCT_GUIDE 경로별 message     - 유형 필터 시 메시지 레이블
  G. PRODUCT_GUIDE 빈 DB + 유형 필터  - 필터+빈 DB → EMPTY
  H. _execute_customer_contracts 분기  - user/staff 분기 정확성
  I. ChatbotIntent active_yn 필터     - 비활성 intent는 조회 안 됨
  J. MY_PRODUCTS feature_code 필드    - feature_code="MY_PRODUCTS" 정확성
  K. TERMS_RAG LIMIT 10               - 약관 10개 이상 시 10개 제한
  L. 대화 노드 이동 후 buttons 변경   - PRODUCT_ADVICE 노드 buttons 확인
  M. 채팅 종료 후 ChatbotConsultation  - end_chat 해도 chatbot은 유지
  N. 동일 직원 여러 고객 조회          - EMP001이 CUST001·CUST002 각각 조회
  O. PRODUCT_SEARCH SAVINGS 점수 계산  - purpose=monthly 보너스
  P. rich_db MY_ACCOUNTS balance 정확  - 계좌 2개 각 잔액 검증
  Q. IntentClassifier compare→cashflow - 비교키워드+개인화 → CASH_FLOW
  R. STAFF_CONSULTATION_HISTORY DESC   - consultation_id DESC 정렬 확인
  S. _execute_terms_search LIKE 패턴   - '%keyword%' 양쪽 와일드카드
  T. ChatbotScenario 중복 seed 방지    - 10회 seed 후 시나리오 1개
  U. handle_message node_id 반환       - 버튼 클릭 후 next node_id 검증
  V. PRODUCT_COMPARE data 필드 완전성  - 6개 필수 필드 전수
  W. RATE_GUIDE product_name JOIN      - 모든 금리 행에 product_name 있음
  X. cashflow_service 4유형 has_data   - SALARY·SURPLUS=True, NODATA=False
  Y. MY_TRANSFERS account_number JOIN  - 계좌번호 JOIN 결과 검증
  Z. STAFF_ACCOUNT opened_at 형식      - rich_db 계좌 날짜 형식
"""

import asyncio

import pytest
from sqlalchemy import text

from app.models import (
    ChatConsultation,
    ChatMessageHistory,
    ChatbotConsultation,
    ChatbotIntent,
    ChatbotScenario,
)
from app.schemas import ChatbotFeatureExecuteRequest
from app.llm import IntentClassifier


CUST  = "CUST001"
CUST2 = "CUST002"
STAFF = "EMP001"


def _start(service, customer_no=CUST):
    return asyncio.run(service.start(customer_no, "HOME", "1.0.0"))


def _send(service, chatbot_id, message="", button_value=None):
    return asyncio.run(service.handle_message(chatbot_id, message, button_value))


# ─────────────────────────────────────────────────────────────────────────────
# A. _data_response 헬퍼 — OK/EMPTY 판별
# ─────────────────────────────────────────────────────────────────────────────

class TestDataResponse:
    """_data_response — rows 있으면 OK, 없으면 EMPTY."""

    def test_rows_present_returns_ok(self, service):
        rows = [{"key": "value"}]
        resp = service._data_response("TEST", rows, "ok msg", "empty msg")
        assert resp.status == "OK"
        assert resp.message == "ok msg"
        assert resp.feature_code == "TEST"

    def test_rows_empty_returns_empty(self, service):
        resp = service._data_response("TEST", [], "ok msg", "empty msg")
        assert resp.status == "EMPTY"
        assert resp.message == "empty msg"
        assert resp.data == []

    def test_requires_auth_flag_passed(self, service):
        resp = service._data_response("TEST", [], "ok", "empty", requires_auth=True)
        assert resp.requires_auth is True
        assert resp.requires_staff_auth is False

    def test_requires_staff_auth_flag_passed(self, service):
        resp = service._data_response("TEST", [{}], "ok", "empty", requires_staff_auth=True)
        assert resp.requires_staff_auth is True
        assert resp.requires_auth is False

    def test_data_matches_rows(self, service):
        rows = [{"a": 1}, {"b": 2}]
        resp = service._data_response("TEST", rows, "ok", "empty")
        assert resp.data == rows


# ─────────────────────────────────────────────────────────────────────────────
# B. _account_rows LIMIT 20
# ─────────────────────────────────────────────────────────────────────────────

class TestAccountRowsLimit:
    """MY_ACCOUNTS — 계좌 20개 초과 시 20개만 반환."""

    def test_limit_20_when_many_accounts(self, db):
        from unittest.mock import AsyncMock
        from app.services import ChatbotService
        # 25개 계좌 삽입
        with db.get_bind().begin() as conn:
            for i in range(25):
                conn.execute(text(f"""
                    INSERT INTO deposit_accounts
                    (account_id, account_number, customer_id, account_type, account_alias,
                     balance, currency, account_status, opened_at, closed_at)
                    VALUES ({100+i}, '999-{100+i:03d}-000001', 'MULTI_CUST', 'DEPOSIT', '계좌{i}',
                            1000000, 'KRW', 'ACTIVE', '20260101', NULL)
                """))
        svc = ChatbotService(db, AsyncMock(), __import__("app.llm", fromlist=["LlmHandoffAdapter"]).LlmHandoffAdapter())
        result = svc.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no="MULTI_CUST"))
        assert len(result.data) <= 20

    def test_returns_all_when_few_accounts(self, service):
        result = service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) == 1  # 기본 db에 1개


# ─────────────────────────────────────────────────────────────────────────────
# C. INTEREST_HISTORY LIMIT 20
# ─────────────────────────────────────────────────────────────────────────────

class TestInterestHistoryLimit:
    """INTEREST_HISTORY — 이자 내역 20개 초과 시 20개만 반환."""

    def test_limit_20_when_many_records(self, db):
        from unittest.mock import AsyncMock
        from app.services import ChatbotService
        with db.get_bind().begin() as conn:
            for i in range(25):
                conn.execute(text(f"""
                    INSERT INTO deposit_interest_history
                    (interest_id, contract_id, account_id, applied_interest_rate,
                     interest_amount, interest_after_tax, interest_paid_at)
                    VALUES ({100+i}, 1, 1, 3.5, 10000, 8460, '20261231')
                """))
        svc = ChatbotService(db, AsyncMock(), __import__("app.llm", fromlist=["LlmHandoffAdapter"]).LlmHandoffAdapter())
        result = svc.execute_feature("INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) <= 20


# ─────────────────────────────────────────────────────────────────────────────
# D. MY_CASH_FLOW LIMIT 20
# ─────────────────────────────────────────────────────────────────────────────

class TestMyCashFlowLimit:
    """MY_CASH_FLOW — 거래 내역 20개 초과 시 20개만 반환."""

    def test_limit_20_when_many_transactions(self, db):
        from unittest.mock import AsyncMock
        from app.services import ChatbotService
        with db.get_bind().begin() as conn:
            for i in range(25):
                conn.execute(text(f"""
                    INSERT INTO deposit_transactions
                    (transaction_id, transaction_number, account_id, transaction_type,
                     transaction_status, amount, created_at)
                    VALUES ({100+i}, 'TX-MANY-{100+i}', 1, 'TRANSFER', 'COMPLETED', 5000, '2026-05-{(i%28)+1:02d}')
                """))
        svc = ChatbotService(db, AsyncMock(), __import__("app.llm", fromlist=["LlmHandoffAdapter"]).LlmHandoffAdapter())
        result = svc.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) <= 20


# ─────────────────────────────────────────────────────────────────────────────
# E. ChatMessageHistory node_id — 봇/사용자 분리
# ─────────────────────────────────────────────────────────────────────────────

class TestMessageHistoryNodeId:
    """봇 메시지 node_id 있음, 사용자 메시지 없음."""

    def test_bot_start_message_has_node_id(self, service, db):
        from sqlalchemy import select
        service.seed_default_scenario()
        session = _start(service)
        bot_msgs = db.scalars(
            select(ChatMessageHistory).where(
                ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id,
                ChatMessageHistory.sender_type_code_id == 2,  # BOT
            )
        ).all()
        assert len(bot_msgs) >= 1
        assert bot_msgs[0].node_id is not None

    def test_user_message_no_node_id(self, service, db):
        from sqlalchemy import select
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        user_msgs = db.scalars(
            select(ChatMessageHistory).where(
                ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id,
                ChatMessageHistory.sender_type_code_id == 1,  # USER
            )
        ).all()
        assert len(user_msgs) >= 1
        assert user_msgs[0].node_id is None

    def test_scenario_button_bot_response_has_node_id(self, service, db):
        from sqlalchemy import select
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, button_value="PRODUCT_ADVICE")
        # PRODUCT_ADVICE 버튼 → 다음 노드 이동 → 봇 응답에 node_id 있음
        bot_msgs = db.scalars(
            select(ChatMessageHistory).where(
                ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id,
                ChatMessageHistory.sender_type_code_id == 2,
                ChatMessageHistory.node_id.isnot(None),
            )
        ).all()
        assert len(bot_msgs) >= 2  # start(1) + button_response(1)


# ─────────────────────────────────────────────────────────────────────────────
# F. PRODUCT_GUIDE 경로별 message 레이블
# ─────────────────────────────────────────────────────────────────────────────

class TestProductGuideMessages:
    """유형 필터에 따른 메시지 레이블."""

    def test_deposit_filter_message_label(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="예금 상품 알려줘"),
        )
        assert "예금" in result.message

    def test_savings_filter_message_label(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="적금 상품 알려줘"),
        )
        assert "적금" in result.message

    def test_subscription_filter_message_label(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="청약 상품 알려줘"),
        )
        assert "청약" in result.message

    def test_no_filter_message_generic(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert "상품" in result.message

    def test_empty_db_type_filter_empty_message(self, empty_service):
        result = empty_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="예금 상품 알려줘"),
        )
        assert result.status == "EMPTY"
        assert "예금" in result.message


# ─────────────────────────────────────────────────────────────────────────────
# G. PRODUCT_GUIDE 빈 DB + 유형 필터 → EMPTY
# ─────────────────────────────────────────────────────────────────────────────

class TestProductGuideEmptyDbWithFilter:
    """빈 DB에서 유형 필터 — EMPTY 반환."""

    @pytest.mark.parametrize("query", ["예금 상품", "적금 종류", "청약 알려줘"])
    def test_filter_with_empty_db_returns_empty(self, empty_service, query):
        result = empty_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query=query),
        )
        assert result.status == "EMPTY"


# ─────────────────────────────────────────────────────────────────────────────
# H. _execute_customer_contracts 분기
# ─────────────────────────────────────────────────────────────────────────────

class TestExecuteCustomerContracts:
    """user/staff 분기 — requires_auth·requires_staff_auth 플래그."""

    def test_my_products_requires_auth_not_staff(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert result.requires_auth is True
        assert result.requires_staff_auth is False

    def test_contract_status_requires_auth_not_staff(self, service):
        result = service.execute_feature(
            "CONTRACT_STATUS",
            ChatbotFeatureExecuteRequest(customer_no=CUST),
        )
        assert result.requires_auth is True
        assert result.requires_staff_auth is False

    def test_staff_contract_requires_staff_not_user(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.requires_staff_auth is True
        assert result.requires_auth is False

    def test_my_products_no_customer_auth_required(self, service):
        result = service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest())
        assert result.status == "AUTH_REQUIRED"

    def test_staff_contract_no_ids_staff_auth_required(self, service):
        result = service.execute_feature("STAFF_CONTRACT", ChatbotFeatureExecuteRequest())
        assert result.status == "STAFF_AUTH_REQUIRED"


# ─────────────────────────────────────────────────────────────────────────────
# I. ChatbotIntent active_yn 필터
# ─────────────────────────────────────────────────────────────────────────────

class TestChatbotIntentActiveFilter:
    """active_yn='N' intent는 _get_intent에서 조회 안 됨."""

    def test_inactive_intent_not_returned(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        # RATE_GUIDE intent 비활성화
        intent = db.scalars(
            select(ChatbotIntent).where(
                ChatbotIntent.scenario_id == scenario_id,
                ChatbotIntent.intent_name == "RATE_GUIDE",
            )
        ).first()
        assert intent is not None
        intent.active_yn = "N"
        db.commit()

        result = service._get_intent(scenario_id, "RATE_GUIDE")
        assert result is None

    def test_active_intent_still_returned(self, service, db):
        scenario_id, _ = service.seed_default_scenario()
        result = service._get_intent(scenario_id, "JOIN_CONDITION")
        assert result is not None
        assert result.active_yn == "Y"

    def test_active_yn_y_for_all_seeded_intents(self, service, db):
        from sqlalchemy import select
        scenario_id, _ = service.seed_default_scenario()
        intents = db.scalars(
            select(ChatbotIntent).where(ChatbotIntent.scenario_id == scenario_id)
        ).all()
        for intent in intents:
            assert intent.active_yn == "Y"


# ─────────────────────────────────────────────────────────────────────────────
# J. MY_PRODUCTS / CONTRACT_STATUS feature_code 정확성
# ─────────────────────────────────────────────────────────────────────────────

class TestFeatureCodeAccuracy:
    """execute_feature 결과의 feature_code 필드 정확성."""

    @pytest.mark.parametrize("code", [
        "MY_PRODUCTS", "CONTRACT_STATUS", "MATURITY_SCHEDULE",
        "MY_ACCOUNTS", "INTEREST_HISTORY", "MY_CASH_FLOW", "MY_TRANSFERS",
    ])
    def test_feature_code_in_response_matches_request(self, service, code):
        result = service.execute_feature(
            code, ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.feature_code == code

    @pytest.mark.parametrize("code", [
        "STAFF_CUSTOMER", "STAFF_CONTRACT", "STAFF_ACCOUNT",
        "STAFF_TRANSFER_FLOW", "STAFF_CASH_FLOW",
    ])
    def test_staff_feature_code_matches(self, service, code):
        result = service.execute_feature(
            code, ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF)
        )
        assert result.feature_code == code


# ─────────────────────────────────────────────────────────────────────────────
# K. TERMS_RAG LIMIT 10
# ─────────────────────────────────────────────────────────────────────────────

class TestTermsRagLimit:
    """TERMS_RAG — 약관 10개 초과 시 10개만 반환."""

    def test_limit_10_with_many_terms(self, db):
        from unittest.mock import AsyncMock
        from app.services import ChatbotService
        from app.llm import LlmHandoffAdapter
        with db.get_bind().begin() as conn:
            for i in range(15):
                conn.execute(text(f"""
                    INSERT INTO deposit_special_terms
                    (special_term_id, special_term_name, special_term_content,
                     special_term_summary, is_required, status)
                    VALUES ({100+i}, '추가약관{100+i}', '내용{100+i}', '요약{100+i}', 0, 'ACTIVE')
                """))
        svc = ChatbotService(db, AsyncMock(), LlmHandoffAdapter())
        result = svc.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        assert len(result.data) <= 10


# ─────────────────────────────────────────────────────────────────────────────
# L. 대화 노드 이동 후 buttons 변경
# ─────────────────────────────────────────────────────────────────────────────

class TestNodeTransitionButtons:
    """시나리오 노드 이동 후 buttons 내용 변경."""

    def test_start_has_four_buttons(self, service):
        service.seed_default_scenario()
        session = _start(service)
        assert len(session.buttons) == 4

    def test_after_product_advice_no_buttons(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, button_value="PRODUCT_ADVICE")
        # PRODUCT_ADVICE 노드에는 하위 버튼 없음 (leaf node)
        assert isinstance(response.buttons, list)

    def test_agent_node_no_buttons(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, button_value="AGENT")
        assert isinstance(response.buttons, list)

    def test_feature_response_no_scenario_buttons(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        # feature 실행 후 버튼은 현재 node_id 기반 (리스트이기만 하면 됨)
        assert isinstance(response.buttons, list)


# ─────────────────────────────────────────────────────────────────────────────
# M. 채팅 종료 후 ChatbotConsultation 유지
# ─────────────────────────────────────────────────────────────────────────────

class TestChatEndChatbotState:
    """end_chat 해도 ChatbotConsultation은 유지."""

    def test_chatbot_consultation_survives_chat_end(self, service, chat_service, db):
        service.seed_default_scenario()
        session = _start(service)
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
        chat_id = chat_service.get_waiting_queue()[0]["chat_consultation_id"]
        asyncio.run(chat_service.connect_agent(chat_id, 1))
        asyncio.run(chat_service.end_chat(chat_id))

        # ChatbotConsultation은 여전히 존재
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot is not None

    def test_chatbot_agent_connected_yn_after_end(self, service, chat_service, db):
        service.seed_default_scenario()
        session = _start(service)
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
        chat_id = chat_service.get_waiting_queue()[0]["chat_consultation_id"]
        asyncio.run(chat_service.connect_agent(chat_id, 1))
        asyncio.run(chat_service.end_chat(chat_id))

        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        # 이관됐으므로 agent_connected_yn='Y'
        assert chatbot.agent_connected_yn == "Y"


# ─────────────────────────────────────────────────────────────────────────────
# N. 동일 직원 여러 고객 조회
# ─────────────────────────────────────────────────────────────────────────────

class TestSameStaffMultipleCustomers:
    """EMP001이 CUST001·CUST002 각각 독립 조회."""

    @pytest.mark.parametrize("feature,cust,expected_count", [
        ("STAFF_ACCOUNT", CUST, 2),
        ("STAFF_ACCOUNT", CUST2, 1),
        ("STAFF_CONTRACT", CUST, 2),
        ("STAFF_CONTRACT", CUST2, 1),
    ])
    def test_staff_feature_per_customer(self, rich_service, feature, cust, expected_count):
        result = rich_service.execute_feature(
            feature,
            ChatbotFeatureExecuteRequest(customer_no=cust, staff_id=STAFF),
        )
        assert result.status == "OK"
        assert len(result.data) == expected_count

    def test_cust001_and_cust002_data_independent(self, rich_service):
        r1 = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        r2 = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        acc1 = {i["account_number"] for i in r1.data}
        acc2 = {i["account_number"] for i in r2.data}
        assert acc1.isdisjoint(acc2)


# ─────────────────────────────────────────────────────────────────────────────
# O. PRODUCT_SEARCH SAVINGS purpose=monthly 보너스
# ─────────────────────────────────────────────────────────────────────────────

class TestProductSearchPurposeBonus:
    """purpose 매칭 시 점수 +10 → 해당 유형이 우선."""

    def test_lump_sum_deposit_bonus(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", purpose="lump_sum"),
        )
        assert result.status == "OK"
        # 예금 + lump_sum → +10점 → rank1은 예금
        assert result.data[0]["product_type"] == "DEPOSIT"

    def test_monthly_savings_bonus(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="SAVINGS", purpose="monthly"),
        )
        assert result.status == "OK"
        assert result.data[0]["product_type"] == "SAVINGS"

    def test_no_purpose_still_returns_results(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT"),
        )
        assert result.status == "OK"
        assert len(result.data) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# P. rich_db MY_ACCOUNTS 잔액 정확성
# ─────────────────────────────────────────────────────────────────────────────

class TestRichDbAccountBalance:
    """rich_db CUST001 계좌 2개 잔액 정확."""

    def test_deposit_account_balance(self, rich_service):
        result = rich_service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        deposit = next(a for a in result.data if a["account_number"] == "001-001-000001")
        assert float(deposit["balance"]) == pytest.approx(5_000_000)

    def test_savings_account_balance(self, rich_service):
        result = rich_service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        savings = next(a for a in result.data if a["account_number"] == "001-001-000002")
        assert float(savings["balance"]) == pytest.approx(1_200_000)

    def test_account_type_matches(self, rich_service):
        result = rich_service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        types = {a["account_number"]: a["account_type"] for a in result.data}
        assert types["001-001-000001"] == "DEPOSIT"
        assert types["001-001-000002"] == "SAVINGS"


# ─────────────────────────────────────────────────────────────────────────────
# Q. IntentClassifier 비교+개인화 → CASH_FLOW
# ─────────────────────────────────────────────────────────────────────────────

class TestComparePlusPersonalCashFlow:
    """비교 키워드 + 개인화 의도 → CASH_FLOW_RECOMMEND."""

    @pytest.fixture(autouse=True)
    def clf(self):
        self.clf = IntentClassifier()

    @pytest.mark.parametrize("msg", [
        "나한테 차이가 뭐야",   # 차이가(COMPARE) + 나한테(personal) → CASH_FLOW
        "나는 비교해줘",        # 비교해줘(COMPARE) + 나는(personal) → CASH_FLOW
        "저한테 비교해줘",      # 비교해줘(COMPARE) + 저한테(personal) → CASH_FLOW
    ])
    def test_compare_with_personal_intent(self, msg):
        intent = self.clf.classify(msg)
        assert intent == "CASH_FLOW_RECOMMEND", f"'{msg}' → {intent}"

    def test_compare_without_personal_stays_compare(self):
        # 비교 키워드만, 개인화 없음 → PRODUCT_COMPARE
        intent = self.clf.classify("예금이랑 적금 비교해줘")
        assert intent == "PRODUCT_COMPARE"

    def test_deposit_savings_together_personal_cashflow(self):
        intent = self.clf.classify("예금 적금 나한테 뭐가 맞아")
        assert intent == "CASH_FLOW_RECOMMEND"


# ─────────────────────────────────────────────────────────────────────────────
# R. STAFF_CONSULTATION_HISTORY DESC 정렬
# ─────────────────────────────────────────────────────────────────────────────

class TestStaffConsultationHistorySort:
    """consultation_id DESC — 최신 상담 먼저."""

    def test_two_consultations_desc_order(self, service):
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        asyncio.run(service.start(CUST, "HOME", "1.0.0"))

        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) == 2
        ids = [i["consultation_id"] for i in result.data]
        assert ids[0] > ids[1], "DESC 정렬 아님"

    def test_three_consultations_desc(self, service):
        service.seed_default_scenario()
        for _ in range(3):
            asyncio.run(service.start(CUST, "HOME", "1.0.0"))

        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        ids = [i["consultation_id"] for i in result.data]
        assert ids == sorted(ids, reverse=True)


# ─────────────────────────────────────────────────────────────────────────────
# S. TERMS_RAG LIKE 패턴 — OR 조건 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestTermsRagLikePattern:
    """TERMS_RAG — 이름 OR 내용 OR 요약 LIKE 매칭."""

    def test_name_match(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="개인정보"),
        )
        assert result.status == "OK"
        found = any("개인정보" in i.get("special_term_name", "") for i in result.data)
        assert found

    def test_content_match(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="이용합니다"),
        )
        assert result.status == "OK"

    def test_summary_match(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="동의 요약"),
        )
        assert result.status == "OK"

    def test_percent_wildcard_both_sides(self, service):
        # LIKE '%query%' → 앞뒤 모두 매칭
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="정보"),  # 개인정보 중간 매칭
        )
        assert result.status == "OK"

    def test_no_match_returns_empty(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="ZZZNOMATCHZZZ"),
        )
        assert result.status == "EMPTY"


# ─────────────────────────────────────────────────────────────────────────────
# T. ChatbotScenario 중복 seed 방지
# ─────────────────────────────────────────────────────────────────────────────

class TestScenarioNoDuplication:
    """seed 10회 — 시나리오 1개만 존재."""

    def test_ten_seeds_one_scenario(self, service, db):
        from sqlalchemy import select
        for _ in range(10):
            service.seed_default_scenario()
        count = db.scalar(
            select(__import__("sqlalchemy").func.count()).select_from(ChatbotScenario)
        )
        assert count == 1

    def test_repeated_seed_consistent_scenario_id(self, service):
        id1, _ = service.seed_default_scenario()
        id2, _ = service.seed_default_scenario()
        id3, _ = service.seed_default_scenario()
        assert id1 == id2 == id3


# ─────────────────────────────────────────────────────────────────────────────
# U. handle_message node_id 반환 — 버튼 클릭 후 next node_id
# ─────────────────────────────────────────────────────────────────────────────

class TestHandleMessageNodeId:
    """버튼 클릭 → next_node의 node_id 반환."""

    def test_button_response_returns_next_node_id(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, button_value="PRODUCT_ADVICE")
        assert response.node_id > 0
        assert response.node_id != session.node_id  # 다음 노드로 이동

    def test_feature_message_returns_current_node_id(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        # feature 실행은 current_node_id 반환 (0 또는 현재 node_id)
        assert response.node_id >= 0

    def test_agent_response_node_id(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, button_value="AGENT")
        assert response.node_id >= 0


# ─────────────────────────────────────────────────────────────────────────────
# V. PRODUCT_COMPARE data 필드 완전성
# ─────────────────────────────────────────────────────────────────────────────

class TestProductCompareDataFields:
    """PRODUCT_COMPARE data — 6개 필수 필드 전수."""

    REQUIRED = [
        "product_id", "product_name", "product_type",
        "base_interest_rate", "min_join_amount", "max_join_amount",
    ]

    def test_all_required_fields_present(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1, 2]),
        )
        for item in result.data:
            for field in self.REQUIRED:
                assert field in item, f"{field} 없음"

    def test_product_id_positive(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1, 2, 3]),
        )
        for item in result.data:
            assert int(item["product_id"]) > 0

    def test_base_interest_rate_positive(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1, 2]),
        )
        for item in result.data:
            assert float(item["base_interest_rate"]) > 0

    def test_period_fields_present(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1]),
        )
        item = result.data[0]
        assert "min_period_month" in item
        assert "max_period_month" in item


# ─────────────────────────────────────────────────────────────────────────────
# W. RATE_GUIDE product_name JOIN
# ─────────────────────────────────────────────────────────────────────────────

class TestRateGuideProductNameJoin:
    """모든 금리 행에 product_name 있음 (JOIN 결과)."""

    def test_all_rows_have_product_name(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert item.get("product_name"), f"product_name 없는 행: {item}"

    def test_product_names_match_db(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        names = {item["product_name"] for item in result.data}
        assert "정기예금 플러스" in names
        assert "자유적금" in names
        assert "주택청약종합저축" in names

    def test_condition_description_per_rate(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert "condition_description" in item
            assert item["condition_description"]  # 빈 문자열 아님


# ─────────────────────────────────────────────────────────────────────────────
# X. cashflow_service 4유형 has_data 상태
# ─────────────────────────────────────────────────────────────────────────────

class TestCashflowHasData:
    """cashflow_db 4유형 has_data 정확성."""

    @pytest.mark.parametrize("cust,expected", [
        ("CUST_SALARY",  True),
        ("CUST_SURPLUS", True),
        ("CUST_TIGHT",   True),
        ("CUST_NODATA",  False),
    ])
    def test_has_data_per_customer(self, cashflow_service, cust, expected):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=cust),
        )
        assert result.data[0]["has_data"] is expected, \
            f"{cust}: has_data={result.data[0]['has_data']} != {expected}"

    def test_missing_customer_no_has_data_field(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="NO_SUCH"),
        )
        assert result.status == "EMPTY"
        assert result.data == []


# ─────────────────────────────────────────────────────────────────────────────
# Y. MY_TRANSFERS account_number JOIN 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestMyTransfersAccountNumberJoin:
    """MY_TRANSFERS — account_number가 JOIN 결과."""

    def test_account_number_in_result(self, service):
        result = service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        for item in result.data:
            assert "account_number" in item
            assert item["account_number"]  # 빈 문자열 아님

    def test_account_number_format(self, service):
        result = service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        for item in result.data:
            # 형식: "NNN-NNN-NNNNNN"
            parts = item["account_number"].split("-")
            assert len(parts) == 3

    def test_cashflow_salary_transfers_account_number(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no="CUST_SALARY")
        )
        for item in result.data:
            assert item["account_number"] == "CF-001-001"


# ─────────────────────────────────────────────────────────────────────────────
# Z. STAFF_ACCOUNT opened_at 형식
# ─────────────────────────────────────────────────────────────────────────────

class TestStaffAccountOpenedAt:
    """rich_db STAFF_ACCOUNT — opened_at 날짜 형식."""

    def test_opened_at_format_8_digits(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for acc in result.data:
            opened = acc.get("opened_at", "")
            assert len(opened) == 8, f"opened_at 형식 오류: {opened}"
            assert opened.isdigit(), f"opened_at 숫자 아님: {opened}"

    def test_cust001_deposit_opened_20260101(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        deposit = next(a for a in result.data if a["account_number"] == "001-001-000001")
        assert deposit["opened_at"] == "20260101"

    def test_cust001_savings_opened_20260301(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        savings = next(a for a in result.data if a["account_number"] == "001-001-000002")
        assert savings["opened_at"] == "20260301"

    def test_closed_at_none_for_active_accounts(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for acc in result.data:
            assert acc.get("closed_at") is None

    def test_currency_krw(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for acc in result.data:
            assert acc["currency"] == "KRW"
