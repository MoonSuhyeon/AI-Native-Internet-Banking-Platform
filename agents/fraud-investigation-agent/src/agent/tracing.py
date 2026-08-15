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

**여기는 조사 도메인만 안다.** span 을 열고 값을 가리는 일은
``harness_core.tracing`` 이 한다. 이 파일에 남는 것은 "조사에서 무엇이 볼 값인가"
— 가설 분포, 남은 예산, 어떤 도구를 왜 골랐나 — 뿐이다. 에이전트마다 등록 로직을
따로 두면 그중 하나가 어긋나고, 어긋난 쪽이 조용히 안 남는다.

식별자만 여기서 가명으로 바꾼다. 무엇이 식별자인지는 도메인이 알고, 자유 텍스트
마스킹은 하네스가 span 에 붙이는 길목에서 일괄로 한다.

**켜지 않으면 아무 일도 없다.** ``PHOENIX_ENABLED`` 가 참일 때만 동작하고, 그 외에는
전부 no-op 이다. 실패도 조용히 삼킨다 — 관측은 보조라서 조사 루프를 멈추면 안 된다.
"""

from __future__ import annotations

import contextlib

from harness_core.pii import pseudonymize
from harness_core.tracing import SPAN_KIND, reset_for_test as _reset_for_test  # noqa: F401
from harness_core.tracing import get_tracer as _get_tracer  # noqa: F401
from harness_core.tracing import current_trace_id  # noqa: F401  (graph 가 쓴다)
from harness_core.tracing import span as _span

# 노드 이름 → span 종류. plan 은 LLM 이 도구를 고르고, act 는 도구를 부르고,
# observe 는 가설 분포를 갱신한다(LLM 아님).
_NODE_KIND = {"plan": "LLM", "act": "TOOL", "observe": "CHAIN"}

def _verdict(log) -> str | None:
    """마지막 도구 선택의 판정. 채점기가 없어도 추적은 돌아야 한다."""
    try:
        from .evaluation import score_tool_choice

        return score_tool_choice(log[-1], {e.tool for e in log[:-1]}).value
    except Exception:
        return None


# ── 속성 만들기 (순수 함수 — 조사에서 무엇이 볼 값인가) ──────────────────────


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
        # 이 선택이 예산을 판별에 썼는지. 결정적 채점이라 추적에 그대로 실을 수 있다
        # — LLM 심판이면 같은 trace 를 두 번 볼 때 값이 달라진다.
        attrs["eval.tool_choice"] = _verdict(log)

    return _clean(attrs)


def _clean(attrs: dict) -> dict:
    """None 을 버린다. 가리는 일은 하네스가 span 에 붙일 때 한다."""
    return {k: v for k, v in attrs.items() if v is not None}


# ── span 열기 ──────────────────────────────────────────────────────────────


@contextlib.contextmanager
def investigation_span(case, budget=None):
    """조사 1건을 감싸는 루트 span. 노드 span 이 이 아래로 붙는다."""
    with _span("investigate", "AGENT", investigation_attrs(case, budget)) as span:
        yield span


@contextlib.contextmanager
def trace_node(name: str, state=None):
    """노드 1회 실행. ``act`` 는 어떤 도구를 불렀는지 이름에 담는다."""
    label = name
    if name == "act":
        log = getattr(state, "tool_log", None) or []
        if log:
            label = f"act:{getattr(log[-1], 'tool', '') or ''}".rstrip(":")
    with _span(label, _NODE_KIND.get(name, "CHAIN"), node_attrs(name, state)) as span:
        yield span
