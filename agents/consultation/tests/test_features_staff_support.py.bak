"""
STAFF_SUPPORT 카테고리 기능 상세 테스트.

포함 기능:
  STAFF_CUSTOMER              - 고객 정보 조회
  STAFF_CONTRACT              - 고객 계약 조회
  STAFF_ACCOUNT               - 고객 계좌 조회
  STAFF_TRANSFER_FLOW         - 고객 이체 흐름 조회
  STAFF_CONSULTATION_HISTORY  - 상담 이력 조회

공통 규칙:
  - customer_no 만 있고 staff_id 없음 → STAFF_AUTH_REQUIRED
  - staff_id 만 있고 customer_no 없음 → STAFF_AUTH_REQUIRED
  - 둘 다 있음                         → OK (또는 EMPTY)
"""

import asyncio

import pytest

from app.schemas import ChatbotFeatureExecuteRequest


STAFF_FEATURES = [
    "STAFF_CUSTOMER",
    "STAFF_CONTRACT",
    "STAFF_ACCOUNT",
    "STAFF_TRANSFER_FLOW",
    "STAFF_CONSULTATION_HISTORY",
]

CUST = "CUST001"
STAFF = "EMP001"


# ── 공통: 직원 인증 필요 ────────────────────────────────────────────────────

class TestStaffAuthRequired:
    @pytest.mark.parametrize("feature_code", STAFF_FEATURES)
    def test_no_params_returns_staff_auth_required(self, service, feature_code):
        result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())
        assert result.status == "STAFF_AUTH_REQUIRED"

    @pytest.mark.parametrize("feature_code", STAFF_FEATURES)
    def test_customer_no_only_returns_staff_auth_required(self, service, feature_code):
        result = service.execute_feature(
            feature_code, ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.status == "STAFF_AUTH_REQUIRED"

    @pytest.mark.parametrize("feature_code", STAFF_FEATURES)
    def test_staff_id_only_returns_staff_auth_required(self, service, feature_code):
        result = service.execute_feature(
            feature_code, ChatbotFeatureExecuteRequest(staff_id=STAFF)
        )
        assert result.status == "STAFF_AUTH_REQUIRED"

    @pytest.mark.parametrize("feature_code", STAFF_FEATURES)
    def test_requires_staff_auth_flag_set(self, service, feature_code):
        result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())
        assert result.requires_staff_auth is True

    @pytest.mark.parametrize("feature_code", STAFF_FEATURES)
    def test_requires_auth_flag_not_set(self, service, feature_code):
        result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False

    @pytest.mark.parametrize("feature_code", STAFF_FEATURES)
    def test_message_present(self, service, feature_code):
        result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())
        assert result.message


# ── STAFF_CUSTOMER ────────────────────────────────────────────────────────────

class TestStaffCustomer:
    def test_returns_ok_with_both_params(self, service):
        result = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) >= 1

    def test_data_contains_account_fields(self, service):
        result = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        item = result.data[0]
        assert "account_id" in item
        assert "account_number" in item
        assert "customer_no" in item
        assert "balance" in item
        assert "account_status" in item

    def test_customer_no_matches(self, service):
        result = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for item in result.data:
            assert item["customer_no"] == CUST

    def test_seeded_account_number(self, service):
        result = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        numbers = [i["account_number"] for i in result.data]
        assert "001-123-000001" in numbers

    def test_requires_staff_auth_flag(self, service):
        result = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.requires_staff_auth is True

    def test_unknown_customer_returns_empty(self, service):
        result = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no="NO_SUCH", staff_id=STAFF),
        )
        assert result.status == "EMPTY"


# ── STAFF_CONTRACT ────────────────────────────────────────────────────────────

class TestStaffContract:
    def test_returns_ok_with_both_params(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) >= 1

    def test_data_contains_contract_fields(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
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

    def test_seeded_contract_no(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        contracts = [i["contract_no"] for i in result.data]
        assert "CTR-001" in contracts

    def test_seeded_product_name(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        contract = next(i for i in result.data if i["contract_no"] == "CTR-001")
        assert contract["product_name"] == "정기예금 플러스"

    def test_seeded_join_amount(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert float(result.data[0]["join_amount"]) == pytest.approx(5_000_000)

    def test_seeded_interest_rate(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert float(result.data[0]["contract_interest_rate"]) == pytest.approx(3.5)

    def test_requires_staff_auth_flag(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.requires_staff_auth is True


# ── STAFF_ACCOUNT ────────────────────────────────────────────────────────────

class TestStaffAccount:
    def test_returns_ok_with_both_params(self, service):
        result = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) >= 1

    def test_data_contains_account_fields(self, service):
        result = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        item = result.data[0]
        assert "account_id" in item
        assert "account_number" in item
        assert "customer_no" in item
        assert "account_type" in item
        assert "balance" in item
        assert "currency" in item
        assert "account_status" in item
        assert "opened_at" in item

    def test_seeded_account_number(self, service):
        result = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        numbers = [i["account_number"] for i in result.data]
        assert "001-123-000001" in numbers

    def test_seeded_balance(self, service):
        result = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        account = next(i for i in result.data if i["account_number"] == "001-123-000001")
        assert float(account["balance"]) == pytest.approx(5_000_000)

    def test_seeded_opened_at(self, service):
        result = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.data[0]["opened_at"] == "20260101"

    def test_closed_at_is_none(self, service):
        result = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.data[0]["closed_at"] is None

    def test_requires_staff_auth_flag(self, service):
        result = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.requires_staff_auth is True


# ── STAFF_TRANSFER_FLOW ───────────────────────────────────────────────────────

class TestStaffTransferFlow:
    def test_returns_ok_with_both_params(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) >= 1

    def test_data_contains_transaction_fields(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        item = result.data[0]
        assert "transaction_id" in item
        assert "transaction_number" in item
        assert "account_number" in item
        assert "customer_no" in item
        assert "transaction_type" in item
        assert "transaction_status" in item
        assert "amount" in item
        assert "created_at" in item

    def test_seeded_transaction_number(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        tx_numbers = [i["transaction_number"] for i in result.data]
        assert "TX-001" in tx_numbers

    def test_seeded_transaction_type(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        tx = next(i for i in result.data if i["transaction_number"] == "TX-001")
        assert tx["transaction_type"] == "TRANSFER"

    def test_seeded_transaction_status(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        tx = next(i for i in result.data if i["transaction_number"] == "TX-001")
        assert tx["transaction_status"] == "COMPLETED"

    def test_seeded_amount(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        tx = next(i for i in result.data if i["transaction_number"] == "TX-001")
        assert float(tx["amount"]) == pytest.approx(10_000)

    def test_account_number_joined(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        tx = result.data[0]
        assert tx["account_number"] == "001-123-000001"

    def test_customer_no_joined(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for tx in result.data:
            assert tx["customer_no"] == CUST

    def test_unknown_customer_returns_empty(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no="NO_SUCH", staff_id=STAFF),
        )
        assert result.status == "EMPTY"

    def test_requires_staff_auth_flag(self, service):
        result = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.requires_staff_auth is True


# ── STAFF_CONSULTATION_HISTORY ────────────────────────────────────────────────

class TestStaffConsultationHistory:
    def test_returns_empty_when_no_history(self, service):
        """상담 이력이 없을 때 EMPTY 반환 (시드 데이터에 consultation 없음)."""
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "EMPTY"
        assert result.data == []

    def test_returns_ok_after_chatbot_start(self, service):
        """챗봇 상담 시작 후 이력 조회 → OK."""
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "0.1.0"))

        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "OK"
        assert len(result.data) >= 1

    def test_history_contains_required_fields(self, service):
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "0.1.0"))

        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        item = result.data[0]
        assert "consultation_id" in item
        assert "customer_no" in item
        assert "content_summary" in item
        assert "consulted_at" in item

    def test_history_customer_no_matches(self, service):
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "0.1.0"))

        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for item in result.data:
            assert item["customer_no"] == CUST

    def test_multiple_consultations_ordered_desc(self, service):
        """여러 상담이면 최신 순으로 내림차순 정렬."""
        service.seed_default_scenario()
        asyncio.run(service.start(CUST, "HOME", "0.1.0"))
        asyncio.run(service.start(CUST, "HOME", "0.1.0"))

        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) >= 2
        ids = [i["consultation_id"] for i in result.data]
        assert ids == sorted(ids, reverse=True)

    def test_unknown_customer_returns_empty(self, service):
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no="NO_SUCH", staff_id=STAFF),
        )
        assert result.status == "EMPTY"

    def test_requires_staff_auth_flag(self, service):
        result = service.execute_feature(
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.requires_staff_auth is True
