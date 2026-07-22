"""
전체 흐름 데모 스크립트.

실행:
    python scripts/demo.py

외부 의존성 없음 — SQLite 인메모리 DB로 단독 실행됩니다.

시연 순서:
  1. 챗봇 시작                (고객 CUST001 접속)
  2. 버튼 선택                (금융상품 상담)
  3. 상담사 연결 요청          (AGENT 버튼 → LLM 전환)
  4. 상담사 대기열 확인
  5. 상담사 연결 수락          (직원 EMP-777)
  6. 양방향 메시지 교환
  7. 전체 메시지 이력 조회
  8. 상담 종료                 (만족도 5점)
"""

import asyncio
import io
import sys
import time
from pathlib import Path
from unittest.mock import AsyncMock

# Windows 터미널 UTF-8 출력
if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.database import Base
from app.llm import LlmHandoffAdapter
from app.services import ChatbotService, ChatService, _SENDER_LABEL


# ── 시드 DB ──────────────────────────────────────────────────────────────────

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
                deposit_product_name TEXT NOT NULL,
                deposit_product_type TEXT, description TEXT,
                base_interest_rate NUMERIC, min_join_amount NUMERIC,
                max_join_amount NUMERIC, min_period_month INTEGER,
                max_period_month INTEGER, is_early_termination_allowed BOOLEAN,
                is_tax_benefit_available BOOLEAN, deposit_product_status TEXT)""",
            "INSERT INTO deposit_banking_products VALUES (1,'정기예금 플러스','DEPOSIT','안정적인 정기예금',3.5,100000,100000000,1,60,1,1,'SELLING')",
            """CREATE TABLE banking_deposit_product_interest_rates (
                rate_id INTEGER PRIMARY KEY, banking_product_id INTEGER,
                rate_type TEXT, minimum_contract_period INTEGER,
                maximum_contract_period INTEGER, interest_rate NUMERIC,
                condition_description TEXT)""",
            "INSERT INTO banking_deposit_product_interest_rates VALUES (1,1,'BASE',12,24,3.5,'기본금리')",
            """CREATE TABLE deposit_special_terms (
                special_term_id INTEGER PRIMARY KEY, special_term_name TEXT,
                special_term_content TEXT, special_term_summary TEXT,
                is_required BOOLEAN, status TEXT)""",
            "INSERT INTO deposit_special_terms VALUES (1,'개인정보 수집 이용 동의','개인정보를 수집하고 이용합니다.','개인정보 동의 요약',1,'ACTIVE')",
            """CREATE TABLE deposit_accounts (
                account_id INTEGER PRIMARY KEY, account_number TEXT,
                customer_id TEXT, account_type TEXT, account_alias TEXT,
                balance NUMERIC, currency TEXT, account_status TEXT,
                opened_at TEXT, closed_at TEXT)""",
            "INSERT INTO deposit_accounts VALUES (1,'001-123-000001','CUST001','DEPOSIT','내 예금',5000000,'KRW','ACTIVE','20260101',NULL)",
            """CREATE TABLE deposit_contracts (
                contract_id INTEGER PRIMARY KEY, contract_number TEXT,
                customer_id TEXT, banking_product_id INTEGER,
                join_amount NUMERIC, contract_interest_rate NUMERIC,
                started_at TEXT, maturity_at TEXT, contract_status TEXT)""",
            "INSERT INTO deposit_contracts VALUES (1,'CTR-001','CUST001',1,5000000,3.5,'20260101','20270101','ACTIVE')",
            """CREATE TABLE deposit_interest_history (
                interest_id INTEGER PRIMARY KEY, contract_id INTEGER,
                account_id INTEGER, applied_interest_rate NUMERIC,
                interest_amount NUMERIC, interest_after_tax_amount NUMERIC,
                paid_at TEXT)""",
            "INSERT INTO deposit_interest_history VALUES (1,1,1,3.5,175000,148050,'20261231')",
            """CREATE TABLE deposit_transactions (
                transaction_id INTEGER PRIMARY KEY, transaction_number TEXT,
                account_id INTEGER, transaction_type TEXT,
                transaction_status TEXT, amount NUMERIC, created_at TEXT)""",
            "INSERT INTO deposit_transactions VALUES (1,'TX-001',1,'TRANSFER','COMPLETED',10000,'2026-05-21')",
        ]:
            conn.execute(text(sql))
    return Session(engine)


# ── 출력 헬퍼 ────────────────────────────────────────────────────────────────

def _sep(title: str = "") -> None:
    if title:
        pad = max(0, 58 - len(title))
        print(f"\n{'─'*3} {title} {'─'*pad}")
    else:
        print("─" * 64)


def _step(n: int, title: str) -> None:
    print(f"\n\033[1;36m[Step {n}] {title}\033[0m")


def _msg(sender: str, text: str) -> None:
    icons = {"BOT": "🤖", "USER": "👤", "AGENT": "💼"}
    icon = icons.get(sender, "❓")
    print(f"  {icon}  [{sender:5s}]  {text}")


def _ok(msg: str) -> None:
    print(f"  \033[32m✓  {msg}\033[0m")


def _info(key: str, val) -> None:
    print(f"  {'·':2s} {key:<20s}: {val}")


# ── 데모 실행 ────────────────────────────────────────────────────────────────

async def run_demo() -> None:
    print("\n" + "=" * 64)
    print("  상담 서비스 전체 흐름 데모")
    print("  챗봇 → LLM 전환 → 상담사 채팅 → 종료")
    print("=" * 64)

    db = _build_db()
    mock_kafka = AsyncMock()
    chatbot_svc = ChatbotService(db, mock_kafka, LlmHandoffAdapter())
    chat_svc = ChatService(db, mock_kafka)

    # ── Step 1: 시나리오 시드 ─────────────────────────────────────────────
    _step(1, "챗봇 시나리오 초기화")
    scenario_id, first_node_id = chatbot_svc.seed_default_scenario()
    _ok(f"시나리오 ID={scenario_id}, 첫 노드 ID={first_node_id}")

    # ── Step 2: 챗봇 상담 시작 ───────────────────────────────────────────
    _step(2, "챗봇 상담 시작  (고객: CUST001)")
    started = await chatbot_svc.start("CUST001", "HOME", "1.0.0")
    _info("consultation_id", started.consultation_id)
    _info("chatbot_consultation_id", started.chatbot_consultation_id)
    _sep("챗봇 메시지")
    _msg("BOT", started.message)
    print("\n  선택 가능한 버튼:")
    for btn in started.buttons:
        print(f"    [{btn.value}] {btn.text}")

    # ── Step 3: 버튼 선택 (금융상품 상담) ───────────────────────────────
    _step(3, "버튼 선택  →  금융상품 상담")
    resp1 = await chatbot_svc.handle_message(
        started.chatbot_consultation_id, "금융상품 상담", "PRODUCT_ADVICE"
    )
    _msg("USER", "금융상품 상담")
    _msg("BOT", resp1.message)
    _info("처리방식", resp1.process_method)
    _info("상담사이관", resp1.agent_transfer_required)

    # ── Step 4: 상담사 연결 요청 (AGENT 버튼) ───────────────────────────
    _step(4, "상담사 연결 요청  →  LLM 전환")
    resp2 = await chatbot_svc.handle_message(
        started.chatbot_consultation_id, "상담사 연결해주세요", "AGENT"
    )
    _msg("USER", "상담사 연결해주세요")
    _msg("BOT", resp2.message)
    _info("처리방식", resp2.process_method)
    _info("상담사이관 필요", f"\033[33m{resp2.agent_transfer_required}\033[0m")

    # ── Step 5: 상담사 대기열 확인 ───────────────────────────────────────
    _step(5, "상담사 대기열 확인")
    queue = chat_svc.get_waiting_queue()
    print(f"  대기 중인 상담: {len(queue)}건")
    for item in queue:
        _info("chat_consultation_id", item["chat_consultation_id"])
        _info("customer_no", item["customer_no"])
        _info("waiting_since", item["waiting_since"])
    chat_id = queue[0]["chat_consultation_id"]

    # ── Step 6: 상담사 연결 수락 ─────────────────────────────────────────
    _step(6, "상담사 연결 수락  (직원 ID: 777)")
    chat = await chat_svc.connect_agent(chat_id, employee_id=777)
    _ok(f"상태: CONNECTED")
    _info("employee_id", chat.employee_id)
    _info("agent_connected_at", chat.agent_connected_at)
    _info("waiting_seconds", chat.waiting_seconds)

    # ── Step 7: 메시지 교환 ──────────────────────────────────────────────
    _step(7, "메시지 교환")
    exchanges = [
        (3, "안녕하세요! 무엇을 도와드릴까요?"),
        (1, "정기예금 만기가 언제인지 확인하고 싶어요."),
        (3, "네, 고객님 계약 CTR-001 기준 만기일은 2027년 1월 1일입니다."),
        (1, "중도해지하면 이자가 얼마나 줄어드나요?"),
        (3, "중도해지 시 약정이율의 50%가 적용됩니다. 약관을 함께 안내해 드릴까요?"),
        (1, "네, 부탁드립니다."),
        (3, "약관 내용을 문자로 발송해 드리겠습니다. 다른 문의사항 있으신가요?"),
        (1, "아니요, 감사합니다."),
    ]
    for sender_code, content in exchanges:
        sender = _SENDER_LABEL[sender_code]
        await chat_svc.send_message(chat_id, content, sender_code)
        _msg(sender, content)
        time.sleep(0.05)

    # ── Step 8: 메시지 이력 전체 조회 ───────────────────────────────────
    _step(8, "전체 메시지 이력 조회  (챗봇 + 상담사)")
    messages = chat_svc.get_messages(chat_id)
    print(f"  총 {len(messages)}개 메시지\n")
    for m in messages:
        sender = _SENDER_LABEL.get(m.sender_type_code_id or 0, "?")
        icon = {"BOT": "🤖", "USER": "👤", "AGENT": "💼"}.get(sender, "❓")
        print(f"  {icon} [{sender:5s}] {m.message_content}")

    # ── Step 9: 상담 종료 ────────────────────────────────────────────────
    _step(9, "상담 종료  (만족도: 5점)")
    ended = await chat_svc.end_chat(chat_id, satisfaction_score=5)
    _ok("상담 종료 완료")
    _info("status", "ENDED")
    _info("active_yn", ended.active_yn)
    _info("satisfaction_score", ended.satisfaction_score)
    _info("chat_seconds", ended.chat_seconds)
    _info("chat_ended_at", ended.chat_ended_at)

    # ── Kafka 이벤트 요약 ────────────────────────────────────────────────
    _step(10, "Kafka 발행 이벤트 요약")
    all_calls = mock_kafka.publish.call_args_list + mock_kafka.publish_chat.call_args_list
    for call in all_calls:
        event_type = call.args[0] if call.args else call.kwargs.get("event_type", "?")
        topic = "chatbot" if call in mock_kafka.publish.call_args_list else "chat"
        print(f"  📨  [{topic:7s}]  {event_type}")

    print("\n" + "=" * 64)
    print("  \033[32m데모 완료\033[0m")
    print("=" * 64 + "\n")
    db.close()


if __name__ == "__main__":
    asyncio.run(run_demo())
