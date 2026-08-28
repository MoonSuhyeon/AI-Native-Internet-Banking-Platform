"""상품 관련 feature executor.

담당 feature: PRODUCT_GUIDE, RATE_GUIDE, JOIN_CONDITION,
              PRODUCT_COMPARE, TERMS_RAG, FAQ
"""
from __future__ import annotations

from typing import Any

from app import core_banking_client
from app.features.base import FeatureExecutorBase
from app.schemas import ChatbotFeatureExecuteRequest, ChatbotFeatureExecuteResponse


class ProductFeatureExecutor(FeatureExecutorBase):

    # ── PRODUCT_GUIDE ─────────────────────────────────────────────────────────

    def execute_product_guide(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        """예금·적금·청약 상품 목록 안내.

        항상 DB에서 직접 조회 — 상품명·금리·가입기간·가입대상을 포함해 반환한다.
        query 에 예금/적금/청약/입출금 키워드가 있으면 해당 유형만 필터링한다.
        """
        query_text = (request.query or "").lower()

        has_예금  = any(k in query_text for k in ("예금", "정기예금"))
        has_적금  = "적금" in query_text
        has_청약  = "청약" in query_text
        has_통장  = any(k in query_text for k in ("입출금", "통장"))

        type_filter:    str | None = None
        subtype_filter: str | None = None
        exclude_demand: bool = True   # 기본: 입출금자유 제외

        if has_통장 and not has_예금 and not has_적금 and not has_청약:
            type_filter, subtype_filter, exclude_demand = "DEPOSIT", "DEMAND", False
        elif has_예금 and not has_적금 and not has_청약:
            type_filter, subtype_filter, exclude_demand = "DEPOSIT", "TERM", False
        elif has_적금 and not has_예금 and not has_청약:
            type_filter, exclude_demand = "SAVINGS", False
        elif has_청약 and not has_예금 and not has_적금:
            type_filter, exclude_demand = "SUBSCRIPTION", False

        # SQL WHERE 절이 하던 걸러내기를 여기서 한다. 상품은 core-banking 소유라
        # 직접 조회하지 않는다 — 스키마가 바뀌면 조용히 깨진다.
        rows = core_banking_client.fetch_products_for_guide()

        customer_age = self._get_customer_age(request.customer_no)
        youth_ok = self._is_youth_eligible(customer_age)

        def keep(r: dict[str, Any]) -> bool:
            if type_filter and r.get("deposit_product_type") != type_filter:
                return False
            if subtype_filter:
                if r.get("_deposit_type") != subtype_filter:
                    return False
            elif exclude_demand and r.get("_deposit_type") == "DEMAND":
                return False
            # 청년 전용(대상그룹 3) 은 나이 조건을 못 넘기면 뺀다
            if not youth_ok and 3 in (r.get("_target_group_ids") or []):
                return False
            # 군인 전용은 나이만으로 판별할 수 없어 상품명으로 뺀다
            name = r.get("deposit_product_name") or ""
            if any(k in name for k in ("장병", "군인", "군무원")):
                return False
            return True

        rows = [r for r in rows if keep(r)]
        for r in rows:
            r.pop("_deposit_type", None)
            r.pop("_target_group_ids", None)

        _LABEL = {"DEPOSIT": "예금", "SAVINGS": "적금", "SUBSCRIPTION": "청약"}
        label = _LABEL.get(type_filter or "", "수신 상품")
        ok_msg = f"{label} 상품 목록을 조회했습니다." if type_filter else "수신 상품 목록을 조회했습니다."
        return self._data_response("PRODUCT_GUIDE", rows, ok_msg, "등록된 수신 상품 데이터가 없습니다.")

    # ── PRODUCT_DETAIL ────────────────────────────────────────────────────────

    def execute_product_detail(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        """상품 상세 안내.

        사용자가 특정 상품에 대해 물어봤을 때 가입 기간, 금액, 금리, 특징 등 모든 정보를 제공함.
        """
        query = (request.query or "").strip()

        # 상품명 LIKE 검색
        if query:
            escaped = query.replace("\\", "\\\\").replace("%", r"\%").replace("_", r"\_")
            like = f"%{escaped}%"
            rows = core_banking_client.fetch_products_by_name(query)
            if rows:
                return self._data_response("PRODUCT_DETAIL", rows, "검색된 상품의 상세 정보입니다.")

        return self._data_response("PRODUCT_DETAIL", [], "해당 상품을 찾을 수 없습니다.", "상세 정보를 조회할 수 있는 상품이 없습니다.")

    # ── RATE_GUIDE ────────────────────────────────────────────────────────────

    def execute_rate_guide(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        rows = core_banking_client.fetch_interest_rates()
        return self._data_response("RATE_GUIDE", rows, "금리/우대금리 조회를 완료했습니다.", "등록된 금리 데이터가 없습니다.")

    # ── JOIN_CONDITION ────────────────────────────────────────────────────────

    def execute_join_condition(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        rows = core_banking_client.fetch_join_conditions()
        return self._data_response("JOIN_CONDITION", rows, "가입 조건 조회를 완료했습니다.", "등록된 가입 조건 데이터가 없습니다.")

    # ── PRODUCT_COMPARE ───────────────────────────────────────────────────────

    def execute_product_compare(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        product_ids = request.compare_product_ids or ([request.product_id] if request.product_id else [])
        if product_ids:
            rows = core_banking_client.fetch_compare_by_ids(list(product_ids))
        else:
            rows = core_banking_client.fetch_compare_top(5)
        return self._data_response("PRODUCT_COMPARE", rows, "상품 비교 조회를 완료했습니다.", "비교할 상품 데이터가 없습니다.")

    # ── TERMS_RAG ─────────────────────────────────────────────────────────────

    def execute_terms_search(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        query = (request.query or "").strip()

        # SQL LIKE 검색 (빈 쿼리 시 "%" → 전체 반환)
        # %, _, \ 이스케이프해 사용자 입력이 와일드카드로 동작하지 않도록 방지
        if query:
            escaped = query.replace("\\", "\\\\").replace("%", r"\%").replace("_", r"\_")
            like = f"%{escaped}%"
        else:
            like = "%"
        rows = core_banking_client.fetch_special_terms(query)
        return self._data_response("TERMS_RAG", rows, "약관 검색을 완료했습니다.", "검색 가능한 약관 데이터가 없습니다.")

    # ── FAQ ───────────────────────────────────────────────────────────────────

    def execute_faq(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        return ChatbotFeatureExecuteResponse(
            feature_code="FAQ",
            status="OK",
            message="수신 상품 FAQ 응답입니다.",
            data=[
                {"question": "예금과 적금의 차이는 무엇인가요?", "answer": "예금은 목돈을 맡기고, 적금은 정해진 주기로 납입하는 상품입니다."},
                {"question": "우대금리는 어떻게 적용되나요?", "answer": "상품별 우대 조건 충족 여부에 따라 기본금리에 추가됩니다."},
                {"question": "중도해지하면 어떻게 되나요?", "answer": "상품 약관의 중도해지이율이 적용될 수 있어 약관 확인이 필요합니다."},
            ],
        )

    # ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    def _enrich_rag_results(
        self, rag_results: list[dict[str, Any]], cf: dict[str, Any] | None
    ) -> list[dict[str, Any]]:
        """RAG 검색 결과에 추천 이유와 match_score 를 추가한다."""
        enriched = []
        for rank, r in enumerate(rag_results, start=1):
            reasons: list[str] = []

            ptype     = r.get("deposit_product_type") or r.get("product_type", "")
            min_amt   = float(r.get("min_join_amount") or 0)
            rate      = float(r.get("base_interest_rate") or 0)
            rag_score = float(r.get("_score", 0))

            if cf:
                total_balance   = cf.get("total_balance", 0)
                monthly_surplus = cf.get("monthly_surplus", 0)

                if ptype == "DEPOSIT" and total_balance >= min_amt:
                    reasons.append(f"보유 잔액({total_balance:,.0f}원)으로 가입 가능")
                if ptype == "SAVINGS" and monthly_surplus >= min_amt:
                    reasons.append(f"월 여유자금({monthly_surplus:,.0f}원)으로 납입 가능")
                if rate >= 3.5:
                    reasons.append("고금리 혜택")

            if not reasons:
                reasons.append(f"질문과 {rag_score:.0%} 유사도로 매칭된 상품")

            match_score = max(10, round(rag_score * 100) - (rank - 1) * 3)
            enriched.append({
                **{k: v for k, v in r.items() if not k.startswith("_")},
                "recommend_reason": ", ".join(reasons),
                "match_score":      match_score,
            })
        return enriched
