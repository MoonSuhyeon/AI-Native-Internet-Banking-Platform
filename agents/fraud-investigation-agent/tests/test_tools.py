"""Stage tools 테스트 — 8개 조회 도구가 케이스에서 목 응답을 읽는지 확인.

검증 초점(§16-4 분별력):
- case_h1: device=평소기기, related=동일디바이스 11명, str=4건  → H1(보이스피싱 조직)
- case_h2: auth=인증실패/비번변경, device=낯선기기              → H2(계정탈취)
"""

from agent.tools import (
    TOOLS,
    get_auth_events,
    get_device_fingerprint,
    get_party,
    get_related_accounts,
    get_str_history,
    load_case,
)


def test_registry_has_eight_tools():
    expected = {
        "get_party",
        "get_customer",
        "get_auth_events",
        "get_device_fingerprint",
        "get_fds_history",
        "get_str_history",
        "get_related_accounts",
        "get_aml_history",
    }
    assert set(TOOLS) == expected


def test_tools_are_read_only_toolresult():
    case = load_case("case_h5")
    cid = case.alert.customer_id
    res = get_party(case, cid)
    # ToolResult 표준 형태 + Evidence 변환 가능(tool_log/evidence 연동)
    assert res.tool == "get_party"
    ev = res.to_evidence()
    assert ev.tool == "get_party" and ev.signal == res.signal


def test_case_h1_device_known_related_eleven_str_four():
    case = load_case("case_h1")
    alert = case.alert

    device = get_device_fingerprint(case, alert.account)
    assert device.data["known_device"] is True          # 평소 기기 → H2 아님

    related = get_related_accounts(case, alert.account)
    assert related.data["same_device_customers"] == 11   # 동일 디바이스 11명
    assert related.data["organized_ring"] is True        # 조직 링(T2)

    strh = get_str_history(case, alert.customer_id)
    assert strh.data["past_str_reports"] == 4            # STR 4건


def test_case_h2_auth_fail_and_unknown_device():
    case = load_case("case_h2")
    alert = case.alert

    auth = get_auth_events(case, alert.customer_id)
    assert auth.data["recent_cert_fail"] == 3            # 인증 실패 연발
    assert auth.data["password_changed_recently"] is True  # 비번 변경 직후

    device = get_device_fingerprint(case, alert.account)
    assert device.data["known_device"] is False          # 낯선 기기 → H2쪽


def test_get_party_no_decisive_for_normal_cases():
    for name in ("case_h1", "case_h2", "case_h5"):
        case = load_case(name)
        assert get_party(case, case.alert.customer_id).decisive_fact is None
