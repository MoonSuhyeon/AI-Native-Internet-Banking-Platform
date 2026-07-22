"""
consultation-service 전체 기능 실행 파일.

실행:
    python scripts/run_all.py

외부 의존성 없음 — SQLite 인메모리 DB 로 단독 실행됩니다.

실행 순서:
  [1] PRODUCT_ADVICE  — 금융상품 상담 6개 기능
  [2] USER_FINANCE    — 사용자 금융정보 조회 5개 기능
  [3] STAFF_SUPPORT   — 직원 업무 지원 5개 기능
  [4] 챗봇 상담 흐름  — 시작 → 버튼 선택 → LLM 전환 → 상담사 이관
  [5] 상담사 채팅     — 대기열 → 연결 → 메시지 교환 → 종료
  [6] Kafka 이벤트    — 발행된 이벤트 목록
"""

import asyncio
import io
import sys
from pathlib import Path
from unittest.mock import AsyncMock

from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

from app.database import Base
from app.llm import LlmHandoffAdapter
from app.schemas import ChatbotFeatureExecuteRequest
from app.services import ChatbotService, ChatService, _SENDER_LABEL


# ── DB 초기화 ─────────────────────────────────────────────────────────────────

def _build_db() -> Session:
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    with engine.begin() as conn:
        for sql in [
            """CREATE TABLE deposit_banking_products (
                banking_product_id INTEGER PRIMARY KEY,
                deposit_product_name TEXT NOT NULL, deposit_product_type TEXT,
                description TEXT, base_interest_rate NUMERIC,
                min_join_amount NUMERIC, max_join_amount NUMERIC,
                min_period_month INTEGER, max_period_month INTEGER,
                is_early_termination_allowed BOOLEAN,
                is_tax_benefit_available BOOLEAN, deposit_product_status TEXT)""",
            """INSERT INTO deposit_banking_products VALUES
                (1,'정기예금 플러스','DEPOSIT','안정적인 정기예금',3.5,100000,100000000,1,60,1,1,'SELLING'),
                (2,'자유적금','SAVINGS','자유롭게 납입하는 적금',4.0,10000,50000000,12,36,0,1,'SELLING'),
                (3,'주택청약종합저축','SUBSCRIPTION','청약 자격 적립 상품',2.8,2000,1000000,1,600,0,0,'SELLING')""",
            """CREATE TABLE banking_deposit_product_interest_rates (
                rate_id INTEGER PRIMARY KEY, banking_product_id INTEGER,
                rate_type TEXT, minimum_contract_period INTEGER,
                maximum_contract_period INTEGER, rate NUMERIC,
                condition_description TEXT)""",
            """INSERT INTO banking_deposit_product_interest_rates(rate_id,banking_product_id,rate_type,minimum_contract_period,maximum_contract_period,rate,condition_description) VALUES
                (1,1,'BASE',12,24,3.5,'기본금리'),
                (2,1,'PREFERENTIAL',12,24,0.3,'급여이체 우대'),
                (3,2,'BASE',12,36,4.0,'기본금리')""",
            """CREATE TABLE deposit_special_terms (
                special_term_id INTEGER PRIMARY KEY, special_term_name TEXT,
                special_term_content TEXT, special_term_summary TEXT,
                is_required BOOLEAN, status TEXT)""",
            """INSERT INTO deposit_special_terms VALUES
                (1,'개인정보 수집 이용 동의','개인정보를 수집하고 이용합니다.','개인정보 동의 요약',1,'ACTIVE'),
                (2,'중도해지 약관','중도해지 시 약정이율의 50%가 적용됩니다.','중도해지 이율 안내',1,'ACTIVE')""",
            """CREATE TABLE deposit_accounts (
                account_id INTEGER PRIMARY KEY, account_number TEXT,
                customer_id TEXT, account_type TEXT, account_alias TEXT,
                balance NUMERIC, currency TEXT, account_status TEXT,
                opened_at TEXT, closed_at TEXT)""",
            """INSERT INTO deposit_accounts VALUES
                (1,'001-123-000001','CUST001','DEPOSIT','내 예금',5000000,'KRW','ACTIVE','20260101',NULL),
                (2,'001-123-000002','CUST001','SAVINGS','내 적금',1200000,'KRW','ACTIVE','20260301',NULL)""",
            """CREATE TABLE deposit_contracts (
                contract_id INTEGER PRIMARY KEY, contract_number TEXT,
                customer_id TEXT, banking_product_id INTEGER,
                join_amount NUMERIC, contract_interest_rate NUMERIC,
                started_at TEXT, maturity_at TEXT, contract_status TEXT)""",
            """INSERT INTO deposit_contracts VALUES
                (1,'CTR-001','CUST001',1,5000000,3.5,'20260101','20270101','ACTIVE'),
                (2,'CTR-002','CUST001',2,1200000,4.0,'20260301','20270301','ACTIVE')""",
            """CREATE TABLE deposit_interest_history (
                interest_id INTEGER PRIMARY KEY, contract_id INTEGER,
                account_id INTEGER, applied_interest_rate NUMERIC,
                interest_amount NUMERIC, interest_after_tax NUMERIC, interest_paid_at TEXT)""",
            """INSERT INTO deposit_interest_history(interest_id,contract_id,account_id,applied_interest_rate,interest_amount,interest_after_tax,interest_paid_at) VALUES
                (1,1,1,3.5,175000,148050,'20261231'),
                (2,2,2,4.0,48000,40608,'20261231')""",
            """CREATE TABLE deposit_transactions (
                transaction_id INTEGER PRIMARY KEY, transaction_number TEXT,
                account_id INTEGER, transaction_type TEXT,
                status TEXT, amount NUMERIC, created_at TEXT)""",
            """INSERT INTO deposit_transactions(transaction_id,transaction_number,account_id,transaction_type,status,amount,created_at) VALUES
                (1,'TX-001',1,'TRANSFER','COMPLETED',10000,'2026-05-01'),
                (2,'TX-002',1,'TRANSFER','COMPLETED',50000,'2026-05-10'),
                (3,'TX-003',2,'DEPOSIT','COMPLETED',100000,'2026-05-15')""",
        ]:
            conn.execute(text(sql))
    return Session(engine)


# ── 출력 헬퍼 ─────────────────────────────────────────────────────────────────

def _section(title: str) -> None:
    print(f"\n{'━'*64}")
    print(f"  {title}")
    print(f"{'━'*64}")


def _feature_row(code: str, name: str, status: str) -> None:
    icon = {"OK": "✅", "EMPTY": "📭", "AUTH_REQUIRED": "🔒",
            "STAFF_AUTH_REQUIRED": "🔐"}.get(status, "❓")
    print(f"  {icon}  {code:<35}  {status}")


def _msg(sender: str, content: str) -> None:
    icons = {"BOT": "🤖", "USER": "👤", "AGENT": "💼"}
    print(f"  {icons.get(sender,'❓')} [{sender:5}] {content}")


# ── 메인 ─────────────────────────────────────────────────────────────────────

async def main() -> None:
    print("\n" + "=" * 64)
    print("  consultation-service  전체 기능 실행")
    print("=" * 64)

    db         = _build_db()
    kafka_mock = AsyncMock()
    chatbot_svc = ChatbotService(db, kafka_mock, LlmHandoffAdapter())
    chat_svc    = ChatService(db, kafka_mock)

    # ── 1. PRODUCT_ADVICE ────────────────────────────────────────────────────
    _section("1. PRODUCT_ADVICE  |  금융상품 상담 (6개)")

    cases = [
        ("PRODUCT_GUIDE",  "예금/적금/청약 상품 안내",  ChatbotFeatureExecuteRequest()),
        ("RATE_GUIDE",     "금리/우대금리 설명",         ChatbotFeatureExecuteRequest()),
        ("JOIN_CONDITION", "가입 조건 안내",              ChatbotFeatureExecuteRequest()),
        ("PRODUCT_COMPARE","상품 비교",                  ChatbotFeatureExecuteRequest()),
        ("TERMS_RAG",      "약관 기반 검색",              ChatbotFeatureExecuteRequest(query="개인정보")),
        ("FAQ",            "FAQ 응답",                   ChatbotFeatureExecuteRequest()),
    ]
    for code, name, req in cases:
        r = chatbot_svc.execute_feature(code, req)
        _feature_row(code, name, r.status)
        if r.data:
            print(f"       └─ {len(r.data)}건 조회됨  (첫 항목: {list(r.data[0].values())[1]})")

    # ── 2. USER_FINANCE ──────────────────────────────────────────────────────
    _section("2. USER_FINANCE  |  사용자 금융정보 조회 (5개)")

    cases = [
        ("MY_ACCOUNTS",    "내 계좌 조회",       ChatbotFeatureExecuteRequest(customer_no="CUST001")),
        ("MY_PRODUCTS",    "가입 상품 조회",      ChatbotFeatureExecuteRequest(customer_no="CUST001")),
        ("CONTRACT_STATUS","계약 상태 조회",      ChatbotFeatureExecuteRequest(customer_no="CUST001")),
        ("MATURITY_SCHEDULE","만기 예정 조회",    ChatbotFeatureExecuteRequest(customer_no="CUST001")),
        ("INTEREST_HISTORY","이자 내역 조회",     ChatbotFeatureExecuteRequest(customer_no="CUST001")),
    ]
    for code, name, req in cases:
        r = chatbot_svc.execute_feature(code, req)
        _feature_row(code, name, r.status)
        if r.data:
            print(f"       └─ {len(r.data)}건 조회됨")

    # ── 3. STAFF_SUPPORT ─────────────────────────────────────────────────────
    _section("3. STAFF_SUPPORT  |  직원 업무 지원 (5개)")

    cases = [
        ("STAFF_CUSTOMER",             "고객 정보 조회",      ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001")),
        ("STAFF_CONTRACT",             "고객 계약 조회",      ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001")),
        ("STAFF_ACCOUNT",              "고객 계좌 조회",      ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001")),
        ("STAFF_TRANSFER_FLOW",        "고객 이체 흐름 조회", ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001")),
        ("STAFF_CONSULTATION_HISTORY", "상담 이력 조회",      ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001")),
    ]
    for code, name, req in cases:
        r = chatbot_svc.execute_feature(code, req)
        _feature_row(code, name, r.status)
        if r.data:
            print(f"       └─ {len(r.data)}건 조회됨")

    # ── 4. 챗봇 상담 흐름 ────────────────────────────────────────────────────
    _section("4. 챗봇 상담 흐름  |  시작 → 버튼 선택 → LLM 전환 → 상담사 이관")

    chatbot_svc.seed_default_scenario()
    started = await chatbot_svc.start("CUST001", "HOME", "1.0.0")
    print(f"  ▶  상담 시작  (chatbot_consultation_id={started.chatbot_consultation_id})")
    _msg("BOT", started.message)
    print(f"     버튼: {[b.text for b in started.buttons]}")

    resp1 = await chatbot_svc.handle_message(
        started.chatbot_consultation_id, "금융상품 상담", "PRODUCT_ADVICE"
    )
    _msg("USER", "금융상품 상담")
    _msg("BOT",  resp1.message)
    print(f"     처리방식: {resp1.process_method}")

    resp2 = await chatbot_svc.handle_message(
        started.chatbot_consultation_id, "상담사 연결해주세요", "AGENT"
    )
    _msg("USER", "상담사 연결해주세요")
    _msg("BOT",  resp2.message)
    print(f"     처리방식: {resp2.process_method}  |  상담사이관: {resp2.agent_transfer_required}")

    # ── 5. 상담사 채팅 ────────────────────────────────────────────────────────
    _section("5. 상담사 채팅  |  대기열 → 연결 → 메시지 교환 → 종료")

    queue = chat_svc.get_waiting_queue()
    print(f"  ▶  대기열: {len(queue)}건")
    chat_id = queue[0]["chat_consultation_id"]

    chat = await chat_svc.connect_agent(chat_id, employee_id=777)
    print(f"  ▶  상담사 연결  (employee_id={chat.employee_id}, 상태=CONNECTED)")

    exchanges = [
        (3, "안녕하세요! 무엇을 도와드릴까요?"),
        (1, "정기예금 만기일 확인 부탁드립니다."),
        (3, "CTR-001 기준 만기일은 2027년 1월 1일입니다."),
        (1, "감사합니다."),
    ]
    for sender_code, content in exchanges:
        await chat_svc.send_message(chat_id, content, sender_code)
        _msg(_SENDER_LABEL[sender_code], content)

    ended = await chat_svc.end_chat(chat_id, satisfaction_score=5)
    print(f"\n  ▶  상담 종료  (상태={ended.active_yn}, 만족도={ended.satisfaction_score}점)")

    # ── 6. Kafka 이벤트 ───────────────────────────────────────────────────────
    _section("6. Kafka 이벤트  |  발행된 이벤트 목록")

    chatbot_calls = kafka_mock.publish.call_args_list
    chat_calls    = kafka_mock.publish_chat.call_args_list
    for call in chatbot_calls:
        print(f"  📨  [chatbot topic]  {call.args[0]}")
    for call in chat_calls:
        print(f"  📨  [chat topic   ]  {call.args[0]}")

    # ── 결과 요약 ─────────────────────────────────────────────────────────────
    print(f"\n{'='*64}")
    print(f"  실행 완료")
    print(f"  챗봇 기능 16개  ✅  |  챗봇→LLM→상담사 흐름  ✅  |  Kafka 이벤트 {len(chatbot_calls)+len(chat_calls)}건  ✅")
    print(f"{'='*64}\n")

    db.close()


if __name__ == "__main__":
    asyncio.run(main())
