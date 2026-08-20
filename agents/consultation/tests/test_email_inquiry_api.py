"""이메일상담 접수 API.

무엇을 지키는가. 접수한 문의가 **남긴 사람의 것**으로 남고, **남긴 사람만** 읽는다.
문의 본문에는 계좌·거래 이야기가 그대로 적히므로, 조회가 새면 그 내용이 새는 것과
같다.

POST /email-inquiries
GET  /email-inquiries
"""

import pytest
from fastapi.testclient import TestClient

from app.database import get_db
from app.main import app


@pytest.fixture()
def client(db):
    app.dependency_overrides[get_db] = lambda: db
    yield TestClient(app)
    app.dependency_overrides.pop(get_db, None)


def _payload(**over):
    body = {
        "reply_email": "customer@example.com",
        "title": "이체 수수료 문의",
        "content": "타행 이체 수수료가 어떻게 되는지 알고 싶습니다.",
    }
    body.update(over)
    return body


def test_접수하면_상담이력도_함께_남는다(client, customer_headers):
    res = client.post("/email-inquiries", json=_payload(), headers=customer_headers())

    assert res.status_code == 201
    body = res.json()
    assert body["reply_email"] == "customer@example.com"
    # 상담 이력이 한 곳으로 모이지 않으면 "이 고객이 몇 번 문의했는가" 를
    # 채널마다 따로 세어야 한다.
    assert body["consultation_id"] > 0
    assert body["answered_at"] is None


def test_로그인하지_않으면_접수할_수_없다(client):
    # 신원 없이 통과시키면 아무나 남의 이름으로 문의를 남길 수 있다.
    res = client.post("/email-inquiries", json=_payload())

    assert res.status_code == 403


def test_이메일_형식이_아니면_거절한다(client, customer_headers):
    # 답변을 보낼 곳이 틀리면 접수는 되고 답변만 사라진다 — 고객은 기다린다.
    res = client.post(
        "/email-inquiries",
        json=_payload(reply_email="주소아님"),
        headers=customer_headers(),
    )

    assert res.status_code == 422


def test_내용이_비면_거절한다(client, customer_headers):
    res = client.post(
        "/email-inquiries",
        json=_payload(content=""),
        headers=customer_headers(),
    )

    assert res.status_code == 422


def test_내가_남긴_문의만_보인다(client, customer_headers):
    client.post("/email-inquiries", json=_payload(title="내 문의"),
                headers=customer_headers("CUST001"))
    client.post("/email-inquiries", json=_payload(title="남의 문의"),
                headers=customer_headers("CUST002"))

    mine = client.get("/email-inquiries", headers=customer_headers("CUST001")).json()

    assert [r["title"] for r in mine] == ["내 문의"]


def test_고객번호를_바꿔_불러도_남의_문의는_안_보인다(client, customer_headers):
    client.post("/email-inquiries", json=_payload(title="남의 문의"),
                headers=customer_headers("CUST002"))

    # 쿼리 파라미터로 고객번호를 받지 않으므로 이 값은 무시된다.
    res = client.get("/email-inquiries?customer_no=CUST002",
                     headers=customer_headers("CUST001"))

    assert res.json() == []
