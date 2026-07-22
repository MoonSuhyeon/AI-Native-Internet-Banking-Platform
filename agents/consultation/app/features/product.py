"""상품 관련 feature executor.

담당 feature: PRODUCT_GUIDE, RATE_GUIDE, JOIN_CONDITION,
              PRODUCT_COMPARE, TERMS_RAG, FAQ
"""
from __future__ import annotations

from typing import Any

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

        extra_where = ""
        extra_params: dict[str, Any] = {}
        if type_filter:
            extra_where += " AND p.deposit_product_type = :type_filter"
            extra_params["type_filter"] = type_filter
        if subtype_filter:
            extra_where += " AND bdp.deposit_type = :subtype_filter"
            extra_params["subtype_filter"] = subtype_filter
        elif exclude_demand:
            extra_where += " AND bdp.deposit_type IS DISTINCT FROM 'DEMAND'"

        # 나이 기반 청년·군인 전용 상품 필터
        customer_age = self._get_customer_age(request.customer_no)
        if not self._is_youth_eligible(customer_age):
            # 청년 전용(target_group_id=3) 상품 제외
            extra_where += (
                " AND p.banking_product_id NOT IN ("
                "  SELECT banking_product_id FROM banking_deposit_product_target_groups"
                "  WHERE target_group_id = 3"
                ")"
            )
        # 군인 전용 상품은 상품명 키워드로 항상 제외 (나이만으로 판별 불가)
        extra_where += (
            " AND p.deposit_product_name NOT LIKE '%장병%'"
            " AND p.deposit_product_name NOT LIKE '%군인%'"
            " AND p.deposit_product_name NOT LIKE '%군무원%'"
        )

        sql = """
            SELECT p.banking_product_id        AS product_id,
                   p.deposit_product_name      AS product_name,
                   p.deposit_product_type      AS product_type,
                   p.description               AS product_desc,
                   p.base_interest_rate,
                   p.min_period_month,
                   p.max_period_month,
                   p.min_join_amount,
                   p.max_join_amount,
                   p.is_early_termination_allowed,
                   p.is_tax_benefit_available,
                   p.is_auto_renewal_available,
                   COALESCE(
                       STRING_AGG(
                           DISTINCT tg.target_group_name || ' (' || tg.description || ')',
                           ', '
                       ),
                       '개인고객 (나이 제한 없음)'
                   ) AS target_groups,
                   COALESCE(rate_sum.max_rate, p.base_interest_rate) AS effective_rate
              FROM deposit_banking_products p
              LEFT JOIN banking_deposit_products bdp
                     ON bdp.banking_product_id = p.banking_product_id
              LEFT JOIN banking_deposit_product_target_groups btg
                     ON btg.banking_product_id = p.banking_product_id
              LEFT JOIN deposit_target_groups tg
                     ON tg.target_group_id = btg.target_group_id
              LEFT JOIN (
                   SELECT banking_product_id, SUM(rate) AS max_rate
                     FROM banking_deposit_product_interest_rates
                    WHERE rate_type IN ('BASE', 'PREFERENTIAL')
                    GROUP BY banking_product_id
              ) rate_sum ON rate_sum.banking_product_id = p.banking_product_id
             WHERE p.deposit_product_status = 'SELLING'
        """ + extra_where + """
             GROUP BY p.banking_product_id, p.deposit_product_name, p.deposit_product_type,
                      p.description, p.base_interest_rate, p.min_period_month, p.max_period_month,
                      p.min_join_amount, p.max_join_amount, p.is_early_termination_allowed,
                      p.is_tax_benefit_available, p.is_auto_renewal_available,
                      rate_sum.max_rate
             ORDER BY COALESCE(rate_sum.max_rate, p.base_interest_rate) DESC NULLS LAST
             LIMIT 20
        """
        rows = self._rows(sql, extra_params or None)

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
            rows = self._rows(
                r"""
                SELECT banking_product_id AS product_id,
                       deposit_product_name AS product_name,
                       deposit_product_type AS product_type,
                       description,
                       base_interest_rate,
                       min_join_amount,
                       max_join_amount,
                       min_period_month,
                       max_period_month,
                       is_early_termination_allowed,
                       is_tax_benefit_available
                  FROM deposit_banking_products
                 WHERE deposit_product_name LIKE :query ESCAPE '\'
                   AND deposit_product_status = 'SELLING'
                 LIMIT 3
                """,
                {"query": like}
            )
            if rows:
                return self._data_response("PRODUCT_DETAIL", rows, "검색된 상품의 상세 정보입니다.")

        return self._data_response("PRODUCT_DETAIL", [], "해당 상품을 찾을 수 없습니다.", "상세 정보를 조회할 수 있는 상품이 없습니다.")

    # ── RATE_GUIDE ────────────────────────────────────────────────────────────

    def execute_rate_guide(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        rows = self._rows(
            """
            SELECT r.rate_id,
                   r.banking_product_id AS product_id,
                   p.deposit_product_name AS product_name,
                   r.rate_type,
                   r.minimum_contract_period,
                   r.maximum_contract_period,
                   r.rate AS interest_rate,
                   r.condition_description
              FROM banking_deposit_product_interest_rates r
              JOIN deposit_banking_products p ON p.banking_product_id = r.banking_product_id
             ORDER BY r.banking_product_id, r.rate_id
             LIMIT 20
            """
        )
        return self._data_response("RATE_GUIDE", rows, "금리/우대금리 조회를 완료했습니다.", "등록된 금리 데이터가 없습니다.")

    # ── JOIN_CONDITION ────────────────────────────────────────────────────────

    def execute_join_condition(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        rows = self._rows(
            """
            SELECT banking_product_id AS product_id,
                   deposit_product_name AS product_name,
                   min_join_amount,
                   max_join_amount,
                   min_period_month,
                   max_period_month,
                   is_early_termination_allowed,
                   is_tax_benefit_available,
                   deposit_product_status AS product_status
              FROM deposit_banking_products
             ORDER BY banking_product_id
             LIMIT 20
            """
        )
        return self._data_response("JOIN_CONDITION", rows, "가입 조건 조회를 완료했습니다.", "등록된 가입 조건 데이터가 없습니다.")

    # ── PRODUCT_COMPARE ───────────────────────────────────────────────────────

    def execute_product_compare(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        product_ids = request.compare_product_ids or ([request.product_id] if request.product_id else [])
        if product_ids:
            rows = self._rows(
                """
                SELECT banking_product_id AS product_id,
                       deposit_product_name AS product_name,
                       deposit_product_type AS product_type,
                       base_interest_rate,
                       min_join_amount,
                       max_join_amount,
                       min_period_month,
                       max_period_month
                  FROM deposit_banking_products
                 WHERE banking_product_id IN :product_ids
                 ORDER BY banking_product_id
                """,
                {"product_ids": tuple(product_ids)},
                expanding_params=("product_ids",),
            )
        else:
            rows = self._rows(
                """
                SELECT banking_product_id AS product_id,
                       deposit_product_name AS product_name,
                       deposit_product_type AS product_type,
                       base_interest_rate,
                       min_join_amount,
                       max_join_amount,
                       min_period_month,
                       max_period_month
                  FROM deposit_banking_products
                 ORDER BY base_interest_rate DESC, banking_product_id
                 LIMIT 5
                """
            )
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
        rows = self._rows(
            r"""
            SELECT special_term_id,
                   special_term_name,
                   special_term_content,
                   special_term_summary,
                   is_required,
                   status
              FROM deposit_special_terms
             WHERE special_term_name LIKE :query ESCAPE '\'
                OR special_term_content LIKE :query ESCAPE '\'
                OR special_term_summary LIKE :query ESCAPE '\'
             ORDER BY special_term_id
             LIMIT 10
            """,
            {"query": like},
        )
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
