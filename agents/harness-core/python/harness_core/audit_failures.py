"""감사 기록 실패를 알리는 자리.

**왜 필요한가.** 저장 구현은 감사 실패를 fail-soft 로 처리한다 — 기록이 안 남았다고
고객 응답이나 조사를 막으면 안 되기 때문이다. 그래서 지금까지 남는 것은 로그 한 줄뿐이었고,
로그 한 줄은 아무도 보지 않는다. 감사가 조용히 멈춰도 알아챌 방법이 없었다
(docs/decisions/agent-harness-consolidation.md 다음 순서 2).

**왜 여기서 직접 메트릭을 올리지 않는가.** 이 패키지는 표준 라이브러리만 쓴다.
fraud-investigation-agent 에는 DB 드라이버도 prometheus_client 도 없다. 여기에
prometheus_client 를 넣으면 그 에이전트는 계약을 쓰기 위해 관측 라이브러리를 얹어야 한다.
그래서 여기 두는 것은 **알림 지점**뿐이고, 무엇으로 셀지는 각 에이전트가 정한다.

사용:

    from harness_core import add_audit_failure_listener

    def _count(entry, exc):
        my_counter.labels(agent=entry.agent_name, kind=entry.decision_kind).inc()

    add_audit_failure_listener(_count)
"""

from __future__ import annotations

import logging
from typing import Any, Callable

logger = logging.getLogger(__name__)

#: (entry, exc) 를 받는다. 반환값은 쓰지 않는다.
AuditFailureListener = Callable[[Any, BaseException], None]

_listeners: list[AuditFailureListener] = []


def add_audit_failure_listener(listener: AuditFailureListener) -> None:
    """감사 기록 실패 알림을 받을 함수를 등록한다.

    같은 함수를 두 번 등록하지 않는다 — 모듈이 두 번 임포트되는 경로에서
    같은 실패가 두 번 세어지면 지표를 믿을 수 없게 된다.
    """
    if listener not in _listeners:
        _listeners.append(listener)


def remove_audit_failure_listener(listener: AuditFailureListener) -> None:
    """등록을 지운다. 없으면 조용히 넘어간다.

    되돌리기(``audit_spool.FileAuditSpool.replay``)처럼 잠깐만 붙였다 떼는 쓰임이 있다.
    """
    if listener in _listeners:
        _listeners.remove(listener)


def clear_audit_failure_listeners() -> None:
    """등록을 모두 지운다. 테스트 격리용."""
    _listeners.clear()


def notify_audit_failure(entry: Any, exc: BaseException) -> None:
    """등록된 곳에 실패를 알린다.

    **여기서 예외가 새어 나가지 않는다.** 감사 실패를 세는 일이 실패해서 본래 흐름을
    무너뜨리면, 감사를 fail-soft 로 만든 이유가 통째로 사라진다.
    """
    for listener in list(_listeners):
        try:
            listener(entry, exc)
        except Exception:
            logger.exception("감사 실패 알림 처리 중 오류 listener=%r", listener)
