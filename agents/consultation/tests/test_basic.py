from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.main import app, get_chatbot_service

CUST = "CUST001"
STAFF = "EMP001"


# ── 1차: 기본 검증 ─────────────────────────────────────────────────────────────

def test_app_importable():
    assert app is not None


def test_openapi_json_200():
    with patch("app.database.Base.metadata.create_all"):
        with TestClient(app) as client:
            response = client.get("/openapi.json")
    assert response.status_code == 200


# ── 2차: API 검증 ──────────────────────────────────────────────────────────────

def _client(service) -> TestClient:
    app.dependency_overrides[get_chatbot_service] = lambda: service
    return TestClient(app)


def test_상품_목록_조회(service):
    """GET /chatbot/features — 챗봇 기능(상품) 목록"""
    client = _client(service)
    try:
        resp = client.get("/chatbot/features")
        assert resp.status_code == 200
        features = resp.json()
        assert isinstance(features, list)
        assert len(features) > 0
        assert "code" in features[0]
    finally:
        app.dependency_overrides.clear()


def test_상품_상세_조회(service):
    """GET /chatbot/features/{feature_code} — PRODUCT_GUIDE 상세"""
    client = _client(service)
    try:
        resp = client.get("/chatbot/features/PRODUCT_GUIDE")
        assert resp.status_code == 200
        body = resp.json()
        assert body["code"] == "PRODUCT_GUIDE"
        assert body["category_code"] == "PRODUCT_ADVICE"
    finally:
        app.dependency_overrides.clear()


@pytest.mark.skip(reason="계약 생성 API 없음 — deposit-service 영역, consultation-service 미구현")
def test_계약_생성():
    pass


def test_계좌_조회(service):
    """POST /chatbot/features/MY_ACCOUNTS/execute — 고객 계좌 목록"""
    client = _client(service)
    try:
        resp = client.post(
            "/chatbot/features/MY_ACCOUNTS/execute",
            json={"customer_no": CUST},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "OK"
        assert body["data"][0]["customer_no"] == CUST
    finally:
        app.dependency_overrides.clear()


def test_거래내역_조회(service):
    """POST /chatbot/features/STAFF_TRANSFER_FLOW/execute — 거래내역"""
    client = _client(service)
    try:
        resp = client.post(
            "/chatbot/features/STAFF_TRANSFER_FLOW/execute",
            json={"customer_no": CUST, "staff_id": STAFF},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "OK"
        assert any(t["transaction_number"] == "TX-001" for t in body["data"])
    finally:
        app.dependency_overrides.clear()
