"""사기조사 감사 기록 배선 — 하네스 계약의 두 번째 언어 검증 (5단계).

이 파일이 5단계의 시험대다. Java 기준으로 뽑은 계약이 **DB 도 ORM 도 없는**
Python 사이드카에서 성립하는지 여기서 판명된다.
결과는 docs/decisions/agent-harness-consolidation.md 5단계에 적었다.

기록 지점이 둘이다. 다른 에이전트는 하나였다.

1. **조사 종료** — 에이전트가 무엇을 권고했는가
2. **승인 후 실행** — 사람이 승인해서 무엇이 실행됐는가

둘을 한 건으로 합칠 수 없다. 권고와 실행 사이에 사람이 있고, 그 사이에서
거부될 수 있기 때문이다. 권고만 남기면 "그래서 실제로 지급정지가 걸렸는가"를
알 수 없고, 실행만 남기면 "무슨 근거로 그랬는가"를 알 수 없다.

이 두 지점이 계약에 구멍 두 개를 드러냈고(행위자 자리 없음, 한 대상에 판단 하나
가정), 계약을 확장해 메웠다. 그래서 지금은 ``decision_kind`` 로 둘을 구분하고
행위자는 컬럼에 들어간다.
"""

from __future__ import annotations

import logging
import os

from harness_core import AgentAuditEntry, NoOpAgentAuditLog
from harness_core.audit_psycopg import PsycopgAgentAuditLog

logger = logging.getLogger(__name__)

AGENT_NAME = "fraud-investigation"

#: 무엇에 대한 판단인가. 도메인 식별자는 하네스가 아니라 이쪽이 정한다.
SUBJECT_TYPE = "FRAUD_CASE"

#: 에이전트가 무엇을 권고했는가 (사람 개입 전).
KIND_RECOMMENDATION = "RECOMMENDATION"

#: 사람 승인 뒤 무엇이 실행됐는가. 권고와 합칠 수 없다 — 사이에서 거부될 수 있다.
KIND_ACTION_EXECUTION = "ACTION_EXECUTION"

#: 접속 문자열이 없으면 감사를 끈다. 이 에이전트는 DB 없이 도는 데모 경로가 있어
#: (목 도구 + 인메모리 체크포인터) DSN 을 강제하면 그 경로가 죽는다.
_DSN_ENV = "FRAUD_AUDIT_DSN"

_audit_log = None


def get_audit_log():
    """감사 저장소. DSN 이 있으면 실제 저장소, 없으면 NoOp.

    **없을 때 None 을 돌려주지 않는다.** 기능을 끄는 것이 부르는 쪽을 깨뜨리면 안 된다.
    """
    global _audit_log
    if _audit_log is None:
        dsn = os.getenv(_DSN_ENV, "").strip()
        if dsn:
            _audit_log = PsycopgAgentAuditLog(dsn, agent_name=AGENT_NAME)
        else:
            logger.warning(
                "%s 미설정 — 사기조사 권고가 남지 않는다 (데모 경로에서는 정상)", _DSN_ENV
            )
            _audit_log = NoOpAgentAuditLog()
    return _audit_log


def record_investigation(state, *, case_name: str | None = None) -> None:
    """조사 결과(권고)를 남긴다.

    ``tool_calls_json`` 에 tool_log 를 그대로 담는다. 사기조사에서는 결론보다
    **어떤 도구를 왜 그 순서로 불렀는가**가 더 자주 문제가 된다 — 조사 경로가
    편향돼 있으면 결론이 옳아 보여도 근거가 없기 때문이다.
    """
    rec = getattr(state, "recommendation", None)
    entry = AgentAuditEntry(
        agent_name=AGENT_NAME,
        subject_type=SUBJECT_TYPE,
        subject_id=str(state.alert.id),
        # 권고는 사람 개입 전이라 행위자가 없다. 그것도 사실이라 NULL 로 남는다.
        decision_kind=KIND_RECOMMENDATION,
        request_json=AgentAuditEntry.json_of({
            "case": case_name,
            "alert": state.alert.model_dump(mode="json"),
        }),
        output_json=AgentAuditEntry.json_of({
            "recommendation": rec.model_dump(mode="json") if rec is not None else None,
            "scenarios": {k.value if hasattr(k, "value") else str(k): v
                          for k, v in (state.scenarios or {}).items()},
            "decisive_fact": (
                state.decisive_fact.model_dump(mode="json") if state.decisive_fact else None
            ),
            "budget_left": state.budget_left,
        }),
        tool_calls_json=AgentAuditEntry.json_of(
            [t.model_dump(mode="json") for t in (state.tool_log or [])]
        ),
        # 이 값이 있어야 감사 기록에서 그 실행의 추적으로 건너갈 수 있다.
        # 추적이 꺼져 있으면 None 이고, NULL 이 "추적이 없다" 는 사실을 말한다.
        trace_id=getattr(state, "trace_id", None),
    )
    get_audit_log().record(entry)


def record_action_execution(
    *,
    alert_id: str,
    approved: bool,
    actor_roles: list[str],
    executed_actions: list[str],
    actor_id: str | None = None,
    claimed_roles: list[str] | None = None,
    thread_id: str | None = None,
    trace_id: str | None = None,
) -> None:
    """승인 뒤 무엇이 실행됐는지 남긴다.

    ``decision_kind`` 로 권고와 구분한다. 이것이 없으면 "이 사건의 권고를 다오"에
    실행 기록이 돌아온다.

    **``actor_id``·``actor_roles`` 컬럼에는 검증된 신원만 들어간다.**
    게이트웨이가 JWT 에서 주입했다고 확인된 값만이다. 확인할 수 없는 주장은
    ``claimed_roles`` 로 받아 ``request_json`` 에 넣는다 — 기록은 남기되
    컬럼과 섞지 않는다. 섞으면 나중에 기록을 읽는 사람이 검증된 것과 자칭한 것을
    구별할 수 없고, 그러면 컬럼 전체의 신뢰도가 자칭 수준으로 떨어진다.
    """
    entry = AgentAuditEntry(
        agent_name=AGENT_NAME,
        subject_type=SUBJECT_TYPE,
        subject_id=str(alert_id),
        decision_kind=KIND_ACTION_EXECUTION,
        actor_id=actor_id,
        actor_roles=AgentAuditEntry.json_of(actor_roles),
        # 추적 id 다. 예전에는 여기에 LangGraph 스레드 id 를 넣었는데, 그 값으로는
        # Phoenix 의 어떤 추적도 찾을 수 없다 — 이어지지 않는 링크가 이어지는 것처럼
        # 보였다. 스레드 id 는 아래 request_json 에 남긴다(그쪽 용도로는 유효하다).
        trace_id=trace_id,
        request_json=AgentAuditEntry.json_of({
            "approved": approved,
            # 검증되지 않은 자칭 역할. 컬럼이 아니라 여기 남는 것 자체가 신뢰수준 표시다.
            "claimed_roles": claimed_roles or [],
            "identity_verified": actor_id is not None,
            "thread_id": thread_id,
        }),
        output_json=AgentAuditEntry.json_of({
            "approved": approved,
            "executed_actions": executed_actions,
        }),
    )
    get_audit_log().record(entry)
