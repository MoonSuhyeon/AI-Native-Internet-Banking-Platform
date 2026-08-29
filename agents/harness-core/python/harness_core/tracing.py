"""추적 계약 — 에이전트가 무엇을 했는지 OTLP 로 남긴다.

**왜 하네스에 있나.** Java 쪽 ``com.bank.harness.trace`` 와 같은 자리다. 두 런타임이
같은 하네스를 구현한다 — 공유되는 것은 코드가 아니라 계약이다. 속성 이름을 표준
(OpenInference · GenAI 시맨틱)으로 쓰면 수집기를 바꿔도 코드가 그대로다.

**기본값을 뒤집었다.** Langfuse 의 ``@observe`` 는 함수 인자와 반환값을 **자동으로**
추적에 싣는다. 상담·목표 에이전트에 그게 걸려 있었고, 그 함수들이 받는 것은 고객
질문·문서·계좌 요약이다. 즉 켜는 순간 추적 저장소가 고객 데이터 사본이 됐다.

여기서는 **아무것도 자동으로 싣지 않는다.** 실을 것을 :func:`update_current_span`
으로 명시해야 하고, 그렇게 실은 값도 :mod:`harness_core.pii` 를 지난다. 관측을
켜는 일이 유출을 늘리는 일이 되지 않게 하려면 기본값이 이쪽이어야 한다.

가리는 것은 두 갈래다. 자유 텍스트는 정규식으로 치환하고, **이름이 식별자를 뜻하는
필드는 가명으로 바꾼다.** 고객번호는 정규식에 걸리지 않아 앞의 것만으로는 그대로
나갔다 — 무엇을 가릴지는 값이 아니라 필드 이름이 알려 준다.

**켜지 않으면 아무 일도 없다.** ``PHOENIX_ENABLED`` 가 참일 때만 동작하고 실패는
조용히 삼킨다. 관측은 보조라서 본 작업을 멈추면 안 된다.
"""

from __future__ import annotations

import contextlib
import functools
import inspect
import os
import threading

from .pii import scrub

# OpenInference 가 정의한 span 종류. Phoenix UI 가 이 값에 따라 다르게 그린다.
SPAN_KIND = "openinference.span.kind"

_lock = threading.Lock()
_tracer = None
_init_done = False


def enabled() -> bool:
    return os.getenv("PHOENIX_ENABLED", "false").lower() in ("1", "true", "yes")


def get_tracer():
    """tracer provider 를 프로세스에서 한 번만 등록한다.

    span 마다 클라이언트를 만들면 부모-자식이 끊겨 실행 한 건이 하나의 트리로
    묶이지 않는다. 그래서 여기서 캐시한다.
    """
    global _tracer, _init_done
    if _init_done:
        return _tracer
    with _lock:
        if _init_done:
            return _tracer
        _init_done = True
        if not enabled():
            return None
        try:
            from phoenix.otel import register  # 지연 import — 미설치여도 패키지는 뜬다

            provider = register(
                project_name=os.getenv("PHOENIX_PROJECT", "agents"),
                endpoint=os.getenv("PHOENIX_GRPC_ENDPOINT", "http://localhost:4317"),
                auto_instrument=False,  # 우리가 붙이는 span 만 쓴다
            )
            _tracer = provider.get_tracer("harness_core")
        except Exception:
            _tracer = None  # 관측이 본 작업을 막지 않는다
        return _tracer


def reset_for_test() -> None:
    """등록 캐시를 비운다. 운영 경로에서는 쓰지 않는다."""
    global _tracer, _init_done
    with _lock:
        _tracer = None
        _init_done = False


@contextlib.contextmanager
def span(name: str, kind: str = "CHAIN", attributes: dict | None = None):
    """span 하나를 연다. 꺼져 있거나 실패하면 ``None`` 을 넘긴다."""
    tracer = get_tracer()
    if tracer is None:
        yield None
        return
    try:
        cm = tracer.start_as_current_span(name)
        current = cm.__enter__()
    except Exception:
        yield None
        return
    set_attributes(current, {SPAN_KIND: kind, **(attributes or {})})
    try:
        yield current
    except BaseException as exc:  # 본 작업의 예외는 span 에 남기고 그대로 올린다
        with contextlib.suppress(Exception):
            cm.__exit__(type(exc), exc, exc.__traceback__)
        raise
    else:
        with contextlib.suppress(Exception):
            cm.__exit__(None, None, None)


def set_attributes(target, attributes: dict) -> None:
    """``None`` 을 버리고 남은 값을 가려서 span 에 붙인다.

    가리는 곳을 여기 하나로 모으는 이유는, 속성이 늘어날 때 마스킹을 다시 떠올리지
    않아도 새 필드가 자동으로 이 경로를 지나게 하기 위해서다.

    **속성 이름을 함께 넘긴다.** ``customer_id="9111"`` 은 어떤 정규식에도 걸리지
    않으므로, 무엇을 가려야 하는지는 값이 아니라 이름이 알려 준다. 이름을 넘기지
    않으면 ``harness.customer_id`` 처럼 최상위에 바로 놓인 식별자가 통째로 빠진다.
    """
    if target is None:
        return
    for key, value in attributes.items():
        if value is None:
            continue
        with contextlib.suppress(Exception):
            target.set_attribute(key, _flatten(scrub(value, key=key)))


def _flatten(value):
    """OpenTelemetry 가 받는 형태로 낮춘다. dict·list 는 문자열로 접는다."""
    if isinstance(value, (str, bool, int, float)):
        return value
    return repr(value)


def current_trace_id() -> str | None:
    """지금 열려 있는 추적의 id (32자리 hex). 없으면 ``None``.

    **감사 기록과 추적을 잇는 유일한 값이다.** 감사 로그에 이 값이 있어야 "이 권고가
    왜 나왔나" 를 물었을 때 그 실행의 도구 호출 순서까지 되짚을 수 있다. 없으면 감사는
    결론만 남고 과정은 사라진다.

    **span 이 닫힌 뒤에 부르면 ``None`` 이다.** 감사 기록은 보통 실행이 끝난 뒤에
    쓰이므로, 값을 쓸 자리가 아니라 **span 안에서 미리 받아 두어야** 한다.

    추적이 꺼져 있으면 ``None`` 이고, 그대로 남기는 것이 맞다. 다른 식별자(스레드 id
    같은 것)를 대신 넣으면 이어지지 않는 링크가 이어지는 것처럼 보인다.
    """
    try:
        from opentelemetry import trace as _trace

        ctx = _trace.get_current_span().get_span_context()
        if not ctx.is_valid:
            return None
        return format(ctx.trace_id, "032x")
    except Exception:
        return None


def update_current_span(*, input=None, output=None, metadata: dict | None = None) -> None:
    """지금 열려 있는 span 에 값을 덧붙인다 (가려서).

    Langfuse 의 ``langfuse_context.update_current_observation`` 자리를 대신한다.
    """
    tracer_off = get_tracer() is None
    if tracer_off:
        return
    try:
        from opentelemetry import trace as _trace

        current = _trace.get_current_span()
    except Exception:
        return
    attrs = {"harness.input": input, "harness.output": output}
    for key, value in (metadata or {}).items():
        attrs[f"harness.{key}"] = value
    set_attributes(current, attrs)


def observe(name: str | None = None, *, kind: str = "CHAIN", **_ignored):
    """함수 한 번 실행을 span 으로 감싼다.

    **인자와 반환값을 싣지 않는다.** Langfuse 의 같은 이름 데코레이터는 자동으로
    실었고 그것이 유출 경로였다. 실을 것은 :func:`update_current_span` 으로 명시한다.

    ``capture_input`` 같은 옛 인자는 받아서 버린다 — 호출부를 한 번에 다 고치지
    않아도 되게. 어차피 아무것도 자동 수집하지 않으므로 의미가 없다.
    """

    def decorate(fn):
        label = name or getattr(fn, "__name__", "span")

        # 비동기 함수를 동기 래퍼로 감싸면 span 이 코루틴을 **만들자마자** 닫힌다.
        # 실제 작업은 그 밖에서 돌아 아무것도 안 남고, 예외도 안 나서 조용하다.
        if inspect.iscoroutinefunction(fn):

            @functools.wraps(fn)
            async def async_wrapper(*args, **kwargs):
                with span(label, kind):
                    return await fn(*args, **kwargs)

            return async_wrapper

        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            with span(label, kind):
                return fn(*args, **kwargs)

        return wrapper

    return decorate
