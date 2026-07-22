"""조사 도구 — AXful Bank 자산을 Tool로 노출 (§16-3).

계층: 조회(자유) → 판단(llm) → 동작(HITL+RBAC). 이 모듈은 **조회 도구만**.
8개 도구 전부 **조회 전용·부작용 없음**이며, PoC 단계에선 케이스 JSON의 목 응답을
``ToolResult`` 로 감싼다. 실서비스면 각 함수 자리에서 Java `/internal` API 를 친다
(주석으로 표시). 동작(지급정지·STR)은 여기 없다 — recommend.py 가 제안만 만든다(원칙 3).

벡터·RAG·캐시 안 씀(원칙 5). 사실은 케이스(=DB 대체) 딕셔너리 조회로만.

각 도구는 ``(case, customer_id|account) -> ToolResult`` 형태이고, 선택·기록은
``TOOLS`` 레지스트리로 일원화해 tool_log 에 남기기 쉽게 했다.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from .models import (
    Case,
    DecisiveFact,
    DecisiveFactKind,
    ToolResult,
)

CASES_DIR = Path(__file__).resolve().parents[2] / "data" / "cases"


def load_case(name: str) -> Case:
    """data/cases/<name>.json 을 Case 로 로드 (PoC 데이터 원천)."""
    path = CASES_DIR / f"{name}.json"
    with path.open(encoding="utf-8") as fh:
        return Case.model_validate(json.load(fh))


def _resp(case: Case, tool: str) -> dict:
    """케이스에서 해당 도구의 목 응답을 꺼낸다 (없으면 빈 dict)."""
    return dict(case.tool_responses.get(tool, {}))


# --------------------------------------------------------------------------- #
# 실연결 토글 — get_auth_events 만 customer-service 실 백엔드 호출 가능 (Stage 7)
#   활성: TRIAGE_REAL_TOOLS 에 "get_auth_events" 포함 + TRIAGE_BACKEND_URL 설정
#   인증(둘 중 택1):
#     · 게이트웨이 헤더(로컬 직접호출): TRIAGE_USER_ID + TRIAGE_USER_ROLE 를
#       X-User-Id / X-User-Role 로 전달. customer-service 는 JWT 를 직접 검증하지 않고
#       GatewayHeaderAuthFilter 로 이 헤더를 신뢰한다(게이트웨이가 단일 검증 지점).
#       /internal/auth/** 는 직원 역할(ROLE_*) 필요 → ROLE_HQ_RISK 등.
#     · Bearer(게이트웨이 경유): TRIAGE_INTERNAL_TOKEN (직원 JWT) → Authorization.
# 그 외 도구는 항상 목 (§16-9 경계). 응답 dict 형태는 목과 동일해 그래프 로직 불변.
# --------------------------------------------------------------------------- #
def _tool_is_real(tool: str) -> bool:
    real = {t.strip() for t in os.getenv("TRIAGE_REAL_TOOLS", "").split(",") if t.strip()}
    return tool in real and bool(os.getenv("TRIAGE_BACKEND_URL"))


def _internal_headers() -> dict:
    """직원 인증 헤더 구성 — 게이트웨이 헤더(로컬) 우선, Bearer(게이트웨이 경유) 보강."""
    headers: dict = {}
    user_id = os.getenv("TRIAGE_USER_ID")
    if user_id:
        headers["X-User-Id"] = user_id
        headers["X-User-Role"] = os.getenv("TRIAGE_USER_ROLE", "ROLE_HQ_RISK")
    token = os.getenv("TRIAGE_INTERNAL_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _fetch_auth_events_real(customer_id: str) -> dict:
    """customer-service 내부 API 호출 → 목과 동일한 dict 형태로 매핑."""
    import httpx  # 지연 import (실연결 시에만 필요)

    base = os.environ["TRIAGE_BACKEND_URL"].rstrip("/")
    resp = httpx.get(
        f"{base}/api/v1/internal/auth/{customer_id}/events",
        headers=_internal_headers(),
        timeout=5.0,
    )
    resp.raise_for_status()
    body = resp.json()
    data = body.get("data", body)  # ApiResponse 는 {data: ...} 로 감쌈
    return {
        "recent_cert_fail": data.get("recentCertFail", 0),
        "password_changed_recently": data.get("passwordChangedRecently", False),
        "events": [],
        "_source": "real",
    }


# --------------------------------------------------------------------------- #
# 1. get_party — party 도메인. 사망·후견(→fail-closed), 명의자 불일치(→T1)
# --------------------------------------------------------------------------- #
def get_party(case: Case, customer_id: str) -> ToolResult:
    # 실서비스: GET /internal/party/{customer_id}
    data = _resp(case, "get_party")
    decisive: DecisiveFact | None = None

    life = data.get("life_status", "ALIVE")
    guardianship = data.get("guardianship", "NONE")
    if life == "DEATH":
        decisive = DecisiveFact(
            kind=DecisiveFactKind.DEATH, source="get_party", detail="END_REASON=DEATH"
        )
    elif guardianship == "ADULT":  # 성년후견(피성년후견인) 단독거래만 즉시 차단
        decisive = DecisiveFact(
            kind=DecisiveFactKind.GUARDIANSHIP,
            source="get_party",
            detail="성년후견 개시 — 단독거래 무효",
        )

    name_match = data.get("name_match", True)
    parts = [f"생존={life}", f"후견={guardianship}", f"명의일치={name_match}"]
    if decisive:
        parts.append("→ 결정적 사실(fail-closed)")
    elif not name_match:
        parts.append("→ 명의자 불일치(T1)")
    return ToolResult(
        tool="get_party", signal=" · ".join(parts), data=data, decisive_fact=decisive
    )


# --------------------------------------------------------------------------- #
# 2. get_customer — customer. 평소 거래·디바이스 baseline
# --------------------------------------------------------------------------- #
def get_customer(case: Case, customer_id: str) -> ToolResult:
    # 실서비스: GET /internal/customer/{customer_id}
    data = _resp(case, "get_customer")
    signal = (
        f"평소 평균 {data.get('avg_tx_amount', 0):,}원 · "
        f"주채널={data.get('usual_channel', '?')} · "
        f"가입 {data.get('tenure_years', 0)}년"
    )
    return ToolResult(tool="get_customer", signal=signal, data=data)


# --------------------------------------------------------------------------- #
# 3. get_auth_events — 인증보안계(CERT_FAIL_BLOCK). 인증 실패·비번 변경(→H2)
# --------------------------------------------------------------------------- #
def get_auth_events(case: Case, customer_id: str) -> ToolResult:
    # 실연결 가능(Stage 7): GET /api/v1/internal/auth/{customerId}/events (customer-service)
    if _tool_is_real("get_auth_events"):
        try:
            data = _fetch_auth_events_real(customer_id)
        except Exception as exc:  # noqa: BLE001 — 실 백엔드 장애는 조사를 죽이지 않는다
            # fail-soft(원칙 4): 실호출 실패 시 조사 전체를 500 내지 않고 목으로 폴백.
            data = _resp(case, "get_auth_events")
            data["_source"] = "mock_fallback"
            data["_real_error"] = type(exc).__name__
    else:
        data = _resp(case, "get_auth_events")
    cert_fail = data.get("recent_cert_fail", 0)
    pw_changed = data.get("password_changed_recently", False)
    flags = []
    if cert_fail:
        flags.append(f"인증실패 {cert_fail}회")
    if pw_changed:
        flags.append("비번 변경 직후")
    signal = (" · ".join(flags) + " → H2(계정탈취) 신호") if flags else "인증 이상 없음"
    return ToolResult(tool="get_auth_events", signal=signal, data=data)


# --------------------------------------------------------------------------- #
# 4. get_device_fingerprint — FDS·세션. 평소/낯선 기기
# --------------------------------------------------------------------------- #
def get_device_fingerprint(case: Case, account: str) -> ToolResult:
    # 실서비스: GET /internal/fds/device?account={account}
    data = _resp(case, "get_device_fingerprint")
    known = data.get("known_device", True)
    signal = (
        f"평소 기기(device={data.get('device_id', '?')}) → H1쪽"
        if known
        else f"낯선 기기(device={data.get('device_id', '?')}, 최초사용) → H2쪽"
    )
    return ToolResult(tool="get_device_fingerprint", signal=signal, data=data)


# --------------------------------------------------------------------------- #
# 5. get_fds_history — fds_detection·incident. 과거 탐지 패턴
# --------------------------------------------------------------------------- #
def get_fds_history(case: Case, customer_id: str) -> ToolResult:
    # 실서비스: GET /internal/fds/{customer_id}/history
    data = _resp(case, "get_fds_history")
    signal = (
        f"과거 탐지 {data.get('past_detections', 0)}회 · "
        f"최근 패턴={data.get('last_pattern', 'none')}"
    )
    return ToolResult(tool="get_fds_history", signal=signal, data=data)


# --------------------------------------------------------------------------- #
# 6. get_str_history — STR. 과거 의심거래 보고
# --------------------------------------------------------------------------- #
def get_str_history(case: Case, customer_id: str) -> ToolResult:
    # 실서비스: GET /internal/str/{customer_id}
    data = _resp(case, "get_str_history")
    reports = data.get("past_str_reports", 0)
    signal = f"과거 STR 보고 {reports}건" + (" → 의심 누적" if reports else "")
    return ToolResult(tool="get_str_history", signal=signal, data=data)


# --------------------------------------------------------------------------- #
# 7. get_related_accounts — 거래·수취 네트워크. 동일 디바이스 다고객(→T1·T2·T3)
# --------------------------------------------------------------------------- #
def get_related_accounts(case: Case, account: str) -> ToolResult:
    # 실서비스: GET /internal/network/related?account={account}
    data = _resp(case, "get_related_accounts")
    same_device = data.get("same_device_customers", 1)
    ring = data.get("organized_ring", False)
    linked_str = data.get("linked_str_reports", 0)
    flags = [f"동일 디바이스 {same_device}명"]
    if ring:
        flags.append("조직 링(T2)")
    if same_device > 1:
        flags.append("머니뮬 의심(T1)")
    if linked_str:
        flags.append(f"연관 STR {linked_str}건")
    return ToolResult(tool="get_related_accounts", signal=" · ".join(flags), data=data)


# --------------------------------------------------------------------------- #
# 8. get_aml_history — AML. 구조화·세탁 이력(→H3·H4)
# --------------------------------------------------------------------------- #
def get_aml_history(case: Case, customer_id: str) -> ToolResult:
    # 실서비스: GET /internal/aml/{customer_id}/history
    data = _resp(case, "get_aml_history")
    structuring = data.get("structuring", False)
    hops = data.get("layering_hops", 0)
    flags = []
    if structuring:
        flags.append("구조화 정황")
    if hops > 1:
        flags.append(f"다단 경유 {hops}홉")
    signal = (" · ".join(flags) + " → H3/H4 신호") if flags else "AML 이상 없음"
    return ToolResult(tool="get_aml_history", signal=signal, data=data)


# 도구 레지스트리 — Planner(llm.py)·graph 가 이름으로 호출하고 tool_log 에 남긴다.
TOOLS = {
    fn.__name__: fn
    for fn in (
        get_party,
        get_customer,
        get_auth_events,
        get_device_fingerprint,
        get_fds_history,
        get_str_history,
        get_related_accounts,
        get_aml_history,
    )
}
