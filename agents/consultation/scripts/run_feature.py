"""
기능별 독립 실행 스크립트.

사용법:
    python scripts/run_feature.py <FEATURE_CODE> [옵션]

예시:
    python scripts/run_feature.py PRODUCT_GUIDE
    python scripts/run_feature.py MY_ACCOUNTS --customer-no CUST001
    python scripts/run_feature.py STAFF_TRANSFER_FLOW --customer-no CUST001 --staff-id EMP001
    python scripts/run_feature.py TERMS_RAG --query 개인정보
    python scripts/run_feature.py PRODUCT_COMPARE --product-ids 1 2

외부 의존성 없음 — SQLite 인메모리 DB + 시드 데이터로 단독 실행됩니다.
"""

import argparse
import asyncio
import io
import json
import sys
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
from app.schemas import ChatbotFeatureExecuteRequest
from app.services import ChatbotService


# ── 시드 데이터 ──────────────────────────────────────────────────────────────

def _build_db() -> Session:
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
            (1,'정기예금 플러스','DEPOSIT','안정적인 정기예금',3.5,100000,100000000,1,60,1,1,'SELLING'),
            (2,'자유적금','SAVINGS','자유롭게 납입하는 적금',4.0,10000,50000000,12,36,0,1,'SELLING'),
            (3,'주택청약종합저축','SUBSCRIPTION','청약 자격 적립 상품',2.8,2000,1000000,1,600,0,0,'SELLING')
        """))
        conn.execute(text("""
            CREATE TABLE banking_deposit_product_interest_rates (
                rate_id INTEGER PRIMARY KEY,
                banking_product_id INTEGER,
                rate_type TEXT,
                minimum_contract_period INTEGER,
                maximum_contract_period INTEGER,
                interest_rate NUMERIC,
                condition_description TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO banking_deposit_product_interest_rates VALUES
            (1,1,'BASE',12,24,3.5,'기본금리'),
            (2,1,'PREFERENTIAL',12,24,0.3,'급여이체 우대'),
            (3,2,'BASE',12,36,4.0,'기본금리'),
            (4,3,'BASE',12,600,2.8,'기본금리')
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
            (1,'개인정보 수집 이용 동의','개인정보를 수집하고 이용합니다.','개인정보 동의 요약',1,'ACTIVE'),
            (2,'중도해지 약관','중도해지 시 약정 이율의 50%가 적용됩니다.','중도해지 이율 안내',1,'ACTIVE')
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
            (1,'001-123-000001','CUST001','DEPOSIT','내 예금',5000000,'KRW','ACTIVE','20260101',NULL),
            (2,'001-123-000002','CUST001','SAVINGS','내 적금',1200000,'KRW','ACTIVE','20260301',NULL)
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
            (1,'CTR-001','CUST001',1,5000000,3.5,'20260101','20270101','ACTIVE'),
            (2,'CTR-002','CUST001',2,1200000,4.0,'20260301','20270301','ACTIVE')
        """))
        conn.execute(text("""
            CREATE TABLE deposit_interest_history (
                interest_id INTEGER PRIMARY KEY,
                contract_id INTEGER,
                account_id INTEGER,
                applied_interest_rate NUMERIC,
                interest_amount NUMERIC,
                interest_after_tax_amount NUMERIC,
                paid_at TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_interest_history VALUES
            (1,1,1,3.5,175000,148050,'20261231'),
            (2,2,2,4.0,48000,40608,'20261231')
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
            (1,'TX-001',1,'TRANSFER','COMPLETED',10000,'2026-05-01'),
            (2,'TX-002',1,'TRANSFER','COMPLETED',50000,'2026-05-10'),
            (3,'TX-003',2,'DEPOSIT','COMPLETED',100000,'2026-05-15')
        """))
    return Session(engine)


# ── 출력 헬퍼 ────────────────────────────────────────────────────────────────

def _print_result(result) -> None:
    status_icon = {"OK": "✅", "EMPTY": "📭", "AUTH_REQUIRED": "🔒",
                   "STAFF_AUTH_REQUIRED": "🔐", "NOT_FOUND": "❌"}.get(result.status, "❓")

    print(f"\n{'='*60}")
    print(f"  기능코드  : {result.feature_code}")
    print(f"  상태      : {status_icon} {result.status}")
    print(f"  메시지    : {result.message}")
    if result.requires_auth:
        print(f"  인증 필요  : 고객 본인 인증")
    if result.requires_staff_auth:
        print(f"  인증 필요  : 직원 권한")
    print(f"{'='*60}")

    if result.data:
        print(f"\n  📊 데이터 ({len(result.data)}건):\n")
        for i, item in enumerate(result.data, 1):
            print(f"  [{i}]")
            for k, v in item.items():
                if v is not None:
                    print(f"      {k:30s}: {v}")
    print()


# ── 기능 설명 ────────────────────────────────────────────────────────────────

FEATURE_INFO = {
    "PRODUCT_GUIDE":              ("PRODUCT_ADVICE",  "예금/적금/청약 상품 안내"),
    "RATE_GUIDE":                 ("PRODUCT_ADVICE",  "금리/우대금리 설명"),
    "JOIN_CONDITION":             ("PRODUCT_ADVICE",  "가입 조건 안내"),
    "PRODUCT_COMPARE":            ("PRODUCT_ADVICE",  "상품 비교"),
    "TERMS_RAG":                  ("PRODUCT_ADVICE",  "약관 기반 검색"),
    "FAQ":                        ("PRODUCT_ADVICE",  "FAQ 응답"),
    "MY_ACCOUNTS":                ("USER_FINANCE",    "내 계좌 조회"),
    "MY_PRODUCTS":                ("USER_FINANCE",    "가입 상품 조회"),
    "CONTRACT_STATUS":            ("USER_FINANCE",    "계약 상태 조회"),
    "MATURITY_SCHEDULE":          ("USER_FINANCE",    "만기 예정 조회"),
    "INTEREST_HISTORY":           ("USER_FINANCE",    "이자 내역 조회"),
    "STAFF_CUSTOMER":             ("STAFF_SUPPORT",   "고객 정보 조회"),
    "STAFF_CONTRACT":             ("STAFF_SUPPORT",   "고객 계약 조회"),
    "STAFF_ACCOUNT":              ("STAFF_SUPPORT",   "고객 계좌 조회"),
    "STAFF_TRANSFER_FLOW":        ("STAFF_SUPPORT",   "고객 이체 흐름 조회"),
    "STAFF_CONSULTATION_HISTORY": ("STAFF_SUPPORT",   "상담 이력 조회"),
}


def _list_features() -> None:
    print("\n사용 가능한 기능 목록:\n")
    current_cat = ""
    for code, (cat, name) in FEATURE_INFO.items():
        if cat != current_cat:
            print(f"  [{cat}]")
            current_cat = cat
        print(f"    {code:<30s}  {name}")
    print()


# ── 메인 ─────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="챗봇 기능 단독 실행 도구",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
예시:
  python scripts/run_feature.py PRODUCT_GUIDE
  python scripts/run_feature.py MY_ACCOUNTS --customer-no CUST001
  python scripts/run_feature.py STAFF_TRANSFER_FLOW --customer-no CUST001 --staff-id EMP001
  python scripts/run_feature.py TERMS_RAG --query 개인정보
  python scripts/run_feature.py PRODUCT_COMPARE --product-ids 1 2 3
  python scripts/run_feature.py --list
        """,
    )
    parser.add_argument("feature_code", nargs="?", help="실행할 기능 코드")
    parser.add_argument("--customer-no", default=None, help="고객번호 (USER_FINANCE, STAFF_SUPPORT 필수)")
    parser.add_argument("--staff-id",    default=None, help="직원ID (STAFF_SUPPORT 필수)")
    parser.add_argument("--query",       default=None, help="검색 키워드 (TERMS_RAG)")
    parser.add_argument("--product-ids", nargs="*", type=int, default=[], help="상품ID 목록 (PRODUCT_COMPARE)")
    parser.add_argument("--product-id",  type=int, default=None, help="단일 상품ID (PRODUCT_COMPARE)")
    parser.add_argument("--list", "-l",  action="store_true", help="기능 목록 출력")

    args = parser.parse_args()

    if args.list or not args.feature_code:
        _list_features()
        return

    code = args.feature_code.upper()
    if code not in FEATURE_INFO:
        print(f"\n❌ 알 수 없는 기능 코드: {code}")
        _list_features()
        sys.exit(1)

    cat, name = FEATURE_INFO[code]
    print(f"\n▶  {name}  ({code})")
    print(f"   카테고리: {cat}")

    db = _build_db()
    try:
        service = ChatbotService(db, AsyncMock(), LlmHandoffAdapter())
        request = ChatbotFeatureExecuteRequest(
            customer_no=args.customer_no,
            staff_id=args.staff_id,
            query=args.query,
            product_id=args.product_id,
            compare_product_ids=args.product_ids or [],
        )
        result = service.execute_feature(code, request)
        _print_result(result)
    finally:
        db.close()


if __name__ == "__main__":
    main()
