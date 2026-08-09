"""엔드포인트마다 신원을 요구하는지 확인한다.

**왜 이 파일이 필요한가.** 이 서비스의 엔드포인트 24개 중 인증을 요구하던 것은
직원 기능 5종뿐이었다. 나머지는 요청 body·쿼리·폼에 적힌 값을 그대로 믿었고,
그래서 다음이 가능했다.

- ``POST /chatbot/transfer`` 에 남의 고객번호를 적으면 **그 사람 계좌에서 돈이 나간다.**
  출금 계좌 소유권 검사는 있었지만 ``customer_id = :cno`` 의 ``cno`` 가 공격자가
  보낸 값이라, 자기가 적은 값과 대조하고 있었다. core-banking 에도
  ``X-Customer-Id: req.customer_no`` 를 그대로 실어 보내 그쪽 소유권 검증까지
  같은 거짓말을 근거로 판단했다.
- ``GET /chat/history`` 에서 ``customer_no`` 를 빼면 전 고객 상담 이력이 나온다.
- ``PATCH /agents/{id}`` 로 아무나 상담원 비밀번호를 바꿔 계정을 가져간다.
- ``sender_type: "AGENT"`` 로 적으면 상담사 말풍선으로 남의 대화에 끼어든다.

이런 결함의 공통점은 **조용하다**는 것이다. 요청은 200 을 받고 화면도 정상이며
기록도 남는다. 다만 그 기록의 주인이 엉뚱한 사람일 뿐이다.

여기서 보는 것은 규칙 하나다 — **신원은 게이트웨이에서만 온다.**
"""

import pytest
from fastapi.testclient import TestClient

from app.main import app, get_chatbot_service, get_chat_service
from conftest import GATEWAY_SECRET

CUST = "CUST001"
OTHER_CUST = "CUST999"


@pytest.fixture()
def client(service, chat_service):
    # chat_service 도 주입해야 한다. Depends 는 함수 본문보다 먼저 풀리므로,
    # 여기서 DB 연결이 터지면 인가 검사에 닿기도 전에 다른 오류가 난다.
    app.dependency_overrides[get_chatbot_service] = lambda: service
    app.dependency_overrides[get_chat_service] = lambda: chat_service
    c = TestClient(app)
    yield c
    app.dependency_overrides.clear()


@pytest.fixture()
def customer_headers(monkeypatch):
    """게이트웨이가 고객 토큰을 검증해 통과시킨 상태."""
    monkeypatch.setenv("CONSULTATION_GATEWAY_SHARED_SECRET", GATEWAY_SECRET)

    def headers(customer_no: str = CUST) -> dict[str, str]:
        return {"X-Gateway-Auth": GATEWAY_SECRET, "X-Customer-Id": customer_no}

    return headers


# ── 직원 전용 경로 ───────────────────────────────────────────────────────────

STAFF_ONLY_GETS = [
    "/agents",
    "/chat/history",
    "/chat/queue",
]


@pytest.mark.parametrize("path", STAFF_ONLY_GETS)
def test_직원_전용_조회는_신원_없이_열리지_않는다(client, path, staff_headers):
    """전 고객 이력·대기열·상담원 계정 목록이 그냥 열려 있었다."""
    staff_headers()  # 시크릿만 설정, 헤더는 주지 않는다

    assert client.get(path).status_code == 403


@pytest.mark.parametrize("path", STAFF_ONLY_GETS)
def test_고객_토큰으로는_직원_전용_조회가_안_된다(client, path, customer_headers):
    """로그인한 고객이 다른 고객의 상담 이력을 볼 수 있으면 안 된다."""
    assert client.get(path, headers=customer_headers()).status_code == 403


def test_상담원_비밀번호_변경은_막힌다(client, staff_headers):
    """아무나 남의 상담원 비밀번호를 바꿔 계정을 가져갈 수 있던 경로."""
    staff_headers()

    resp = client.patch("/agents/1", json={"password": "hijacked"})

    assert resp.status_code == 403


def test_상담원_계정_생성_비활성화도_막힌다(client, staff_headers):
    staff_headers()

    assert client.post("/agents", json={
        "login_id": "attacker", "password": "x", "name": "침입자", "role": "AGENT",
    }).status_code == 403
    assert client.delete("/agents/1").status_code == 403


def test_직원_신원이_있으면_열린다(client, staff_headers):
    """막기만 하고 열리지 않으면 기능이 죽은 것과 구별되지 않는다."""
    assert client.get("/chat/history", headers=staff_headers()).status_code == 200


# ── 자금 이동 ────────────────────────────────────────────────────────────────

class TestTransfer:
    """가장 심각했던 경로. 실제로 돈이 나간다."""

    def _payload(self, customer_no: str) -> dict:
        return {
            "customer_no": customer_no,
            "from_account_id": 1,
            "to_account_number": "1234567890",
            "amount": 1_000_000,
        }

    def test_신원_없이는_이체할_수_없다(self, client, customer_headers):
        customer_headers()  # 시크릿만 설정

        resp = client.post("/chatbot/transfer", json=self._payload(CUST))

        assert resp.status_code == 403

    def test_body_의_고객번호로는_남의_계좌를_건드릴_수_없다(self, client, customer_headers):
        """헤더는 CUST001 인데 body 에 CUST999 를 적는 경우.

        이쪽이 더 위험하다 — 요청이 200 으로 성공할 수 있고, 그러면 아무도
        이상을 눈치채지 못한 채 남의 돈이 움직인다.
        """
        resp = client.post(
            "/chatbot/transfer",
            json=self._payload(OTHER_CUST),
            headers=customer_headers(CUST),
        )

        # body 값이 채택됐다면 CUST999 의 계좌를 찾았을 것이다.
        # 헤더의 CUST001 이 쓰였다면 그 사람 계좌가 없어 "계좌를 찾을 수 없습니다".
        assert resp.status_code == 200
        assert resp.json()["status"] == "ERROR"
        assert OTHER_CUST not in resp.text


# ── 고객 사칭 ────────────────────────────────────────────────────────────────

def test_서류_제출은_본인_확인이_필요하다(client, customer_headers):
    """남의 계정 앞으로 서류를 올릴 수 있으면 안 된다 — 흔적이 남는 동작이다."""
    customer_headers()

    resp = client.post(
        "/chatbot/documents/upload",
        files={"file": ("a.pdf", b"%PDF-1.4 test", "application/pdf")},
        data={"doc_type": "ENROLLMENT"},
    )

    assert resp.status_code == 403


def test_상담사_연결_요청은_본인_확인이_필요하다(client, customer_headers):
    """남의 이름으로 상담사를 부르면 상담사가 엉뚱한 사람과 대화하게 된다."""
    customer_headers()

    resp = client.post("/chat/request", json={"customer_no": OTHER_CUST})

    assert resp.status_code == 403


def test_상담_시작은_body_의_고객번호를_쓰지_않는다(client, customer_headers):
    """남의 번호를 적어도 자기 이름으로 열린다."""
    resp = client.post(
        "/chatbot/consultations/start",
        json={"customer_no": OTHER_CUST, "entry_screen": "WEB_PERSONAL",
              "app_version": "0.1.0"},
        headers=customer_headers(CUST),
    )

    assert resp.status_code == 200
    # 남의 번호로 열렸다면 그 상담이 CUST999 소유가 된다.
    assert OTHER_CUST not in resp.text


def test_익명_상담_시작은_계속_된다(client, customer_headers):
    """로그인 없이 상품을 물어보는 흐름을 함께 막으면 안 된다."""
    customer_headers()

    resp = client.post(
        "/chatbot/consultations/start",
        json={"customer_no": "", "entry_screen": "WEB_PERSONAL", "app_version": "0.1.0"},
    )

    assert resp.status_code == 200


def test_고객_기능은_body_의_고객번호를_쓰지_않는다(client, customer_headers):
    """남의 상품·계약·현금흐름을 조회할 수 있던 경로."""
    resp = client.post(
        "/chatbot/features/MY_PRODUCTS/execute",
        json={"customer_no": OTHER_CUST},
        headers=customer_headers(CUST),
    )

    assert resp.status_code == 200
    assert OTHER_CUST not in resp.text


def test_익명이면_본인_확인을_요구한다(client, customer_headers):
    """기존 동작 유지 — 막는 방식이 '인증 필요' 여야 조용한 실패가 아니다."""
    customer_headers()

    resp = client.post("/chatbot/features/MY_PRODUCTS/execute", json={"customer_no": CUST})

    assert resp.status_code == 200
    assert resp.json()["requires_auth"] is True
