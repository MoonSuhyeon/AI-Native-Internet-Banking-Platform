"""실거래 조사 진입점.

**왜 필요한가.** 조사 루프는 완성돼 있었지만 입력이 ``data/cases/*.json`` 파일뿐이라,
탐지기가 실제로 잡은 거래를 넣을 방법이 없었다. 탐지와 조사가 이어지지 않는 상태였다.

**동시에 지켜야 할 것.** 기존 목 경로를 끊으면 안 된다. 데모와 eval 워크플로
(``eval-fraud.yml``)가 목으로 도는데, 그게 깨지면 CI 가 죽는다. 그래서 두 경로가
함께 산다는 것을 여기서 못박는다.
"""

from fastapi.testclient import TestClient

from conftest import GATEWAY_HEADERS

from agent.api import app


def _transaction(**overrides) -> dict:
    payload = {
        "alert_id": "TXN-1",
        "customer_id": "9001",
        "account": "110-222-333333",
        "amount": 15_000_000,
        "payee": "999-888-777777",
        "channel": "MOBILE",
        "anomaly_score": 0.7,
        "signals": ["HIGH_AMOUNT", "NEW_RECEIVER"],
    }
    payload.update(overrides)
    return payload


def test_real_transaction_is_investigated():
    """탐지기가 넘긴 실거래가 조사 루프를 통과한다."""
    client = TestClient(app, headers=GATEWAY_HEADERS)

    res = client.post("/api/investigate", json={"transaction": _transaction()})

    assert res.status_code == 200, res.text
    body = res.json()
    # 사건 이름이 거래에서 나온다 — 파일 이름이 아니다.
    assert body["case"] == "txn-TXN-1"
    assert body["alert"]["customer_id"] == "9001"
    assert body["alert"]["tx_context"]["amount"] == 15_000_000
    # 권고까지 나오고 HITL 대기 상태여야 한다. 에이전트는 실행하지 않는다.
    assert body["recommendation"]["status"]
    assert body["hitl_pending"] is True


def test_detection_signals_are_carried_into_the_case():
    """탐지 신호가 조사 입력에 남는다 — 심사원이 왜 걸렸는지 알아야 한다."""
    client = TestClient(app, headers=GATEWAY_HEADERS)

    res = client.post("/api/investigate", json={"transaction": _transaction()})

    assert "HIGH_AMOUNT" in res.json()["description"]


def test_mock_case_path_still_works():
    """기존 목 경로는 그대로다 — 데모와 eval 워크플로가 이 경로로 돈다."""
    client = TestClient(app, headers=GATEWAY_HEADERS)

    res = client.post("/api/investigate", json={"case": "case_h1"})

    assert res.status_code == 200, res.text
    assert res.json()["case"] == "case_h1"


def test_missing_input_is_rejected():
    """둘 다 없으면 400. 조용히 빈 조사를 돌리지 않는다."""
    client = TestClient(app, headers=GATEWAY_HEADERS)

    res = client.post("/api/investigate", json={})

    assert res.status_code == 400


def test_unknown_case_still_404():
    """없는 케이스 이름은 여전히 404 — 400 으로 뭉뚱그리지 않는다."""
    client = TestClient(app, headers=GATEWAY_HEADERS)

    res = client.post("/api/investigate", json={"case": "no_such_case"})

    assert res.status_code == 404
