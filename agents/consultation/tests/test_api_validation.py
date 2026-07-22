from fastapi.testclient import TestClient

from app.main import app, get_chat_service, get_chatbot_service


def _client(service, chat_service):
    app.dependency_overrides[get_chatbot_service] = lambda: service
    app.dependency_overrides[get_chat_service] = lambda: chat_service
    return TestClient(app)


def _waiting_chat_id(client: TestClient) -> int:
    client.post("/chatbot/scenarios/default")
    start = client.post("/chatbot/consultations/start", json={"customer_no": "CUST001"}).json()
    client.post(
        f"/chatbot/consultations/{start['chatbot_consultation_id']}/messages",
        json={"message": "agent", "button_value": "AGENT"},
    )
    return client.get("/chat/queue").json()[0]["chat_consultation_id"]


def test_chat_page_returns_static_html():
    client = TestClient(app)

    response = client.get("/chat")

    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]


def test_chatbot_start_accepts_schema_defaults(service, chat_service):
    client = _client(service, chat_service)
    try:
        client.post("/chatbot/scenarios/default")

        response = client.post("/chatbot/consultations/start", json={})

        assert response.status_code == 200
        assert response.json()["chatbot_consultation_id"] > 0
    finally:
        app.dependency_overrides.clear()


def test_connect_agent_requires_employee_id(service, chat_service):
    client = _client(service, chat_service)
    try:
        chat_id = _waiting_chat_id(client)

        response = client.post(f"/chat/consultations/{chat_id}/connect", json={})

        assert response.status_code == 422
    finally:
        app.dependency_overrides.clear()


def test_send_chat_message_requires_message(service, chat_service):
    client = _client(service, chat_service)
    try:
        chat_id = _waiting_chat_id(client)
        client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})

        response = client.post(
            f"/chat/consultations/{chat_id}/messages",
            json={"sender_type": "AGENT"},
        )

        assert response.status_code == 422
    finally:
        app.dependency_overrides.clear()


def test_end_chat_rejects_satisfaction_score_below_range(service, chat_service):
    client = _client(service, chat_service)
    try:
        chat_id = _waiting_chat_id(client)
        client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})

        response = client.post(
            f"/chat/consultations/{chat_id}/end",
            json={"satisfaction_score": 0},
        )

        assert response.status_code == 422
    finally:
        app.dependency_overrides.clear()


def test_end_chat_rejects_satisfaction_score_above_range(service, chat_service):
    client = _client(service, chat_service)
    try:
        chat_id = _waiting_chat_id(client)
        client.post(f"/chat/consultations/{chat_id}/connect", json={"employee_id": 1})

        response = client.post(
            f"/chat/consultations/{chat_id}/end",
            json={"satisfaction_score": 6},
        )

        assert response.status_code == 422
    finally:
        app.dependency_overrides.clear()
