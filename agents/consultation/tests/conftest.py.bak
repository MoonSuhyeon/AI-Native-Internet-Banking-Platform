import os
import sys
from pathlib import Path
from unittest.mock import AsyncMock

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
os.environ["CONSULTATION_KAFKA_ENABLED"] = "false"

from app.database import Base
from app.llm import LlmAdapter, LlmHandoffAdapter
from app.services import ChatbotService


@pytest.fixture()
def db() -> Session:
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE deposit_banking_products (
                banking_product_id INTEGER PRIMARY KEY,
                deposit_product_name TEXT NOT NULL,
                deposit_product_type TEXT,
                description TEXT,
                base_interest_rate NUMERIC,
                min_join_amount NUMERIC,
                max_join_amount NUMERIC,
                min_period_month INTEGER,
                max_period_month INTEGER,
                is_early_termination_allowed BOOLEAN,
                is_tax_benefit_available BOOLEAN,
                deposit_product_status TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_banking_products VALUES
            (1, '정기예금 플러스', 'DEPOSIT', '안정적인 정기예금', 3.5, 100000, 100000000, 1, 60, 1, 1, 'SELLING')
        """))
        conn.execute(text("""
            CREATE TABLE banking_deposit_product_interest_rates (
                rate_id INTEGER PRIMARY KEY,
                banking_product_id INTEGER,
                rate_type TEXT,
                minimum_contract_period INTEGER,
                maximum_contract_period INTEGER,
                rate NUMERIC,
                condition_description TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO banking_deposit_product_interest_rates VALUES
            (1, 1, 'BASE', 12, 24, 3.5, '기본금리')
        """))
        conn.execute(text("""
            CREATE TABLE deposit_special_terms (
                special_term_id INTEGER PRIMARY KEY,
                special_term_name TEXT,
                special_term_content TEXT,
                special_term_summary TEXT,
                is_required BOOLEAN,
                status TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_special_terms VALUES
            (1, '개인정보 수집 이용 동의', '개인정보를 수집하고 이용합니다.', '개인정보 동의 요약', 1, 'ACTIVE')
        """))
        conn.execute(text("""
            CREATE TABLE deposit_accounts (
                account_id INTEGER PRIMARY KEY,
                account_number TEXT,
                customer_id TEXT,
                account_type TEXT,
                account_alias TEXT,
                balance NUMERIC,
                currency TEXT,
                account_status TEXT,
                opened_at TEXT,
                closed_at TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_accounts VALUES
            (1, '001-123-000001', 'CUST001', 'DEPOSIT', '내 예금', 5000000, 'KRW', 'ACTIVE', '20260101', NULL)
        """))
        conn.execute(text("""
            CREATE TABLE deposit_contracts (
                contract_id INTEGER PRIMARY KEY,
                contract_number TEXT,
                customer_id TEXT,
                banking_product_id INTEGER,
                join_amount NUMERIC,
                contract_interest_rate NUMERIC,
                started_at TEXT,
                maturity_at TEXT,
                contract_status TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_contracts VALUES
            (1, 'CTR-001', 'CUST001', 1, 5000000, 3.5, '20260101', '20270101', 'ACTIVE')
        """))
        conn.execute(text("""
            CREATE TABLE deposit_interest_history (
                interest_id INTEGER PRIMARY KEY,
                contract_id INTEGER,
                account_id INTEGER,
                applied_interest_rate NUMERIC,
                interest_amount NUMERIC,
                interest_after_tax NUMERIC,
                interest_paid_at TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_interest_history VALUES
            (1, 1, 1, 3.5, 175000, 148050, '20261231')
        """))
        conn.execute(text("""
            CREATE TABLE deposit_transactions (
                transaction_id INTEGER PRIMARY KEY,
                transaction_number TEXT,
                account_id INTEGER,
                transaction_type TEXT,
                transaction_status TEXT,
                amount NUMERIC,
                created_at TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_transactions VALUES
            (1, 'TX-001', 1, 'TRANSFER', 'COMPLETED', 10000, '2026-05-21')
        """))
    session = Session(engine)
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


class MockLlmAdapter(LlmAdapter):
    """실제 API 호출 없이 고정 응답을 반환하는 LLM mock."""
    def __init__(self):
        super().__init__(api_key="mock-key", model="gpt-4o-mini")

    def answer(self, message: str, context: str = "") -> str:
        return f"[LLM 응답] {message}에 대한 AI 답변입니다."


@pytest.fixture()
def service(db: Session) -> ChatbotService:
    return ChatbotService(db, AsyncMock(), LlmHandoffAdapter())


@pytest.fixture()
def llm_service(db: Session) -> ChatbotService:
    """LlmAdapter(mock)가 연결된 서비스 — LLM fallback 시나리오 전용."""
    return ChatbotService(db, AsyncMock(), LlmHandoffAdapter(), MockLlmAdapter())


@pytest.fixture()
def rich_llm_service(rich_db: Session) -> ChatbotService:
    """LlmAdapter(mock) + 풍부한 DB."""
    return ChatbotService(rich_db, AsyncMock(), LlmHandoffAdapter(), MockLlmAdapter())


@pytest.fixture()
def chat_service(db: Session):
    from app.services import ChatService
    return ChatService(db, AsyncMock())


# ── 빈 deposit DB (consultation 테이블만 존재) ────────────────────────────────

def _create_empty_deposit_tables(conn) -> None:
    """deposit 관련 테이블만 생성, 데이터는 없음."""
    for ddl in [
        """CREATE TABLE deposit_banking_products (
            banking_product_id INTEGER PRIMARY KEY,
            deposit_product_name TEXT NOT NULL,
            deposit_product_type TEXT,
            description TEXT,
            base_interest_rate NUMERIC,
            min_join_amount NUMERIC,
            max_join_amount NUMERIC,
            min_period_month INTEGER,
            max_period_month INTEGER,
            is_early_termination_allowed BOOLEAN,
            is_tax_benefit_available BOOLEAN,
            deposit_product_status TEXT)""",
        """CREATE TABLE banking_deposit_product_interest_rates (
            rate_id INTEGER PRIMARY KEY,
            banking_product_id INTEGER,
            rate_type TEXT,
            minimum_contract_period INTEGER,
            maximum_contract_period INTEGER,
            rate NUMERIC,
            condition_description TEXT)""",
        """CREATE TABLE deposit_special_terms (
            special_term_id INTEGER PRIMARY KEY,
            special_term_name TEXT,
            special_term_content TEXT,
            special_term_summary TEXT,
            is_required BOOLEAN,
            status TEXT)""",
        """CREATE TABLE deposit_accounts (
            account_id INTEGER PRIMARY KEY,
            account_number TEXT,
            customer_id TEXT,
            account_type TEXT,
            account_alias TEXT,
            balance NUMERIC,
            currency TEXT,
            account_status TEXT,
            opened_at TEXT,
            closed_at TEXT)""",
        """CREATE TABLE deposit_contracts (
            contract_id INTEGER PRIMARY KEY,
            contract_number TEXT,
            customer_id TEXT,
            banking_product_id INTEGER,
            join_amount NUMERIC,
            contract_interest_rate NUMERIC,
            started_at TEXT,
            maturity_at TEXT,
            contract_status TEXT)""",
        """CREATE TABLE deposit_interest_history (
            interest_id INTEGER PRIMARY KEY,
            contract_id INTEGER,
            account_id INTEGER,
            applied_interest_rate NUMERIC,
            interest_amount NUMERIC,
            interest_after_tax NUMERIC,
            interest_paid_at TEXT)""",
        """CREATE TABLE deposit_transactions (
            transaction_id INTEGER PRIMARY KEY,
            transaction_number TEXT,
            account_id INTEGER,
            transaction_type TEXT,
            transaction_status TEXT,
            amount NUMERIC,
            created_at TEXT)""",
    ]:
        conn.execute(text(ddl))


@pytest.fixture()
def empty_deposit_db() -> Session:
    """deposit 테이블은 있지만 데이터가 전혀 없는 DB — 빈 DB 시나리오 전용."""
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    with engine.begin() as conn:
        _create_empty_deposit_tables(conn)
    session = Session(engine)
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


@pytest.fixture()
def empty_service(empty_deposit_db: Session) -> ChatbotService:
    return ChatbotService(empty_deposit_db, AsyncMock(), LlmHandoffAdapter())


# ── 다중 레코드 DB ─────────────────────────────────────────────────────────────

@pytest.fixture()
def rich_db() -> Session:
    """
    다양한 시나리오 테스트를 위한 풍부한 시드 DB.

    상품 3개 (DEPOSIT / SAVINGS / SUBSCRIPTION)
    금리 5개
    약관 3개 (ACTIVE 2 + INACTIVE 1)
    CUST001: 계좌 2, 계약 2 (ACTIVE+MATURED), 이자 2, 거래 3
    CUST002: 계좌 1, 계약 1, 이자 1, 거래 1
    CUST_EMPTY: 데이터 없음
    """
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    with engine.begin() as conn:
        _create_empty_deposit_tables(conn)

        # ── 상품 3개 ──────────────────────────────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_banking_products VALUES
            (1,'정기예금 플러스','DEPOSIT','안정적인 정기예금',3.5,100000,100000000,1,60,1,1,'SELLING'),
            (2,'자유적금','SAVINGS','자유롭게 납입하는 적금',4.0,10000,50000000,12,36,0,1,'SELLING'),
            (3,'주택청약종합저축','SUBSCRIPTION','청약 자격 적립 상품',2.8,2000,1000000,1,600,0,0,'SELLING')
        """))

        # ── 금리 5개 (상품1:2개, 상품2:2개, 상품3:1개) ────────────────────────
        conn.execute(text("""
            INSERT INTO banking_deposit_product_interest_rates VALUES
            (1,1,'BASE',12,24,3.5,'기본금리'),
            (2,1,'PREFERENTIAL',12,24,0.3,'급여이체 우대'),
            (3,2,'BASE',12,36,4.0,'기본금리'),
            (4,2,'PREFERENTIAL',24,36,0.5,'자동이체 우대'),
            (5,3,'BASE',12,600,2.8,'기본금리')
        """))

        # ── 약관 3개 (ACTIVE 2 + INACTIVE 1) ─────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_special_terms VALUES
            (1,'개인정보 수집 이용 동의','개인정보를 수집하고 이용합니다.','개인정보 동의 요약',1,'ACTIVE'),
            (2,'중도해지 약관','중도해지 시 약정이율의 50%가 적용됩니다.','중도해지 이율 안내',1,'ACTIVE'),
            (3,'구 이용약관','폐지된 약관입니다.','구 약관',0,'INACTIVE')
        """))

        # ── 계좌: CUST001 2개, CUST002 1개 ───────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_accounts VALUES
            (1,'001-001-000001','CUST001','DEPOSIT','내 예금',5000000,'KRW','ACTIVE','20260101',NULL),
            (2,'001-001-000002','CUST001','SAVINGS','내 적금',1200000,'KRW','ACTIVE','20260301',NULL),
            (3,'001-002-000001','CUST002','DEPOSIT','예금',3000000,'KRW','ACTIVE','20260601',NULL)
        """))

        # ── 계약: CUST001 2개(ACTIVE+MATURED), CUST002 1개 ───────────────────
        conn.execute(text("""
            INSERT INTO deposit_contracts VALUES
            (1,'CTR-001','CUST001',1,5000000,3.5,'20260101','20270101','ACTIVE'),
            (2,'CTR-002','CUST001',2,1200000,4.0,'20250101','20260101','MATURED'),
            (3,'CTR-003','CUST002',1,3000000,3.5,'20260601','20270601','ACTIVE')
        """))

        # ── 이자 내역: CUST001 2건, CUST002 1건 ──────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_interest_history VALUES
            (1,1,1,3.5,175000,148050,'20261231'),
            (2,2,2,4.0,48000,40608,'20260101'),
            (3,3,3,3.5,105000,88830,'20261231')
        """))

        # ── 거래: CUST001 3건(계좌1), CUST002 1건 ─────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_transactions VALUES
            (1,'TX-001',1,'TRANSFER','COMPLETED',10000,'2026-05-01'),
            (2,'TX-002',1,'TRANSFER','PENDING',50000,'2026-05-10'),
            (3,'TX-003',2,'DEPOSIT','COMPLETED',100000,'2026-05-15'),
            (4,'TX-004',3,'TRANSFER','COMPLETED',20000,'2026-05-20')
        """))

    session = Session(engine)
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


@pytest.fixture()
def rich_service(rich_db: Session) -> ChatbotService:
    return ChatbotService(rich_db, AsyncMock(), LlmHandoffAdapter())


# ── 현금흐름 분석 추천 전용 DB ─────────────────────────────────────────────────
#
# 고객 유형 4종:
#   CUST_SALARY  : 급여형 (월 3,000,000원 정기 입금, 총 잔액 8,000,000원)
#   CUST_SURPLUS : 목돈형 (총 잔액 50,000,000원, 잉여자금 많음)
#   CUST_TIGHT   : 긴축형 (지출 > 입금, 잉여자금 음수)
#   CUST_NODATA  : 계좌는 있으나 거래 없음 (has_data=False)

@pytest.fixture()
def cashflow_db() -> Session:
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    with engine.begin() as conn:
        _create_empty_deposit_tables(conn)

        # ── 상품 4종 ──────────────────────────────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_banking_products VALUES
            (1,'정기예금 플러스','DEPOSIT','안정적인 정기예금',3.5,100000,100000000,1,60,1,1,'SELLING'),
            (2,'자유적금','SAVINGS','자유롭게 납입하는 적금',4.0,10000,50000000,12,36,0,1,'SELLING'),
            (3,'주택청약종합저축','SUBSCRIPTION','청약 자격 적립',2.8,2000,1000000,1,600,0,0,'SELLING'),
            (4,'고금리정기적금','SAVINGS','높은 금리 정기 적금',4.5,50000,30000000,12,24,0,1,'SELLING')
        """))

        # ── 금리 ──────────────────────────────────────────────────────────────
        conn.execute(text("""
            INSERT INTO banking_deposit_product_interest_rates VALUES
            (1,1,'BASE',12,24,3.5,'기본금리'),
            (2,2,'BASE',12,36,4.0,'기본금리'),
            (3,3,'BASE',12,600,2.8,'기본금리'),
            (4,4,'BASE',12,24,4.5,'기본금리')
        """))

        # ── 약관 ──────────────────────────────────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_special_terms VALUES
            (1,'개인정보 수집 이용 동의','개인정보를 수집하고 이용합니다.','개인정보 동의 요약',1,'ACTIVE')
        """))

        # ── 계좌 ──────────────────────────────────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_accounts VALUES
            (10,'CF-001-001','CUST_SALARY','DEPOSIT','급여계좌',8000000,'KRW','ACTIVE','20260101',NULL),
            (20,'CF-002-001','CUST_SURPLUS','DEPOSIT','목돈계좌',50000000,'KRW','ACTIVE','20260101',NULL),
            (30,'CF-003-001','CUST_TIGHT','DEPOSIT','생활계좌',300000,'KRW','ACTIVE','20260101',NULL),
            (40,'CF-004-001','CUST_NODATA','DEPOSIT','미사용계좌',1000000,'KRW','ACTIVE','20260101',NULL)
        """))

        # ── 계약 ──────────────────────────────────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_contracts VALUES
            (10,'CF-CTR-001','CUST_SALARY',2,500000,4.0,'20260101','20270101','ACTIVE'),
            (20,'CF-CTR-002','CUST_SURPLUS',1,50000000,3.5,'20260101','20270101','ACTIVE')
        """))

        # ── 이자 내역 ─────────────────────────────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_interest_history VALUES
            (10,10,10,4.0,20000,16920,'20260630'),
            (20,20,20,3.5,1750000,1480500,'20261231')
        """))

        # ── 거래: CUST_SALARY (급여 3M 입금 3회 + 고정지출 2회) ───────────────
        conn.execute(text("""
            INSERT INTO deposit_transactions VALUES
            (101,'CF-TX-101',10,'DEPOSIT','COMPLETED',3000000,'2026-03-25'),
            (102,'CF-TX-102',10,'DEPOSIT','COMPLETED',3000000,'2026-04-25'),
            (103,'CF-TX-103',10,'DEPOSIT','COMPLETED',3000000,'2026-05-25'),
            (104,'CF-TX-104',10,'WITHDRAWAL','COMPLETED',800000,'2026-03-01'),
            (105,'CF-TX-105',10,'WITHDRAWAL','COMPLETED',800000,'2026-04-01'),
            (106,'CF-TX-106',10,'WITHDRAWAL','COMPLETED',800000,'2026-05-01'),
            (107,'CF-TX-107',10,'TRANSFER','COMPLETED',200000,'2026-03-15'),
            (108,'CF-TX-108',10,'TRANSFER','COMPLETED',200000,'2026-04-15')
        """))

        # ── 거래: CUST_SURPLUS (대형 예금 이동) ──────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_transactions VALUES
            (201,'CF-TX-201',20,'DEPOSIT','COMPLETED',20000000,'2026-01-05'),
            (202,'CF-TX-202',20,'DEPOSIT','COMPLETED',15000000,'2026-02-10'),
            (203,'CF-TX-203',20,'WITHDRAWAL','COMPLETED',5000000,'2026-03-20')
        """))

        # ── 거래: CUST_TIGHT (지출 > 입금) ───────────────────────────────────
        conn.execute(text("""
            INSERT INTO deposit_transactions VALUES
            (301,'CF-TX-301',30,'DEPOSIT','COMPLETED',500000,'2026-03-25'),
            (302,'CF-TX-302',30,'WITHDRAWAL','COMPLETED',700000,'2026-03-01'),
            (303,'CF-TX-303',30,'WITHDRAWAL','COMPLETED',700000,'2026-04-01'),
            (304,'CF-TX-304',30,'WITHDRAWAL','COMPLETED',700000,'2026-05-01')
        """))

        # ── 거래: CUST_NODATA (없음) ─────────────────────────────────────────
        # 거래 레코드 없음

    session = Session(engine)
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


@pytest.fixture()
def cashflow_service(cashflow_db: Session) -> ChatbotService:
    """현금흐름 분석 테스트용 서비스 (LLM 없음, 룰 기반 fallback 사용)."""
    return ChatbotService(cashflow_db, AsyncMock(), LlmHandoffAdapter())


class MockRecommendLlmAdapter(MockLlmAdapter):
    """recommend() 메서드를 지원하는 LLM mock."""

    def recommend(
        self,
        cash_flow: dict,
        products: list[dict],
        user_query: str,
        history_ctx: str = "",
    ) -> str:
        total = float(cash_flow.get("total_balance", 0))
        surplus = float(cash_flow.get("monthly_surplus", 0))
        product_names = [
            p.get("deposit_product_name") or p.get("product_name", "")
            for p in products[:2]
        ]
        return (
            f"[LLM 맞춤 추천] 잔액 {total:,.0f}원, 월 잉여 {surplus:,.0f}원 기준 — "
            f"추천 상품: {', '.join(product_names)}"
        )


@pytest.fixture()
def cashflow_llm_service(cashflow_db: Session) -> ChatbotService:
    """현금흐름 분석 + LLM mock이 연결된 서비스."""
    return ChatbotService(
        cashflow_db, AsyncMock(), LlmHandoffAdapter(), MockRecommendLlmAdapter()
    )
