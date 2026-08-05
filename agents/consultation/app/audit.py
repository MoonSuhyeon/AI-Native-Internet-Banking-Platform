"""상담 감사 기록 배선.

챗봇이 고객에게 무엇을 답했는지 남는 기록이 없었다. 대출심사에는 감사 로그가 12파일
있는데 상담·사기·목표 에이전트에는 0이었다 — 중복이 아니라 **부재**가 문제였다
(docs/decisions/agent-harness-consolidation.md 실측 2).

계약과 저장 구현은 harness_core 가 갖고 있다. 여기 있는 것은 배선뿐이다:
어느 저장소를 쓸지 고르고, 상담 도메인의 값을 계약이 정한 필드에 채워 넣는다.
"""

from __future__ import annotations

import logging
from pathlib import Path

from app.config import get_settings
from app.database import SessionLocal
from harness_core import AgentAuditEntry, NoOpAgentAuditLog
from harness_core.audit_sqlalchemy import SqlAlchemyAgentAuditLog
from harness_core.schema import apply_schema_sql

logger = logging.getLogger(__name__)

#: 감사 테이블 정의. harness-core 원본의 사본이며 대조 테스트가 지킨다.
#: 이미지에서는 /app/sql, 로컬에서는 레포의 같은 위치.
_SCHEMA_SQL = Path(__file__).resolve().parent.parent / "sql" / "harness-audit.sql"


def ensure_harness_audit_schema(engine) -> None:
    """감사 테이블을 기동 시 만든다.

    **왜 core-banking 마이그레이션이 아니라 여기인가.**
    이 테이블의 소유자는 consultation-service 다. core-banking 은 V12 에서
    챗봇·상담 테이블을 deposit-db 에서 걷어내면서 "소유자는 consultation-service 이고
    자기가 만든다"를 명시했고, V18 은 도메인 소유권 원칙("각 도메인 테이블은 소유
    서비스의 DB 에만 존재한다")을 못박았다. 감사 테이블을 남의 마이그레이션 체인에
    넣으면 그 둘이 되돌린 패턴을 다시 만드는 것이다.

    SQLAlchemy 모델로 만들지 않는 이유는 INSERT-ONLY 트리거 때문이다.
    기록을 고칠 수 없게 하는 것이 이 테이블의 존재 이유인데 모델로는 표현되지 않는다.

    실행 자체는 harness_core 가 한다 — 트리거 본문의 ``%`` 때문에 드라이버가
    파라미터로 오해하는 함정이 있어서, 그 처리를 에이전트마다 다시 알아내지 않게 했다.

    실패해도 기동을 막지 않는다. 감사가 없다고 상담이 멈추면 안 된다.
    """
    apply_schema_sql(engine, _SCHEMA_SQL)

AGENT_NAME = "consultation"

#: 무엇에 대한 판단인가. 도메인 식별자는 하네스가 아니라 이쪽이 정한다.
SUBJECT_TYPE = "CONSULT_SESSION"

_audit_log = None


def get_audit_log():
    """감사 저장소. 설정에 따라 실제 저장소 또는 NoOp.

    **끄더라도 None 을 돌려주지 않는다.** Java 쪽에서 감사를 끄자 빈이 사라져
    의존하는 쪽이 통째로 깨진 적이 있다. 기능을 끄는 것이 부르는 쪽을 깨뜨리면 안 된다.
    """
    global _audit_log
    if _audit_log is None:
        settings = get_settings()
        if settings.harness_audit_enabled:
            _audit_log = SqlAlchemyAgentAuditLog(SessionLocal, agent_name=AGENT_NAME)
        else:
            logger.warning("하네스 감사 기록이 꺼져 있다 — 상담 응답이 남지 않는다")
            _audit_log = NoOpAgentAuditLog()
    return _audit_log


def record_chatbot_turn(
    *,
    chatbot_consultation_id: int,
    user_message: str,
    answer: str,
    process_method: str,
    agent_transfer_required: bool,
    feature_code: str | None = None,
    trace_id: str | None = None,
) -> None:
    """챗봇 한 턴을 감사에 남긴다.

    ``output_json`` 에 고객에게 실제로 나간 문장을 그대로 담는다. 이것이
    이 기록의 존재 이유다 — "무엇을 답했는가"를 사후에 확인할 수 없으면
    상담 품질도 규제 대응도 근거가 없다.

    감사 실패가 고객 응답을 막지 않는다. 그러나 조용히 넘어가지도 않는다
    (harness_core 쪽에서 예외를 로그로 남긴다).
    """
    entry = AgentAuditEntry(
        agent_name=AGENT_NAME,
        subject_type=SUBJECT_TYPE,
        subject_id=str(chatbot_consultation_id),
        trace_id=trace_id,
        request_json=AgentAuditEntry.json_of({"message": user_message}),
        output_json=AgentAuditEntry.json_of({
            "answer": answer,
            "process_method": process_method,
            "agent_transfer_required": agent_transfer_required,
            "feature_code": feature_code,
        }),
    )
    get_audit_log().record(entry)
