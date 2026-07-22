"""Stage 7 — get_auth_events 실연결 토글 (HTTP 목, 네트워크 없음).

실 백엔드(customer-service)는 띄우지 않고, 토글 분기와 응답 매핑만 검증한다.
나머지 도구(🔴)는 토글과 무관하게 항상 목임도 확인.
"""

import agent.tools as tools
from agent.tools import get_auth_events, get_str_history, load_case


def test_default_is_mock(monkeypatch):
    monkeypatch.delenv("TRIAGE_REAL_TOOLS", raising=False)
    monkeypatch.delenv("TRIAGE_BACKEND_URL", raising=False)
    case = load_case("case_h2")  # 목: recent_cert_fail=3
    res = get_auth_events(case, case.alert.customer_id)
    assert res.data["recent_cert_fail"] == 3
    assert res.data.get("_source") != "real"


def test_real_toggle_calls_backend(monkeypatch):
    # 실연결 활성 + fetch 를 가짜로 대체(httpx 미사용)
    monkeypatch.setenv("TRIAGE_REAL_TOOLS", "get_auth_events")
    monkeypatch.setenv("TRIAGE_BACKEND_URL", "http://localhost:8081")
    monkeypatch.setattr(
        tools,
        "_fetch_auth_events_real",
        lambda cid: {
            "recent_cert_fail": 7,
            "password_changed_recently": True,
            "events": [],
            "_source": "real",
        },
    )
    case = load_case("case_h2")  # 케이스 목값(3)이 아니라 실값(7)이 와야 함
    res = get_auth_events(case, case.alert.customer_id)
    assert res.data["_source"] == "real"
    assert res.data["recent_cert_fail"] == 7
    assert "인증실패 7회" in res.signal  # 신호도 실값 반영


def test_only_auth_events_is_real(monkeypatch):
    # 🔴 도구는 실연결 목록에 없으면 토글 무시하고 목 유지
    monkeypatch.setenv("TRIAGE_REAL_TOOLS", "get_auth_events")
    monkeypatch.setenv("TRIAGE_BACKEND_URL", "http://localhost:8081")
    assert tools._tool_is_real("get_auth_events") is True
    assert tools._tool_is_real("get_str_history") is False
    case = load_case("case_h1")
    # str_history 는 항상 케이스 목값
    assert get_str_history(case, case.alert.customer_id).data["past_str_reports"] == 4


def test_real_disabled_without_backend_url(monkeypatch):
    monkeypatch.setenv("TRIAGE_REAL_TOOLS", "get_auth_events")
    monkeypatch.delenv("TRIAGE_BACKEND_URL", raising=False)
    assert tools._tool_is_real("get_auth_events") is False  # URL 없으면 비활성
