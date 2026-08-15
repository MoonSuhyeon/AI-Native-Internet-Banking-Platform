import os
import sys
from pathlib import Path
from unittest.mock import AsyncMock

_KNOWN_FAILURES_FILE = Path(__file__).parent / "known_failures.txt"


def _known_failures() -> set[str]:
    if not _KNOWN_FAILURES_FILE.exists():
        return set()
    return {
        line.strip()
        for line in _KNOWN_FAILURES_FILE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def pytest_collection_modifyitems(items):
    """알려진 실패를 strict xfail 로 표시한다.

    **덮어두는 장치가 아니라 줄여가는 장치다.** strict 라서 목록에 있는 테스트가
    통과하기 시작하면 XPASS 로 CI 가 빨개지고, 고친 사람이 목록에서 지우게 된다.
    그냥 skip 이나 느슨한 xfail 로 두면 목록이 조용히 영구 부채가 된다.

    배경: consultation 테스트를 돌리는 CI 가 없어 24개 파일이 두 달 넘게 썩었다.
    남은 91건을 다 고칠 때까지 CI 를 미루면 그동안 통과하는 1,508건도 지켜지지 않는다.
    """
    known = _known_failures()
    if not known:
        return
    import pytest as _pytest

    for item in items:
        if item.nodeid in known:
            item.add_marker(_pytest.mark.xfail(
                strict=True,
                reason="known_failures.txt 등재 — 고치면 목록에서 지울 것",
            ))

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
# harness_core 는 sys.path 를 만지지 않는다. venv 에 설치해서 쓴다
# (requirements-dev.txt 의 -e ../harness-core/python).
os.environ["CONSULTATION_KAFKA_ENABLED"] = "false"

# Settings.database_url 은 기본값이 없다 — 운영에서 미설정이면 기동 즉시 죽는 것이
# 의도다. 그런데 아래 `from app.database import Base` 가 그 Settings 를 만들기 때문에,
# 값이 없으면 테스트가 한 건도 실행되지 않고 **수집 단계에서 통째로** 죽는다.
# CI 는 env 로 넣어 주지만 로컬은 아무도 안 넣어서, 로컬에서만 스위트 전체가
# 안 돌아가는 상태였다.
#
# 테스트 본체는 인메모리 SQLite 픽스처를 쓰고 감사 저장소도 `audit_spy` 가 갈아끼우므로
# 이 값은 실제로 쓰이지 않는다. 그래서 "닿을 수 없는 주소" 를 넣는다 — 혹시라도 무언가
# 진짜로 접속을 시도하면 조용히 도는 대신 즉시 터져서 드러나야 한다.
#
# setdefault 라 CI 가 준 진짜 DSN 이 있으면 그쪽이 이긴다.
os.environ.setdefault(
    "CONSULTATION_DATABASE_URL",
    "postgresql+psycopg://unused:unused@127.0.0.1:1/settings-validation-only",
)

from app.database import Base
from app.llm import LlmAdapter, LlmHandoffAdapter
from app.services import ChatbotService


@pytest.fixture(autouse=True)
def _block_outbound_http(monkeypatch):
    """테스트에서 나가는 HTTP 를 즉시 끊는다.

    서비스 코드는 goal-agent(GOAL_AGENT_URL, 기본 http://host.docker.internal:8000,
    timeout 30초)와 customer-service 를 실제로 호출한다. 아무도 막지 않으면
    테스트가 그 호출을 그대로 내보낸다.

      - GitHub 러너: host.docker.internal 이름 해석이 실패해 즉시 떨어진다.
      - Docker Desktop 이 깔린 로컬: 이름은 해석되는데 연결이 막혀 호출마다
        30초씩 멈춘다. 같은 스위트가 CI 에서는 몇 분, 로컬에서는 몇십 분이 된다.

    ConnectError 를 즉시 던져 CI 에서 벌어지는 일(연결 실패 → 코드의 fallback 경로)
    을 그대로 재현하되, 기다리지는 않는다. 외부 호출 결과를 검증해야 하는 테스트가
    생기면 그 테스트에서 이 fixture 를 덮어쓰지 말고 응답을 명시적으로 mock 할 것.

    막는 자리는 실제 소켓을 여는 transport 다. FastAPI TestClient 는 httpx.Client
    를 상속하지만 ASGITransport 로 앱을 직접 호출하므로 여기에 걸리지 않는다.
    (httpx.Client.send 를 막으면 API 테스트가 통째로 죽는다.)
    """
    import httpx

    def _refuse(*args, **kwargs):
        raise httpx.ConnectError("테스트에서는 외부 HTTP 호출이 차단된다")

    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", _refuse)
    monkeypatch.setattr(httpx.AsyncHTTPTransport, "handle_async_request", _refuse)


class RecordingAuditLog:
    """테스트용 인메모리 감사 저장소. harness_core 저장소와 같은 모양만 갖춘다."""

    def __init__(self) -> None:
        self.entries: list = []

    def record(self, entry) -> None:
        self.entries.append(entry)

    def find_latest(self, subject_type: str, subject_id: str, decision_kind=None):
        for entry in reversed(self.entries):
            if entry.subject_type != subject_type or entry.subject_id != subject_id:
                continue
            if decision_kind is None or entry.decision_kind == decision_kind:
                return entry
        return None


@pytest.fixture(autouse=True)
def audit_spy(monkeypatch) -> RecordingAuditLog:
    """감사 저장소를 인메모리 spy 로 바꾼다.

    바꾸지 않으면 get_audit_log() 가 SessionLocal(=CONSULTATION_DATABASE_URL 이
    가리키는 실제 DB)에 붙은 저장소를 만든다. 테스트 본체는 인메모리 SQLite 를
    쓰므로 감사 INSERT 만 엉뚱한 DB 로 나가고, 거기엔 harness_audit_log 가 없어
    턴마다 예외 트레이스가 찍힌다. 실패가 응답을 막지는 않지만(설계된 동작),
    로그가 시끄러워 진짜 오류를 덮는다.

    spy 로 바꾸면 그 소음이 사라지고, 감사 호출이 붙어 있는지를 SQLite 테스트에서도
    확인할 수 있다. 저장이 실제로 되는지(JSONB·INSERT-ONLY 트리거)는 SQLite 로
    확인할 수 없어 test_audit_wiring.py 가 실제 PostgreSQL 로 따로 본다.
    """
    import app.audit as audit_mod

    spy = RecordingAuditLog()
    monkeypatch.setattr(audit_mod, "_audit_log", spy)
    return spy


# ── 직원 신원 (게이트웨이 주입 헤더) ─────────────────────────────────────────
#
# 직원용 기능(STAFF_*)의 직원 ID 는 더 이상 요청 body 에서 오지 않는다. 게이트웨이가
# JWT 를 검증해 X-Employee-Id 를 주입하고, 상담 서비스는 그 헤더만 믿는다.
#
# 그래서 테스트도 body 가 아니라 헤더로 신원을 준다. 예전 테스트들이 body 로
# staff_id 를 보내 통과하던 것은 **위조가 가능하던 상태를 검증하고 있던 것**이라,
# 이 픽스처로 옮기면서 함께 고쳤다.

#: 게이트웨이를 거쳤다는 증거. 실제 값은 배포 시 주입한다.
GATEWAY_SECRET = "test-gateway-secret"


@pytest.fixture()
def staff_headers(monkeypatch):
    """게이트웨이가 직원 토큰을 검증해 통과시킨 상태를 만든다."""
    monkeypatch.setenv("CONSULTATION_GATEWAY_SHARED_SECRET", GATEWAY_SECRET)

    def headers(employee_id: str = "EMP001") -> dict[str, str]:
        return {"X-Gateway-Auth": GATEWAY_SECRET, "X-Employee-Id": employee_id}

    return headers


#: 기존 흐름 테스트가 쓰는 기본 헤더 — 게이트웨이를 거친 직원 토큰을 흉내 낸다.
#:
#: 직원 토큰은 실제로 두 헤더를 다 갖는다. JwtProvider 가 sub 에 customerId 를,
#: empId 에 employeeId 를 넣기 때문이다(직원도 개인 계좌가 있다). 그래서 이 조합은
#: 억지가 아니라 실제로 존재하는 상태다.
#:
#: 이 파일들의 목적은 상담 흐름이 끝까지 도는지 보는 것이고, 누가 무엇을 열 수
#: 있는지는 test_endpoint_authorization.py 가 따로 본다.
GATEWAY_HEADERS = {
    "X-Gateway-Auth": GATEWAY_SECRET,
    "X-Customer-Id": "CUST001",
    "X-Employee-Id": "EMP001",
}


@pytest.fixture(autouse=True)
def _gateway_secret_for_flow_tests(monkeypatch):
    """흐름 테스트가 게이트웨이 뒤에 있는 것처럼 만든다.

    autouse 인 이유: 인증을 붙이기 전에 쓰인 테스트가 수십 개라, 빠뜨리면 그 파일만
    조용히 403 으로 실패한다. 인가 자체는 전용 테스트가 시크릿을 직접 다루며 본다.
    """
    monkeypatch.setenv("CONSULTATION_GATEWAY_SHARED_SECRET", GATEWAY_SECRET)


@pytest.fixture()
def customer_headers(monkeypatch):
    """게이트웨이가 고객 토큰을 검증해 통과시킨 상태.

    고객 식별자도 더 이상 요청 body 에서 오지 않는다. 예전 테스트들이 body 의
    customer_no 로 통과하던 것은 **사칭이 가능하던 상태를 검증하고 있던 것**이라,
    이 픽스처로 옮기면서 함께 고쳤다.
    """
    monkeypatch.setenv("CONSULTATION_GATEWAY_SHARED_SECRET", GATEWAY_SECRET)

    def headers(customer_no: str = "CUST001") -> dict[str, str]:
        return {"X-Gateway-Auth": GATEWAY_SECRET, "X-Customer-Id": customer_no}

    return headers


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
                deposit_product_status TEXT,
                -- 아래 셋은 앱이 조회하는데 스텁에 없어 45건이 죽던 컬럼이다.
                -- 뒤에 붙인다 — 중간에 넣으면 위치 기반 INSERT 가 전부 어긋난다.
                preferential_rate_condition TEXT,
                is_auto_renewal_available BOOLEAN,
                is_passbook_issued BOOLEAN
            )
        """))
        conn.execute(text("""
            INSERT INTO deposit_banking_products (banking_product_id, deposit_product_name, deposit_product_type, description, base_interest_rate, min_join_amount, max_join_amount, min_period_month, max_period_month, is_early_termination_allowed, is_tax_benefit_available, deposit_product_status) VALUES
            (1, '정기예금 플러스', 'DEPOSIT', '안정적인 정기예금', 3.5, 100000, 100000000, 1, 60, 1, 1, 'SELLING')
        """))
        conn.execute(text("""
            CREATE TABLE banking_deposit_products (
                banking_product_id INTEGER PRIMARY KEY,
                is_compound_interest BOOLEAN
            )
        """))
        conn.execute(text("""
            CREATE TABLE banking_deposit_product_target_groups (
                banking_product_id INTEGER,
                target_group_id INTEGER
            )
        """))
        conn.execute(text("""
            CREATE TABLE deposit_target_groups (
                target_group_id INTEGER PRIMARY KEY,
                target_group_name TEXT,
                description TEXT
            )
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
                status TEXT,
                amount NUMERIC,
                transaction_at TEXT,
                created_at TEXT)
        """))
        conn.execute(text(f"""
            INSERT INTO deposit_transactions VALUES
            (1, 'TX-001', 1, 'TRANSFER', 'COMPLETED', 10000, '{_days_ago(20)}', '{_days_ago(20)}')
        """))
        # employees 는 Base.metadata.create_all 이 먼저 만든다(app.models.Employee).
        # 그 위에 다시 CREATE 하면 "table employees already exists" 로 픽스처가 죽는다 —
        # 모델이 나중에 추가됐는데 이 stub 이 남아 있었고, consultation 테스트를 돌리는
        # CI 가 없어서 깨진 채로 방치됐다.
        #
        # 모델 테이블을 그대로 쓰지 않고 stub 으로 바꿔 끼우는 이유:
        # 기능 코드는 `WHERE employee_id = :sid` 로 **문자열** staff_id('EMP001')를 조회하는데
        # 모델의 employee_id 는 INTEGER PRIMARY KEY(SQLite 에서 rowid 별칭)라 문자열을 못 받는다.
        # 여기서 컬럼 타입을 맞춰주지 않으면 datatype mismatch 로 db 픽스처 전체가 죽는다.
        # (운영 스키마와 조회 타입이 어긋나 있다는 뜻이라 별도로 볼 일이다.)
        conn.execute(text("DROP TABLE IF EXISTS employees"))
        conn.execute(text("""
            CREATE TABLE employees (
                employee_id TEXT PRIMARY KEY,
                status TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO employees VALUES ('EMP001', 'ACTIVE')
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

    def answer(self, message: str, context: str = "") -> tuple[str, bool]:
        return f"[LLM 응답] {message}에 대한 AI 답변입니다.", False


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
            deposit_product_status TEXT,
            preferential_rate_condition TEXT,
            is_auto_renewal_available BOOLEAN,
            is_passbook_issued BOOLEAN)""",
        """CREATE TABLE banking_deposit_products (
            banking_product_id INTEGER PRIMARY KEY,
            is_compound_interest BOOLEAN)""",
        """CREATE TABLE banking_deposit_product_target_groups (
            banking_product_id INTEGER,
            target_group_id INTEGER)""",
        """CREATE TABLE deposit_target_groups (
            target_group_id INTEGER PRIMARY KEY,
            target_group_name TEXT,
            description TEXT)""",
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
            status TEXT,
            amount NUMERIC,
            transaction_at TEXT,
            created_at TEXT)""",
        # employees 는 아래에서 DROP 후 stub 으로 다시 만든다 (위 db 픽스처와 같은 이유).
    ]:
        conn.execute(text(ddl))
    conn.execute(text("DROP TABLE IF EXISTS employees"))
    conn.execute(text("""CREATE TABLE employees (
        employee_id TEXT PRIMARY KEY,
        status TEXT)"""))
    conn.execute(text("INSERT INTO employees VALUES ('EMP001', 'ACTIVE')"))


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
            INSERT INTO deposit_banking_products (banking_product_id, deposit_product_name, deposit_product_type, description, base_interest_rate, min_join_amount, max_join_amount, min_period_month, max_period_month, is_early_termination_allowed, is_tax_benefit_available, deposit_product_status) VALUES
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
        conn.execute(text(f"""
            INSERT INTO deposit_transactions VALUES
            (1,'TX-001',1,'TRANSFER','COMPLETED',10000,'{_days_ago(34)}','{_days_ago(34)}'),
            (2,'TX-002',1,'TRANSFER','PENDING',50000,'{_days_ago(31)}','{_days_ago(31)}'),
            (3,'TX-003',2,'DEPOSIT','COMPLETED',100000,'{_days_ago(26)}','{_days_ago(26)}'),
            (4,'TX-004',3,'TRANSFER','COMPLETED',20000,'{_days_ago(21)}','{_days_ago(21)}')
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


# ── 거래일 헬퍼 ───────────────────────────────────────────────────────────────
# 현금흐름 분석이 now-90일 컷오프로 거래를 거르므로 **고정 날짜를 쓰면 안 된다.**
# 작성 시점에는 통과하다가 달력이 지나가면 조용히 "거래 없음"이 되어
# has_data=False·monthly_surplus=0 으로 무너진다 (실제로 그렇게 깨져 있었다).
# 테스트 하네스 규약 5번 참조.
def _days_ago(n: int) -> str:
    """n일 전 날짜 (YYYY-MM-DD). 컷오프 안쪽에 머물도록 상대값으로 만든다."""
    from datetime import datetime, timedelta, timezone
    return (datetime.now(timezone.utc) - timedelta(days=n)).strftime("%Y-%m-%d")


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
            INSERT INTO deposit_banking_products (banking_product_id, deposit_product_name, deposit_product_type, description, base_interest_rate, min_join_amount, max_join_amount, min_period_month, max_period_month, is_early_termination_allowed, is_tax_benefit_available, deposit_product_status) VALUES
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
        conn.execute(text(f"""
            INSERT INTO deposit_transactions VALUES
            (101,'CF-TX-101',10,'DEPOSIT','COMPLETED',3000000,'{_days_ago(70)}','{_days_ago(70)}'),
            (102,'CF-TX-102',10,'DEPOSIT','COMPLETED',3000000,'{_days_ago(40)}','{_days_ago(40)}'),
            (103,'CF-TX-103',10,'DEPOSIT','COMPLETED',3000000,'{_days_ago(10)}','{_days_ago(10)}'),
            (104,'CF-TX-104',10,'WITHDRAWAL','COMPLETED',800000,'{_days_ago(85)}','{_days_ago(85)}'),
            (105,'CF-TX-105',10,'WITHDRAWAL','COMPLETED',800000,'{_days_ago(64)}','{_days_ago(64)}'),
            (106,'CF-TX-106',10,'WITHDRAWAL','COMPLETED',800000,'{_days_ago(34)}','{_days_ago(34)}'),
            (107,'CF-TX-107',10,'TRANSFER','COMPLETED',200000,'{_days_ago(80)}','{_days_ago(80)}'),
            (108,'CF-TX-108',10,'TRANSFER','COMPLETED',200000,'{_days_ago(50)}','{_days_ago(50)}')
        """))

        # ── 거래: CUST_SURPLUS (대형 예금 이동) ──────────────────────────────
        conn.execute(text(f"""
            INSERT INTO deposit_transactions VALUES
            (201,'CF-TX-201',20,'DEPOSIT','COMPLETED',20000000,'{_days_ago(89)}','{_days_ago(89)}'),
            (202,'CF-TX-202',20,'DEPOSIT','COMPLETED',15000000,'{_days_ago(88)}','{_days_ago(88)}'),
            (203,'CF-TX-203',20,'WITHDRAWAL','COMPLETED',5000000,'{_days_ago(75)}','{_days_ago(75)}')
        """))

        # ── 거래: CUST_TIGHT (지출 > 입금) ───────────────────────────────────
        conn.execute(text(f"""
            INSERT INTO deposit_transactions VALUES
            (301,'CF-TX-301',30,'DEPOSIT','COMPLETED',500000,'{_days_ago(70)}','{_days_ago(70)}'),
            (302,'CF-TX-302',30,'WITHDRAWAL','COMPLETED',700000,'{_days_ago(85)}','{_days_ago(85)}'),
            (303,'CF-TX-303',30,'WITHDRAWAL','COMPLETED',700000,'{_days_ago(64)}','{_days_ago(64)}'),
            (304,'CF-TX-304',30,'WITHDRAWAL','COMPLETED',700000,'{_days_ago(34)}','{_days_ago(34)}')
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
