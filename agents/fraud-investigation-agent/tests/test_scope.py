"""조사 스코프 — 에이전트가 볼 수 있는 범위가 사건에 묶여 있는가.

**무엇을 지키려는 테스트인가.** 예전에도 값은 맞았다. 도구가 볼 대상을 사건의
알림에서 가져왔으니 다른 고객이 조회되지는 않았다. 다만 그것이 *규칙이 강제돼서*가
아니라 `graph.py` 와 `api.py` 의 **두 줄이 우연히 같아서**였다.

그래서 여기 테스트는 "지금 다른 고객이 조회되나" 를 묻지 않는다. 그건 지금도
아니었다. 대신 **범위를 벗어나려는 시도가 거절되는가** 를 묻는다. 도구 호출을 진짜
tool-calling 으로 바꾸는 날, 이 테스트가 있어야 경계가 살아남는다.
"""

from __future__ import annotations

import pytest

from agent import tools as tools_mod
from agent.models import Alert, TxContext
from agent.scope import ACCOUNT_TOOLS, CaseScope, OutOfScopeError
from agent.tools import call_tool, load_case

OTHER_CUSTOMER = "CUST-9999"
OTHER_ACCOUNT = "110-777-7007"


def _alert() -> Alert:
    return Alert(
        id="ALT-SCOPE",
        account="110-111-1001",
        customer_id="CUST-1001",
        tx_context=TxContext(amount=1_000_000, channel="MOBILE"),
    )


class TestScopeShape:
    def test_알림에서_범위가_정해진다(self):
        scope = CaseScope.of(_alert())
        assert scope.customer_id == "CUST-1001"
        assert scope.accounts == frozenset({"110-111-1001"})

    def test_만든_뒤에는_넓힐_수_없다(self):
        # 조사 도중 범위가 늘어나면 그 순간부터 경계가 없는 것과 같다.
        scope = CaseScope.of(_alert())
        with pytest.raises(Exception):
            scope.customer_id = OTHER_CUSTOMER  # type: ignore[misc]

    def test_도구_종류에_따라_대상이_다르다(self):
        scope = CaseScope.of(_alert())
        assert scope.subject_for("get_party") == "CUST-1001"
        assert scope.subject_for("get_device_fingerprint") == "110-111-1001"

    def test_계좌_도구_목록이_한_곳에만_있다(self):
        # 예전에는 graph.py 가 자기 사본을 들고 있었다. 두 벌이면 한쪽만 바뀐다.
        assert "get_device_fingerprint" in ACCOUNT_TOOLS
        assert "get_related_accounts" in ACCOUNT_TOOLS
        assert "get_party" not in ACCOUNT_TOOLS


class TestRefusal:
    """범위 밖 요구는 거절된다. 이것이 이 파일의 핵심이다."""

    def test_남의_고객번호는_거절된다(self):
        scope = CaseScope.of(_alert())
        with pytest.raises(OutOfScopeError):
            scope.check("get_party", OTHER_CUSTOMER)

    def test_남의_계좌도_거절된다(self):
        scope = CaseScope.of(_alert())
        with pytest.raises(OutOfScopeError):
            scope.check("get_device_fingerprint", OTHER_ACCOUNT)

    def test_거절은_조회_실패와_다른_예외다(self):
        # "없다"(ValueError)와 "봐서는 안 된다"(PermissionError)는 감사에서 뜻이 다르다.
        scope = CaseScope.of(_alert())
        with pytest.raises(PermissionError):
            scope.check("get_party", OTHER_CUSTOMER)

    def test_고객_도구에_계좌번호를_넣어도_거절된다(self):
        # 축을 헷갈리면 남의 것을 보게 된다. 값이 이 사건 것이어도 축이 틀리면 안 된다.
        scope = CaseScope.of(_alert())
        with pytest.raises(OutOfScopeError):
            scope.check("get_party", "110-111-1001")


class TestCallTool:
    """호출부가 대상을 만들지 못하게 하는 관문."""

    def test_대상을_안_넘기면_스코프가_정해_준다(self):
        case = load_case("case_h1")
        scope = CaseScope.of(case.alert)
        result = call_tool(case, "get_party", scope)
        assert result.tool == "get_party"

    def test_범위_밖_대상을_넘기면_도구가_실행되지_않는다(self, monkeypatch):
        # 거절이 "부르고 나서 버리는" 것이면 이미 조회가 나간 뒤다.
        called: list = []
        monkeypatch.setitem(
            tools_mod.TOOLS, "get_party", lambda c, i: called.append(i)
        )
        case = load_case("case_h1")
        scope = CaseScope.of(case.alert)

        with pytest.raises(OutOfScopeError):
            call_tool(case, "get_party", scope, subject=OTHER_CUSTOMER)
        assert called == [], "거절했는데 도구가 이미 실행됐다"

    def test_모르는_도구는_KeyError_다(self):
        case = load_case("case_h1")
        with pytest.raises(KeyError):
            call_tool(case, "get_secret_sauce", CaseScope.of(case.alert))


class TestLoopsUseTheSamePath:
    def test_두_루프가_각자_대상을_계산하지_않는다(self):
        # 예전에는 같은 한 줄이 graph.py 와 api.py 에 각각 있었다. 한쪽만 고치면
        # 다른 쪽이 조용히 옛 규칙으로 남는다. 소스에서 그 계산이 사라졌는지 본다.
        from pathlib import Path

        src = Path(__file__).resolve().parents[1] / "src" / "agent"
        for name in ("graph.py", "api.py"):
            text = (src / name).read_text(encoding="utf-8")
            assert "state.alert.account if tool in" not in text, (
                f"{name} 이 대상을 직접 계산한다 — call_tool 을 쓸 것"
            )
