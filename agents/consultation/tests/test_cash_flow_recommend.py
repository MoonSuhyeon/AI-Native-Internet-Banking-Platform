"""
CASH_FLOW_RECOMMEND 기능 — 현금흐름 분석 기반 상품 추천 시나리오 테스트.

고객 유형 4종:
  CUST_SALARY  : 급여형  (월 3,000,000원 정기 입금, 잔액 8,000,000원)
  CUST_SURPLUS : 목돈형  (잔액 50,000,000원, 월 잉여자금 대규모)
  CUST_TIGHT   : 긴축형  (지출 > 입금, 잉여자금 음수)
  CUST_NODATA  : 계좌는 있으나 거래 없음 (has_data=False)

시나리오:
  S1. 고객번호 없음            → AUTH_REQUIRED
  S2. 급여형 고객 (룰 기반)    → 적금 추천
  S3. 목돈형 고객 (룰 기반)    → 예금 추천
  S4. 긴축형 고객 (룰 기반)    → 자유적금 권유
  S5. 거래 없는 고객 (룰 기반) → has_data=False 안내
  S6. 존재하지 않는 고객       → EMPTY (계좌 없음)
  S7. 규칙 기반 추천 고정       → LLM 응답 문구 미포함
  S8. 쿼리 텍스트 전달         → 규칙 기반 추천 유지
  S11. 현금흐름 지표 data 반환  → 구조 검증
  S12. 상품 목록 data 반환      → product_count 검증
  S13. 자동 intent 분류         → "내 패턴 분석" 메시지 → CASH_FLOW_RECOMMEND
  S14. feature 정의 확인        → USER_FINANCE 카테고리 포함
"""

import pytest

from app.llm import IntentClassifier
from app.schemas import ChatbotFeatureExecuteRequest


# ── 공통 상수 ─────────────────────────────────────────────────────────────────

CUST_SALARY  = "CUST_SALARY"
CUST_SURPLUS = "CUST_SURPLUS"
CUST_TIGHT   = "CUST_TIGHT"
CUST_NODATA  = "CUST_NODATA"
CUST_MISSING = "NO_SUCH_CUSTOMER_XYZ"

FEATURE = "CASH_FLOW_RECOMMEND"


class TestPreferentialRateNotice:
    """추천 상품 카드의 우대금리 조건을 안내 문구에 함께 노출한다."""

    def test_appends_notice_for_preferential_rate_products(self, cashflow_service):
        message = cashflow_service._append_preferential_rate_notice(
            "추천 상품입니다.",
            [
                {
                    "product_name": "직장인우대예금",
                    "pref_condition": "급여이체 실적 등록",
                },
                {
                    "product_name": "기본예금",
                    "pref_condition": "",
                },
            ],
        )

        assert "[우대금리 가능 상품 안내]" in message
        assert "직장인우대예금: 급여이체 실적 등록" in message
        assert "기본예금" not in message

    def test_keeps_message_when_no_preferential_rate_products(self, cashflow_service):
        message = cashflow_service._append_preferential_rate_notice(
            "추천 상품입니다.",
            [{"product_name": "기본예금", "pref_condition": ""}],
        )

        assert message == "추천 상품입니다."


# ── S1. 인증 필요 ─────────────────────────────────────────────────────────────

class TestAuthRequired:
    """customer_no 없이 호출하면 AUTH_REQUIRED 반환."""

    def test_no_customer_no_returns_auth_required(self, cashflow_service):
        result = cashflow_service.execute_feature(FEATURE, ChatbotFeatureExecuteRequest())
        assert result.status == "AUTH_REQUIRED"

    def test_requires_auth_flag_set(self, cashflow_service):
        result = cashflow_service.execute_feature(FEATURE, ChatbotFeatureExecuteRequest())
        assert result.requires_auth is True

    def test_does_not_require_staff_auth(self, cashflow_service):
        result = cashflow_service.execute_feature(FEATURE, ChatbotFeatureExecuteRequest())
        assert result.requires_staff_auth is False

    def test_message_present(self, cashflow_service):
        result = cashflow_service.execute_feature(FEATURE, ChatbotFeatureExecuteRequest())
        assert result.message

    def test_data_empty_on_auth_required(self, cashflow_service):
        result = cashflow_service.execute_feature(FEATURE, ChatbotFeatureExecuteRequest())
        assert result.data == []


# ── S2. 급여형 고객 (룰 기반) ────────────────────────────────────────────────

class TestSalaryCustomerRuleBased:
    """CUST_SALARY: 월 잉여자금 약 400,000원 → 적금 추천 흐름."""

    def test_returns_ok(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert result.status == "OK"

    def test_feature_code_matches(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert result.feature_code == FEATURE

    def test_requires_auth_flag(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert result.requires_auth is True

    def test_data_has_cash_flow_summary(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert len(result.data) >= 1
        summary = result.data[0]
        assert summary["row_type"] == "cash_flow_summary"
        assert "total_balance" in summary
        assert "monthly_surplus" in summary
        assert "monthly_tx_count" in summary
        assert "has_data" in summary

    def test_has_data_true(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert result.data[0]["has_data"] is True

    def test_total_balance_correct(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        # 계좌 잔액 8,000,000원
        assert float(result.data[0]["total_balance"]) == pytest.approx(8_000_000)

    def test_monthly_surplus_positive(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        # 입금 9,000,000 - 출금 2,800,000 = 6,200,000 / 3 = 2,066,667
        surplus = float(result.data[0]["monthly_surplus"])
        assert surplus > 0

    def test_message_contains_recommendation(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert result.message
        assert len(result.message) > 10

    def test_product_count_in_data(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert result.data[0]["product_count"] >= 1


# ── S3. 목돈형 고객 (룰 기반) ────────────────────────────────────────────────

class TestSurplusCustomerRuleBased:
    """CUST_SURPLUS: 잔액 50,000,000원 → 예금 추천 흐름."""

    def test_returns_ok(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        assert result.status == "OK"

    def test_total_balance_high(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        assert float(result.data[0]["total_balance"]) == pytest.approx(50_000_000)

    def test_message_mentions_deposit_type(self, cashflow_service):
        """룰 기반 추천: 잔액 >= 10M → 예금/목돈 언급."""
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        # "정기예금" 또는 "목돈"이 포함돼야 함
        assert "예금" in result.message or "목돈" in result.message

    def test_has_data_true(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        assert result.data[0]["has_data"] is True


# ── S4. 긴축형 고객 (룰 기반) ────────────────────────────────────────────────

class TestTightCustomerRuleBased:
    """CUST_TIGHT: 지출 > 입금 → 부담 없는 상품 안내."""

    def test_returns_ok(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_TIGHT)
        )
        assert result.status == "OK"

    def test_monthly_surplus_negative_or_low(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_TIGHT)
        )
        # 입금 500,000 - 출금 2,100,000 → 잉여자금 음수
        surplus = float(result.data[0]["monthly_surplus"])
        assert surplus < 500_000  # 확실히 여유 없음

    def test_message_present(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_TIGHT)
        )
        assert result.message
        assert len(result.message) > 10


# ── S5. 거래 없는 고객 ────────────────────────────────────────────────────────

class TestNoDataCustomer:
    """CUST_NODATA: 계좌는 있으나 COMPLETED 거래 없음 → has_data=False."""

    def test_returns_ok_status(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_NODATA)
        )
        assert result.status == "OK"

    def test_has_data_false(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_NODATA)
        )
        assert result.data[0]["has_data"] is False

    def test_message_informs_data_shortage(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_NODATA)
        )
        # 거래 부족 안내 또는 fallback 추천 메시지 존재
        assert result.message


# ── S6. 존재하지 않는 고객 ───────────────────────────────────────────────────

class TestMissingCustomer:
    """존재하지 않는 customer_no → EMPTY (계좌 없어서 분석 불가)."""

    def test_returns_empty_status(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_MISSING)
        )
        assert result.status == "EMPTY"

    def test_empty_data(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_MISSING)
        )
        assert result.data == []

    def test_requires_auth_flag(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_MISSING)
        )
        assert result.requires_auth is True


# ── S7. 규칙 기반 추천 고정 ──────────────────────────────────────────────────

class TestRuleBasedOnly:
    """상품추천은 외부 AI/LLM 응답을 사용하지 않는다."""

    def test_salary_customer_rule_based_ok(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert result.status == "OK"
        assert "[LLM 맞춤 추천]" not in result.message

    def test_surplus_customer_rule_based_ok(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        assert result.status == "OK"
        assert "[LLM 맞춤 추천]" not in result.message

    def test_tight_customer_rule_based_ok(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_TIGHT)
        )
        assert result.status == "OK"
        assert "[LLM 맞춤 추천]" not in result.message

    def test_rule_based_response_includes_product_name(self, cashflow_service):
        """규칙 기반 추천 문구가 실제 상품명을 포함한다."""
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert any(name in result.message for name in ["정기예금", "적금", "청약"])

    def test_rule_based_response_includes_cash_flow_data(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SURPLUS)
        )
        summary = next(row for row in result.data if row.get("row_type") == "cash_flow_summary")
        assert summary["total_balance"] == 50000000


# ── S10. 쿼리 텍스트 전달 ────────────────────────────────────────────────────

class TestQueryText:
    """user_query 텍스트가 LLM에 전달된다 (mock에서 확인)."""

    def test_custom_query_ok(self, cashflow_llm_service):
        result = cashflow_llm_service.execute_feature(
            FEATURE,
            ChatbotFeatureExecuteRequest(
                customer_no=CUST_SALARY,
                query="대출도 알아보고 싶어요",
            ),
        )
        assert result.status == "OK"
        assert result.message  # LLM 응답 존재

    def test_insurance_query_ok(self, cashflow_llm_service):
        result = cashflow_llm_service.execute_feature(
            FEATURE,
            ChatbotFeatureExecuteRequest(
                customer_no=CUST_SALARY,
                query="보험 상품도 추천해줘",
            ),
        )
        assert result.status == "OK"

    def test_fund_query_ok(self, cashflow_llm_service):
        result = cashflow_llm_service.execute_feature(
            FEATURE,
            ChatbotFeatureExecuteRequest(
                customer_no=CUST_SURPLUS,
                query="펀드나 투자 상품 있어?",
            ),
        )
        assert result.status == "OK"


# ── S11~S12. data 구조 검증 ──────────────────────────────────────────────────

class TestDataStructure:
    """response.data에 현금흐름 지표와 product_count가 포함된다."""

    def test_data_contains_required_keys(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        summary = result.data[0]
        assert "total_balance" in summary
        assert "monthly_surplus" in summary
        assert "monthly_tx_count" in summary
        assert "has_data" in summary
        assert "product_count" in summary

    def test_product_count_reflects_selling_products(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        # cashflow_db에 SELLING 상품 4개 중 '청약' 1개 제외 -> 3개
        assert result.data[0]["product_count"] == 3

    def test_monthly_tx_count_positive_for_active_customer(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        assert float(result.data[0]["monthly_tx_count"]) > 0

    def test_data_length_is_at_least_one(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        # 요약(1) + 추천상품(최대 3)
        assert len(result.data) >= 1

class TestAccumulateTypeDiversity:
    """저축 성장형 고객에 대한 Diversity Rule 검증."""

    def test_includes_at_least_one_savings_in_top3(self, cashflow_service):
        # CUST_SALARY는 저축 성장형 (잔액 8M < 연간 저축 24M)
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        recommended_types = [p["product_type"] for p in result.data if p.get("row_type") == "recommended_product"]
        assert "SAVINGS" in recommended_types, "저축 성장형 고객 추천에는 최소 1개의 적금이 포함되어야 함"

    def test_shows_adjusted_score_for_savings(self, cashflow_service):
        result = cashflow_service.execute_feature(
            FEATURE, ChatbotFeatureExecuteRequest(customer_no=CUST_SALARY)
        )
        savings_item = next(p for p in result.data if p.get("row_type") == "recommended_product" and p["product_type"] == "SAVINGS")
        
        # 가중치가 적용되었으므로 raw_score와 score(total_score)가 다를 가능성이 높음 (또는 score >= raw_score)
        assert savings_item["score"] >= savings_item["raw_score"]
        if savings_item["raw_score"] > 0:
            assert savings_item["score"] == pytest.approx(min(100.0, savings_item["raw_score"] * 1.3), 0.1)


# ── S13. intent 자동 분류 ─────────────────────────────────────────────────────

class TestIntentClassification:
    """IntentClassifier가 현금흐름 관련 메시지를 CASH_FLOW_RECOMMEND로 분류한다."""

    @pytest.fixture(autouse=True)
    def classifier(self):
        self.clf = IntentClassifier()

    @pytest.mark.parametrize("message", [
        "내 패턴 분석해서 상품 추천해줘",
        "내 현금 흐름 분석해서 추천해줘",
        "나한테 맞는 상품이 뭐야?",
        "내 소비 패턴에 맞는 적금 있어?",
        "내 상황에 맞는 상품 추천",
        "내 거래 패턴으로 추천해줘",
        "맞춤 추천해줘",
    ])
    def test_classifies_as_cash_flow_recommend(self, message):
        intent = self.clf.classify(message)
        assert intent == "CASH_FLOW_RECOMMEND", (
            f"'{message}' → 예상: CASH_FLOW_RECOMMEND, 실제: {intent}"
        )

    def test_product_guide_still_classified_normally(self):
        """PRODUCT_GUIDE 키워드는 CASH_FLOW_RECOMMEND보다 낮은 우선순위."""
        intent = self.clf.classify("예금 상품 알려줘")
        # CASH_FLOW_RECOMMEND 키워드 없으므로 PRODUCT_GUIDE
        assert intent == "PRODUCT_GUIDE"

    def test_rate_guide_still_highest_priority(self):
        intent = self.clf.classify("금리 얼마야?")
        assert intent == "RATE_GUIDE"


# ── S14. feature 정의 확인 ────────────────────────────────────────────────────

class TestFeatureDefinition:
    """CASH_FLOW_RECOMMEND가 features() 목록과 categories()에 존재한다."""

    def test_feature_exists_in_features_list(self, cashflow_service):
        codes = [f.code for f in cashflow_service.features()]
        assert FEATURE in codes

    def test_feature_category_is_user_finance(self, cashflow_service):
        feature = next(
            f for f in cashflow_service.features() if f.code == FEATURE
        )
        assert feature.category_code == "USER_FINANCE"

    def test_feature_has_sample_questions(self, cashflow_service):
        feature = next(
            f for f in cashflow_service.features() if f.code == FEATURE
        )
        assert len(feature.sample_questions) >= 1

    def test_feature_appears_in_category_response(self, cashflow_service):
        cats = cashflow_service.categories()
        user_finance = next(c for c in cats if c.code == "USER_FINANCE")
        assert FEATURE in user_finance.features

    def test_feature_detail_returns_correct_feature(self, cashflow_service):
        detail = cashflow_service.feature_detail(FEATURE)
        assert detail is not None
        assert detail.code == FEATURE
