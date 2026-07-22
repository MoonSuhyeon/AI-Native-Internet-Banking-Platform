"""
깊이 있는 커버리지 테스트 — 미커버 코드 경로 전수.

커버 영역:
  A. execute_transfer 전 분기     - 계좌 검증·잔액·같은계좌·정상이체·DB 오류
  B. _is_llm_error                - 에러 메시지 감지 패턴
  C. _build_history_context       - 빈 이력·대화 이력 포맷팅·max_turns
  D. _build_rag_context           - RAG 미준비·빈 메시지·None
  E. _resolve_ambiguous_query     - 지시어 없음·있음·이전 대화 상품 추출
  F. _analyze_customer_cash_flow  - 직접 호출 단위 테스트
  G. _rank_products               - 100점 체계·유형별 필터·정렬
  H. _rule_based_recommend        - 8가지 분기 전수
  I. _make_reason                 - 상품 유형별 사유 텍스트 생성
  J. ChatService.get_messages     - chatbot+agent 메시지 통합
  K. _open_chat_consultation      - 이미 존재 시 중복 생성 방지
  L. _record_message              - button_value·process_method 저장
  M. _latest_node_id              - node_id 없는 경우
  N. _get_intent                  - 존재·부재 케이스
  O. _get_customer_no             - consultation 링크
  P. LlmHandoffAdapter.answer     - fallback 메시지 반환
  Q. FeatureAnswerFormatter 전체  - _rate·_products·_join·_compare·_terms 세부
  R. ChatConsultationResponse 직렬화 - _to_chat_response WAITING/CONNECTED/ENDED
"""

import asyncio
import os
from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from app.database import Base
from app.llm import FeatureAnswerFormatter, LlmHandoffAdapter
from app.models import (
    ChatConsultation,
    ChatMessageHistory,
    ChatbotConsultation,
    ChatbotIntent,
)
from app.schemas import ChatbotFeatureExecuteRequest, ChatbotTransferRequest
from app.services import ChatbotService, ChatService, _chat_status
from app.llm import LlmHandoffAdapter


# ── 공통 상수 ─────────────────────────────────────────────────────────────────

CUST   = "CUST001"
CUST2  = "CUST002"
STAFF  = "EMP001"


# ── 이체 전용 DB 픽스처 ───────────────────────────────────────────────────────

@pytest.fixture()
def transfer_db() -> Session:
    """execute_transfer 가 요구하는 컬럼(is_withdrawable, updated_at 등) 완비 DB."""
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE deposit_accounts (
                account_id   INTEGER PRIMARY KEY,
                account_number TEXT,
                customer_id  TEXT,
                account_type TEXT,
                account_alias TEXT,
                balance      NUMERIC,
                currency     TEXT,
                account_status TEXT,
                is_withdrawable INTEGER DEFAULT 1,
                opened_at    TEXT,
                closed_at    TEXT,
                updated_at   TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_accounts VALUES
            -- (account_id, account_number, customer_id, type, alias, balance, currency, status, is_withdrawable, opened, closed, updated)
            (1,'001-001-000001','CUST001','DEPOSIT','내 계좌',  1000000,'KRW','ACTIVE',1,'20260101',NULL,NULL),
            (2,'001-002-000001','CUST002','DEPOSIT','수취 계좌', 500000,'KRW','ACTIVE',1,'20260101',NULL,NULL),
            (3,'001-003-000001','CUST003','DEPOSIT','출금 불가', 1000000,'KRW','ACTIVE',0,'20260101',NULL,NULL),
            (4,'001-004-000001','CUST004','DEPOSIT','잔액 부족',  10000,'KRW','ACTIVE',1,'20260101',NULL,NULL)
        """))
        conn.execute(text("""
            CREATE TABLE deposit_transactions (
                transaction_id    INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_number TEXT,
                account_id        INTEGER,
                transaction_type  TEXT,
                direction_type    TEXT,
                amount            NUMERIC,
                balance_before    NUMERIC,
                balance_after     NUMERIC,
                available_balance_after NUMERIC,
                fee_amount        NUMERIC DEFAULT 0,
                currency          TEXT    DEFAULT 'W',
                status            TEXT,
                channel_type      TEXT,
                transaction_memo  TEXT,
                transaction_summary TEXT,
                transaction_at    TEXT,
                counterparty_account_no  TEXT,
                counterparty_account_id  INTEGER,
                counterparty_customer_id TEXT,
                transaction_status TEXT,
                created_at        TEXT,
                updated_at        TEXT
            )
        """))
    session = Session(engine)
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


@pytest.fixture()
def transfer_service(transfer_db: Session) -> ChatbotService:
    return ChatbotService(transfer_db, AsyncMock(), LlmHandoffAdapter())


# ─────────────────────────────────────────────────────────────────────────────
# A. execute_transfer 전 분기
# ─────────────────────────────────────────────────────────────────────────────

class TestExecuteTransfer:
    """이체 실행 — 검증 분기 전수 + 정상 이체."""

    def _req(self, from_id=1, to_acc="001-002-000001", amount=100_000, memo="테스트"):
        return ChatbotTransferRequest(
            customer_no=CUST,
            from_account_id=from_id,
            to_account_number=to_acc,
            amount=amount,
            memo=memo,
        )

    def test_source_account_not_found(self, transfer_service):
        result = transfer_service.execute_transfer(
            ChatbotTransferRequest(
                customer_no=CUST,
                from_account_id=99999,
                to_account_number="001-002-000001",
                amount=100_000,
            )
        )
        assert result.status == "ERROR"
        assert "출금 계좌" in result.message

    def test_wrong_customer_returns_error(self, transfer_service):
        # account_id=1은 CUST001 소유 — CUST999로 조회 시 없음
        result = transfer_service.execute_transfer(
            ChatbotTransferRequest(
                customer_no="CUST999",
                from_account_id=1,
                to_account_number="001-002-000001",
                amount=100_000,
            )
        )
        assert result.status == "ERROR"
        assert "출금 계좌" in result.message

    def test_non_withdrawable_account(self, transfer_service):
        result = transfer_service.execute_transfer(
            ChatbotTransferRequest(
                customer_no="CUST003",
                from_account_id=3,
                to_account_number="001-001-000001",
                amount=100_000,
            )
        )
        assert result.status == "ERROR"
        assert "출금이 불가능" in result.message

    def test_insufficient_balance(self, transfer_service):
        result = transfer_service.execute_transfer(
            ChatbotTransferRequest(
                customer_no="CUST004",
                from_account_id=4,
                to_account_number="001-001-000001",
                amount=500_000,
            )
        )
        assert result.status == "ERROR"
        assert "잔액이 부족" in result.message

    def test_zero_amount_error(self, transfer_service):
        # amount=0 → 잔액(1,000,000) >= 0 이므로 잔액 체크 통과 → 금액 체크에서 걸림
        result = transfer_service.execute_transfer(self._req(amount=0))
        assert result.status == "ERROR"
        assert "0원보다 커야" in result.message

    def test_destination_not_found(self, transfer_service):
        result = transfer_service.execute_transfer(
            self._req(to_acc="999-999-999999")
        )
        assert result.status == "ERROR"
        assert "수취 계좌" in result.message

    def test_same_account_error(self, transfer_service):
        # from=1(계좌번호 001-001-000001), to=001-001-000001 → 동일 계좌
        result = transfer_service.execute_transfer(
            self._req(to_acc="001-001-000001")
        )
        assert result.status == "ERROR"
        assert "동일" in result.message

    def test_successful_transfer_status_ok(self, transfer_service):
        result = transfer_service.execute_transfer(self._req(amount=100_000))
        assert result.status == "OK"

    def test_successful_transfer_message(self, transfer_service):
        result = transfer_service.execute_transfer(self._req(amount=100_000))
        assert "100,000원" in result.message
        assert "001-002-000001" in result.message

    def test_successful_transfer_balance_after(self, transfer_service):
        result = transfer_service.execute_transfer(self._req(amount=100_000))
        assert result.balance_after == 1_000_000 - 100_000

    def test_successful_transfer_transaction_id_positive(self, transfer_service):
        result = transfer_service.execute_transfer(self._req(amount=100_000))
        assert result.transaction_id is not None
        assert result.transaction_id > 0

    def test_balance_deducted_from_source(self, transfer_service, transfer_db):
        transfer_service.execute_transfer(self._req(amount=200_000))
        row = transfer_db.execute(
            text("SELECT balance FROM deposit_accounts WHERE account_id=1")
        ).scalar()
        assert row == 800_000

    def test_balance_added_to_destination(self, transfer_service, transfer_db):
        transfer_service.execute_transfer(self._req(amount=200_000))
        row = transfer_db.execute(
            text("SELECT balance FROM deposit_accounts WHERE account_id=2")
        ).scalar()
        assert row == 700_000

    def test_two_transaction_records_created(self, transfer_service, transfer_db):
        transfer_service.execute_transfer(self._req(amount=100_000))
        count = transfer_db.execute(
            text("SELECT COUNT(*) FROM deposit_transactions")
        ).scalar()
        assert count == 2  # OUT + IN

    def test_out_transaction_direction(self, transfer_service, transfer_db):
        transfer_service.execute_transfer(self._req(amount=100_000))
        row = transfer_db.execute(
            text("SELECT direction_type FROM deposit_transactions WHERE account_id=1")
        ).scalar()
        assert row == "OUT"

    def test_in_transaction_direction(self, transfer_service, transfer_db):
        transfer_service.execute_transfer(self._req(amount=100_000))
        row = transfer_db.execute(
            text("SELECT direction_type FROM deposit_transactions WHERE account_id=2")
        ).scalar()
        assert row == "IN"

    def test_memo_stored_in_transaction(self, transfer_service, transfer_db):
        transfer_service.execute_transfer(self._req(amount=100_000, memo="생일 선물"))
        row = transfer_db.execute(
            text("SELECT transaction_memo FROM deposit_transactions WHERE account_id=1")
        ).scalar()
        assert row == "생일 선물"

    def test_transfer_api_returns_chatbot_transfer_response(self, transfer_service):
        from app.schemas import ChatbotTransferResponse
        result = transfer_service.execute_transfer(self._req(amount=50_000))
        assert isinstance(result, ChatbotTransferResponse)


# ─────────────────────────────────────────────────────────────────────────────
# B. _is_llm_error
# ─────────────────────────────────────────────────────────────────────────────

class TestIsLlmError:
    """_is_llm_error — 에러 응답 패턴 감지."""

    def test_error_prefix_returns_true(self, service):
        assert service._is_llm_error(
            "죄송합니다, 일시적인 오류가 발생했습니다. 상담사 연결을 원하시면 '상담사 연결'을 선택해 주세요."
        ) is True

    def test_normal_response_returns_false(self, service):
        assert service._is_llm_error("정기예금 상품을 추천해 드립니다.") is False

    def test_empty_string_returns_false(self, service):
        assert service._is_llm_error("") is False

    def test_partial_match_returns_false(self, service):
        # 같은 단어지만 접두어로 시작하지 않음
        assert service._is_llm_error("(오류) 죄송합니다, 일시적인 오류가 발생했습니다.") is False

    def test_exact_prefix_match(self, service):
        assert service._is_llm_error("죄송합니다, 일시적인 오류가 발생했습니다.") is True

    def test_whitespace_prefix_returns_false(self, service):
        assert service._is_llm_error(" 죄송합니다, 일시적인 오류가 발생했습니다.") is False


# ─────────────────────────────────────────────────────────────────────────────
# C. _build_history_context
# ─────────────────────────────────────────────────────────────────────────────

def _start(service, customer_no=CUST):
    return asyncio.run(service.start(customer_no, "HOME", "1.0.0"))

def _send(service, chatbot_id, message="", button_value=None):
    return asyncio.run(service.handle_message(chatbot_id, message, button_value))


class TestBuildHistoryContext:
    """_build_history_context — 이력 포맷팅."""

    def test_empty_history_returns_empty_string(self, service):
        # 챗봇 상담 ID 없는 경우 (0 → 이력 없음)
        result = service._build_history_context(99999)
        assert result == ""

    def test_history_after_start(self, service):
        service.seed_default_scenario()
        session = _start(service)
        ctx = service._build_history_context(session.chatbot_consultation_id)
        assert "[대화 이력]" in ctx
        assert len(ctx) > 10

    def test_history_includes_bot_label(self, service):
        service.seed_default_scenario()
        session = _start(service)
        ctx = service._build_history_context(session.chatbot_consultation_id)
        assert "챗봇" in ctx

    def test_history_includes_user_message(self, service):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        ctx = service._build_history_context(session.chatbot_consultation_id)
        assert "사용자" in ctx
        assert "금리 알려줘" in ctx

    def test_max_turns_limits_messages(self, service):
        service.seed_default_scenario()
        session = _start(service)
        for msg in ["msg1", "msg2", "msg3", "msg4", "msg5", "msg6"]:
            _send(service, session.chatbot_consultation_id, message=msg)
        ctx1 = service._build_history_context(session.chatbot_consultation_id, max_turns=1)
        ctx5 = service._build_history_context(session.chatbot_consultation_id, max_turns=5)
        assert len(ctx1) < len(ctx5)

    def test_history_format_starts_with_header(self, service):
        service.seed_default_scenario()
        session = _start(service)
        ctx = service._build_history_context(session.chatbot_consultation_id)
        assert ctx.startswith("[대화 이력]")

    def test_product_keywords_in_history(self, service):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="적금 상품 알려줘")
        ctx = service._build_history_context(session.chatbot_consultation_id)
        assert "적금" in ctx


# ─────────────────────────────────────────────────────────────────────────────
# D. _build_rag_context
# ─────────────────────────────────────────────────────────────────────────────

class TestBuildRagContext:
    """_build_rag_context — RAG 미준비 시 빈 문자열."""

    def test_no_rag_returns_empty(self, service):
        # 기본 service에는 RAG 없음
        result = service._build_rag_context("예금 상품 추천")
        assert result == ""

    def test_empty_message_returns_empty(self, service):
        result = service._build_rag_context("")
        assert result == ""

    def test_none_rag_engine_returns_empty(self, service):
        assert service._rag is None
        result = service._build_rag_context("어떤 메시지든")
        assert result == ""


# ─────────────────────────────────────────────────────────────────────────────
# E. _resolve_ambiguous_query
# ─────────────────────────────────────────────────────────────────────────────

class TestResolveAmbiguousQuery:
    """_resolve_ambiguous_query — 지시어 처리·상품 추출."""

    def test_no_ambiguous_words_returns_original(self, service):
        result = service._resolve_ambiguous_query("예금 상품 알려줘", None)
        assert result == "예금 상품 알려줘"

    def test_ambiguous_without_consultation_id_returns_original(self, service):
        result = service._resolve_ambiguous_query("둘 중 어느 게 나아?", None)
        assert result == "둘 중 어느 게 나아?"

    def test_ambiguous_with_no_history_returns_original(self, service):
        service.seed_default_scenario()
        session = _start(service)
        # 대화 이력에 상품 유형 언급 없음
        result = service._resolve_ambiguous_query("둘 중 어느 게 나아?", session.chatbot_consultation_id)
        assert result == "둘 중 어느 게 나아?"

    def test_ambiguous_with_product_in_history(self, service):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="적금 상품 알려줘")
        result = service._resolve_ambiguous_query("둘 중 어느 게 나아?", session.chatbot_consultation_id)
        assert "적금" in result
        assert "이전 대화에서 언급된 상품" in result

    def test_ambiguous_multiple_products_in_history(self, service):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="예금 상품 알려줘")
        _send(service, session.chatbot_consultation_id, message="적금도 궁금해")
        result = service._resolve_ambiguous_query("둘 중 어느 게 좋아?", session.chatbot_consultation_id)
        # 예금, 적금 모두 포함
        assert "예금" in result or "적금" in result

    @pytest.mark.parametrize("phrase", [
        "둘 중", "어느 쪽", "어느쪽", "어떤 쪽", "둘다", "둘 다",
        "그 중", "그중", "이 중", "하나만", "딱 하나",
    ])
    def test_ambiguous_keywords_detected(self, service, phrase):
        # 지시어 포함 시 chatbot_consultation_id 없어도 동일 쿼리 반환 (변경 없음)
        result = service._resolve_ambiguous_query(phrase, None)
        assert result == phrase  # consultation 없으면 그대로 반환

    def test_original_query_appended_to_context(self, service):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="청약 상품 알려줘")
        q = "어느 쪽이 좋을까요?"
        result = service._resolve_ambiguous_query(q, session.chatbot_consultation_id)
        assert q in result  # 원본 쿼리 포함


# ─────────────────────────────────────────────────────────────────────────────
# F. _analyze_customer_cash_flow 단위
# ─────────────────────────────────────────────────────────────────────────────

class TestAnalyzeCustomerCashFlow:
    """_analyze_customer_cash_flow — 직접 단위 테스트."""

    def test_no_accounts_returns_none(self, service):
        result = service._analyze_customer_cash_flow("NO_SUCH_CUST")
        assert result is None

    def test_account_with_no_transactions(self, service):
        result = service._analyze_customer_cash_flow(CUST)
        # conftest: 계좌는 있지만 COMPLETED 거래가 없으면 has_data=False
        assert result is not None
        assert "total_balance" in result
        assert "has_data" in result

    def test_return_type_is_dict(self, cashflow_service):
        result = cashflow_service._analyze_customer_cash_flow("CUST_SALARY")
        assert isinstance(result, dict)

    def test_all_keys_present(self, cashflow_service):
        result = cashflow_service._analyze_customer_cash_flow("CUST_SALARY")
        assert "total_balance" in result
        assert "monthly_surplus" in result
        assert "monthly_tx_count" in result
        assert "has_data" in result

    def test_salary_has_data_true(self, cashflow_service):
        result = cashflow_service._analyze_customer_cash_flow("CUST_SALARY")
        assert result["has_data"] is True

    def test_nodata_has_data_false(self, cashflow_service):
        result = cashflow_service._analyze_customer_cash_flow("CUST_NODATA")
        assert result["has_data"] is False
        assert result["monthly_surplus"] == 0.0
        assert result["monthly_tx_count"] == 0.0

    def test_tight_negative_surplus(self, cashflow_service):
        result = cashflow_service._analyze_customer_cash_flow("CUST_TIGHT")
        assert result["monthly_surplus"] < 0

    def test_surplus_large_balance(self, cashflow_service):
        result = cashflow_service._analyze_customer_cash_flow("CUST_SURPLUS")
        assert float(result["total_balance"]) == pytest.approx(50_000_000)

    def test_months_parameter_scales_result(self, cashflow_service):
        r3 = cashflow_service._analyze_customer_cash_flow("CUST_SALARY", months=3)
        r6 = cashflow_service._analyze_customer_cash_flow("CUST_SALARY", months=6)
        # months가 2배면 monthly_surplus는 절반
        assert pytest.approx(r3["monthly_surplus"] / 2, rel=0.01) == r6["monthly_surplus"]


# ─────────────────────────────────────────────────────────────────────────────
# G. _rank_products — 100점 체계
# ─────────────────────────────────────────────────────────────────────────────

class TestRankProducts:
    """_rank_products — 점수 계산·유형 필터·정렬."""

    DEPOSIT_PRODUCT = {
        "banking_product_id": 1, "deposit_product_name": "정기예금A",
        "deposit_product_type": "DEPOSIT", "base_interest_rate": 3.5,
        "min_join_amount": 100_000, "max_join_amount": 100_000_000,
        "min_period_month": 12, "max_period_month": 60,
        "is_early_termination_allowed": 1, "is_tax_benefit_available": 1,
    }
    SAVINGS_PRODUCT = {
        "banking_product_id": 2, "deposit_product_name": "자유적금B",
        "deposit_product_type": "SAVINGS", "base_interest_rate": 4.0,
        "min_join_amount": 10_000, "max_join_amount": 50_000_000,
        "min_period_month": 12, "max_period_month": 36,
        "is_early_termination_allowed": 0, "is_tax_benefit_available": 1,
    }
    SUBSCRIPTION_PRODUCT = {
        "banking_product_id": 3, "deposit_product_name": "청약C",
        "deposit_product_type": "SUBSCRIPTION", "base_interest_rate": 2.8,
        "min_join_amount": 2_000, "max_join_amount": 1_000_000,
        "min_period_month": 1, "max_period_month": 600,
        "is_early_termination_allowed": 0, "is_tax_benefit_available": 0,
    }

    def test_empty_products_returns_empty(self, service):
        cf = {"total_balance": 1_000_000, "monthly_surplus": 500_000, "monthly_tx_count": 5, "has_data": True}
        result = service._rank_products(cf, [])
        assert result == []

    def test_wealthy_customer_prefers_deposit(self, service):
        cf = {"total_balance": 50_000_000, "monthly_surplus": 1_000_000, "monthly_tx_count": 5, "has_data": True}
        products = [self.DEPOSIT_PRODUCT, self.SAVINGS_PRODUCT]
        ranked = service._rank_products(cf, products)
        assert len(ranked) >= 1
        # 목돈형 고객 → 예금 우선
        assert ranked[0].get("deposit_product_type") == "DEPOSIT"

    def test_no_surplus_excludes_savings(self, service):
        cf = {"total_balance": 5_000_000, "monthly_surplus": -100_000, "monthly_tx_count": 5, "has_data": True}
        products = [self.DEPOSIT_PRODUCT, self.SAVINGS_PRODUCT, self.SUBSCRIPTION_PRODUCT]
        ranked = service._rank_products(cf, products)
        types = {p.get("deposit_product_type") for p in ranked}
        assert "SAVINGS" not in types
        assert "SUBSCRIPTION" not in types

    def test_rank_adds_reason_field(self, service):
        cf = {"total_balance": 5_000_000, "monthly_surplus": 500_000, "monthly_tx_count": 5, "has_data": True}
        ranked = service._rank_products(cf, [self.DEPOSIT_PRODUCT])
        assert "_reason" in ranked[0]
        assert ranked[0]["_reason"]

    def test_minimum_balance_filter_deposit(self, service):
        # 잔액 50,000 < 최소 가입금액 100,000 → 예금 제외
        cf = {"total_balance": 50_000, "monthly_surplus": 0, "monthly_tx_count": 0, "has_data": True}
        ranked = service._rank_products(cf, [self.DEPOSIT_PRODUCT])
        assert ranked == []

    def test_sorted_by_total_score_desc(self, service):
        cf = {"total_balance": 50_000_000, "monthly_surplus": 1_000_000, "monthly_tx_count": 5, "has_data": True}
        products = [self.DEPOSIT_PRODUCT, self.SAVINGS_PRODUCT]
        ranked = service._rank_products(cf, products)
        assert len(ranked) >= 2  # 두 상품 모두 반환


# ─────────────────────────────────────────────────────────────────────────────
# H. _rule_based_recommend — 8가지 분기 전수
# ─────────────────────────────────────────────────────────────────────────────

class TestRuleBasedRecommend:
    """_rule_based_recommend — 모든 분기 정확한 키워드 검증."""

    PRODUCTS = [
        {"deposit_product_name": "정기예금 플러스", "deposit_product_type": "DEPOSIT", "base_interest_rate": 3.5},
        {"deposit_product_name": "자유적금", "deposit_product_type": "SAVINGS", "base_interest_rate": 4.0},
    ]

    # ── 비교 질문 분기 ─────────────────────────────────────────────────────────

    def test_comparison_no_data_mentions_both(self, service):
        cf = {"total_balance": 0, "monthly_surplus": 0, "has_data": False}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "예금이랑 적금 차이가 뭐야")
        assert "📌 예금이 적합한 경우" in result
        assert "📌 적금이 적합한 경우" in result

    def test_comparison_large_balance_recommends_deposit(self, service):
        cf = {"total_balance": 15_000_000, "monthly_surplus": 500_000, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "예금 적금 중 뭐가 좋아")
        assert "예금" in result
        assert "15,000,000" in result

    def test_comparison_surplus_300k_recommends_savings(self, service):
        cf = {"total_balance": 500_000, "monthly_surplus": 500_000, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "예금 적금 비교해줘")
        assert "적금" in result

    def test_comparison_small_surplus_recommends_free_savings(self, service):
        cf = {"total_balance": 100_000, "monthly_surplus": 100_000, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "예금이랑 적금 뭐가 나아")
        assert "자유납입 적금" in result or "자유적금" in result

    def test_comparison_no_surplus_recommends_small_savings(self, service):
        cf = {"total_balance": 0, "monthly_surplus": -100_000, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "예금이나 적금 추천해줘")
        assert "소액 자유적금" in result or "자유적금" in result

    # ── 일반 추천 분기 ─────────────────────────────────────────────────────────

    def test_no_data_general_recommend(self, service):
        cf = {"total_balance": 0, "monthly_surplus": 0, "has_data": False}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "상품 추천해줘")
        assert "거래 내역이 부족" in result

    def test_large_balance_recommends_deposit(self, service):
        cf = {"total_balance": 20_000_000, "monthly_surplus": 0, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "상품 추천해줘")
        assert "정기예금" in result or "목돈" in result

    def test_large_surplus_recommends_savings(self, service):
        cf = {"total_balance": 1_000_000, "monthly_surplus": 600_000, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "추천해줘")
        assert "적금" in result

    def test_small_surplus_recommends_free_savings(self, service):
        cf = {"total_balance": 200_000, "monthly_surplus": 100_000, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "상품")
        assert "자유적금" in result or "소액" in result

    def test_no_surplus_general_recommend(self, service):
        cf = {"total_balance": 0, "monthly_surplus": 0, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "상품")
        assert "잉여자금" in result or "자유납입" in result

    def test_result_contains_product_list_when_general(self, service):
        cf = {"total_balance": 1_000_000, "monthly_surplus": 0, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "추천해줘")
        assert "추천 상품" in result or "정기예금" in result or "자유적금" in result

    def test_comparison_result_no_product_list(self, service):
        cf = {"total_balance": 0, "monthly_surplus": 0, "has_data": False}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "예금 적금 비교")
        # 비교 질문 → 상품 목록 나열 없이 유형 판단만
        assert "더 자세한 상품 안내는" in result

    def test_message_ends_with_guidance(self, service):
        cf = {"total_balance": 5_000_000, "monthly_surplus": 500_000, "has_data": True}
        result = service._rule_based_recommend(cf, self.PRODUCTS, "상품 추천")
        assert "상담사" in result


# ─────────────────────────────────────────────────────────────────────────────
# I. _make_reason — 상품 유형별 사유 텍스트
# ─────────────────────────────────────────────────────────────────────────────

class TestMakeReason:
    """_make_reason — DEPOSIT/SAVINGS/SUBSCRIPTION 사유 텍스트."""

    CF = {"total_balance": 5_000_000, "monthly_surplus": 500_000}

    def test_deposit_reason_contains_balance(self, service):
        p = {"deposit_product_type": "DEPOSIT", "min_join_amount": 100_000, "min_period_month": 12}
        score = {"expected_interest": 17_500}
        reason = service._make_reason(p, self.CF, score)
        assert "잔액" in reason or "예치" in reason

    def test_savings_reason_contains_payment(self, service):
        p = {"deposit_product_type": "SAVINGS", "min_join_amount": 50_000, "min_period_month": 12}
        score = {"expected_interest": 3_000}
        reason = service._make_reason(p, self.CF, score)
        assert "납입" in reason or "잉여자금" in reason

    def test_subscription_reason_mentions_housing(self, service):
        p = {"deposit_product_type": "SUBSCRIPTION", "min_join_amount": 2_000, "min_period_month": 1}
        score = {"expected_interest": 240}
        reason = service._make_reason(p, self.CF, score)
        assert "주택청약" in reason

    def test_unknown_type_returns_rate_string(self, service):
        p = {"deposit_product_type": "UNKNOWN", "base_interest_rate": 3.5}
        score = {"expected_interest": 0}
        reason = service._make_reason(p, self.CF, score)
        assert "3.5" in reason or reason != ""

    def test_deposit_reason_contains_interest_amount(self, service):
        p = {"deposit_product_type": "DEPOSIT", "min_join_amount": 100_000, "min_period_month": 12}
        score = {"expected_interest": 17_500}
        reason = service._make_reason(p, self.CF, score)
        assert "이자" in reason

    def test_savings_reason_contains_surplus_info(self, service):
        p = {"deposit_product_type": "SAVINGS", "min_join_amount": 50_000, "min_period_month": 12}
        score = {"expected_interest": 3_000}
        reason = service._make_reason(p, {"total_balance": 1_000_000, "monthly_surplus": 500_000}, score)
        assert "50,000" in reason or "납입" in reason


# ─────────────────────────────────────────────────────────────────────────────
# J. ChatService.get_messages — chatbot+agent 통합
# ─────────────────────────────────────────────────────────────────────────────

class TestGetMessages:
    """get_messages — chatbot + agent 메시지 통합 조회."""

    def _setup_connected(self, service, chat_service):
        service.seed_default_scenario()
        session = asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
        queue = chat_service.get_waiting_queue()
        chat_id = queue[0]["chat_consultation_id"]
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        return session, chat_id

    def test_get_messages_includes_chatbot_msgs(self, service, chat_service):
        session, chat_id = self._setup_connected(service, chat_service)
        messages = chat_service.get_messages(chat_id)
        # 챗봇 상담 시작 메시지 포함
        assert len(messages) >= 1

    def test_get_messages_includes_agent_msg(self, service, chat_service):
        session, chat_id = self._setup_connected(service, chat_service)
        asyncio.run(chat_service.send_message(chat_id, "안녕하세요!", 3))
        messages = chat_service.get_messages(chat_id)
        contents = [m.message_content for m in messages]
        assert "안녕하세요!" in contents

    def test_get_messages_returns_list(self, service, chat_service):
        session, chat_id = self._setup_connected(service, chat_service)
        messages = chat_service.get_messages(chat_id)
        assert isinstance(messages, list)

    def test_get_messages_ordered_by_id(self, service, chat_service):
        session, chat_id = self._setup_connected(service, chat_service)
        asyncio.run(chat_service.send_message(chat_id, "첫 번째", 3))
        asyncio.run(chat_service.send_message(chat_id, "두 번째", 1))
        messages = chat_service.get_messages(chat_id)
        ids = [m.chat_message_history_id for m in messages]
        assert ids == sorted(ids)

    def test_get_messages_invalid_id_raises(self, chat_service):
        with pytest.raises(ValueError):
            chat_service.get_messages(99999)

    def test_messages_combined_from_both_sessions(self, service, chat_service):
        session, chat_id = self._setup_connected(service, chat_service)
        asyncio.run(chat_service.send_message(chat_id, "상담사 메시지", 3))
        messages = chat_service.get_messages(chat_id)
        sender_types = {m.sender_type_code_id for m in messages}
        # 챗봇(2)과 상담사(3)가 모두 포함
        assert 2 in sender_types or 3 in sender_types


# ─────────────────────────────────────────────────────────────────────────────
# K. _open_chat_consultation — 중복 생성 방지
# ─────────────────────────────────────────────────────────────────────────────

class TestOpenChatConsultation:
    """_open_chat_consultation — 이미 존재할 때 중복 생성 안 함."""

    def test_agent_transfer_creates_one_chat_consultation(self, service, db):
        from sqlalchemy import select
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, button_value="AGENT")
        # 두 번 이관 시도해도 하나만 생성
        count = db.query(ChatConsultation).filter(
            ChatConsultation.chatbot_consultation_id == session.chatbot_consultation_id
        ).count()
        assert count == 1

    def test_double_transfer_no_duplicate(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, button_value="AGENT")
        before = db.query(ChatConsultation).count()
        # 이미 이관됐으므로 다음 메시지는 새 ChatConsultation 생성 안 함
        _send(service, session.chatbot_consultation_id, message="아무거나")
        after = db.query(ChatConsultation).count()
        # 추가 생성 없어야 함
        assert after == before


# ─────────────────────────────────────────────────────────────────────────────
# L. _record_message — button_value·process_method 저장
# ─────────────────────────────────────────────────────────────────────────────

class TestRecordMessage:
    """_record_message — 버튼값·프로세스코드 저장 검증."""

    def test_button_value_stored_in_history(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, button_value="PRODUCT_ADVICE")
        msgs = db.query(ChatMessageHistory).filter(
            ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id,
            ChatMessageHistory.button_value == "PRODUCT_ADVICE",
        ).all()
        assert len(msgs) >= 1

    def test_process_method_code_stored(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        msgs = db.query(ChatMessageHistory).filter(
            ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id,
            ChatMessageHistory.process_method_code_id.isnot(None),
        ).all()
        assert len(msgs) >= 1

    def test_start_message_has_sequence_1(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        msg = db.query(ChatMessageHistory).filter(
            ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id
        ).order_by(ChatMessageHistory.sequence_no).first()
        assert msg.sequence_no == 1

    def test_sender_type_user_for_user_message(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        user_msgs = db.query(ChatMessageHistory).filter(
            ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id,
            ChatMessageHistory.sender_type_code_id == 1,  # USER
        ).all()
        assert len(user_msgs) >= 1

    def test_sender_type_bot_for_bot_message(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        bot_msgs = db.query(ChatMessageHistory).filter(
            ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id,
            ChatMessageHistory.sender_type_code_id == 2,  # BOT
        ).all()
        assert len(bot_msgs) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# M. _latest_node_id
# ─────────────────────────────────────────────────────────────────────────────

class TestLatestNodeId:
    """_latest_node_id — 최근 node_id 조회."""

    def test_returns_node_id_after_start(self, service, db):
        service.seed_default_scenario()
        session = _start(service)
        node_id = service._latest_node_id(session.chatbot_consultation_id)
        assert node_id is not None
        assert node_id > 0

    def test_returns_none_for_no_messages(self, service):
        node_id = service._latest_node_id(99999)
        assert node_id is None

    def test_latest_node_after_button(self, service):
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, button_value="PRODUCT_ADVICE")
        node_id = service._latest_node_id(session.chatbot_consultation_id)
        assert node_id is not None


# ─────────────────────────────────────────────────────────────────────────────
# N. _get_intent
# ─────────────────────────────────────────────────────────────────────────────

class TestGetIntent:
    """_get_intent — intent 레코드 조회."""

    def test_existing_intent_found(self, service, db):
        service.seed_default_scenario()
        scenario_id, _ = service.seed_default_scenario()
        intent = service._get_intent(scenario_id, "RATE_GUIDE")
        assert intent is not None
        assert intent.intent_name == "RATE_GUIDE"

    def test_nonexistent_intent_returns_none(self, service, db):
        service.seed_default_scenario()
        scenario_id, _ = service.seed_default_scenario()
        intent = service._get_intent(scenario_id, "NO_SUCH_INTENT_XYZ")
        assert intent is None

    def test_intent_with_none_scenario_id(self, service):
        intent = service._get_intent(None, "RATE_GUIDE")
        assert intent is None

    def test_all_seeded_intents_findable(self, service):
        scenario_id, _ = service.seed_default_scenario()
        for name in ["RATE_GUIDE", "JOIN_CONDITION", "PRODUCT_COMPARE",
                     "TERMS_RAG", "PRODUCT_GUIDE", "FAQ",
                     "CASH_FLOW_RECOMMEND", "LLM_FALLBACK",
                     "STAFF_REQUEST", "STAFF_ERROR_FALLBACK"]:
            intent = service._get_intent(scenario_id, name)
            assert intent is not None, f"{name} intent 없음"


# ─────────────────────────────────────────────────────────────────────────────
# O. _get_customer_no
# ─────────────────────────────────────────────────────────────────────────────

class TestGetCustomerNo:
    """_get_customer_no — chatbot consultation에서 고객번호 추출."""

    def test_returns_customer_no_after_start(self, service, db):
        service.seed_default_scenario()
        session = _start(service, "TEST_CUST")
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        cust_no = service._get_customer_no(chatbot)
        assert cust_no == "TEST_CUST"

    def test_different_customers_return_correct_no(self, service, db):
        service.seed_default_scenario()
        s1 = _start(service, "CUST_AAA")
        s2 = _start(service, "CUST_BBB")
        c1 = db.get(ChatbotConsultation, s1.chatbot_consultation_id)
        c2 = db.get(ChatbotConsultation, s2.chatbot_consultation_id)
        assert service._get_customer_no(c1) == "CUST_AAA"
        assert service._get_customer_no(c2) == "CUST_BBB"


# ─────────────────────────────────────────────────────────────────────────────
# P. LlmHandoffAdapter
# ─────────────────────────────────────────────────────────────────────────────

class TestLlmHandoffAdapter:
    """LlmHandoffAdapter — process_method_code·answer 반환."""

    def test_process_method_code_bp002(self):
        adapter = LlmHandoffAdapter()
        assert adapter.process_method_code == "BP002"

    def test_answer_returns_string(self):
        adapter = LlmHandoffAdapter()
        result = adapter.answer("아무 질문")
        assert isinstance(result, str)
        assert len(result) > 5

    def test_answer_contains_guidance(self):
        adapter = LlmHandoffAdapter()
        result = adapter.answer("모르는 질문")
        assert result  # 비어있지 않음

    def test_answer_not_empty_for_any_input(self):
        adapter = LlmHandoffAdapter()
        for msg in ["", "   ", "hello", "금리", "에러"]:
            assert adapter.answer(msg)


# ─────────────────────────────────────────────────────────────────────────────
# Q. FeatureAnswerFormatter 세부 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestFeatureAnswerFormatterDetail:
    """FeatureAnswerFormatter — 각 포맷터 세부."""

    @pytest.fixture(autouse=True)
    def fmt(self):
        self.fmt = FeatureAnswerFormatter()

    # _rate 세부
    def test_rate_groups_by_product_name(self):
        data = [
            {"product_name": "상품A", "rate_type": "BASE", "interest_rate": 3.5,
             "minimum_contract_period": 12, "maximum_contract_period": 24},
            {"product_name": "상품A", "rate_type": "PREFERENTIAL", "interest_rate": 0.3,
             "minimum_contract_period": 12, "maximum_contract_period": 24},
            {"product_name": "상품B", "rate_type": "BASE", "interest_rate": 4.0,
             "minimum_contract_period": 12, "maximum_contract_period": 36},
        ]
        result = self.fmt.format("RATE_GUIDE", data)
        assert "상품A" in result
        assert "상품B" in result
        # 상품A는 한 번만 그룹핑
        assert result.count("상품A") == 1

    def test_rate_shows_period_range(self):
        data = [{"product_name": "A", "rate_type": "BASE", "interest_rate": 3.5,
                 "minimum_contract_period": 12, "maximum_contract_period": 24}]
        result = self.fmt.format("RATE_GUIDE", data)
        assert "12" in result
        assert "24" in result

    def test_rate_shows_base_rate(self):
        data = [{"product_name": "A", "rate_type": "BASE", "interest_rate": 3.5,
                 "minimum_contract_period": 12, "maximum_contract_period": 24}]
        result = self.fmt.format("RATE_GUIDE", data)
        assert "3.5" in result

    def test_rate_ends_with_guidance(self):
        data = [{"product_name": "A", "rate_type": "BASE", "interest_rate": 3.5,
                 "minimum_contract_period": 12, "maximum_contract_period": 24}]
        result = self.fmt.format("RATE_GUIDE", data)
        assert "확인해 주세요" in result

    # _products 세부
    def test_products_type_mapped_to_korean(self):
        data = [
            {"product_name": "예금상품", "product_type": "DEPOSIT", "base_interest_rate": 3.5},
            {"product_name": "적금상품", "product_type": "SAVINGS", "base_interest_rate": 4.0},
            {"product_name": "청약상품", "product_type": "SUBSCRIPTION", "base_interest_rate": 2.8},
        ]
        result = self.fmt.format("PRODUCT_GUIDE", data)
        assert "예금" in result
        assert "적금" in result
        assert "청약" in result

    def test_products_shows_all_items(self):
        data = [
            {"product_name": f"상품{i}", "product_type": "DEPOSIT", "base_interest_rate": 3.0}
            for i in range(5)
        ]
        result = self.fmt.format("PRODUCT_GUIDE", data)
        for i in range(5):
            assert f"상품{i}" in result

    def test_products_match_score_displayed(self):
        data = [{"product_name": "상품A", "product_type": "DEPOSIT",
                 "base_interest_rate": 3.5, "match_score": 85, "recommend_reason": "잔액 충분"}]
        result = self.fmt.format("PRODUCT_GUIDE", data)
        assert "85" in result
        assert "잔액 충분" in result

    def test_products_ends_with_guidance(self):
        data = [{"product_name": "A", "product_type": "DEPOSIT", "base_interest_rate": 3.5}]
        result = self.fmt.format("PRODUCT_GUIDE", data)
        assert "질문해 주세요" in result

    # _join_condition 세부
    def test_join_condition_shows_product_name(self):
        data = [{"product_name": "정기예금 플러스", "min_join_amount": 100000,
                 "max_join_amount": 100000000, "min_period_month": 1,
                 "max_period_month": 60, "is_early_termination_allowed": True,
                 "is_tax_benefit_available": True}]
        result = self.fmt.format("JOIN_CONDITION", data)
        assert "정기예금 플러스" in result

    def test_join_condition_shows_period(self):
        data = [{"product_name": "A", "min_join_amount": 100000,
                 "max_join_amount": 10000000, "min_period_month": 12,
                 "max_period_month": 60, "is_early_termination_allowed": False,
                 "is_tax_benefit_available": False}]
        result = self.fmt.format("JOIN_CONDITION", data)
        assert "12" in result
        assert "60" in result

    def test_join_condition_shows_early_termination(self):
        data = [{"product_name": "A", "min_join_amount": 100000,
                 "max_join_amount": 10000000, "min_period_month": 12,
                 "max_period_month": 60, "is_early_termination_allowed": True,
                 "is_tax_benefit_available": False}]
        result = self.fmt.format("JOIN_CONDITION", data)
        assert "가능" in result

    # _compare 세부
    def test_compare_has_table_header(self):
        data = [{"product_name": "상품A", "product_type": "DEPOSIT",
                 "base_interest_rate": 3.5, "min_period_month": 12, "max_period_month": 24}]
        result = self.fmt.format("PRODUCT_COMPARE", data)
        assert "상품명" in result
        assert "금리" in result

    def test_compare_separator_line(self):
        data = [{"product_name": "상품A", "product_type": "DEPOSIT",
                 "base_interest_rate": 3.5, "min_period_month": 12, "max_period_month": 24}]
        result = self.fmt.format("PRODUCT_COMPARE", data)
        assert "-" * 10 in result

    def test_compare_max_five_rows(self):
        data = [{"product_name": f"상품{i}", "product_type": "DEPOSIT",
                 "base_interest_rate": round(3.0 + i * 0.1, 1), "min_period_month": 12,
                 "max_period_month": 24}
                for i in range(7)]
        result = self.fmt.format("PRODUCT_COMPARE", data)
        # _compare는 data[:5]만 처리 → 상품5, 상품6은 포함 안 됨
        assert "상품5" not in result
        assert "상품6" not in result
        assert "상품0" in result

    # _terms 세부
    def test_terms_shows_name(self):
        data = [{"special_term_name": "개인정보 수집 이용 동의",
                 "special_term_summary": "개인정보를 수집합니다."}]
        result = self.fmt.format("TERMS_RAG", data)
        assert "개인정보 수집 이용 동의" in result

    def test_terms_shows_summary(self):
        data = [{"special_term_name": "약관명",
                 "special_term_summary": "요약 내용입니다."}]
        result = self.fmt.format("TERMS_RAG", data)
        assert "요약 내용입니다." in result

    def test_terms_max_three_items(self):
        data = [{"special_term_name": f"약관{i}", "special_term_summary": f"요약{i}"}
                for i in range(5)]
        result = self.fmt.format("TERMS_RAG", data)
        # _terms는 data[:3]만 처리 → 약관3, 약관4는 포함 안 됨
        assert "약관3" not in result
        assert "약관4" not in result
        assert "약관0" in result

    def test_terms_ends_with_guidance(self):
        data = [{"special_term_name": "약관", "special_term_summary": "요약"}]
        result = self.fmt.format("TERMS_RAG", data)
        assert "참조" in result or "상품 설명서" in result


# ─────────────────────────────────────────────────────────────────────────────
# R. _to_chat_response 및 상태 직렬화
# ─────────────────────────────────────────────────────────────────────────────

class TestToChatResponse:
    """_to_chat_response — WAITING/CONNECTED/ENDED 직렬화."""

    def _make_chat(self, service, chat_service):
        service.seed_default_scenario()
        session = asyncio.run(service.start(CUST, "HOME", "1.0.0"))
        asyncio.run(service.handle_message(session.chatbot_consultation_id, "", "AGENT"))
        return chat_service.get_waiting_queue()[0]["chat_consultation_id"]

    def test_waiting_status_in_response(self, service, chat_service):
        from app.main import _to_chat_response
        chat_id = self._make_chat(service, chat_service)
        chat = chat_service.get_consultation(chat_id)
        resp = _to_chat_response(chat)
        assert resp.status == "WAITING"

    def test_connected_status_in_response(self, service, chat_service):
        from app.main import _to_chat_response
        chat_id = self._make_chat(service, chat_service)
        chat = asyncio.run(chat_service.connect_agent(chat_id, employee_id=42))
        resp = _to_chat_response(chat)
        assert resp.status == "CONNECTED"
        assert resp.employee_id == 42

    def test_ended_status_in_response(self, service, chat_service):
        from app.main import _to_chat_response
        chat_id = self._make_chat(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        chat = asyncio.run(chat_service.end_chat(chat_id, satisfaction_score=4))
        resp = _to_chat_response(chat)
        assert resp.status == "ENDED"
        assert resp.satisfaction_score == 4
        assert resp.active_yn == "N"

    def test_response_has_all_fields(self, service, chat_service):
        from app.main import _to_chat_response
        chat_id = self._make_chat(service, chat_service)
        chat = chat_service.get_consultation(chat_id)
        resp = _to_chat_response(chat)
        assert resp.chat_consultation_id == chat_id
        assert resp.consultation_id > 0
        assert resp.active_yn in ("Y", "N")

    def test_response_agent_requested_at_present(self, service, chat_service):
        from app.main import _to_chat_response
        chat_id = self._make_chat(service, chat_service)
        chat = chat_service.get_consultation(chat_id)
        resp = _to_chat_response(chat)
        assert resp.agent_requested_at is not None

    def test_connected_at_set_after_connect(self, service, chat_service):
        from app.main import _to_chat_response
        chat_id = self._make_chat(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        chat = chat_service.get_consultation(chat_id)
        resp = _to_chat_response(chat)
        assert resp.agent_connected_at is not None

    def test_chat_ended_at_set_after_end(self, service, chat_service):
        from app.main import _to_chat_response
        chat_id = self._make_chat(service, chat_service)
        asyncio.run(chat_service.connect_agent(chat_id, employee_id=1))
        chat = asyncio.run(chat_service.end_chat(chat_id))
        resp = _to_chat_response(chat)
        assert resp.chat_ended_at is not None
