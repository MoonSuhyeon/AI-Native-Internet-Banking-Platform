"""
챗봇 기능 HTTP API 엔드포인트 테스트.

GET  /chatbot/categories
GET  /chatbot/features
GET  /chatbot/features/{feature_code}
POST /chatbot/features/{feature_code}/execute
"""

import pytest
from fastapi.testclient import TestClient

from app.main import app, get_chatbot_service


# ── 헬퍼 ────────────────────────────────────────────────────────────────────

def _client(service) -> TestClient:
    app.dependency_overrides[get_chatbot_service] = lambda: service
    return TestClient(app)


CUST = "CUST001"
STAFF = "EMP001"


# ── 카테고리 ─────────────────────────────────────────────────────────────────

class TestCategoriesEndpoint:
    def test_returns_200(self, service):
        client = _client(service)
        try:
            assert client.get("/chatbot/categories").status_code == 200
        finally:
            app.dependency_overrides.clear()

    def test_returns_three_categories(self, service):
        client = _client(service)
        try:
            body = client.get("/chatbot/categories").json()
            assert len(body) == 3
        finally:
            app.dependency_overrides.clear()

    def test_category_codes(self, service):
        client = _client(service)
        try:
            codes = [c["code"] for c in client.get("/chatbot/categories").json()]
            assert codes == ["PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT"]
        finally:
            app.dependency_overrides.clear()

    def test_category_has_required_fields(self, service):
        client = _client(service)
        try:
            cat = client.get("/chatbot/categories").json()[0]
            assert "code" in cat
            assert "name" in cat
            assert "description" in cat
            assert "features" in cat
        finally:
            app.dependency_overrides.clear()

    def test_product_advice_has_features(self, service):
        client = _client(service)
        try:
            cats = client.get("/chatbot/categories").json()
            pa = next(c for c in cats if c["code"] == "PRODUCT_ADVICE")
            assert len(pa["features"]) >= 1
            assert "PRODUCT_GUIDE" in pa["features"]
        finally:
            app.dependency_overrides.clear()

    def test_user_finance_has_features(self, service):
        client = _client(service)
        try:
            cats = client.get("/chatbot/categories").json()
            uf = next(c for c in cats if c["code"] == "USER_FINANCE")
            assert "MY_ACCOUNTS" in uf["features"]
            assert "INTEREST_HISTORY" in uf["features"]
        finally:
            app.dependency_overrides.clear()

    def test_staff_support_has_features(self, service):
        client = _client(service)
        try:
            cats = client.get("/chatbot/categories").json()
            ss = next(c for c in cats if c["code"] == "STAFF_SUPPORT")
            assert "STAFF_TRANSFER_FLOW" in ss["features"]
        finally:
            app.dependency_overrides.clear()


# ── 기능 목록 ─────────────────────────────────────────────────────────────────

class TestFeaturesListEndpoint:
    def test_returns_200(self, service):
        client = _client(service)
        try:
            assert client.get("/chatbot/features").status_code == 200
        finally:
            app.dependency_overrides.clear()

    def test_returns_all_features(self, service):
        client = _client(service)
        try:
            features = client.get("/chatbot/features").json()
            codes = {f["code"] for f in features}
            expected = {
                "PRODUCT_GUIDE", "RATE_GUIDE", "JOIN_CONDITION", "PRODUCT_COMPARE",
                "TERMS_RAG", "FAQ",
                "MY_ACCOUNTS", "MY_PRODUCTS", "CONTRACT_STATUS",
                "MATURITY_SCHEDULE", "INTEREST_HISTORY",
                "STAFF_CUSTOMER", "STAFF_CONTRACT", "STAFF_ACCOUNT",
                "STAFF_TRANSFER_FLOW", "STAFF_CONSULTATION_HISTORY",
            }
            assert expected.issubset(codes)
        finally:
            app.dependency_overrides.clear()

    def test_feature_has_required_fields(self, service):
        client = _client(service)
        try:
            feature = client.get("/chatbot/features").json()[0]
            assert "code" in feature
            assert "category_code" in feature
            assert "name" in feature
            assert "summary" in feature
            assert "sample_questions" in feature
            assert "api_status" in feature
        finally:
            app.dependency_overrides.clear()

    def test_total_count_is_positive(self, service):
        client = _client(service)
        try:
            features = client.get("/chatbot/features").json()
            assert len(features) > 0, "등록된 피처가 하나 이상 있어야 합니다"
        finally:
            app.dependency_overrides.clear()


# ── 기능 상세 ─────────────────────────────────────────────────────────────────

class TestFeatureDetailEndpoint:
    @pytest.mark.parametrize("feature_code", [
        "PRODUCT_GUIDE", "RATE_GUIDE", "JOIN_CONDITION", "PRODUCT_COMPARE",
        "TERMS_RAG", "FAQ",
        "MY_ACCOUNTS", "MY_PRODUCTS", "CONTRACT_STATUS",
        "MATURITY_SCHEDULE", "INTEREST_HISTORY",
        "STAFF_CUSTOMER", "STAFF_CONTRACT", "STAFF_ACCOUNT",
        "STAFF_TRANSFER_FLOW", "STAFF_CONSULTATION_HISTORY",
    ])
    def test_each_feature_returns_200(self, service, feature_code):
        client = _client(service)
        try:
            resp = client.get(f"/chatbot/features/{feature_code}")
            assert resp.status_code == 200
        finally:
            app.dependency_overrides.clear()

    def test_product_guide_detail(self, service):
        client = _client(service)
        try:
            body = client.get("/chatbot/features/PRODUCT_GUIDE").json()
            assert body["code"] == "PRODUCT_GUIDE"
            assert body["category_code"] == "PRODUCT_ADVICE"
            assert len(body["sample_questions"]) >= 1
        finally:
            app.dependency_overrides.clear()

    def test_unknown_feature_returns_404(self, service):
        client = _client(service)
        try:
            resp = client.get("/chatbot/features/NO_SUCH_FEATURE")
            assert resp.status_code == 404
        finally:
            app.dependency_overrides.clear()


# ── 기능 실행: PRODUCT_ADVICE ─────────────────────────────────────────────────

class TestExecuteProductAdvice:
    def test_product_guide_ok(self, service):
        client = _client(service)
        try:
            resp = client.post("/chatbot/features/PRODUCT_GUIDE/execute", json={})
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
            assert len(resp.json()["data"]) >= 1
        finally:
            app.dependency_overrides.clear()

    def test_rate_guide_ok(self, service):
        client = _client(service)
        try:
            resp = client.post("/chatbot/features/RATE_GUIDE/execute", json={})
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
        finally:
            app.dependency_overrides.clear()

    def test_join_condition_ok(self, service):
        client = _client(service)
        try:
            resp = client.post("/chatbot/features/JOIN_CONDITION/execute", json={})
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
        finally:
            app.dependency_overrides.clear()

    def test_product_compare_ok_no_ids(self, service):
        client = _client(service)
        try:
            resp = client.post("/chatbot/features/PRODUCT_COMPARE/execute", json={})
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
        finally:
            app.dependency_overrides.clear()

    def test_product_compare_ok_with_ids(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/PRODUCT_COMPARE/execute",
                json={"compare_product_ids": [1]},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
            assert resp.json()["data"][0]["product_id"] == 1
        finally:
            app.dependency_overrides.clear()

    def test_terms_rag_with_query(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/TERMS_RAG/execute",
                json={"query": "개인정보"},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
        finally:
            app.dependency_overrides.clear()

    def test_faq_ok(self, service):
        client = _client(service)
        try:
            resp = client.post("/chatbot/features/FAQ/execute", json={})
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "OK"
            assert len(body["data"]) >= 1
            assert "question" in body["data"][0]
            assert "answer" in body["data"][0]
        finally:
            app.dependency_overrides.clear()


# ── 기능 실행: USER_FINANCE ───────────────────────────────────────────────────

class TestExecuteUserFinance:
    @pytest.mark.parametrize("feature_code", [
        "MY_ACCOUNTS", "MY_PRODUCTS", "CONTRACT_STATUS",
        "MATURITY_SCHEDULE", "INTEREST_HISTORY",
    ])
    def test_no_customer_no_returns_auth_required(self, service, feature_code):
        client = _client(service)
        try:
            resp = client.post(f"/chatbot/features/{feature_code}/execute", json={})
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "AUTH_REQUIRED"
            assert body["requires_auth"] is True
        finally:
            app.dependency_overrides.clear()

    def test_my_accounts_with_customer_no(self, service):
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

    def test_my_products_with_customer_no(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/MY_PRODUCTS/execute",
                json={"customer_no": CUST},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
        finally:
            app.dependency_overrides.clear()

    def test_contract_status_with_customer_no(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/CONTRACT_STATUS/execute",
                json={"customer_no": CUST},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
        finally:
            app.dependency_overrides.clear()

    def test_maturity_schedule_with_customer_no(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/MATURITY_SCHEDULE/execute",
                json={"customer_no": CUST},
            )
            assert resp.status_code == 200
            assert resp.json()["data"][0]["maturity_at"] == "20270101"
        finally:
            app.dependency_overrides.clear()

    def test_interest_history_with_customer_no(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/INTEREST_HISTORY/execute",
                json={"customer_no": CUST},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "OK"
            assert float(body["data"][0]["interest_amount"]) == pytest.approx(175_000)
        finally:
            app.dependency_overrides.clear()


# ── 기능 실행: STAFF_SUPPORT ──────────────────────────────────────────────────

class TestExecuteStaffSupport:
    @pytest.mark.parametrize("feature_code", [
        "STAFF_CUSTOMER", "STAFF_CONTRACT", "STAFF_ACCOUNT",
        "STAFF_TRANSFER_FLOW", "STAFF_CONSULTATION_HISTORY",
    ])
    def test_no_staff_id_returns_staff_auth_required(self, service, feature_code):
        client = _client(service)
        try:
            resp = client.post(
                f"/chatbot/features/{feature_code}/execute",
                json={"customer_no": CUST},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "STAFF_AUTH_REQUIRED"
            assert body["requires_staff_auth"] is True
        finally:
            app.dependency_overrides.clear()

    def test_staff_customer_ok(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/STAFF_CUSTOMER/execute",
                json={"customer_no": CUST, "staff_id": STAFF},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
            assert resp.json()["data"][0]["customer_no"] == CUST
        finally:
            app.dependency_overrides.clear()

    def test_staff_contract_ok(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/STAFF_CONTRACT/execute",
                json={"customer_no": CUST, "staff_id": STAFF},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "OK"
            assert body["data"][0]["contract_no"] == "CTR-001"
        finally:
            app.dependency_overrides.clear()

    def test_staff_account_ok(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/STAFF_ACCOUNT/execute",
                json={"customer_no": CUST, "staff_id": STAFF},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "OK"
        finally:
            app.dependency_overrides.clear()

    def test_staff_transfer_flow_ok(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/STAFF_TRANSFER_FLOW/execute",
                json={"customer_no": CUST, "staff_id": STAFF},
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "OK"
            tx_numbers = [t["transaction_number"] for t in body["data"]]
            assert "TX-001" in tx_numbers
        finally:
            app.dependency_overrides.clear()

    def test_staff_transfer_flow_data_fields(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/STAFF_TRANSFER_FLOW/execute",
                json={"customer_no": CUST, "staff_id": STAFF},
            )
            tx = resp.json()["data"][0]
            assert "transaction_type" in tx
            assert "transaction_status" in tx
            assert "amount" in tx
            assert "account_number" in tx
        finally:
            app.dependency_overrides.clear()

    def test_staff_consultation_history_empty(self, service):
        client = _client(service)
        try:
            resp = client.post(
                "/chatbot/features/STAFF_CONSULTATION_HISTORY/execute",
                json={"customer_no": CUST, "staff_id": STAFF},
            )
            assert resp.status_code == 200
            assert resp.json()["status"] == "EMPTY"
        finally:
            app.dependency_overrides.clear()

    def test_unknown_feature_returns_404(self, service):
        client = _client(service)
        try:
            resp = client.post("/chatbot/features/NO_SUCH/execute", json={})
            assert resp.status_code == 404
        finally:
            app.dependency_overrides.clear()
