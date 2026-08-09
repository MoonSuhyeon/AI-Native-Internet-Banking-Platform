"""직원 신원이 어디서 오는지 — 신뢰 경계 검증.

**왜 이 파일이 필요한가.** 직원용 기능 5종은 고객 개인정보·계좌·거래·상담이력을
연다. 그래서 "누가 열람했는가"가 이 서비스에서 가장 중요한 기록인데, 오랫동안 그 값이
**화면의 자유 입력값**이었다. 아무 직원 번호나 적으면 그 사람 앞으로 기록이 쌓였다.

더 나쁜 것은 보호가 있는 것처럼 보였다는 점이다. 엔드포인트에 "JWT 토큰에서 추출해
사칭을 방지한다" 는 주석과 코드가 있었지만 세 군데가 끊겨 있어 한 번도 발동하지
않았다 — 프론트가 Authorization 을 붙이지 않았고, Next 프록시가 Content-Type 외
모든 헤더를 버렸으며, 토큰이 없으면 body 값으로 되돌아갔다. 게다가 찾는 클레임
이름(``staff_id``/``employee_id``/``sub``)에 실제 발급 이름인 ``empId`` 가 없어,
토큰이 도착했더라도 ``sub`` 즉 **고객 ID 를 직원 ID 로 기록**할 참이었다.

여기서 검증하는 것은 하나다 — **직원 신원은 게이트웨이에서만 온다.**
body 로 무엇을 보내든, 게이트웨이를 거치지 않았다면 열람할 수 없다.

기존 테스트들이 body 로 staff_id 를 보내 통과하던 것은 위조 가능한 상태를 검증하고
있던 것이라, 이 변경과 함께 헤더 방식으로 옮겼다.
"""

import pytest
from fastapi.testclient import TestClient

from app.main import app, get_chatbot_service
from conftest import GATEWAY_SECRET

CUST = "CUST001"
STAFF = "EMP001"

#: 전부 고객 개인정보를 여는 기능이다. 하나라도 빠지면 그 문만 열린 채 남는다.
STAFF_FEATURES = [
    "STAFF_CUSTOMER",
    "STAFF_CONTRACT",
    "STAFF_ACCOUNT",
    "STAFF_TRANSFER_FLOW",
    "STAFF_CONSULTATION_HISTORY",
]


def _client(service) -> TestClient:
    app.dependency_overrides[get_chatbot_service] = lambda: service
    return TestClient(app)


def _execute(client, feature_code, json=None, headers=None):
    return client.post(
        f"/chatbot/features/{feature_code}/execute",
        json=json if json is not None else {"customer_no": CUST},
        headers=headers or {},
    )


@pytest.fixture()
def client(service):
    c = _client(service)
    yield c
    app.dependency_overrides.clear()


# ── 위조 시도 ────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("feature_code", STAFF_FEATURES)
def test_body_의_staff_id_로는_열람할_수_없다(client, feature_code, staff_headers):
    """예전에 뚫려 있던 바로 그 경로. 화면 입력값이 body 로 실려 오던 방식이다.

    시크릿까지 설정된 상태에서 시도한다 — 즉 "게이트웨이 설정이 없어서 막혔다" 가
    아니라 **body 값 자체를 쓰지 않는다**는 것을 본다.
    """
    staff_headers()  # 시크릿만 설정하고 헤더는 주지 않는다

    resp = _execute(client, feature_code, json={"customer_no": CUST, "staff_id": STAFF})

    assert resp.status_code == 200
    assert resp.json()["status"] == "STAFF_AUTH_REQUIRED", (
        "body 의 staff_id 를 받아들이면 아무 직원 번호로나 고객 정보를 열 수 있다"
    )


@pytest.mark.parametrize("feature_code", STAFF_FEATURES)
def test_게이트웨이_증거_없는_헤더는_믿지_않는다(client, feature_code, staff_headers):
    """X-Employee-Id 를 손으로 붙인 경우.

    상담 서비스는 8087 로 직접 열려 있어서, 헤더만 신뢰하면 게이트웨이를 우회해
    헤더를 손으로 붙이면 그만이다. 그러면 위조 가능성은 그대로인데 감사 기록만
    더 믿음직해 보인다 — 입력칸으로 위조하던 것이 헤더로 위조하는 것으로
    형태만 바뀐다.
    """
    staff_headers()

    resp = _execute(client, feature_code, headers={"X-Employee-Id": STAFF})

    assert resp.json()["status"] == "STAFF_AUTH_REQUIRED"


def test_시크릿이_틀리면_거절한다(client, staff_headers):
    staff_headers()

    resp = _execute(
        client,
        "STAFF_CUSTOMER",
        headers={"X-Gateway-Auth": GATEWAY_SECRET + "x", "X-Employee-Id": STAFF},
    )

    assert resp.json()["status"] == "STAFF_AUTH_REQUIRED"


def test_시크릿_미설정이면_헤더를_믿지_않는다(client, monkeypatch):
    """로컬 기본 상태. 신원을 확인할 수 없으니 열람도 안 된다(fail-closed).

    조용히 통과시키면 위조 가능한 상태가 그대로인데 기록만 그럴듯해진다.
    """
    monkeypatch.delenv("CONSULTATION_GATEWAY_SHARED_SECRET", raising=False)

    resp = _execute(
        client,
        "STAFF_CUSTOMER",
        headers={"X-Gateway-Auth": "", "X-Employee-Id": STAFF},
    )

    assert resp.json()["status"] == "STAFF_AUTH_REQUIRED"


def test_고객_토큰은_직원_기능을_열_수_없다(client, staff_headers):
    """게이트웨이는 고객 토큰에도 X-Employee-Id 를 붙이되 빈 문자열을 넣는다.

    빈 값을 그대로 넘기면 employees 조회가 아무것도 못 찾아 "권한 없음" 이 아니라
    "직원 없음" 으로 흐려진다. 빈 값은 신원이 아니라는 것을 여기서 못 박는다.
    """
    resp = _execute(
        client,
        "STAFF_CUSTOMER",
        headers={"X-Gateway-Auth": GATEWAY_SECRET, "X-Employee-Id": ""},
    )
    staff_headers()

    assert resp.json()["status"] == "STAFF_AUTH_REQUIRED"


# ── 정상 경로 ────────────────────────────────────────────────────────────────

def test_게이트웨이가_주입한_신원이면_열람된다(client, staff_headers):
    """막기만 하고 열리지 않으면 기능이 죽은 것과 구별되지 않는다."""
    resp = _execute(client, "STAFF_CUSTOMER", headers=staff_headers(STAFF))

    assert resp.status_code == 200
    assert resp.json()["status"] == "OK"
    assert resp.json()["data"][0]["customer_no"] == CUST


def test_body_가_헤더를_덮어쓰지_못한다(client, staff_headers):
    """정상 신원으로 들어오면서 body 에 다른 직원을 적는 경우.

    이쪽이 더 위험하다 — 요청은 성공하므로 아무도 이상을 눈치채지 못하는데,
    감사 기록만 남의 이름으로 남는다.
    """
    headers = staff_headers(STAFF)

    resp = client.post(
        "/chatbot/features/STAFF_CUSTOMER/execute",
        json={"customer_no": CUST, "staff_id": "EMP999"},
        headers=headers,
    )

    assert resp.status_code == 200
    # EMP999 는 employees 에 없다. body 가 채택됐다면 "유효하지 않은 직원" 으로
    # 거절됐을 것이다. OK 라는 것은 헤더의 EMP001 이 쓰였다는 뜻이다.
    assert resp.json()["status"] == "OK"


def test_고객용_기능은_영향받지_않는다(client):
    """고객 챗봇 경로는 신원 요구가 다르고 담당도 다르다 — 함께 막히면 안 된다."""
    resp = client.post(
        "/chatbot/features/MY_PRODUCTS/execute",
        json={"customer_no": CUST},
    )

    assert resp.status_code == 200
    assert resp.json()["status"] != "STAFF_AUTH_REQUIRED"
