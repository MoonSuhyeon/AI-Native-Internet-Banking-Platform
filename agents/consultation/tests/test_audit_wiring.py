"""상담 응답이 실제로 감사 테이블에 남는지 — docs/decisions/agent-harness-consolidation.md 2-d.

**왜 이 파일이 필요한가.**
배선이 도는 것은 실제 PostgreSQL 로 확인했지만 그건 손으로 한 번 돌린 것이었고,
자동화된 테스트에는 없었다. 즉 누가 실수로 ``handle_message`` 의 감사 호출을 지워도
아무 테스트도 깨지지 않는 상태였다. 이 레포가 내내 경계해온 "검증 없는 발판" 이
정작 감사 배선 자체에 있었다.

**왜 SQLite 가 아니라 PostgreSQL 인가.**
감사 테이블은 JSONB 와 INSERT-ONLY 트리거에 기대므로 SQLite 로는 검증되지 않는다.
다른 테스트들이 쓰는 인메모리 SQLite 로 이 테스트를 쓰면 통과하지만 아무것도
보장하지 못한다. DSN 이 없으면 **건너뛰지 않고 실패**한다 — 조용히 skip 되면
CI 가 초록인데 감사는 검증되지 않는 상태로 돌아간다.
"""

from __future__ import annotations

import asyncio
import json
import os
import uuid

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

from app.audit import SUBJECT_TYPE, record_chatbot_turn
from harness_core import AgentAuditEntry
from harness_core.audit_sqlalchemy import SqlAlchemyAgentAuditLog
from harness_core.schema import apply_schema_sql

#: 감사 검증용 PostgreSQL. CI 는 서비스 컨테이너로 띄운다.
DSN_ENV = "CONSULTATION_AUDIT_TEST_DSN"

pytestmark = pytest.mark.skipif(
    os.getenv("SKIP_AUDIT_DB_TESTS") == "1",
    reason="감사 DB 테스트를 명시적으로 껐다 (SKIP_AUDIT_DB_TESTS=1)",
)


@pytest.fixture(scope="module")
def audit_engine():
    dsn = os.getenv(DSN_ENV, "").strip()
    if not dsn:
        pytest.fail(
            f"{DSN_ENV} 가 없다. 감사 배선은 실제 PostgreSQL 로만 검증된다 — "
            f"JSONB·INSERT-ONLY 트리거를 SQLite 로는 확인할 수 없다.\n"
            f"\n"
            f"로컬에서 돌리려면:\n"
            f"  docker run -d --rm --name ib-audit-test -p 5442:5432 \\\n"
            f"    -e POSTGRES_DB=audittest -e POSTGRES_USER=audit "
            f"-e POSTGRES_PASSWORD=audit postgres:16-alpine\n"
            f"  {DSN_ENV}=postgresql+psycopg://audit:audit@localhost:5442/audittest pytest\n"
            f"\n"
            f"끄려면 SKIP_AUDIT_DB_TESTS=1 을 명시할 것 (조용한 skip 은 없다)."
        )
    engine = create_engine(dsn)
    # 서비스가 기동 시 하는 것과 같은 경로로 테이블을 만든다.
    from app.audit import _SCHEMA_SQL

    assert apply_schema_sql(engine, _SCHEMA_SQL), "감사 스키마 적용 실패"
    return engine


@pytest.fixture()
def audit_log(audit_engine, monkeypatch):
    """감사 저장소를 테스트 DB 로 바꿔 끼운다."""
    import app.audit as audit_mod

    session_factory = sessionmaker(bind=audit_engine)
    log = SqlAlchemyAgentAuditLog(session_factory, agent_name=audit_mod.AGENT_NAME)
    monkeypatch.setattr(audit_mod, "_audit_log", log)
    return log


def _new_session_id() -> str:
    # 공유 DB 라 다른 테스트가 남긴 행이 보인다. 전역 건수를 세지 않고
    # 내가 만든 것만 조회한다.
    return str(uuid.uuid4().int % 10**9)


# ─────────────────────────────────────────────────────────────────────────────
# 기록이 실제로 남는가
# ─────────────────────────────────────────────────────────────────────────────

def test_상담_한_턴이_감사에_남는다(audit_log):
    session_id = _new_session_id()
    answer = "정기예금 금리는 연 3.5% 입니다."

    record_chatbot_turn(
        chatbot_consultation_id=int(session_id),
        user_message="정기예금 금리 알려줘",
        answer=answer,
        process_method="FEATURE_RATE_GUIDE",
        agent_transfer_required=False,
    )

    saved = audit_log.find_latest(SUBJECT_TYPE, session_id)
    assert saved is not None, "상담 응답이 감사에 남지 않았다"

    output = json.loads(saved.output_json)
    assert output["answer"] == answer, "고객에게 나간 문장이 그대로 남아야 한다"
    assert output["process_method"] == "FEATURE_RATE_GUIDE"


def test_한글_답변이_이스케이프되지_않는다(audit_log):
    session_id = _new_session_id()
    record_chatbot_turn(
        chatbot_consultation_id=int(session_id),
        user_message="q",
        answer="만기가 3개월 남았습니다.",
        process_method="SCENARIO",
        agent_transfer_required=False,
    )

    saved = audit_log.find_latest(SUBJECT_TYPE, session_id)
    # \uXXXX 로 저장되면 사후 조사에서 사람이 읽을 수 없다.
    assert "만기가 3개월" in saved.output_json


def test_상담사_이관도_남는다(audit_log):
    session_id = _new_session_id()
    record_chatbot_turn(
        chatbot_consultation_id=int(session_id),
        user_message="사람 바꿔주세요",
        answer="상담사 연결을 요청했습니다.",
        process_method="STAFF_REQUEST",
        agent_transfer_required=True,
    )

    output = json.loads(audit_log.find_latest(SUBJECT_TYPE, session_id).output_json)
    assert output["agent_transfer_required"] is True


def test_감사_기록은_수정할_수_없다(audit_engine, audit_log):
    session_id = _new_session_id()
    record_chatbot_turn(
        chatbot_consultation_id=int(session_id),
        user_message="q",
        answer="a",
        process_method="SCENARIO",
        agent_transfer_required=False,
    )

    with pytest.raises(Exception) as exc:
        with audit_engine.begin() as conn:
            conn.execute(text(
                "UPDATE harness_audit_log SET output_json = '{}'::jsonb "
                "WHERE subject_id = :sid"
            ), {"sid": session_id})
    assert "추가만 가능" in str(exc.value)


# ─────────────────────────────────────────────────────────────────────────────
# 배선 — handle_message 가 정말 감사를 부르는가
#
# 위 테스트들은 record_chatbot_turn 을 직접 부른다. 그것만으로는 누가
# handle_message 안의 호출을 지워도 잡히지 않는다. 여기서 그 연결을 확인한다.
# ─────────────────────────────────────────────────────────────────────────────

def test_handle_message_가_감사를_남긴다(service, audit_log):
    # pytest-asyncio 를 쓰지 않는다. 이 레포의 기존 테스트와 같은 방식으로
    # asyncio.run 을 쓴다 — @pytest.mark.asyncio 를 붙이면 플러그인이 없어
    # 테스트가 조용히 실행되지 않는다(초록인데 아무것도 검증 안 하는 상태).
    service.seed_default_scenario()

    async def run_flow():
        started = await service.start("CUST001", "HOME", "0.1.0")
        await service.handle_message(
            started.chatbot_consultation_id,
            "금융상품 상담",
            "PRODUCT_ADVICE",
        )
        return started

    started = asyncio.run(run_flow())

    saved = audit_log.find_latest(SUBJECT_TYPE, str(started.chatbot_consultation_id))
    assert saved is not None, (
        "handle_message 가 감사를 남기지 않았다 — 배선이 끊겼다"
    )
    assert saved.agent_name == "consultation"
    assert json.loads(saved.output_json)["answer"], "답변 본문이 비어 있다"


def test_계약_필드가_그대로_저장된다(audit_log):
    """계약이 정한 필드가 왕복하는지. 하네스 쪽 대조 테스트와 짝이다."""
    session_id = _new_session_id()
    audit_log.record(AgentAuditEntry(
        agent_name="consultation",
        subject_type=SUBJECT_TYPE,
        subject_id=session_id,
        trace_id="trace-xyz",
        request_json=AgentAuditEntry.json_of({"message": "q"}),
        output_json=AgentAuditEntry.json_of({"answer": "a"}),
    ))

    saved = audit_log.find_latest(SUBJECT_TYPE, session_id)
    assert saved.trace_id == "trace-xyz", "감사와 추적을 잇는 유일한 연결고리다"
    assert saved.decision_kind == "DECISION"
    assert saved.actor_id is None, "사람이 개입하지 않은 판단은 행위자가 비어 있다"
