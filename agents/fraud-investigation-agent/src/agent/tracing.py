"""Langfuse 트레이싱 훅 (선택) — plan/act/observe 노드용.

셀프호스팅 전제. ``LANGFUSE_ENABLED=true`` + langfuse 설치 + 키가 있을 때만 동작하고,
그 외에는 전부 **no-op** 이다(패키지 import·테스트·기본 실행에 영향 없음).

graph.py 노드가 ``with trace_node("plan", state): ...`` 로 감싸 쓴다. 실패는 조용히
삼켜 조사 루프를 멈추지 않는다(모니터링은 보조 — fail-soft).
"""

from __future__ import annotations

import contextlib
import os


def _enabled() -> bool:
    return os.getenv("LANGFUSE_ENABLED", "false").lower() in ("1", "true", "yes")


def _client():
    try:
        from langfuse import Langfuse  # 지연 import

        return Langfuse(
            host=os.getenv("LANGFUSE_HOST"),
            public_key=os.getenv("LANGFUSE_PUBLIC_KEY"),
            secret_key=os.getenv("LANGFUSE_SECRET_KEY"),
        )
    except Exception:
        return None


@contextlib.contextmanager
def trace_node(name: str, state=None):
    """노드 1회 실행을 langfuse span 으로 감싼다(활성 시). 비활성/실패 시 no-op."""
    if not _enabled():
        yield None
        return
    lf = _client()
    if lf is None:
        yield None
        return
    span = None
    try:
        meta = {}
        if state is not None:
            meta = {
                "budget_left": getattr(state, "budget_left", None),
                "scenarios": {
                    s.value: round(v, 3) for s, v in getattr(state, "scenarios", {}).items()
                },
            }
        span = lf.span(name=name, metadata=meta)  # type: ignore[attr-defined]
    except Exception:
        span = None
    try:
        yield span
    finally:
        try:
            if span is not None:
                span.end()
            lf.flush()
        except Exception:
            pass
