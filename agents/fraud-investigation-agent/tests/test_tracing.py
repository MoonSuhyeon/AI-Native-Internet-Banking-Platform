"""추적 계약 — 무엇이 Phoenix 로 나가고, 무엇이 나가면 안 되는가.

이 테스트가 지키는 것 둘.

1. **원본 식별자가 추적 저장소로 나가지 않는다.** 추적 저장소는 운영 DB 가 아니고
   접근 통제도 다르다. 마스킹을 빼면 여기가 실패한다.
2. **끄면 아무 일도 없다.** 관측은 보조라서 조사 루프를 멈추면 안 된다.

Phoenix 나 opentelemetry 가 깔려 있지 않아도 돌아야 한다. 그래서 속성을 만드는
순수 함수를 직접 검사한다 — 실제로 가리는 곳이 거기다.
"""

from __future__ import annotations

import pytest

from agent import tracing
from agent.models import Alert, AttackScenario, Case, DecisiveFact, DecisiveFactKind, TxContext


@pytest.fixture(autouse=True)
def _fixed_salt(monkeypatch):
    monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
    tracing._reset_for_test()
    yield
    tracing._reset_for_test()


def _case() -> Case:
    return Case(
        name="H1_보이스피싱",
        alert=Alert(
            id="ALERT-9001",
            account="110-234-567890",
            customer_id="9111",
            tx_context=TxContext(amount=8_500_000, channel="MOBILE"),
            anomaly_score=0.87,
        ),
    )


class TestInvestigationAttrs:
    def test_고객번호와_계좌번호가_원본으로_나가지_않는다(self):
        attrs = tracing.investigation_attrs(_case(), budget=6)

        flat = repr(attrs)
        assert "9111" not in flat, "고객번호가 그대로 나갔다"
        assert "110-234-567890" not in flat, "계좌번호가 그대로 나갔다"
        assert "ALERT-9001" not in flat, "알림 ID 가 그대로 나갔다"

    def test_같은_고객은_같은_가명이라_묶을_수_있다(self):
        # 지우지 않고 가명으로 바꾸는 이유. 지우면 "같은 고객의 조사 3건" 을 못 묶는다.
        a = tracing.investigation_attrs(_case(), budget=6)
        b = tracing.investigation_attrs(_case(), budget=3)
        assert a["fraud.customer"] == b["fraud.customer"]

    def test_조사에_필요한_값은_남긴다(self):
        # 전부 가리면 추적을 켠 의미가 없다. 금액·점수·예산은 식별자가 아니다.
        attrs = tracing.investigation_attrs(_case(), budget=6)
        assert attrs[tracing.SPAN_KIND] == "AGENT"
        assert attrs["fraud.anomaly_score"] == 0.87
        assert attrs["fraud.budget"] == 6


class TestNodeAttrs:
    def test_노드마다_span_종류가_다르다(self):
        # Phoenix UI 가 이 값으로 트리를 그린다. 전부 CHAIN 이면 도구 호출이 안 보인다.
        assert tracing.node_attrs("plan")[tracing.SPAN_KIND] == "LLM"
        assert tracing.node_attrs("act")[tracing.SPAN_KIND] == "TOOL"
        assert tracing.node_attrs("observe")[tracing.SPAN_KIND] == "CHAIN"

    def test_가설_분포와_남은_예산을_남긴다(self):
        # 이 둘이 있어야 "왜 여기서 멈췄나" 를 나중에 설명할 수 있다.
        class _S:
            budget_left = 4
            scenarios = {AttackScenario.H1_VOICE_PHISHING: 0.6421}
            decisive_fact = None
            tool_log: list = []

        attrs = tracing.node_attrs("observe", _S())
        assert attrs["fraud.budget_left"] == 4
        assert attrs["fraud.scenario.H1_VOICE_PHISHING"] == 0.642

    def test_결정적_사실을_남긴다(self):
        # fail-closed 로 끝난 조사와 예산 소진으로 끝난 조사를 추적에서 갈라야 한다.
        class _S:
            budget_left = 5
            scenarios: dict = {}
            decisive_fact = DecisiveFact(kind=DecisiveFactKind.DEATH, source="get_party")
            tool_log: list = []

        assert "fraud.decisive" in tracing.node_attrs("observe", _S())

    def test_None_은_속성으로_내보내지_않는다(self):
        # OpenTelemetry 는 None 속성을 거부한다. 넣으면 span 이 통째로 버려진다.
        assert all(v is not None for v in tracing.node_attrs("plan").values())


class TestDisabledByDefault:
    def test_켜지_않으면_tracer_가_없다(self, monkeypatch):
        monkeypatch.delenv("PHOENIX_ENABLED", raising=False)
        assert tracing._get_tracer() is None

    def test_꺼진_상태에서도_컨텍스트가_동작한다(self, monkeypatch):
        # 관측이 조사를 멈추면 안 된다.
        monkeypatch.delenv("PHOENIX_ENABLED", raising=False)
        with tracing.investigation_span(_case(), 6) as root:
            assert root is None
            with tracing.trace_node("plan") as span:
                assert span is None

    def test_phoenix_가_없어도_켜기만_하면_죽지_않는다(self, monkeypatch):
        # 이미지에 패키지가 빠져도 조사는 돌아야 한다.
        monkeypatch.setenv("PHOENIX_ENABLED", "true")
        monkeypatch.setenv("PHOENIX_GRPC_ENDPOINT", "http://127.0.0.1:1")
        with tracing.investigation_span(_case(), 6):
            pass
