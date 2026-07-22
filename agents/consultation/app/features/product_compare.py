"""상품 비교 분석 에이전트.

두 상품을 금리·기간·세제혜택·중도해지 등 항목별로 비교하고
GPT-4o-mini가 고객 상황에 맞는 추천 이유를 설명한다.
"""
from __future__ import annotations

import os
import re
from typing import Any

from app.features.base import FeatureExecutorBase
from app.schemas import ChatbotFeatureExecuteRequest, ChatbotFeatureExecuteResponse


def _langfuse_trace(name: str, input_data: Any, output: Any, model: str = "") -> None:
    """Langfuse 환경변수가 설정된 경우 trace를 직접 전송한다."""
    if not os.getenv("LANGFUSE_SECRET_KEY"):
        return
    try:
        from langfuse import Langfuse
        lf = Langfuse()
        trace = lf.trace(name=name, input=input_data, output=output)
        if model:
            trace.generation(name="llm-call", model=model, input=input_data, output=output)
        lf.flush()
    except Exception as e:
        print(f"[Langfuse] trace error ({name}): {e}", flush=True)


# ── 항목 레이블 ───────────────────────────────────────────────────────────────

_TYPE_LABEL = {"DEPOSIT": "예금", "SAVINGS": "적금", "SUBSCRIPTION": "청약"}


def _yn(val: Any) -> str:
    if val is True or val == "true" or val == 1:
        return "✅ 가능"
    return "❌ 불가"


def _rate_str(val: Any) -> str:
    return f"{float(val):.2f}%" if val is not None else "-"


def _period_str(min_m: Any, max_m: Any) -> str:
    if min_m is None and max_m is None:
        return "-"
    if min_m == max_m:
        return f"{min_m}개월"
    return f"{min_m or '?'}~{max_m or '?'}개월"


def _amount_str(min_a: Any, max_a: Any) -> str:
    def fmt(v: Any) -> str:
        if v is None:
            return "-"
        n = float(v)
        if n >= 1_0000_0000:
            return f"{n/1_0000_0000:.0f}억원"
        if n >= 10_000:
            return f"{n/10_000:.0f}만원"
        return f"{n:,.0f}원"
    return f"{fmt(min_a)}~{fmt(max_a)}"


# ── 상품명 감지 ───────────────────────────────────────────────────────────────

def _find_products_by_name(db: Any, query: str) -> list[dict]:
    """쿼리 텍스트에서 상품명을 감지해 DB에서 조회.

    매칭 전략 (점수 높은 순):
    1. 상품명 전체가 쿼리에 포함
    2. 공백 제거 후 상품명이 쿼리에 포함
    3. 상품명 고유 토큰(4자 이상) 중 하나가 쿼리에 포함
    """
    from sqlalchemy import text
    rows = db.execute(text(
        """
        SELECT banking_product_id   AS product_id,
               deposit_product_name AS product_name,
               deposit_product_type AS product_type,
               description,
               base_interest_rate,
               preferential_rate_condition,
               min_join_amount,
               max_join_amount,
               min_period_month,
               max_period_month,
               is_early_termination_allowed,
               is_tax_benefit_available,
               is_auto_renewal_available,
               is_passbook_issued
          FROM deposit_banking_products
         WHERE deposit_product_status = 'SELLING'
         ORDER BY banking_product_id
        """
    )).mappings().all()

    q_nospace = query.replace(" ", "")
    scored: list[tuple[int, dict]] = []
    seen: set[int] = set()

    for row in rows:
        name = str(row["product_name"] or "")
        pid = int(row["product_id"])
        if pid in seen:
            continue
        name_nospace = name.replace(" ", "")
        score = 0
        if name in query:
            score = 3
        elif name_nospace in q_nospace:
            score = 2
        else:
            # 4자 이상 토큰 중 쿼리에 포함된 개수를 점수로 사용 (다수 매칭일수록 높음)
            tokens = [t for t in re.split(r"[\s,·\-★()]", name) if len(t) >= 4]
            match_count = sum(1 for t in tokens if t in query or t in q_nospace)
            if match_count > 0:
                score = match_count
        if score > 0:
            scored.append((score, dict(row)))
            seen.add(pid)

    # 점수 높은 순 정렬 후 상위 2개 반환
    scored.sort(key=lambda x: -x[0])
    return [r for _, r in scored[:2]]


def _fetch_products_by_ids(db: Any, product_ids: list[int]) -> list[dict]:
    from sqlalchemy import text
    rows = db.execute(text(
        """
        SELECT p.banking_product_id   AS product_id,
               p.deposit_product_name AS product_name,
               p.deposit_product_type AS product_type,
               p.description,
               p.base_interest_rate,
               p.preferential_rate_condition,
               p.min_join_amount,
               p.max_join_amount,
               p.min_period_month,
               p.max_period_month,
               p.is_early_termination_allowed,
               p.is_tax_benefit_available,
               p.is_auto_renewal_available,
               p.is_passbook_issued,
               d.is_compound_interest
          FROM deposit_banking_products p
          LEFT JOIN banking_deposit_products d
                 ON d.banking_product_id = p.banking_product_id
         WHERE p.banking_product_id = ANY(:ids)
        """,
    ), {"ids": product_ids}).mappings().all()
    return [dict(r) for r in rows]


# ── GPT 비교 분석 ─────────────────────────────────────────────────────────────

def _gpt_compare(
    api_key: str,
    model: str,
    p_a: dict,
    p_b: dict,
    customer_info: str = "",
) -> str | None:
    if not api_key:
        return None
    try:
        from openai import OpenAI

        def summary(p: dict) -> str:
            return (
                f"상품명: {p['product_name']}\n"
                f"유형: {_TYPE_LABEL.get(p.get('product_type',''), p.get('product_type',''))}\n"
                f"기본금리: {_rate_str(p.get('base_interest_rate'))}\n"
                f"가입기간: {_period_str(p.get('min_period_month'), p.get('max_period_month'))}\n"
                f"세제혜택: {_yn(p.get('is_tax_benefit_available'))}\n"
                f"중도해지: {_yn(p.get('is_early_termination_allowed'))}\n"
                f"자동갱신: {_yn(p.get('is_auto_renewal_available'))}\n"
                f"우대금리조건: {p.get('preferential_rate_condition') or '없음'}"
            )

        prompt = (
            f"[상품 A]\n{summary(p_a)}\n\n"
            f"[상품 B]\n{summary(p_b)}\n\n"
            f"{customer_info}"
            "위 두 상품을 비교하여 고객에게 어떤 상품이 유리한지 설명해주세요.\n"
            "- 금리·기간·세제혜택·중도해지 조건의 차이를 언급하세요.\n"
            "- '내 상황엔 [상품명]이 유리한 이유'로 결론을 명확히 내려주세요.\n"
            "- 2~4문장으로 간결하게 작성하세요."
        )
        client = OpenAI(api_key=api_key)
        resp = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": "당신은 AXful Bank의 금융 상품 전문 상담사입니다. 한국어로 답변하세요."},
                {"role": "user", "content": prompt},
            ],
            max_tokens=400,
            temperature=0.3,
        )
        result = resp.choices[0].message.content or None
        _langfuse_trace("llm-product-compare", prompt, result, model)
        return result
    except Exception:
        return None


# ── 룰 기반 폴백 비교 메시지 ─────────────────────────────────────────────────

def _rule_compare(p_a: dict, p_b: dict) -> str:
    rate_a = float(p_a.get("base_interest_rate") or 0)
    rate_b = float(p_b.get("base_interest_rate") or 0)
    better = p_a if rate_a >= rate_b else p_b

    lines = [
        f"💡 금리 비교: {p_a['product_name']} {rate_a:.2f}% vs {p_b['product_name']} {rate_b:.2f}%",
    ]
    if p_a.get("is_tax_benefit_available") != p_b.get("is_tax_benefit_available"):
        tax_winner = p_a if p_a.get("is_tax_benefit_available") else p_b
        lines.append(f"🏷️ 세제혜택: {tax_winner['product_name']}에만 적용됩니다.")
    if p_a.get("is_early_termination_allowed") != p_b.get("is_early_termination_allowed"):
        et_winner = p_a if p_a.get("is_early_termination_allowed") else p_b
        lines.append(f"📤 중도해지: {et_winner['product_name']}만 가능합니다.")

    lines.append(f"\n✅ 내 상황엔 **{better['product_name']}**이 유리합니다. (금리 기준)")
    return "\n".join(lines)


# ── Feature Executor ─────────────────────────────────────────────────────────

class ProductCompareAgent(FeatureExecutorBase):

    def execute(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        query = (request.query or "").strip()
        product_ids: list[int] = list(request.compare_product_ids or [])
        if request.product_id:
            product_ids.append(request.product_id)

        # ── 상품 조회 ────────────────────────────────────────────────────────
        if len(product_ids) >= 2:
            products = _fetch_products_by_ids(self.db, product_ids[:2])
        else:
            products = _find_products_by_name(self.db, query)

        if len(products) < 2:
            return ChatbotFeatureExecuteResponse(
                feature_code="PRODUCT_COMPARE",
                status="NEED_INFO",
                message=(
                    "비교할 상품 두 개를 알려주세요.\n"
                    "예: 'AXful 정기예금과 AXful 내맘대로적금 비교해줘'"
                ),
                data=[],
            )

        p_a, p_b = products[0], products[1]

        # ── 고객 정보 컨텍스트 ────────────────────────────────────────────────
        customer_info = ""
        customer_no = (request.customer_no or "").strip()
        if customer_no:
            try:
                cf = self._analyze_customer_cash_flow(customer_no)
                if cf and cf.get("has_data"):
                    surplus = float(cf.get("monthly_surplus") or 0)
                    customer_info = f"[고객 정보] 월 여유자금 약 {surplus:,.0f}원\n\n"
            except Exception:
                pass

        # ── GPT 비교 분석 ─────────────────────────────────────────────────────
        from app.config import get_settings
        s = get_settings()
        gpt_msg = _gpt_compare(s.openai_api_key or "", s.openai_model, p_a, p_b, customer_info)
        analysis = gpt_msg or _rule_compare(p_a, p_b)

        # ── 비교 데이터 구성 ─────────────────────────────────────────────────
        compare_items = [
            {"label": "유형",       "a": _TYPE_LABEL.get(p_a.get("product_type",""), "-"), "b": _TYPE_LABEL.get(p_b.get("product_type",""), "-")},
            {"label": "기본금리",   "a": _rate_str(p_a.get("base_interest_rate")),          "b": _rate_str(p_b.get("base_interest_rate"))},
            {"label": "가입기간",   "a": _period_str(p_a.get("min_period_month"), p_a.get("max_period_month")), "b": _period_str(p_b.get("min_period_month"), p_b.get("max_period_month"))},
            {"label": "가입금액",   "a": _amount_str(p_a.get("min_join_amount"), p_a.get("max_join_amount")),  "b": _amount_str(p_b.get("min_join_amount"), p_b.get("max_join_amount"))},
            {"label": "세제혜택",   "a": _yn(p_a.get("is_tax_benefit_available")),          "b": _yn(p_b.get("is_tax_benefit_available"))},
            {"label": "중도해지",   "a": _yn(p_a.get("is_early_termination_allowed")),      "b": _yn(p_b.get("is_early_termination_allowed"))},
            {"label": "자동갱신",   "a": _yn(p_a.get("is_auto_renewal_available")),         "b": _yn(p_b.get("is_auto_renewal_available"))},
            {"label": "우대금리조건","a": str(p_a.get("preferential_rate_condition") or "없음")[:40], "b": str(p_b.get("preferential_rate_condition") or "없음")[:40]},
        ]

        rows = [
            {
                "row_type":    "compare_product",
                "product_a":   {**p_a, "product_id": p_a.get("product_id")},
                "product_b":   {**p_b, "product_id": p_b.get("product_id")},
                "compare_items": compare_items,
                "analysis":    analysis,
            }
        ]

        return ChatbotFeatureExecuteResponse(
            feature_code="PRODUCT_COMPARE",
            status="OK",
            message=analysis,
            data=rows,
        )
