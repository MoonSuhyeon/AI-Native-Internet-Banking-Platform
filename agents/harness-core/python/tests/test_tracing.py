"""추적 계약 — 무엇이 수집기로 나가는가.

핵심은 **기본값**이다. Langfuse 의 ``@observe`` 는 함수 인자와 반환값을 자동으로
실었고, 그 함수들이 받는 것은 고객 질문·문서·계좌 요약이었다. 관측을 켜는 일이
유출을 늘리는 일이 되면 안 되므로 여기서는 아무것도 자동으로 싣지 않는다.
"""

from __future__ import annotations

import pytest

from harness_core import tracing


class _FakeSpan:
    def __init__(self):
        self.attrs: dict = {}

    def set_attribute(self, key, value):
        self.attrs[key] = value


@pytest.fixture(autouse=True)
def _reset():
    tracing.reset_for_test()
    yield
    tracing.reset_for_test()


class TestSetAttributes:
    def test_자유_텍스트를_가려서_붙인다(self):
        span = _FakeSpan()
        tracing.set_attributes(span, {"harness.input": "010-1234-5678 로 연락"})
        assert "010-1234-5678" not in span.attrs["harness.input"]

    def test_중첩_구조도_가린다(self):
        # dict 를 문자열로 접기 전에 가려야 한다. 순서가 반대면 그대로 나간다.
        span = _FakeSpan()
        tracing.set_attributes(span, {"harness.input": {"tel": {"m": "010-1234-5678"}}})
        assert "010-1234-5678" not in span.attrs["harness.input"]

    def test_None_은_붙이지_않는다(self):
        # OpenTelemetry 는 None 속성을 거부한다. 넣으면 span 이 통째로 버려진다.
        span = _FakeSpan()
        tracing.set_attributes(span, {"a": None, "b": 1})
        assert span.attrs == {"b": 1}

    def test_숫자와_불린은_형태를_지킨다(self):
        # 전부 문자열로 접으면 Phoenix 에서 집계·정렬이 안 된다.
        span = _FakeSpan()
        tracing.set_attributes(span, {"n": 3, "f": 0.5, "b": True})
        assert span.attrs == {"n": 3, "f": 0.5, "b": True}

    def test_span_이_없으면_조용히_지나간다(self):
        tracing.set_attributes(None, {"a": 1})  # 예외가 나면 본 작업이 멈춘다


class TestObserve:
    def test_인자와_반환값을_자동으로_싣지_않는다(self):
        # Langfuse 의 같은 이름 데코레이터가 자동으로 실었고 그게 유출 경로였다.
        recorded: list = []

        @tracing.observe(name="llm-answer")
        def answer(question: str) -> str:
            recorded.append(question)
            return "주민번호는 900101-1234567 입니다"

        assert answer("김철수님 계좌 알려줘") == "주민번호는 900101-1234567 입니다"
        assert recorded == ["김철수님 계좌 알려줘"]  # 함수는 정상 동작

    def test_옛_인자를_받아도_깨지지_않는다(self):
        # capture_input 같은 Langfuse 인자를 호출부가 아직 넘긴다. 받아서 버린다.
        @tracing.observe(name="x", capture_input=False, capture_output=True)
        def f():
            return 1

        assert f() == 1

    def test_이름을_안_주면_함수_이름을_쓴다(self):
        @tracing.observe()
        def my_step():
            return "ok"

        assert my_step.__name__ == "my_step"
        assert my_step() == "ok"

    def test_예외는_그대로_올라간다(self):
        # 관측이 예외를 삼키면 장애가 조용해진다.
        @tracing.observe(name="boom")
        def f():
            raise ValueError("실패")

        with pytest.raises(ValueError):
            f()


class TestAsync:
    """비동기 함수도 감쌀 수 있어야 한다.

    동기 래퍼로 감싸면 span 이 코루틴을 만들자마자 닫힌다. 실제 작업은 그 밖에서
    돌아 아무것도 안 남고, 예외도 안 나서 조용히 비어 있게 된다. 상담의 턴 처리가
    async 라 이 자리가 실제로 필요하다.
    """

    def test_코루틴_함수를_감싸도_결과가_그대로다(self):
        import asyncio

        @tracing.observe(name="turn", kind="AGENT")
        async def handle(x):
            await asyncio.sleep(0)
            return x * 2

        assert asyncio.run(handle(21)) == 42

    def test_코루틴을_반환하지_않고_await_가능해야_한다(self):
        import asyncio
        import inspect

        @tracing.observe(name="turn")
        async def handle():
            return "ok"

        assert inspect.iscoroutinefunction(handle), "동기 래퍼로 감싸면 여기서 걸린다"
        assert asyncio.run(handle()) == "ok"

    def test_비동기_예외도_그대로_올라간다(self):
        import asyncio

        @tracing.observe(name="boom")
        async def f():
            raise ValueError("실패")

        with pytest.raises(ValueError):
            asyncio.run(f())


class TestDisabled:
    def test_켜지_않으면_tracer_가_없다(self, monkeypatch):
        monkeypatch.delenv("PHOENIX_ENABLED", raising=False)
        assert tracing.get_tracer() is None

    def test_꺼진_상태에서도_span_이_동작한다(self, monkeypatch):
        monkeypatch.delenv("PHOENIX_ENABLED", raising=False)
        with tracing.span("x") as s:
            assert s is None

    def test_꺼진_상태의_update_는_아무_일도_안_한다(self, monkeypatch):
        monkeypatch.delenv("PHOENIX_ENABLED", raising=False)
        tracing.update_current_span(input="010-1234-5678")
