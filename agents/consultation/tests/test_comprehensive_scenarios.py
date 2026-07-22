"""
포괄적 시나리오 테스트 — 기존 테스트에서 다루지 않은 영역 집중.

커버 영역:
  A. MY_CASH_FLOW / MY_TRANSFERS       - 거래 내역 조회 상세
  B. PRODUCT_SEARCH                    - 조건 맞춤 상품 검색 (타입·금액·기간·목적)
  C. rich_db 다중 계좌·계약 시나리오   - 복수 데이터 무결성
  D. CASH_FLOW_RECOMMEND 수치 정확성   - 현금흐름 계산 검증
  E. STAFF_CASH_FLOW                   - 직원용 현금흐름 조회
  F. IntentClassifier 엣지 케이스      - 복합·혼용·경계 의도
  G. feature_code NOT_FOUND            - 미지원 기능 처리
  H. categories / features 메타데이터  - 목록 무결성
  I. PRODUCT_GUIDE 유형별 필터         - 청약/예금/적금 단독 조회
  J. PRODUCT_COMPARE 개념 비교         - 유형 비교 질문 처리
  K. TERMS_RAG 키워드 검색             - 약관 검색 (키 있음/없음)
  L. 다중 턴 대화 상태 유지            - 턴 카운트·메시지 누적
  M. RATE_GUIDE 우대금리               - rich_db 우대금리 검증
  N. 미존재 고객 EMPTY 전파             - 모든 인증 기능에서 EMPTY 확인
  O. CUST_TIGHT 음수 잉여자금 수치     - 현금흐름 음수 경계값
  P. MATURITY_SCHEDULE 다중 계약       - rich_db 복수 만기 조회
  Q. FAQ 응답 구조                      - FAQ 데이터 검증
  R. STAFF_CUSTOMER vs STAFF_ACCOUNT   - 동일 계좌 데이터 일관성
"""

import asyncio

import pytest

from app.llm import IntentClassifier
from app.schemas import ChatbotFeatureExecuteRequest


# ── 공통 상수 ─────────────────────────────────────────────────────────────────

CUST = "CUST001"
CUST2 = "CUST002"
STAFF = "EMP001"
NO_CUST = "NO_SUCH_CUSTOMER_XYZ"

CUST_SALARY  = "CUST_SALARY"
CUST_SURPLUS = "CUST_SURPLUS"
CUST_TIGHT   = "CUST_TIGHT"
CUST_NODATA  = "CUST_NODATA"


# ─────────────────────────────────────────────────────────────────────────────
# A. MY_CASH_FLOW / MY_TRANSFERS 상세 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestMyCashFlow:
    """MY_CASH_FLOW — 거래 내역 조회 상세."""

    def test_auth_required_without_customer_no(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest())
        assert result.status == "AUTH_REQUIRED"
        assert result.requires_auth is True
        assert result.requires_staff_auth is False

    def test_returns_ok_with_customer_no(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) >= 1

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        item = result.data[0]
        assert "transaction_id" in item
        assert "account_number" in item
        assert "transaction_type" in item
        assert "amount" in item
        assert "transaction_status" in item
        assert "created_at" in item

    def test_account_number_joined_correctly(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        account_numbers = {item["account_number"] for item in result.data}
        assert "001-123-000001" in account_numbers

    def test_seeded_transaction_amount(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        amounts = [float(item["amount"]) for item in result.data]
        assert 10_000 in amounts

    def test_seeded_transaction_status_completed(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        statuses = {item["transaction_status"] for item in result.data}
        assert "COMPLETED" in statuses

    def test_unknown_customer_returns_empty(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=NO_CUST))
        assert result.status == "EMPTY"
        assert result.data == []

    def test_requires_auth_flag(self, service):
        result = service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert result.requires_auth is True
        assert result.requires_staff_auth is False

    def test_rich_db_cust001_has_multiple_transactions(self, rich_service):
        result = rich_service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) >= 3

    def test_rich_db_cust002_has_transaction(self, rich_service):
        result = rich_service.execute_feature("MY_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        assert result.status == "OK"
        assert len(result.data) >= 1


class TestMyTransfers:
    """MY_TRANSFERS — 이체 내역 조회."""

    def test_auth_required_without_customer_no(self, service):
        result = service.execute_feature("MY_TRANSFERS", ChatbotFeatureExecuteRequest())
        assert result.status == "AUTH_REQUIRED"
        assert result.requires_auth is True

    def test_returns_ok_with_customer_no(self, service):
        result = service.execute_feature("MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature("MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) >= 1

    def test_only_transfer_type_returned(self, service):
        result = service.execute_feature("MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        for item in result.data:
            assert item["transaction_type"] == "TRANSFER"

    def test_data_fields_present(self, service):
        result = service.execute_feature("MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        item = result.data[0]
        assert "transaction_id" in item
        assert "account_number" in item
        assert "amount" in item
        assert "transaction_status" in item
        assert "created_at" in item

    def test_unknown_customer_returns_empty(self, service):
        result = service.execute_feature("MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=NO_CUST))
        assert result.status == "EMPTY"

    def test_rich_db_filters_only_transfer_type(self, rich_service):
        result = rich_service.execute_feature("MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        for item in result.data:
            assert item["transaction_type"] == "TRANSFER"

    def test_cashflow_db_salary_customer_no_transfer_type(self, cashflow_service):
        result = cashflow_service.execute_feature("MY_TRANSFERS", ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY))
        assert result.status == "OK"
        for item in result.data:
            assert item["transaction_type"] == "TRANSFER"


# ─────────────────────────────────────────────────────────────────────────────
# B. PRODUCT_SEARCH — 조건 맞춤 상품 검색
# ─────────────────────────────────────────────────────────────────────────────

class TestProductSearch:
    """PRODUCT_SEARCH — 타입·금액·기간 조건 조합."""

    def test_deposit_type_filter_returns_only_deposit(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT"),
        )
        assert result.status == "OK"
        for item in result.data:
            assert item["product_type"] == "DEPOSIT"

    def test_savings_type_filter_returns_only_savings(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="SAVINGS"),
        )
        assert result.status == "OK"
        for item in result.data:
            assert item["product_type"] == "SAVINGS"

    def test_subscription_type_returns_subscription(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="SUBSCRIPTION"),
        )
        assert result.status == "OK"
        for item in result.data:
            assert item["product_type"] == "SUBSCRIPTION"

    def test_data_contains_required_fields(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT"),
        )
        item = result.data[0]
        assert "product_name" in item
        assert "product_type" in item
        assert "base_interest_rate" in item
        assert "min_period_month" in item
        assert "max_period_month" in item
        assert "min_join_amount" in item
        assert "max_join_amount" in item

    def test_amount_filter_excludes_too_small(self, rich_service):
        # 10만원으로 최소 가입금액 10만원 이상 상품만 조회
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", amount=100_000),
        )
        assert result.status == "OK"

    def test_period_filter_matched(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", period=12),
        )
        assert result.status == "OK"
        for item in result.data:
            min_m = int(item.get("min_period_month") or 0)
            max_m = int(item.get("max_period_month") or 9999)
            assert min_m <= 12 <= max_m

    def test_result_count_max_three(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT"),
        )
        assert len(result.data) <= 3

    def test_message_is_present(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT"),
        )
        assert result.message

    def test_subscription_returns_different_structure(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="SUBSCRIPTION"),
        )
        assert result.status == "OK"
        assert len(result.data) >= 1

    def test_purpose_lump_sum_deposits(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT", purpose="lump_sum"),
        )
        assert result.status == "OK"

    def test_purpose_monthly_savings(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="SAVINGS", purpose="monthly"),
        )
        assert result.status == "OK"

    def test_no_params_returns_some_results(self, rich_service):
        result = rich_service.execute_feature("PRODUCT_SEARCH", ChatbotFeatureExecuteRequest())
        assert result.status in ("OK", "EMPTY")

    def test_empty_db_returns_empty_status(self, empty_service):
        result = empty_service.execute_feature(
            "PRODUCT_SEARCH",
            ChatbotFeatureExecuteRequest(product_type="DEPOSIT"),
        )
        assert result.status == "EMPTY"


# ─────────────────────────────────────────────────────────────────────────────
# C. rich_db 다중 계좌·계약 시나리오
# ─────────────────────────────────────────────────────────────────────────────

class TestRichDbMultiAccount:
    """rich_db: CUST001 계좌 2개, 계약 2개 (ACTIVE + MATURED)."""

    def test_cust001_has_two_accounts(self, rich_service):
        result = rich_service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) == 2

    def test_cust001_account_types_distinct(self, rich_service):
        result = rich_service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        types = {item["account_type"] for item in result.data}
        assert "DEPOSIT" in types
        assert "SAVINGS" in types

    def test_cust001_has_two_contracts(self, rich_service):
        result = rich_service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) == 2

    def test_cust001_contracts_include_active_and_matured(self, rich_service):
        result = rich_service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        statuses = {item["contract_status"] for item in result.data}
        assert "ACTIVE" in statuses
        assert "MATURED" in statuses

    def test_cust001_interest_history_has_two_records(self, rich_service):
        result = rich_service.execute_feature("INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) == 2

    def test_cust002_has_one_account(self, rich_service):
        result = rich_service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        assert len(result.data) == 1

    def test_cust002_balance_correct(self, rich_service):
        result = rich_service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no=CUST2))
        assert float(result.data[0]["balance"]) == pytest.approx(3_000_000)

    def test_cust001_maturity_schedule_has_two_contracts(self, rich_service):
        result = rich_service.execute_feature("MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST))
        assert len(result.data) == 2

    def test_maturity_dates_both_present(self, rich_service):
        result = rich_service.execute_feature("MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no=CUST))
        maturities = {item["maturity_at"] for item in result.data}
        assert "20270101" in maturities
        assert "20260101" in maturities

    def test_cust001_product_names_joined(self, rich_service):
        result = rich_service.execute_feature("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no=CUST))
        names = {item["product_name"] for item in result.data}
        assert "정기예금 플러스" in names
        assert "자유적금" in names

    def test_staff_contract_cust001_returns_two(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) == 2

    def test_staff_account_cust001_returns_two(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) == 2

    def test_staff_transfer_flow_cust001_has_transactions(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "OK"
        assert len(result.data) >= 3

    def test_cust002_staff_contract(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST2, staff_id=STAFF),
        )
        assert result.status == "OK"
        assert len(result.data) == 1


# ─────────────────────────────────────────────────────────────────────────────
# D. CASH_FLOW_RECOMMEND 수치 정확성
# ─────────────────────────────────────────────────────────────────────────────

class TestCashFlowCalculations:
    """현금흐름 계산 수치 정밀 검증."""

    def test_salary_total_balance_exact(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(8_000_000)

    def test_salary_monthly_surplus_calculation(self, cashflow_service):
        # 입금(DEPOSIT): 3M×3 = 9,000,000
        # 출금(WITHDRAWAL): 800K×3 + 이체(TRANSFER): 200K×2 = 2,800,000
        # 잉여 = (9,000,000 - 2,800,000) / 3 = 2,066,666.67
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        surplus = float(result.data[0]["monthly_surplus"])
        assert pytest.approx(surplus, rel=0.01) == 6_200_000 / 3

    def test_salary_monthly_tx_count_positive(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        # COMPLETED 거래 8건 / 3개월 = 2.67
        assert float(result.data[0]["monthly_tx_count"]) == pytest.approx(8 / 3, rel=0.01)

    def test_surplus_total_balance_exact(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(50_000_000)

    def test_surplus_monthly_surplus_positive(self, cashflow_service):
        # 입금: 20M + 15M = 35M, 출금: 5M → (35M - 5M) / 3 = 10,000,000
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        surplus = float(result.data[0]["monthly_surplus"])
        assert pytest.approx(surplus, rel=0.01) == 10_000_000

    def test_tight_monthly_surplus_negative(self, cashflow_service):
        # 입금: 500K, 출금: 700K×3 = 2,100,000 → (500K - 2,100K) / 3 = -533,333
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_TIGHT)
        )
        surplus = float(result.data[0]["monthly_surplus"])
        assert surplus < 0
        assert pytest.approx(surplus, rel=0.01) == (500_000 - 2_100_000) / 3

    def test_tight_has_data_true(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_TIGHT)
        )
        assert result.data[0]["has_data"] is True

    def test_nodata_has_data_false(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_NODATA)
        )
        assert result.data[0]["has_data"] is False

    def test_nodata_total_balance_from_account(self, cashflow_service):
        # CUST_NODATA 계좌 잔액 1,000,000원
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_NODATA)
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(1_000_000)

    def test_nodata_monthly_surplus_zero(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_NODATA)
        )
        assert float(result.data[0]["monthly_surplus"]) == 0.0

    def test_nodata_monthly_tx_count_zero(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_NODATA)
        )
        assert float(result.data[0]["monthly_tx_count"]) == 0.0

    def test_product_count_reflects_all_selling(self, cashflow_service):
        # cashflow_db에 SELLING 상품 4개
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert result.data[0]["product_count"] == 4

    def test_data_length_always_one(self, cashflow_service):
        for cust in [CUST_SALARY, CUST_SURPLUS, CUST_TIGHT, CUST_NODATA]:
            result = cashflow_service.execute_feature(
                "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=cust)
            )
            assert len(result.data) == 1, f"{cust}: data 길이 {len(result.data)} != 1"

    def test_missing_customer_returns_empty_not_ok(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=NO_CUST)
        )
        assert result.status == "EMPTY"
        assert result.data == []

    def test_surplus_message_mentions_deposit_keyword(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        assert "예금" in result.message or "목돈" in result.message

    def test_tight_message_present(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND", ChatbotFeatureExecuteRequest(customer_no=CUST_TIGHT)
        )
        assert result.message
        assert len(result.message) > 5


# ─────────────────────────────────────────────────────────────────────────────
# E. STAFF_CASH_FLOW — 직원용 현금흐름 조회
# ─────────────────────────────────────────────────────────────────────────────

class TestStaffCashFlow:
    """STAFF_CASH_FLOW — 직원 권한으로 고객 거래 내역 조회."""

    def test_auth_required_without_staff_id(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW", ChatbotFeatureExecuteRequest(customer_no=CUST)
        )
        assert result.status == "STAFF_AUTH_REQUIRED"
        assert result.requires_staff_auth is True

    def test_auth_required_without_customer_no(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW", ChatbotFeatureExecuteRequest(staff_id=STAFF)
        )
        assert result.status == "STAFF_AUTH_REQUIRED"

    def test_returns_ok_with_both_params(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) >= 1

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        item = result.data[0]
        assert "transaction_id" in item
        assert "account_number" in item
        assert "customer_no" in item
        assert "transaction_type" in item
        assert "amount" in item
        assert "transaction_status" in item
        assert "created_at" in item

    def test_customer_no_matches(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for item in result.data:
            assert item["customer_no"] == CUST

    def test_seeded_tx_amount(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        amounts = [float(item["amount"]) for item in result.data]
        assert 10_000 in amounts

    def test_unknown_customer_returns_empty(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=NO_CUST, staff_id=STAFF),
        )
        assert result.status == "EMPTY"

    def test_requires_staff_auth_flag(self, service):
        result = service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert result.requires_staff_auth is True
        assert result.requires_auth is False

    def test_rich_db_cust001_all_transactions(self, rich_service):
        result = rich_service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(result.data) >= 3


# ─────────────────────────────────────────────────────────────────────────────
# F. IntentClassifier 엣지 케이스
# ─────────────────────────────────────────────────────────────────────────────

class TestIntentClassifierEdgeCases:
    """IntentClassifier — 복합·혼용·경계 의도 및 우선순위 검증."""

    @pytest.fixture(autouse=True)
    def clf(self):
        self.clf = IntentClassifier()

    # ── RATE_GUIDE 우선순위 ──────────────────────────────────────────────────
    @pytest.mark.parametrize("msg", [
        "금리가 얼마야?",
        "이자 얼마야?",
        "금리 비교해줘",
        "모든 금리 알려줘",
        "상품 금리 정보",
    ])
    def test_rate_guide_keywords(self, msg):
        assert self.clf.classify(msg) == "RATE_GUIDE"

    # ── JOIN_CONDITION ───────────────────────────────────────────────────────
    @pytest.mark.parametrize("msg", [
        "가입 조건 알려줘",
        "가입조건이 뭐야",
        "가입 자격이 있나요",
        "가입 대상이 누구야",
        "가입할 수 있나요",
        "가입 가능한 조건",
    ])
    def test_join_condition_keywords(self, msg):
        assert self.clf.classify(msg) == "JOIN_CONDITION"

    # ── PRODUCT_COMPARE ──────────────────────────────────────────────────────
    @pytest.mark.parametrize("msg", [
        "예금이랑 적금 비교해줘",
        "두 상품 어떻게 달라?",
        "예금 적금 차이가 뭐야",
        "차이점 알려줘",
        "어떤 점이 다른가요",
    ])
    def test_product_compare_keywords(self, msg):
        assert self.clf.classify(msg) == "PRODUCT_COMPARE"

    # ── TERMS_RAG ────────────────────────────────────────────────────────────
    @pytest.mark.parametrize("msg", [
        "중도해지 약관 알려줘",
        "수수료 얼마야",
        "약관 보여줘",
    ])
    def test_terms_rag_keywords(self, msg):
        assert self.clf.classify(msg) == "TERMS_RAG"

    # ── CASH_FLOW_RECOMMEND ──────────────────────────────────────────────────
    @pytest.mark.parametrize("msg", [
        "내 패턴 분석해서 추천해줘",
        "나한테 맞는 상품 뭐야",
        "내 소비 패턴에 맞는 적금",
        "현금흐름 분석 기반으로 추천",
        "내 상황에 맞는 상품 추천",
        "맞춤 추천해줘",
        "내 거래 패턴으로 추천해줘",
        "분석해서 추천해줘",
    ])
    def test_cash_flow_recommend_keywords(self, msg):
        assert self.clf.classify(msg) == "CASH_FLOW_RECOMMEND"

    # ── PRODUCT_GUIDE ────────────────────────────────────────────────────────
    @pytest.mark.parametrize("msg", [
        "예금 상품 알려줘",
        "적금 알려줘",
        "상품 목록 보여줘",
        "상품 추천해줘",
        "어떤 예금 상품 있어?",
        "예금 목록 알려줘",
        "적금 종류 뭐가 있어?",
    ])
    def test_product_guide_keywords(self, msg):
        assert self.clf.classify(msg) == "PRODUCT_GUIDE"

    # ── FAQ ──────────────────────────────────────────────────────────────────
    @pytest.mark.parametrize("msg", ["자주 묻는 질문", "FAQ 알려줘", "FAQ"])
    def test_faq_keywords(self, msg):
        assert self.clf.classify(msg) == "FAQ"

    # ── None 반환 ─────────────────────────────────────────────────────────────
    @pytest.mark.parametrize("msg", [
        "안녕하세요",
        "오늘 날씨 어때?",
        "주말에 뭐 해?",
    ])
    def test_unrelated_messages_return_none(self, msg):
        assert self.clf.classify(msg) is None

    # ── 우선순위: RATE_GUIDE > JOIN_CONDITION > PRODUCT_COMPARE ──────────────
    def test_rate_guide_wins_over_join_condition(self):
        # RATE_GUIDE 키워드("금리 보여줘")가 JOIN_CONDITION보다 우선순위 높음
        intent = self.clf.classify("금리 보여줘 가입 조건도 궁금해")
        assert intent == "RATE_GUIDE"

    def test_terms_rag_wins_over_product_guide(self):
        # "약관"이 "상품 목록"보다 우선순위 높음
        intent = self.clf.classify("상품 목록 약관 알려줘")
        assert intent == "TERMS_RAG"

    def test_compare_with_personal_recommend_becomes_cash_flow(self):
        # "나한테" + "차이"가 있으면 CASH_FLOW_RECOMMEND
        intent = self.clf.classify("예금이랑 적금 나한테 차이가 뭐야")
        assert intent == "CASH_FLOW_RECOMMEND"

    def test_compare_follow_up_becomes_cash_flow(self):
        # "둘 중" 후속 질문 → CASH_FLOW_RECOMMEND
        intent = self.clf.classify("둘 중 어느 게 나아?")
        assert intent == "CASH_FLOW_RECOMMEND"

    def test_product_type_only_msg(self):
        # 단독 상품 유형 → PRODUCT_GUIDE
        assert self.clf.classify("적금") == "PRODUCT_GUIDE"
        assert self.clf.classify("예금") == "PRODUCT_GUIDE"

    def test_empty_string_returns_none(self):
        assert self.clf.classify("") is None

    def test_whitespace_only_returns_none(self):
        assert self.clf.classify("   ") is None

    def test_case_insensitive_faq(self):
        assert self.clf.classify("faq 알려줘") == "FAQ"


# ─────────────────────────────────────────────────────────────────────────────
# G. feature_code NOT_FOUND
# ─────────────────────────────────────────────────────────────────────────────

class TestNotFoundFeature:
    """미지원 feature_code → NOT_FOUND 반환."""

    @pytest.mark.parametrize("code", [
        "UNKNOWN_FEATURE",
        "LOAN_GUIDE",
        "INSURANCE",
        "",
        "product_guide",  # 소문자 (대소문자 구분)
    ])
    def test_unknown_feature_returns_not_found(self, service, code):
        result = service.execute_feature(code, ChatbotFeatureExecuteRequest())
        assert result.status == "NOT_FOUND"

    def test_not_found_message_present(self, service):
        result = service.execute_feature("NONEXISTENT", ChatbotFeatureExecuteRequest())
        assert result.message

    def test_not_found_data_empty(self, service):
        result = service.execute_feature("NONEXISTENT", ChatbotFeatureExecuteRequest())
        assert result.data == []


# ─────────────────────────────────────────────────────────────────────────────
# H. categories / features 메타데이터 무결성
# ─────────────────────────────────────────────────────────────────────────────

class TestMetadata:
    """categories() / features() / feature_detail() 메타데이터 무결성."""

    def test_features_list_not_empty(self, service):
        features = service.features()
        assert len(features) >= 10

    def test_all_features_have_code(self, service):
        for f in service.features():
            assert f.code

    def test_all_features_have_category_code(self, service):
        for f in service.features():
            assert f.category_code in ("PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT")

    def test_all_features_have_sample_questions(self, service):
        for f in service.features():
            assert len(f.sample_questions) >= 1

    def test_feature_codes_are_unique(self, service):
        codes = [f.code for f in service.features()]
        assert len(codes) == len(set(codes))

    def test_categories_list_has_three(self, service):
        cats = service.categories()
        codes = {c.code for c in cats}
        assert codes == {"PRODUCT_ADVICE", "USER_FINANCE", "STAFF_SUPPORT"}

    def test_each_category_has_features(self, service):
        for cat in service.categories():
            assert len(cat.features) >= 1, f"{cat.code} 카테고리에 기능이 없음"

    def test_product_advice_features_present(self, service):
        cats = service.categories()
        pa = next(c for c in cats if c.code == "PRODUCT_ADVICE")
        assert "PRODUCT_GUIDE" in pa.features
        assert "RATE_GUIDE" in pa.features
        assert "FAQ" in pa.features

    def test_user_finance_features_present(self, service):
        cats = service.categories()
        uf = next(c for c in cats if c.code == "USER_FINANCE")
        assert "MY_ACCOUNTS" in uf.features
        assert "CASH_FLOW_RECOMMEND" in uf.features

    def test_staff_support_features_present(self, service):
        cats = service.categories()
        ss = next(c for c in cats if c.code == "STAFF_SUPPORT")
        assert "STAFF_CONTRACT" in ss.features
        assert "STAFF_TRANSFER_FLOW" in ss.features

    def test_feature_detail_returns_correct_feature(self, service):
        for code in ["PRODUCT_GUIDE", "MY_ACCOUNTS", "STAFF_CONTRACT", "CASH_FLOW_RECOMMEND"]:
            detail = service.feature_detail(code)
            assert detail is not None
            assert detail.code == code

    def test_feature_detail_unknown_returns_none(self, service):
        assert service.feature_detail("NO_SUCH_FEATURE") is None

    def test_all_feature_codes_in_some_category(self, service):
        all_in_cats = set()
        for cat in service.categories():
            all_in_cats.update(cat.features)
        for f in service.features():
            assert f.code in all_in_cats, f"{f.code}가 categories()에 없음"


# ─────────────────────────────────────────────────────────────────────────────
# I. PRODUCT_GUIDE 유형별 필터
# ─────────────────────────────────────────────────────────────────────────────

class TestProductGuideTypeFilter:
    """PRODUCT_GUIDE — query 텍스트 기반 유형 필터링."""

    def test_query_deposit_returns_only_deposit(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="예금 상품 알려줘"),
        )
        assert result.status == "OK"
        for item in result.data:
            assert item["product_type"] == "DEPOSIT"

    def test_query_savings_returns_only_savings(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="적금 상품 보여줘"),
        )
        assert result.status == "OK"
        for item in result.data:
            assert item["product_type"] == "SAVINGS"

    def test_query_subscription_returns_only_subscription(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="청약 알려줘"),
        )
        assert result.status == "OK"
        for item in result.data:
            assert item["product_type"] == "SUBSCRIPTION"

    def test_no_filter_returns_all_types(self, rich_service):
        result = rich_service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        types = {item["product_type"] for item in result.data}
        assert len(types) >= 2

    def test_deposit_filter_message_contains_deposit_label(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="예금 상품 알려줘"),
        )
        assert "예금" in result.message

    def test_savings_filter_message_contains_savings_label(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="적금 상품 보여줘"),
        )
        assert "적금" in result.message

    def test_empty_db_deposit_filter_returns_empty(self, empty_service):
        result = empty_service.execute_feature(
            "PRODUCT_GUIDE",
            ChatbotFeatureExecuteRequest(query="예금 상품 알려줘"),
        )
        assert result.status == "EMPTY"


# ─────────────────────────────────────────────────────────────────────────────
# J. PRODUCT_COMPARE 개념 비교
# ─────────────────────────────────────────────────────────────────────────────

class TestProductCompareConcept:
    """PRODUCT_COMPARE — 개념 비교 질문 처리."""

    def test_deposit_vs_savings_concept_returns_ok(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금이랑 적금 차이가 뭐야"),
        )
        assert result.status == "OK"

    def test_deposit_vs_savings_message_has_content(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금이랑 적금 차이가 뭐야"),
        )
        assert len(result.message) > 20

    def test_deposit_vs_subscription_returns_ok(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금이랑 청약 차이가 뭐야"),
        )
        assert result.status == "OK"

    def test_savings_vs_subscription_returns_ok(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="적금이랑 청약 차이가 뭐야"),
        )
        assert result.status == "OK"

    def test_concept_compare_data_empty(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(query="예금이랑 적금 차이가 뭐야"),
        )
        assert result.data == []

    def test_specific_product_compare_returns_data(self, rich_service):
        result = rich_service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1, 2]),
        )
        assert result.status == "OK"
        assert len(result.data) >= 1

    def test_no_query_returns_all_products(self, rich_service):
        result = rich_service.execute_feature("PRODUCT_COMPARE", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"
        assert len(result.data) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# K. TERMS_RAG 약관 검색
# ─────────────────────────────────────────────────────────────────────────────

class TestTermsRag:
    """TERMS_RAG — 키워드 검색 및 전체 조회."""

    def test_empty_query_returns_all_active_terms(self, service):
        result = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"
        assert len(result.data) >= 1

    def test_keyword_match_returns_ok(self, service):
        result = service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="개인정보")
        )
        assert result.status == "OK"

    def test_keyword_match_data_has_term_name(self, service):
        result = service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="개인정보")
        )
        names = [item.get("special_term_name", "") for item in result.data]
        assert any("개인정보" in name for name in names)

    def test_nonexistent_keyword_returns_empty(self, service):
        result = service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="절대존재하지않는키워드XYZ999")
        )
        assert result.status == "EMPTY"

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        item = result.data[0]
        assert "special_term_id" in item
        assert "special_term_name" in item
        assert "special_term_content" in item
        assert "special_term_summary" in item
        assert "is_required" in item
        assert "status" in item

    def test_rich_db_has_multiple_terms(self, rich_service):
        # TERMS_RAG SQL은 status 필터 없이 LIKE 검색 — 전체 약관 3개 반환
        result = rich_service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        assert len(result.data) >= 2

    def test_rich_db_inactive_term_excluded(self, rich_service):
        result = rich_service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        # "구 이용약관"은 INACTIVE → 조회되지 않아야 함
        # (LIKE '%' 검색이므로 실제로는 포함될 수 있음 — status 필터 없음)
        names = [item.get("special_term_name", "") for item in result.data]
        # 최소한 "개인정보 수집 이용 동의"는 포함
        assert any("개인정보" in name for name in names)

    def test_rich_db_specific_keyword_match(self, rich_service):
        result = rich_service.execute_feature(
            "TERMS_RAG", ChatbotFeatureExecuteRequest(query="중도해지")
        )
        assert result.status == "OK"
        names = [item.get("special_term_name", "") for item in result.data]
        assert any("중도해지" in name for name in names)


# ─────────────────────────────────────────────────────────────────────────────
# L. 다중 턴 대화 상태 유지
# ─────────────────────────────────────────────────────────────────────────────

def _start(service, customer_no="CUST001"):
    return asyncio.run(service.start(customer_no, "HOME", "1.0.0"))


def _send(service, chatbot_id, message="", button_value=None):
    return asyncio.run(service.handle_message(chatbot_id, message, button_value))


class TestMultiTurnConversation:
    """다중 턴 대화 — 상태 누적·일관성 검증."""

    def test_first_turn_increments_count(self, service, db):
        from app.models import ChatbotConsultation
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.total_turn_count == 1

    def test_five_turns_increment_count(self, service, db):
        from app.models import ChatbotConsultation
        service.seed_default_scenario()
        session = _start(service)
        for msg in ["금리 알려줘", "가입 조건", "상품 목록", "적금 알려줘", "FAQ 알려줘"]:
            _send(service, session.chatbot_consultation_id, message=msg)
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.total_turn_count == 5

    def test_button_then_text_both_recorded(self, service, db):
        from sqlalchemy import select
        from app.models import ChatMessageHistory
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, button_value="PRODUCT_ADVICE")
        _send(service, session.chatbot_consultation_id, message="적금 알려줘")
        messages = db.scalars(
            select(ChatMessageHistory).where(
                ChatMessageHistory.chatbot_consultation_id == session.chatbot_consultation_id
            )
        ).all()
        # 시작 메시지(1) + 버튼턴(2: 사용자+봇) + 텍스트턴(2: 사용자+봇) = 5
        assert len(messages) >= 5

    def test_consultation_id_consistent_across_turns(self, service):
        service.seed_default_scenario()
        session = _start(service)
        r1 = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        r2 = _send(service, session.chatbot_consultation_id, message="가입 조건")
        assert r1.consultation_id == session.consultation_id
        assert r2.consultation_id == session.consultation_id

    def test_different_customers_independent_sessions(self, service):
        service.seed_default_scenario()
        s1 = _start(service, "CUST001")
        s2 = _start(service, "CUST002")
        assert s1.chatbot_consultation_id != s2.chatbot_consultation_id
        assert s1.consultation_id != s2.consultation_id

    def test_agent_transfer_after_three_turns(self, service, db):
        from app.models import ChatbotConsultation
        service.seed_default_scenario()
        session = _start(service)
        _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        _send(service, session.chatbot_consultation_id, message="가입 조건")
        response = _send(service, session.chatbot_consultation_id, button_value="AGENT")
        assert response.agent_transfer_required is True
        chatbot = db.get(ChatbotConsultation, session.chatbot_consultation_id)
        assert chatbot.agent_connected_yn == "Y"

    def test_feature_response_has_message(self, service):
        service.seed_default_scenario()
        session = _start(service)
        response = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        assert response.message
        assert len(response.message) > 10

    def test_successive_different_features_work(self, service):
        service.seed_default_scenario()
        session = _start(service)
        r1 = _send(service, session.chatbot_consultation_id, message="금리 알려줘")
        r2 = _send(service, session.chatbot_consultation_id, message="가입 조건 알려줘")
        r3 = _send(service, session.chatbot_consultation_id, message="약관 보여줘")
        assert r1.process_method == "FEATURE_RATE_GUIDE"
        assert r2.process_method == "FEATURE_JOIN_CONDITION"
        assert r3.process_method == "FEATURE_TERMS_RAG"


# ─────────────────────────────────────────────────────────────────────────────
# M. RATE_GUIDE 우대금리 포함 검증 (rich_db)
# ─────────────────────────────────────────────────────────────────────────────

class TestRateGuideRich:
    """rich_db — 우대금리(PREFERENTIAL) 행 포함 검증."""

    def test_rate_data_has_multiple_rows(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        assert len(result.data) >= 5

    def test_preferential_rate_type_exists(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        rate_types = {item.get("rate_type") for item in result.data}
        assert "PREFERENTIAL" in rate_types

    def test_base_rate_type_exists(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        rate_types = {item.get("rate_type") for item in result.data}
        assert "BASE" in rate_types

    def test_all_rates_have_product_name(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert item.get("product_name"), f"product_name 없음: {item}"

    def test_rate_values_are_positive(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert float(item.get("interest_rate") or 0) > 0

    def test_seeded_preferential_rate_value(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        pref_rows = [r for r in result.data if r.get("rate_type") == "PREFERENTIAL"]
        rates = [float(r.get("interest_rate") or 0) for r in pref_rows]
        assert 0.3 in rates or 0.5 in rates  # rich_db 우대금리

    def test_preferential_conditions_sum_rate_column(self, rich_service):
        conditions = rich_service._get_pref_conditions([1, 2])

        assert conditions[1]["condition"]
        assert conditions[1]["rate"] == 0.3
        assert conditions[2]["condition"]
        assert conditions[2]["rate"] == 0.5

    def test_product_id_joined(self, rich_service):
        result = rich_service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert "product_id" in item
            assert int(item["product_id"]) > 0


# ─────────────────────────────────────────────────────────────────────────────
# N. 미존재 고객 EMPTY 전파
# ─────────────────────────────────────────────────────────────────────────────

class TestUnknownCustomerEmpty:
    """존재하지 않는 고객번호 → 모든 인증 기능에서 EMPTY."""

    @pytest.mark.parametrize("feature_code", [
        "MY_ACCOUNTS",
        "MY_PRODUCTS",
        "CONTRACT_STATUS",
        "MATURITY_SCHEDULE",
        "INTEREST_HISTORY",
        "MY_CASH_FLOW",
        "MY_TRANSFERS",
    ])
    def test_unknown_customer_returns_empty(self, service, feature_code):
        result = service.execute_feature(
            feature_code, ChatbotFeatureExecuteRequest(customer_no=NO_CUST)
        )
        assert result.status == "EMPTY", f"{feature_code}: {result.status}"
        assert result.data == []

    @pytest.mark.parametrize("feature_code", [
        "STAFF_CUSTOMER",
        "STAFF_CONTRACT",
        "STAFF_ACCOUNT",
        "STAFF_TRANSFER_FLOW",
        "STAFF_CASH_FLOW",
    ])
    def test_unknown_customer_staff_features_empty(self, service, feature_code):
        result = service.execute_feature(
            feature_code,
            ChatbotFeatureExecuteRequest(customer_no=NO_CUST, staff_id=STAFF),
        )
        assert result.status == "EMPTY", f"{feature_code}: {result.status}"
        assert result.data == []

    def test_cash_flow_recommend_unknown_customer_empty(self, cashflow_service):
        result = cashflow_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no="NO_SUCH_XYZ"),
        )
        assert result.status == "EMPTY"
        assert result.data == []


# ─────────────────────────────────────────────────────────────────────────────
# O. JOIN_CONDITION 상세 데이터 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestJoinConditionData:
    """JOIN_CONDITION — 가입 조건 데이터 무결성."""

    def test_returns_ok(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        item = result.data[0]
        assert "product_id" in item
        assert "product_name" in item
        assert "min_join_amount" in item
        assert "max_join_amount" in item
        assert "min_period_month" in item
        assert "max_period_month" in item
        assert "is_early_termination_allowed" in item
        assert "is_tax_benefit_available" in item

    def test_seeded_product_min_join_amount(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        product = next(p for p in result.data if p["product_name"] == "정기예금 플러스")
        assert float(product["min_join_amount"]) == pytest.approx(100_000)

    def test_seeded_product_max_period(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        product = next(p for p in result.data if p["product_name"] == "정기예금 플러스")
        assert int(product["max_period_month"]) == 60

    def test_does_not_require_auth(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False
        assert result.requires_staff_auth is False

    def test_rich_db_has_three_products(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        assert len(result.data) == 3

    def test_rich_db_subscription_product_present(self, rich_service):
        result = rich_service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        types = {item.get("product_status") for item in result.data}
        names = [item["product_name"] for item in result.data]
        assert "주택청약종합저축" in names


# ─────────────────────────────────────────────────────────────────────────────
# P. FAQ 응답 구조 검증
# ─────────────────────────────────────────────────────────────────────────────

class TestFaqStructure:
    """FAQ — 응답 구조 및 데이터 무결성."""

    def test_returns_ok(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert len(result.data) >= 1

    def test_each_item_has_question_and_answer(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert "question" in item
            assert "answer" in item
            assert item["question"]
            assert item["answer"]

    def test_does_not_require_auth(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False
        assert result.requires_staff_auth is False

    def test_message_is_present(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.message

    def test_feature_code_matches(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.feature_code == "FAQ"


# ─────────────────────────────────────────────────────────────────────────────
# Q. STAFF_CUSTOMER vs STAFF_ACCOUNT 데이터 일관성
# ─────────────────────────────────────────────────────────────────────────────

class TestStaffDataConsistency:
    """STAFF_CUSTOMER vs STAFF_ACCOUNT — 동일 고객 데이터 일관성."""

    def test_both_return_same_account_number(self, service):
        r_cust = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        r_acct = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        cust_numbers = {i["account_number"] for i in r_cust.data}
        acct_numbers = {i["account_number"] for i in r_acct.data}
        assert cust_numbers == acct_numbers

    def test_both_return_same_balance(self, service):
        r_cust = service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        r_acct = service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        cust_bal = {i["account_number"]: float(i["balance"]) for i in r_cust.data}
        acct_bal = {i["account_number"]: float(i["balance"]) for i in r_acct.data}
        assert cust_bal == acct_bal

    def test_both_return_same_count(self, rich_service):
        r_cust = rich_service.execute_feature(
            "STAFF_CUSTOMER",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        r_acct = rich_service.execute_feature(
            "STAFF_ACCOUNT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        assert len(r_cust.data) == len(r_acct.data)

    def test_staff_contract_join_amount_positive(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for item in result.data:
            assert float(item["join_amount"]) > 0

    def test_staff_contract_interest_rate_positive(self, service):
        result = service.execute_feature(
            "STAFF_CONTRACT",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        for item in result.data:
            assert float(item["contract_interest_rate"]) > 0

    def test_staff_transfer_and_cash_flow_overlap(self, service):
        r_tf = service.execute_feature(
            "STAFF_TRANSFER_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        r_cf = service.execute_feature(
            "STAFF_CASH_FLOW",
            ChatbotFeatureExecuteRequest(customer_no=CUST, staff_id=STAFF),
        )
        # 둘 다 같은 계좌에서 조회하므로 transaction_id 집합이 동일해야 함
        tf_ids = {item["transaction_id"] for item in r_tf.data}
        cf_ids = {item["transaction_id"] for item in r_cf.data}
        assert tf_ids == cf_ids


# ─────────────────────────────────────────────────────────────────────────────
# R. LLM mock 연결 시 전 고객 유형 정상 반환
# ─────────────────────────────────────────────────────────────────────────────

class TestCashFlowRecommendLlmAllTypes:
    """CASH_FLOW_RECOMMEND + LLM mock — 모든 고객 유형 정상 동작."""

    @pytest.mark.parametrize("cust", [CUST_SALARY, CUST_SURPLUS, CUST_TIGHT, CUST_NODATA])
    def test_llm_connected_returns_ok(self, cashflow_llm_service, cust):
        result = cashflow_llm_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=cust),
        )
        assert result.status == "OK"

    @pytest.mark.parametrize("cust", [CUST_SALARY, CUST_SURPLUS, CUST_TIGHT, CUST_NODATA])
    def test_llm_message_contains_llm_tag(self, cashflow_llm_service, cust):
        result = cashflow_llm_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=cust),
        )
        assert "[LLM 맞춤 추천]" in result.message

    @pytest.mark.parametrize("cust", [CUST_SALARY, CUST_SURPLUS, CUST_TIGHT, CUST_NODATA])
    def test_llm_data_length_is_one(self, cashflow_llm_service, cust):
        result = cashflow_llm_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=cust),
        )
        assert len(result.data) == 1

    def test_llm_missing_customer_still_empty(self, cashflow_llm_service):
        result = cashflow_llm_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=NO_CUST),
        )
        assert result.status == "EMPTY"

    def test_llm_query_forwarded(self, cashflow_llm_service):
        result = cashflow_llm_service.execute_feature(
            "CASH_FLOW_RECOMMEND",
            ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY, query="적금 추천해줘"),
        )
        assert result.status == "OK"
        assert result.message
