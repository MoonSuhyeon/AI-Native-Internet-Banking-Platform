from fastapi.testclient import TestClient

from app.main import app, get_chatbot_service


def _client(service):
    app.dependency_overrides[get_chatbot_service] = lambda: service
    return TestClient(app)


def test_health_endpoint():
    client = TestClient(app)

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_categories_and_features_endpoints(service):
    client = _client(service)
    try:
        categories = client.get("/chatbot/categories")
        features = client.get("/chatbot/features")
        detail = client.get("/chatbot/features/PRODUCT_GUIDE")

        assert categories.status_code == 200
        assert features.status_code == 200
        assert detail.status_code == 200
        assert categories.json()[0]["code"] == "PRODUCT_ADVICE"
        assert any(feature["code"] == "MY_ACCOUNTS" for feature in features.json())
        assert detail.json()["name"] == "예금/적금/청약 상품 안내"
    finally:
        app.dependency_overrides.clear()


def test_feature_detail_not_found_returns_404(service):
    client = _client(service)
    try:
        response = client.get("/chatbot/features/UNKNOWN")

        assert response.status_code == 404
    finally:
        app.dependency_overrides.clear()


def test_feature_execute_endpoint(service):
    client = _client(service)
    try:
        response = client.post(
            "/chatbot/features/MY_ACCOUNTS/execute",
            json={"customer_no": "CUST001"},
        )

        body = response.json()
        assert response.status_code == 200
        assert body["feature_code"] == "MY_ACCOUNTS"
        assert body["status"] == "OK"
        assert body["requires_auth"] is True
        assert body["data"][0]["customer_no"] == "CUST001"
    finally:
        app.dependency_overrides.clear()


def test_feature_execute_not_found_returns_404(service):
    client = _client(service)
    try:
        response = client.post("/chatbot/features/UNKNOWN/execute", json={})

        assert response.status_code == 404
    finally:
        app.dependency_overrides.clear()


def test_chatbot_consultation_http_flow(service):
    client = _client(service)
    try:
        seed = client.post("/chatbot/scenarios/default")
        started = client.post(
            "/chatbot/consultations/start",
            json={
                "customer_no": "CUST001",
                "entry_screen": "HOME",
                "app_version": "0.1.0",
            },
        )
        start_body = started.json()
        message = client.post(
            f"/chatbot/consultations/{start_body['chatbot_consultation_id']}/messages",
            json={"message": "금융상품 상담", "button_value": "PRODUCT_ADVICE"},
        )

        assert seed.status_code == 200
        assert started.status_code == 200
        assert start_body["consultation_id"] > 0
        assert len(start_body["buttons"]) == 4
        assert message.status_code == 200
        assert message.json()["process_method"] == "SCENARIO"
    finally:
        app.dependency_overrides.clear()


def test_chatbot_message_missing_consultation_returns_404(service):
    client = _client(service)
    try:
        response = client.post(
            "/chatbot/consultations/999999/messages",
            json={"message": "없는 상담"},
        )

        assert response.status_code == 404
    finally:
        app.dependency_overrides.clear()
