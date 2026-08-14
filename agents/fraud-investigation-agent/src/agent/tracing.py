"""Phoenix(OpenInference) 트레이싱 — 조사 한 건을 하나의 트리로 남긴다.

**무엇이 달라졌나.** 이전 구현은 Langfuse 를 썼고, ``trace_node`` 가 호출될 때마다
클라이언트를 새로 만들었다. 그래서 ``plan`` · ``act`` · ``observe`` 가 **각각 루트
span** 이 됐다. 조사 한 건이 하나로 안 묶이니 "어떤 순서로 도구를 골랐나" 를 볼 수
없었다. 관측 도구를 붙이고도 볼 수 있는 게 없던 셈이다.

지금은 프로세스에서 tracer 를 **한 번만** 등록하고, 조사 1건을 루트로 노드를 자식으로
붙인다. Phoenix UI 에 이렇게 그려진다::

    investigate                    (AGENT)   ← 조사 1건 = session
    ├─ plan                        (LLM)     ← 어떤 도구를 왜 골랐나
    ├─ act:get_device_fingerprint  (TOOL)    ← 무엇을 불렀나
    ├─ observe                     (CHAIN)   ← 가설이 어떻게 움직였나
    └─ ... (예산 소진 또는 확정까지 반복)

**나가는 값은 전부 가린다.** 추적 저장소는 운영 DB 가 아니고 접근 통제도 다르다.
계측만 켜고 마스킹을 나중에 붙이면 그 사이 쌓인 trace 가 전부 PII 사본이 된다.
그래서 속성을 만드는 자리(:func:`investigation_attrs` · :func:`node_attrs`)에서
바로 가린다. 규칙은 ``harness_core.pii`` 에 있다 — 에이전트마다 각자 가리면 한 곳이
빠지고, 빠진 곳이 유출 경로로 남는다.

**켜지 않으면 아무 일도 없다.** ``PHOENIX_ENABLED`` 가 참일 때만 동작하고, 그 외에는
전부 no-op 이다. 실패도 조용히 삼킨다 — 관측은 보조라서 조사 루프를 멈추면 안 된다.
"""

from __future__ import annotations

import contextlib
import os
import threading

from harness_core.pii import pseudonymize, scrub

# OpenInference 가 정의한 span 종류. Phoenix UI 가 이 값에 따라 다르게 그린다.
SPAN_KIND = "openinference.span.kind"

# 노드 이름 → span 종류. plan 은 LLM 이 도구를 고르고, act 는 도구를 부르고,
# observe 는 가설 분포를 갱신한다(LLM 아님).
_NODE_KIND = {"plan": "LLM", "act": "TOOL", "observe": "CHAIN"}

_lock = threading.Lock()
_tracer = None
_init_done = False


def _enabled() -> bool:
    return os.getenv("PHOENIX_ENABLED", "false").lower() in ("1", "true", "yes")


def _get_tracer():
    """tracer provider 를 프로세스에서 한 번만 등록한다.

    이전 구현이 span 마다 클라이언트를 만들어 트리가 끊겼다. 여기서 캐시한다.
    """
    global _tracer, _init_done
    if _init_done:
        return _tracer
    with _lock:
        if _init_done:
            return _tracer
        _init_done = True
        if not _enabled():
            return None
        try:
            from phoenix.otel import register  # 지연 import — 미설치여도 패키지는 뜬다

            provider = register(
                project_name=os.getenv("PHOENIX_PROJECT", "fraud-investigation"),
                endpoint=os.getenv("PHOENIX_GRPC_ENDPOINT", "http://localhost:4317"),
                auto_instrument=False,  # 우리가 붙이는 span 만 쓴다
            )
            _tracer = provider.get_tracer(__name__)
        except Exception:
            _tracer = None  # 관측이 조사를 막지 않는다
        return _tracer


def _reset_for_test() -> None:
    """테스트에서 등록 캐시를 비운다. 운영 경로에서는 쓰지 않는다."""
    global _tracer, _init_done
    with _lock:
        _tracer = None
        _init_done = False


# ── 속성 만들기 (순수 함수 — 여기서 가린다) ─────────────────────────────────


def investigation_attrs(case, budget=None) -> dict:
    """조사 루트 span 의 속성.

    고객번호·계좌번호는 지우지 않고 **가명으로 바꾼다.** 지우면 "같은 고객의 조사
    3건" 을 묶을 수 없고, 그러면 추적을 켠 이유가 없어진다.
    """
    alert = getattr(case, "alert", None)
    attrs = {
        SPAN_KIND: "AGENT",
        "session.id": pseudonymize(getattr(alert, "id", None), "case"),
        "fraud.customer": pseudonymize(getattr(alert, "customer_id", None), "cust"),
        "fraud.account": pseudonymize(getattr(alert, "account", None), "acct"),
        "fraud.anomaly_score": getattr(alert, "anomaly_score", None),
        "fraud.budget": budget,
    }
    return _clean(attrs)


def node_attrs(name: str, state=None) -> dict:
    """노드 span 의 속성. 가설 분포와 남은 예산이 핵심이다.

    이 둘이 있어야 "왜 여기서 멈췄나"(확정 · 예산 소진 · 결정적 사실)를 나중에
    설명할 수 있다.
    """
    attrs = {SPAN_KIND: _NODE_KIND.get(name, "CHAIN")}
    if state is None:
        return _clean(attrs)

    attrs["fraud.budget_left"] = getattr(state, "budget_left", None)
    scenarios = getattr(state, "scenarios", None) or {}
    for scenario, value in scenarios.items():
        key = getattr(scenario, "value", scenario)
        attrs[f"fraud.scenario.{key}"] = round(float(value), 3)

    decisive = getattr(state, "decisive_fact", None)
    if decisive is not None:
        attrs["fraud.decisive"] = getattr(
            getattr(decisive, "kind", None), "value", str(decisive)
        )

    log = getattr(state, "tool_log", None) or []
    if name == "act" and log:
        last = log[-1]
        attrs["tool.name"] = getattr(last, "tool", None)
        attrs["fraud.tool_reason"] = getattr(last, "reason", None)

    return _clean(attrs)


def _clean(attrs: dict) -> dict:
    """None 을 버리고 남은 값을 전부 가린다.

    ``scrub`` 을 여기 한 곳에 두는 이유는, 속성이 늘 때마다 마스킹을 다시
    떠올리지 않아도 되게 하기 위해서다. 새 필드는 자동으로 이 경로를 지난다.
    """
    return {k: scrub(v) for k, v in attrs.items() if v is not None}


# ── span 열기 ──────────────────────────────────────────────────────────────


@contextlib.contextmanager
def _traced(name: str, attrs: dict):
    tracer = _get_tracer()
    if tracer is None:
        yield None
        return
    try:
        span_cm = tracer.start_as_current_span(name)
        span = span_cm.__enter__()
    except Exception:
        yield None
        return
    try:
        for key, value in attrs.items():
            span.set_attribute(key, value)
    except Exception:
        pass
    try:
        yield span
    except BaseException as exc:  # 조사 쪽 예외는 span 에 남기고 그대로 올린다
        with contextlib.suppress(Exception):
            span_cm.__exit__(type(exc), exc, exc.__traceback__)
        raise
    else:
        with contextlib.suppress(Exception):
            span_cm.__exit__(None, None, None)


@contextlib.contextmanager
def investigation_span(case, budget=None):
    """조사 1건을 감싸는 루트 span. 노드 span 이 이 아래로 붙는다."""
    with _traced("investigate", investigation_attrs(case, budget)) as span:
        yield span


@contextlib.contextmanager
def trace_node(name: str, state=None):
    """노드 1회 실행. ``act`` 는 어떤 도구를 불렀는지 이름에 담는다."""
    label = name
    if name == "act":
        log = getattr(state, "tool_log", None) or []
        if log:
            label = f"act:{getattr(log[-1], 'tool', '') or ''}".rstrip(":")
    with _traced(label, node_attrs(name, state)) as span:
        yield span
