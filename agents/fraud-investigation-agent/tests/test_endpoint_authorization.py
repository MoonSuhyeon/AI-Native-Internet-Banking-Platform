"""조사 에이전트 엔드포인트가 신원을 요구하는지.

**왜 승인만으로는 부족한가.** 예전에는 ``/api/approve`` 만 신원을 확인했다. 승인이
지급정지·STR 을 발동시키는 지점이라 거기부터 막은 것은 맞지만, 그 앞의 두 문이
열려 있었다.

- ``GET /api/cases`` — 조사 큐. 응답에 고객 ID·계좌·금액·수취인이 그대로 담긴다.
- ``POST /api/investigate`` — 조사 실행. 고객의 인증 이력·거래 내역을 끌어와 보고,
  부를 때마다 LLM 호출이 일어난다.

즉 동작은 못 해도 **열람은 자유로웠다.** 누가 어떤 고객을 조사 대상으로 삼을지도
아무나 정할 수 있었으니, 조사 자체가 사찰이 될 수 있는 상태였다. 비용과 처리량도
아무나 소진시킬 수 있고, 그 사이 진짜 알림이 밀린다.

여기서 보는 규칙은 하나다 — **신원은 게이트웨이에서만 온다.**
"""

import pytest
from fastapi.testclient import TestClient

from agent.api import app
from conftest import GATEWAY_HEADERS, GATEWAY_SECRET


def _transaction() -> dict:
    return {
        "alert_id": "TXN-AUTHZ-1",
        "customer_id": "9001",
        "account": "110-222-333333",
        "amount": 3_000_000,
        "payee": "999-888-777777",
        "channel": "MOBILE",
        "anomaly_score": 0.8,
        "signals": ["HIGH_AMOUNT"],
    }


@pytest.fixture()
def client():
    return TestClient(app)


# ── 막히는 경우 ──────────────────────────────────────────────────────────────

@pytest.mark.parametrize("call", ["cases", "investigate"])
def test_신원_없이는_열리지_않는다(client, call):
    resp = (client.get("/api/cases") if call == "cases"
            else client.post("/api/investigate", json={"transaction": _transaction()}))

    assert resp.status_code == 403


@pytest.mark.parametrize("call", ["cases", "investigate"])
def test_헤더를_손으로_붙여도_통하지_않는다(client, call):
    """사이드카가 8090 으로 열려 있는 한, 헤더만 신뢰하면 위조 방식만 바뀐다."""
    headers = {"X-Employee-Id": "EMP999"}
    resp = (client.get("/api/cases", headers=headers) if call == "cases"
            else client.post("/api/investigate",
                             json={"transaction": _transaction()}, headers=headers))

    assert resp.status_code == 403


@pytest.mark.parametrize("call", ["cases", "investigate"])
def test_시크릿이_틀리면_거절한다(client, call):
    headers = {"X-Gateway-Auth": GATEWAY_SECRET + "x", "X-Employee-Id": "EMP001"}
    resp = (client.get("/api/cases", headers=headers) if call == "cases"
            else client.post("/api/investigate",
                             json={"transaction": _transaction()}, headers=headers))

    assert resp.status_code == 403


@pytest.mark.parametrize("call", ["cases", "investigate"])
def test_고객_토큰은_거절한다(client, call):
    """게이트웨이는 고객 토큰에도 헤더를 붙이되 빈 직원 ID 를 넣는다."""
    headers = {"X-Gateway-Auth": GATEWAY_SECRET, "X-Employee-Id": ""}
    resp = (client.get("/api/cases", headers=headers) if call == "cases"
            else client.post("/api/investigate",
                             json={"transaction": _transaction()}, headers=headers))

    assert resp.status_code == 403


def test_시크릿_미설정이면_아무도_못_연다(client, monkeypatch):
    """로컬 기본 상태. 신원을 확인할 수 없으니 조사도 못 한다(fail-closed)."""
    monkeypatch.delenv("FRAUD_GATEWAY_SHARED_SECRET", raising=False)

    assert client.get("/api/cases", headers=GATEWAY_HEADERS).status_code == 403


# ── 열리는 경우 ──────────────────────────────────────────────────────────────

def test_게이트웨이가_주입한_신원이면_큐가_열린다(client):
    """막기만 하고 열리지 않으면 기능이 죽은 것과 구별되지 않는다."""
    resp = client.get("/api/cases", headers=GATEWAY_HEADERS)

    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_게이트웨이가_주입한_신원이면_조사가_돈다(client):
    resp = client.post("/api/investigate",
                       json={"transaction": _transaction()}, headers=GATEWAY_HEADERS)

    assert resp.status_code == 200
    assert resp.json()["hitl_pending"] is True


# ── 공개로 남겨야 하는 것 ────────────────────────────────────────────────────

@pytest.mark.parametrize("path", ["/health", "/metrics"])
def test_헬스와_지표는_공개다(client, path):
    """이 둘까지 막으면 스크레이프와 헬스체크가 죽는다 — 감시가 먼저 꺼진다."""
    assert client.get(path).status_code == 200
