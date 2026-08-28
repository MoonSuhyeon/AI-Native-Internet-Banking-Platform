"""core-banking 조회 클라이언트.

수신 데이터(상품·계약·계좌·거래)는 core-banking 의 것이다. 여기서 직접 SQL 로 읽으면
두 가지가 무너진다.

1. **경계** — core-banking 이 스키마를 바꾸면 이 서비스가 조용히 깨진다. 컴파일도
   테스트도 잡아 주지 않는다.
2. **인가·감사** — 고객 데이터 경로에는 행위자·사유 검증과 열람 감사가 붙어 있다
   (``/v1/internal/banking/**``). SQL 로 우회하면 그 통제가 통째로 빠진다.

이 모듈은 그 경계를 넘는 **유일한 정상 경로**다.

전환은 자원 단위로 끊어서 한다. 공개 상품 → 계약/만기 → 계좌/잔액 → 거래 순이고,
지금은 **공개 상품까지** 옮겨져 있다. 상품은 고객 데이터가 아니라서 행위자·사유가
필요 없고, 그래서 가장 먼저 옮길 수 있다.

조회 실패는 빈 결과로 돌린다. 상담은 상품 목록을 못 읽으면 안내를 줄이면 되지만,
여기서 예외를 올리면 대화 자체가 끊긴다.
"""
from __future__ import annotations

from typing import Any

import httpx

from app.config import get_settings

_TIMEOUT = 2.0


def _get(path: str, params: dict[str, Any] | None = None) -> Any:
    url = f"{get_settings().core_banking_url}{path}"
    try:
        with httpx.Client(timeout=_TIMEOUT) as client:
            resp = client.get(url, params=params or {})
        if resp.status_code != 200:
            return None
        body = resp.json()
    except Exception:
        return None
    # ApiResponse 로 감싸 오는 경우와 그대로 오는 경우를 함께 받는다.
    if isinstance(body, dict) and "data" in body:
        return body["data"]
    return body


def fetch_selling_products() -> list[dict[str, Any]]:
    """판매 중인 수신 상품. 실패 시 빈 목록.

    반환 키는 SQL 시절 컬럼 이름을 유지한다 — 호출부를 함께 바꾸지 않기 위해서다.
    """
    data = _get("/products", {"productStatus": "SELLING"})
    if not isinstance(data, list):
        return []
    return [_product_view(p) for p in data]


def fetch_selling_products_for_compare() -> list[dict[str, Any]]:
    """비교 화면이 쓰는 넓은 형태. 우대조건·자동재예치·통장발행까지 포함한다."""
    data = _get("/products", {"productStatus": "SELLING"})
    if not isinstance(data, list):
        return []
    return [_compare_view(p) for p in data]


def _compare_view(p: dict[str, Any]) -> dict[str, Any]:
    return {
        "product_id":                   p.get("productId"),
        "product_name":                 p.get("productName"),
        "product_type":                 p.get("productType"),
        "description":                  p.get("description"),
        "base_interest_rate":           p.get("baseInterestRate"),
        "preferential_rate_condition":  p.get("preferentialRateCondition"),
        "min_join_amount":              p.get("minJoinAmount"),
        "max_join_amount":              p.get("maxJoinAmount"),
        "min_period_month":             p.get("minPeriodMonth"),
        "max_period_month":             p.get("maxPeriodMonth"),
        "is_early_termination_allowed": p.get("isEarlyTerminationAllowed"),
        "is_tax_benefit_available":     p.get("isTaxBenefitAvailable"),
        "is_auto_renewal_available":    p.get("isAutoRenewalAvailable"),
        "is_passbook_issued":           p.get("isPassbookIssued"),
    }


def _product_view(p: dict[str, Any]) -> dict[str, Any]:
    return {
        "product_id":                   p.get("productId"),
        "deposit_product_name":         p.get("productName"),
        "deposit_product_type":         p.get("productType"),
        "base_interest_rate":           p.get("baseInterestRate"),
        "min_join_amount":              p.get("minJoinAmount"),
        "max_join_amount":              p.get("maxJoinAmount"),
        "min_period_month":             p.get("minPeriodMonth"),
        "max_period_month":             p.get("maxPeriodMonth"),
        "is_early_termination_allowed": p.get("isEarlyTerminationAllowed"),
        "is_tax_benefit_available":     p.get("isTaxBenefitAvailable"),
        "description":                  p.get("description"),
    }
