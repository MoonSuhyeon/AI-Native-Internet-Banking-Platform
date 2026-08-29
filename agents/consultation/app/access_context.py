"""이 요청의 행위자를 아래 계층까지 들고 간다.

**왜 필요한가.** 고객 데이터를 읽는 core-banking 내부 API 는 행위자를 요구한다 —
직원 대리 조회면 ``X-Employee-Id`` 와 조회 사유, 고객 본인 조회면 ``X-Customer-Id``.
그런데 기능 실행기는 FastAPI 의 ``Request`` 를 보지 못하고 Pydantic 스키마만 받는다.

**왜 인자로 넘기지 않는가.** 실행기 → 헬퍼 → 클라이언트로 이어지는 호출이 깊어,
행위자를 인자로 흘리려면 수십 개 시그니처를 바꿔야 한다. 그러다 한 군데를 빠뜨리면
그 경로만 조용히 신원 없이 나가고, 그것은 <b>거절이 아니라 누락</b>으로 나타난다.

**대신 요청 범위 컨텍스트를 쓴다.** 신원을 읽는 곳은 ``identity`` 한 곳이고,
여기에 담는 곳도 요청 진입점 한 곳이다. 담기지 않으면 헤더가 안 붙고,
core-banking 은 행위자 없는 조회를 거절한다 — 빠뜨리면 조용히 통과하는 것이 아니라
막힌다. 그것이 이 구조를 고른 이유다.
"""
from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass


@dataclass(frozen=True)
class AccessContext:
    """이 요청의 행위자와 조회 맥락."""

    customer_no: str | None = None
    employee_id: str | None = None
    reason: str | None = None
    trace_id: str | None = None

    def headers(self) -> dict[str, str]:
        """core-banking 내부 API 에 실어 보낼 헤더."""
        h: dict[str, str] = {}
        if self.employee_id:
            h["X-Employee-Id"] = str(self.employee_id)
        if self.customer_no:
            h["X-Customer-Id"] = str(self.customer_no)
        if self.reason:
            h["X-Access-Reason"] = self.reason
        if self.trace_id:
            h["X-Trace-Id"] = self.trace_id
        return h


_EMPTY = AccessContext()
_current: ContextVar[AccessContext] = ContextVar("consultation_access_context", default=_EMPTY)


def set_context(ctx: AccessContext) -> None:
    _current.set(ctx)


def current() -> AccessContext:
    return _current.get()


def clear() -> None:
    _current.set(_EMPTY)


def for_customer(customer_no: str | None, trace_id: str | None = None) -> AccessContext:
    """고객 본인 조회. 사유를 요구하지 않는다 — 자기 것을 보는 데 사유는 없다."""
    return AccessContext(customer_no=customer_no, trace_id=trace_id)


def for_employee(employee_id: str | None, reason: str, trace_id: str | None = None) -> AccessContext:
    """직원 대리 조회. 사유가 반드시 있어야 한다."""
    return AccessContext(employee_id=employee_id, reason=reason, trace_id=trace_id)
