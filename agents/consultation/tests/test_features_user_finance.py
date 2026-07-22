"""
USER_FINANCE 카테고리 기능 상세 테스트.

포함 기능:
  MY_ACCOUNTS       - 내 계좌 조회
  MY_PRODUCTS       - 가입 상품 조회
  CONTRACT_STATUS   - 계약 상태 조회
  MATURITY_SCHEDULE - 만기 예정 조회
  INTEREST_HISTORY  - 이자 내역 조회

공통 규칙:
  - customer_no 없이 호출 → AUTH_REQUIRED
  - customer_no 포함 호출  → OK (시드 데이터 기준)
"""

import pytest

from app.schemas import ChatbotFeatureExecuteRequest


# ── 공통 헬퍼 ────────────────────────────────────────────────────────────────

USER_FINANCE_FEATURES = [
    "MY_ACCOUNTS",
    "MY_PRODUCTS",
    "CONTRACT_STATUS",
    "MATURITY_SCHEDULE",
    "INTEREST_HISTORY",
]

CUST = "CUST001"


# ── 공통: 인증 필요 ─────────────────────────────────────────────────────────

class TestUserFinanceAuthRequired:
    @pytest.mark.parametrize("feature_code", USER_FINANCE_FEATURES)
    def test_missing_customer_no_returns_auth_required(self, service, feature_code):
        result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())
        assert result.status == "AUTH_REQUIRED"

    @pytest.mark.parametrize("feature_code", USER_FINANCE_FEATURES)
    def test_requires_auth_flag_set(self, service, feature_code):
        result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())
        assert result.requires_auth is True

    @pytest.mark.parametrize("feature_code", USER_FINANCE_FEATURES)
    def test_does_not_require_staff_auth(self, service, feature_code):
        result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())
        assert result.requires_staff_auth is False

    @pytest.mark.parametrize("feature_code", USER_FINANCE_FEATURES)
    def test_message_present_on_auth_required(self, service, feature_code):
        result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())
        assert result.message


# ── MY_ACCOUNTS ───────────────────────────────────────────────────────────────

class TestMyAccounts:
    def test_returns_ok_with_customer_no(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert len(result.data) >= 1

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        account = result.data[0]
        assert "account_id" in account
        assert "account_number" in account
        assert "customer_no" in account
        assert "account_type" in account
        assert "balance" in account
        assert "currency" in account
        assert "account_status" in account
        assert "opened_at" in account

    def test_customer_no_matches(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        for account in result.data:
            assert account["customer_no"] == CUST

    def test_seeded_account_number(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        numbers = [a["account_number"] for a in result.data]
        assert "001-123-000001" in numbers

    def test_seeded_account_balance(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        account = next(a for a in result.data if a["account_number"] == "001-123-000001")
        assert float(account["balance"]) == pytest.approx(5_000_000)

    def test_seeded_account_status_active(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        account = result.data[0]
        assert account["account_status"] == "ACTIVE"

    def test_seeded_currency_krw(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.data[0]["currency"] == "KRW"

    def test_unknown_customer_returns_empty(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no="NO_SUCH_CUST")
        )
        assert result.status == "EMPTY"
        assert result.data == []

    def test_requires_auth_flag(self, service):
        result = service.execute_feature(
            "MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.requires_auth is True


# ── MY_PRODUCTS ───────────────────────────────────────────────────────────────

class TestMyProducts:
    def test_returns_ok_with_customer_no(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert len(result.data) >= 1

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        item = result.data[0]
        assert "contract_id" in item
        assert "contract_no" in item
        assert "customer_no" in item
        assert "product_id" in item
        assert "product_name" in item
        assert "join_amount" in item
        assert "contract_interest_rate" in item
        assert "started_at" in item
        assert "maturity_at" in item
        assert "contract_status" in item

    def test_customer_no_matches(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        for item in result.data:
            assert item["customer_no"] == CUST

    def test_seeded_contract_number(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        contracts = [i["contract_no"] for i in result.data]
        assert "CTR-001" in contracts

    def test_seeded_product_name_joined(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        contract = next(i for i in result.data if i["contract_no"] == "CTR-001")
        assert contract["product_name"] == "정기예금 플러스"

    def test_seeded_join_amount(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        contract = result.data[0]
        assert float(contract["join_amount"]) == pytest.approx(5_000_000)

    def test_seeded_contract_status_active(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.data[0]["contract_status"] == "ACTIVE"

    def test_requires_auth_flag(self, service):
        result = service.execute_feature(
            "MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.requires_auth is True


# ── CONTRACT_STATUS ───────────────────────────────────────────────────────────

class TestContractStatus:
    def test_returns_ok_with_customer_no(self, service):
        result = service.execute_feature(
            "CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert len(result.data) >= 1

    def test_data_contains_status_fields(self, service):
        result = service.execute_feature(
            "CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        item = result.data[0]
        assert "contract_status" in item
        assert "started_at" in item
        assert "maturity_at" in item

    def test_seeded_started_at(self, service):
        result = service.execute_feature(
            "CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.data[0]["started_at"] == "20260101"

    def test_seeded_maturity_at(self, service):
        result = service.execute_feature(
            "CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.data[0]["maturity_at"] == "20270101"

    def test_requires_auth_flag(self, service):
        result = service.execute_feature(
            "CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.requires_auth is True


# ── MATURITY_SCHEDULE ─────────────────────────────────────────────────────────

class TestMaturitySchedule:
    def test_returns_ok_with_customer_no(self, service):
        result = service.execute_feature(
            "MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert len(result.data) >= 1

    def test_maturity_date_present(self, service):
        result = service.execute_feature(
            "MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert "maturity_at" in result.data[0]

    def test_seeded_maturity_date(self, service):
        result = service.execute_feature(
            "MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.data[0]["maturity_at"] == "20270101"

    def test_product_name_present(self, service):
        result = service.execute_feature(
            "MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.data[0]["product_name"] == "정기예금 플러스"

    def test_requires_auth_flag(self, service):
        result = service.execute_feature(
            "MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.requires_auth is True


# ── INTEREST_HISTORY ──────────────────────────────────────────────────────────

class TestInterestHistory:
    def test_returns_ok_with_customer_no(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert len(result.data) >= 1

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        item = result.data[0]
        assert "interest_id" in item
        assert "contract_id" in item
        assert "account_id" in item
        assert "applied_interest_rate" in item
        assert "interest_amount" in item
        assert "interest_after_tax_amount" in item
        assert "paid_at" in item

    def test_seeded_applied_rate(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert float(result.data[0]["applied_interest_rate"]) == pytest.approx(3.5)

    def test_seeded_interest_amount(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert float(result.data[0]["interest_amount"]) == pytest.approx(175_000)

    def test_seeded_after_tax_amount(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert float(result.data[0]["interest_after_tax_amount"]) == pytest.approx(148_050)

    def test_seeded_paid_at(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.data[0]["paid_at"] == "20261231"

    def test_unknown_customer_returns_empty(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no="NO_SUCH")
        )
        assert result.status == "EMPTY"

    def test_requires_auth_flag(self, service):
        result = service.execute_feature(
            "INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.requires_auth is True
