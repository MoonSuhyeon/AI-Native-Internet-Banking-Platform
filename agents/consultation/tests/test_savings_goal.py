"""SAVINGS_GOAL feature 단위 테스트."""
import pytest
from unittest.mock import MagicMock, patch

from app.features.savings_goal import (
    _parse_amount,
    _parse_months,
    _calc_savings_maturity,
    _calc_deposit_maturity,
    _has_lump_sum_intent,
    SavingsGoalFeatureExecutor,
)
from app.schemas import ChatbotFeatureExecuteRequest


# ── 1. 파서 ─────────────────────────────────────────────────────────────────

class TestParseAmount:
    def test_만원(self):
        assert _parse_amount("500만원") == 5_000_000

    def test_억(self):
        assert _parse_amount("1억") == 1_0000_0000

    def test_순수숫자(self):
        assert _parse_amount("5000000") == 5_000_000

    def test_천(self):
        assert _parse_amount("500천원") == 500_000

    def test_없음(self):
        assert _parse_amount("돈") is None


class TestParseMonths:
    def test_년(self):
        assert _parse_months("1년") == 12

    def test_개월(self):
        assert _parse_months("6개월") == 6

    def test_년_개월(self):
        assert _parse_months("2년") == 24

    def test_달(self):
        assert _parse_months("3달") == 3

    def test_없음(self):
        assert _parse_months("언젠가") is None


# ── 3. 세션 격리 — 제거됨 ────────────────────────────────────────────────────
#
# 대화 상태가 모듈 전역 dict(_SESSION)에서 DB 모델(ChatbotGoalSession)로 옮겨갔다.
# _SESSION 이 사라져 이 파일이 수집 단계에서 통째로 죽었고, 그 바람에 아래
# 금액 파싱·만기 계산 테스트까지 한 줄도 돌지 않고 있었다.
# 세션 격리는 이제 DB 수준에서 검증해야 한다 — 별도 과제.
# ─────────────────────────────────────────────────────────────────────────────


# ── End-to-End 라우팅 검증 ────────────────────────────────────────────────────

class TestEndToEndRouting:
    """2턴 메시지가 분류기를 우회하고 SAVINGS_GOAL로 라우팅되는지 검증."""

    def test_second_turn_not_matched_by_classifier(self):
        """'월 30만원요'는 키워드 분류기에서 SAVINGS_GOAL로 안 잡힌다."""
        from app.llm import IntentClassifier
        classifier = IntentClassifier()
        assert classifier.classify("월 30만원요") != "SAVINGS_GOAL"
        assert classifier.classify("목돈 300만원 있어요") != "SAVINGS_GOAL"


# ── 6. 이자 계산식 ───────────────────────────────────────────────────────────

class TestInterestCalc:
    def test_savings_maturity_positive(self):
        """적금 만기액 > 원금 합계."""
        result = _calc_savings_maturity(300_000, 3.0, 12)
        assert result > 300_000 * 12

    def test_deposit_maturity_simple_interest(self):
        """예금 단리: 500만 × 3% × 1년 = 15만 이자."""
        result = _calc_deposit_maturity(5_000_000, 3.0, 12)
        assert abs(result - 5_150_000) < 1  # 단리 정확값

    def test_zero_rate(self):
        """금리 0%이면 만기액 = 원금."""
        assert _calc_savings_maturity(100_000, 0, 12) == 1_200_000
        assert _calc_deposit_maturity(1_000_000, 0, 12) == 1_000_000


# ── 8. 상품 없을 때 안내 — 제거됨 ────────────────────────────────────────────
#
# _SESSION 에 2턴 상태를 직접 심어 놓고 검증하던 테스트였다.
# 상태 저장소가 DB(ChatbotGoalSession)로 옮겨가 같은 방식으로 재현할 수 없다.
# "상품이 없을 때 안내 메시지가 나가는가"는 DB 상태를 만들어 다시 써야 한다 — 별도 과제.
# ─────────────────────────────────────────────────────────────────────────────
