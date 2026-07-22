"""
PRODUCT_ADVICE 카테고리 기능 상세 테스트.

포함 기능:
  PRODUCT_GUIDE    - 예금/적금/청약 상품 안내
  RATE_GUIDE       - 금리/우대금리 설명
  JOIN_CONDITION   - 가입 조건 안내
  PRODUCT_COMPARE  - 상품 비교
  TERMS_RAG        - 약관 기반 검색
  FAQ              - FAQ 응답
"""

import pytest

from app.schemas import ChatbotFeatureExecuteRequest


# ── PRODUCT_GUIDE ─────────────────────────────────────────────────────────────

class TestProductGuide:
    def test_returns_ok_status(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_feature_code_matches(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert result.feature_code == "PRODUCT_GUIDE"

    def test_data_not_empty(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert len(result.data) >= 1

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        product = result.data[0]
        assert "product_id" in product
        assert "product_name" in product
        assert "product_type" in product
        assert "base_interest_rate" in product
        assert "min_join_amount" in product
        assert "max_join_amount" in product
        assert "product_status" in product

    def test_seeded_product_name_present(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        names = [p["product_name"] for p in result.data]
        assert "정기예금 플러스" in names

    def test_seeded_product_status_is_selling(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        product = next(p for p in result.data if p["product_name"] == "정기예금 플러스")
        assert product["product_status"] == "SELLING"

    def test_seeded_product_interest_rate(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        product = next(p for p in result.data if p["product_name"] == "정기예금 플러스")
        assert float(product["base_interest_rate"]) == pytest.approx(3.5)

    def test_does_not_require_auth(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False
        assert result.requires_staff_auth is False

    def test_message_is_not_empty(self, service):
        result = service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest())
        assert result.message


# ── RATE_GUIDE ────────────────────────────────────────────────────────────────

class TestRateGuide:
    def test_returns_ok_status(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        assert len(result.data) >= 1

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        rate = result.data[0]
        assert "rate_id" in rate
        assert "product_id" in rate
        assert "product_name" in rate
        assert "rate_type" in rate
        assert "interest_rate" in rate

    def test_join_with_product_name(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        rate = result.data[0]
        # 상품명이 조인되어 있어야 함
        assert rate["product_name"] is not None
        assert len(rate["product_name"]) > 0

    def test_seeded_rate_value(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        assert float(result.data[0]["interest_rate"]) == pytest.approx(3.5)

    def test_seeded_rate_type(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        assert result.data[0]["rate_type"] == "BASE"

    def test_contract_period_range_present(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        rate = result.data[0]
        assert "minimum_contract_period" in rate
        assert "maximum_contract_period" in rate

    def test_does_not_require_auth(self, service):
        result = service.execute_feature("RATE_GUIDE", ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False


# ── JOIN_CONDITION ────────────────────────────────────────────────────────────

class TestJoinCondition:
    def test_returns_ok_status(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_data_not_empty(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        assert len(result.data) >= 1

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
        assert "product_status" in item

    def test_seeded_early_termination_allowed(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        item = result.data[0]
        assert item["is_early_termination_allowed"] in (True, 1, "1", "True")

    def test_seeded_join_amount_range(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        item = result.data[0]
        assert float(item["min_join_amount"]) == pytest.approx(100000)
        assert float(item["max_join_amount"]) == pytest.approx(100000000)

    def test_period_range(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        item = result.data[0]
        assert item["min_period_month"] == 1
        assert item["max_period_month"] == 60

    def test_does_not_require_auth(self, service):
        result = service.execute_feature("JOIN_CONDITION", ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False


# ── PRODUCT_COMPARE ───────────────────────────────────────────────────────────

class TestProductCompare:
    def test_returns_ok_without_product_ids(self, service):
        result = service.execute_feature("PRODUCT_COMPARE", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_returns_ok_with_product_id_list(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1]),
        )
        assert result.status == "OK"

    def test_returns_ok_with_single_product_id(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(product_id=1),
        )
        assert result.status == "OK"

    def test_data_contains_compare_fields(self, service):
        result = service.execute_feature("PRODUCT_COMPARE", ChatbotFeatureExecuteRequest())
        item = result.data[0]
        assert "product_id" in item
        assert "product_name" in item
        assert "product_type" in item
        assert "base_interest_rate" in item
        assert "min_join_amount" in item
        assert "max_join_amount" in item
        assert "min_period_month" in item
        assert "max_period_month" in item

    def test_specific_product_id_filter(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[1]),
        )
        assert len(result.data) == 1
        assert result.data[0]["product_id"] == 1

    def test_nonexistent_product_id_returns_empty(self, service):
        result = service.execute_feature(
            "PRODUCT_COMPARE",
            ChatbotFeatureExecuteRequest(compare_product_ids=[999999]),
        )
        assert result.status == "EMPTY"
        assert result.data == []

    def test_does_not_require_auth(self, service):
        result = service.execute_feature("PRODUCT_COMPARE", ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False


# ── TERMS_RAG ────────────────────────────────────────────────────────────────

class TestTermsRag:
    def test_returns_ok_with_matching_query(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="개인정보"),
        )
        assert result.status == "OK"

    def test_data_contains_required_fields(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="개인정보"),
        )
        term = result.data[0]
        assert "special_term_id" in term
        assert "special_term_name" in term
        assert "special_term_content" in term
        assert "special_term_summary" in term
        assert "is_required" in term
        assert "status" in term

    def test_keyword_search_finds_seeded_term(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="개인정보 수집"),
        )
        names = [t["special_term_name"] for t in result.data]
        assert "개인정보 수집 이용 동의" in names

    def test_empty_query_returns_all(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query=""),
        )
        assert result.status == "OK"
        assert len(result.data) >= 1

    def test_no_query_returns_all(self, service):
        result = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_nonmatching_query_returns_empty(self, service):
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="존재하지않는약관키워드xyz"),
        )
        assert result.status == "EMPTY"
        assert result.data == []

    def test_content_search_works(self, service):
        # special_term_content 에서도 검색됨
        result = service.execute_feature(
            "TERMS_RAG",
            ChatbotFeatureExecuteRequest(query="수집하고 이용"),
        )
        assert result.status == "OK"

    def test_does_not_require_auth(self, service):
        result = service.execute_feature("TERMS_RAG", ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False


# ── FAQ ───────────────────────────────────────────────────────────────────────

class TestFaq:
    def test_returns_ok_status(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.status == "OK"

    def test_feature_code_matches(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.feature_code == "FAQ"

    def test_data_not_empty(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert len(result.data) >= 1

    def test_faq_items_have_question_and_answer(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert "question" in item
            assert "answer" in item

    def test_faq_items_content_not_empty(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        for item in result.data:
            assert len(item["question"]) > 0
            assert len(item["answer"]) > 0

    def test_contains_deposit_savings_faq(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        questions = [item["question"] for item in result.data]
        assert any("예금" in q or "적금" in q for q in questions)

    def test_does_not_require_auth(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.requires_auth is False
        assert result.requires_staff_auth is False

    def test_message_not_empty(self, service):
        result = service.execute_feature("FAQ", ChatbotFeatureExecuteRequest())
        assert result.message
