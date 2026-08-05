"""에이전트 감사 기록 — Python 쪽 계약.

Java 의 ``com.bank.harness.audit`` 와 **같은 테이블에 같은 필드로** 쓴다.
라이브러리를 공유할 수 없으므로 (JVM ↔ CPython) 공유되는 것은 계약뿐이다:
테이블 스키마, 필드 이름, 필드 의미.

계약의 정본은 Java 도 Python 도 아니라 SQL 이다 —
``agents/harness-core/src/main/resources/db/harness/V001__harness_audit_log.sql``.
양쪽이 그 파일을 따르고, ``tests/test_audit_contract.py`` 가 실제로 그런지 확인한다.

이 모듈은 **표준 라이브러리만 쓴다.** fraud-investigation-agent 에는 DB 드라이버가
아예 없어서(langgraph·httpx 뿐) 여기에 SQLAlchemy 를 넣으면 그 에이전트는
계약을 쓰기 위해 DB 의존성을 얹어야 한다. 저장 구현은 ``audit_sqlalchemy`` 로 뺀다.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Protocol

__all__ = [
    "AgentAuditEntry",
    "AgentAuditLog",
    "NoOpAgentAuditLog",
    "KIND_DECISION",
]

#: 사람 개입 없이 에이전트가 내린 보통의 판단. Java 의 ``AgentAuditEntry.KIND_DECISION``.
KIND_DECISION = "DECISION"


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class AgentAuditEntry:
    """에이전트 판단 1건의 감사 기록.

    도메인 타입이 들어오지 않는다. 대출 심사의 ``rev_id`` 도 상담 세션 번호도
    ``subject_type`` + ``subject_id`` 문자열 쌍으로 들어온다. 이렇게 두지 않으면
    다른 도메인의 에이전트가 이 기록을 쓸 수 없고, 실제로 그래서 감사 로그가
    auto-loan-review 에만 있었다.

    필드 이름은 Java ``AgentAuditEntry`` 의 snake_case 대응이며
    테이블 컬럼명과 1:1 이다. 편의를 위해 이름을 바꾸면 계약이 깨진다.

    Attributes:
        agent_name: 판단 주체 (consultation, fraud-investigation, goal-agent …)
        subject_type: 무엇에 대한 판단인가 (CONSULT_SESSION, FRAUD_CASE …)
        subject_id: 그 대상의 식별자. 도메인마다 타입이 다르므로 문자열.
        trace_id: 같은 실행의 추적 식별자. **감사와 추적을 잇는 유일한 연결고리다.**
        request_json: 판단에 들어간 입력 스냅샷
        output_json: 판단 결과 — 챗봇이라면 고객에게 실제로 나간 답변
        tool_calls_json: 호출한 툴 목록
        raw_llm_response: LLM 원문. 추적에는 4,000자로 잘려 실리므로 전문은 여기에만 남는다.
        pii_masked: PII 마스킹 적용 여부
        fallback_reason: 모델 실패로 규칙 기반 결과를 쓴 경우 그 사유
        recorded_at: 기록 시각
        decision_kind: 판단의 종류. 한 대상에 판단이 여러 번 있을 수 있다 —
            사기조사는 사건 하나에 권고와 승인 후 실행이 따로 남고, 둘 사이에
            사람이 있어 합칠 수 없다. 기본값 ``DECISION``.
        actor_id: 이 기록을 만든 사람. 사람 승인을 받는 에이전트에서
            "누가 승인했는가"는 감사의 핵심이다.
            자율 판단(사람 개입 없음)이면 None 이며, 그것도 사실이라 남긴다.
        actor_roles: 그 사람의 권한 역할 (JSON 배열 문자열)
    """

    agent_name: str
    subject_type: str
    subject_id: str
    trace_id: str | None = None
    request_json: str = "{}"
    output_json: str = "{}"
    tool_calls_json: str = "[]"
    raw_llm_response: str | None = None
    pii_masked: bool = True
    fallback_reason: str | None = None
    recorded_at: datetime = field(default_factory=_utcnow)
    decision_kind: str = KIND_DECISION
    actor_id: str | None = None
    actor_roles: str = "[]"

    def __post_init__(self) -> None:
        # Java 쪽 컴팩트 생성자와 같은 보정. 빈 값이 JSONB 컬럼에 들어가면
        # NOT NULL 위반으로 감사 기록이 통째로 유실된다 — 판단은 성공했는데
        # 기록만 사라지는 것이 가장 나쁜 실패다.
        object.__setattr__(self, "request_json", _blank_to(self.request_json, "{}"))
        object.__setattr__(self, "output_json", _blank_to(self.output_json, "{}"))
        object.__setattr__(self, "tool_calls_json", _blank_to(self.tool_calls_json, "[]"))
        object.__setattr__(self, "decision_kind", _blank_to(self.decision_kind, KIND_DECISION))
        object.__setattr__(self, "actor_roles", _blank_to(self.actor_roles, "[]"))

    @staticmethod
    def json_of(value: Any) -> str:
        """dict·list 를 컬럼에 넣을 JSON 문자열로. 한글이 이스케이프되지 않게 한다."""
        return json.dumps(value, ensure_ascii=False, default=str)


def _blank_to(value: str | None, fallback: str) -> str:
    return fallback if value is None or not value.strip() else value


class AgentAuditLog(Protocol):
    """감사 기록 저장소.

    기록은 **추가만 가능**하다. 수정·삭제 경로를 두지 않는 것이 이 계약의 요점이다 —
    감사 기록을 고칠 수 있으면 그것은 감사가 아니다. DB 차원에서도 트리거로 막는다.
    """

    def record(self, entry: AgentAuditEntry) -> None:
        """판단 1건을 남긴다."""
        ...

    def find_latest(
        self, subject_type: str, subject_id: str, decision_kind: str | None = None
    ) -> AgentAuditEntry | None:
        """대상의 가장 최근 기록. ``decision_kind`` 를 주면 그 종류로 좁힌다.

        좁히지 않으면 언제나 마지막 기록이 나온다. 사기조사에서 실제로 그랬다 —
        권고를 물으면 실행 기록이 돌아왔다.
        """
        ...


class NoOpAgentAuditLog:
    """아무것도 기록하지 않는 구현.

    **선택이 아니라 필수다.** Java 쪽에서 감사를 끄자 빈이 사라져 의존하는 쪽이
    통째로 깨진 적이 있다. 기능을 끄는 것이 의존하는 쪽을 깨뜨리면 안 된다.
    감사 테이블이 아직 없는 초기 구축 단계와 단위 테스트를 위한 것이지,
    운영에서 쓰라는 뜻이 아니다.
    """

    def record(self, entry: AgentAuditEntry) -> None:
        return None

    def find_latest(
        self, subject_type: str, subject_id: str, decision_kind: str | None = None
    ) -> AgentAuditEntry | None:
        return None
