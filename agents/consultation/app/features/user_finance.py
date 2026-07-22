"""사용자 금융정보 feature executor.

담당 feature: MY_ACCOUNTS, MY_PRODUCTS, CONTRACT_STATUS,
              MATURITY_SCHEDULE, INTEREST_HISTORY, MY_CASH_FLOW, CASH_FLOW_RECOMMEND
"""
from __future__ import annotations

import logging
from typing import Any

from app.features.base import FeatureExecutorBase
from app.schemas import ChatbotFeatureExecuteRequest, ChatbotFeatureExecuteResponse

logger = logging.getLogger(__name__)


class UserFinanceFeatureExecutor(FeatureExecutorBase):

    # ── MY_ACCOUNTS ───────────────────────────────────────────────────────────

    def execute_my_accounts(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("MY_ACCOUNTS", "계좌 조회에는 고객번호와 본인 인증이 필요합니다.")
        rows = self._account_rows(request.customer_no)
        return self._data_response(
            "MY_ACCOUNTS", rows, "내 계좌 조회를 완료했습니다.", "조회된 계좌가 없습니다.", requires_auth=True
        )

    # ── MATURITY_SCHEDULE ─────────────────────────────────────────────────────

    def execute_maturity_schedule(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("MATURITY_SCHEDULE", "만기 예정 조회에는 고객번호와 본인 인증이 필요합니다.")
        rows = self._contract_rows(request.customer_no)
        return self._data_response(
            "MATURITY_SCHEDULE", rows, "만기 예정 조회를 완료했습니다.", "조회된 만기 예정 계약이 없습니다.", requires_auth=True
        )

    # ── INTEREST_HISTORY ──────────────────────────────────────────────────────

    def execute_interest_history(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("INTEREST_HISTORY", "이자 내역 조회에는 고객번호와 본인 인증이 필요합니다.")
        rows = self._rows(
            """
            SELECT h.interest_id,
                   h.contract_id,
                   h.account_id,
                   h.applied_interest_rate,
                   h.interest_amount,
                   h.interest_after_tax AS interest_after_tax_amount,
                   h.interest_paid_at AS paid_at
              FROM deposit_interest_history h
              JOIN deposit_accounts a ON a.account_id = h.account_id
             WHERE a.customer_id = :customer_no
             ORDER BY h.interest_id DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "INTEREST_HISTORY", rows, "이자 내역 조회를 완료했습니다.", "조회된 이자 내역이 없습니다.", requires_auth=True
        )

    # ── MY_CASH_FLOW ──────────────────────────────────────────────────────────

    def execute_my_cash_flow(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("MY_CASH_FLOW", "현금 흐름 조회에는 고객번호와 본인 인증이 필요합니다.")
        rows = self._rows(
            """
            SELECT t.transaction_id,
                   a.account_number,
                   t.transaction_type,
                   t.amount,
                   t.status AS transaction_status,
                   t.transaction_at
              FROM deposit_transactions t
              JOIN deposit_accounts a ON a.account_id = t.account_id
             WHERE a.customer_id = :customer_no
             ORDER BY t.transaction_at DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "MY_CASH_FLOW", rows, "현금 흐름 조회를 완료했습니다.", "조회된 거래 내역이 없습니다.", requires_auth=True
        )

    # ── CASH_FLOW_RECOMMEND ───────────────────────────────────────────────────

    def execute_cash_flow_recommend(
        self, request: ChatbotFeatureExecuteRequest
    ) -> ChatbotFeatureExecuteResponse:
        """현금흐름 분석 → 점수 모델 기반 개인화 상품 추천."""
        if not request.customer_no:
            return self._auth_required(
                "CASH_FLOW_RECOMMEND",
                "현금흐름 분석 추천에는 고객번호와 본인 인증이 필요합니다.",
            )

        # ── 1. 고객 정보 조회 ────────────────────────────────────────────────
        customer_name = "고객"
        user_age = 36
        try:
            customer_info = self._rows(
                """
                SELECT p.party_name, pp.birth_date
                  FROM party p
                  JOIN party_person pp ON p.party_id = pp.party_id
                  JOIN customer c ON c.party_id = p.party_id
                 WHERE c.customer_id = :customer_no
                """,
                {"customer_no": request.customer_no},
            )
            if customer_info:
                customer_name = customer_info[0].get("party_name", customer_name)
                birth_date = customer_info[0].get("birth_date", "19900101")
                user_age = 2026 - int(str(birth_date)[:4])
        except Exception:
            pass

        # ── 2. 현금흐름 분석 ──────────────────────────────────────────────────
        cf = self._analyze_customer_cash_flow(request.customer_no)
        if cf is None:
            return ChatbotFeatureExecuteResponse(
                feature_code="CASH_FLOW_RECOMMEND",
                status="EMPTY",
                message="계좌 정보를 찾을 수 없습니다.",
                data=[],
                requires_auth=True,
            )

        # ── 3. 판매 중인 수신 상품 및 대상 그룹 조회 ──────────────────────────
        raw_products = self._rows(
            """
            SELECT p.banking_product_id AS product_id,
                   p.deposit_product_name,
                   p.deposit_product_type,
                   p.base_interest_rate,
                   p.min_join_amount,
                   p.max_join_amount,
                   p.min_period_month,
                   p.max_period_month,
                   p.is_early_termination_allowed,
                   p.is_tax_benefit_available,
                   tg.target_group_name,
                   tg.min_age,
                   tg.max_age
              FROM deposit_banking_products p
              LEFT JOIN banking_deposit_product_target_groups bptg ON p.banking_product_id = bptg.banking_product_id
              LEFT JOIN deposit_target_groups tg ON bptg.target_group_id = tg.target_group_id
             WHERE p.deposit_product_status = 'SELLING'
               AND p.deposit_product_type != 'SUBSCRIPTION'
            """
        )
        if not raw_products:
            raw_products = self._rows(
                """
                SELECT banking_product_id AS product_id,
                       deposit_product_name,
                       deposit_product_type,
                       base_interest_rate,
                       min_join_amount,
                       max_join_amount,
                       min_period_month,
                       max_period_month,
                       is_early_termination_allowed,
                       is_tax_benefit_available,
                       NULL AS target_group_name,
                       NULL AS min_age,
                       NULL AS max_age
                  FROM deposit_banking_products
                 WHERE deposit_product_status = 'SELLING'
                   AND deposit_product_type != 'SUBSCRIPTION'
                """
            )

        product_map: dict[Any, dict[str, Any]] = {}
        for row in raw_products:
            pid = row["product_id"]
            if pid not in product_map:
                product_map[pid] = dict(row)
                product_map[pid]["target_groups"] = []
            if row.get("target_group_name"):
                product_map[pid]["target_groups"].append({
                    "name": row["target_group_name"],
                    "min_age": row["min_age"],
                    "max_age": row["max_age"]
                })

        products = list(product_map.values())

        # ── 4. 채점 및 필터링 ────────────────────────────────────────────────
        preferred_type = (request.product_type or "").upper() or None  # "DEPOSIT" / "SAVINGS" / None
        scored_products = self._score_products(cf, products, user_age, preferred_type)
        top3 = scored_products[:3]
        recommendation = self._build_recommendation_message(customer_name, cf, top3)

        # ── 5. 응답 데이터 구성 ──────────────────────────────────────────────
        product_data = [
            {
                "row_type": "cash_flow_summary",
                "total_balance": cf["total_balance"],
                "monthly_surplus": cf["monthly_surplus"],
                "monthly_tx_count": cf["monthly_tx_count"],
                "has_data": cf["has_data"],
                "product_count": len(scored_products)
            }
        ]
        product_data.extend([
            {
                "row_type":           "recommended_product",
                "rank":               i + 1,
                "product_id":         p.get("product_id"),
                "product_name":       p.get("deposit_product_name", ""),
                "product_type":       p.get("deposit_product_type", ""),
                "base_interest_rate": p.get("base_interest_rate"),
                "min_period_month":   p.get("min_period_month"),
                "max_period_month":   p.get("max_period_month"),
                "target_groups":      ", ".join([tg["name"] for tg in p["target_groups"]]) if p["target_groups"] else "개인고객",
                "score":              p.get("total_score", 0),
                "raw_score":          p.get("raw_score", 0),
                "suit_score":         p.get("suit_score", 0),
                "return_score":       p.get("return_score", 0),
                "liquidity_score":    p.get("liquidity_score", 0),
                "benefit_score":      p.get("benefit_score", 0)
            }
            for i, p in enumerate(top3)
        ])

        return ChatbotFeatureExecuteResponse(
            feature_code="CASH_FLOW_RECOMMEND",
            status="OK",
            message=recommendation,
            data=product_data,
            requires_auth=True,
        )

    # ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    def _score_products(self, cf: dict[str, Any], products: list[dict[str, Any]], user_age: int, preferred_type: str | None = None) -> list[dict[str, Any]]:
        """Scoring Model (100점 만점) 적용."""
        total_balance = float(cf.get("total_balance", 0))
        monthly_surplus = float(cf.get("monthly_surplus", 0))
        tx_count = float(cf.get("monthly_tx_count", 0))
        is_accumulate_type = total_balance < (monthly_surplus * 12)

        scored = []
        for p in products:
            pname = p["deposit_product_name"]
            ptype = p["deposit_product_type"]

            # 상품 타입 필터 (예금/적금 명시 선택 시)
            if preferred_type and ptype != preferred_type:
                continue

            # 특수 대상 제외 (군인, 장병, 군무원)
            if any(keyword in pname for keyword in ["군인", "장병", "군무원"]):
                logger.debug(f"Filtered (Target): {pname}")
                continue

            # 연령 제한
            age_ok = True
            if p.get("target_groups"):
                age_ok = False
                for tg in p["target_groups"]:
                    min_a = tg["min_age"] or 0
                    max_a = tg["max_age"] or 150
                    if min_a <= user_age <= max_a:
                        age_ok = True
                        break
            if not age_ok:
                logger.debug(f"Filtered (Age): {pname}, Age: {user_age}")
                continue

            # 최소 가입 금액
            min_amt = float(p.get("min_join_amount") or 0)
            if ptype == "DEPOSIT" and min_amt > total_balance:
                logger.debug(f"Filtered (Amount/DEPOSIT): {pname}")
                continue
            if ptype == "SAVINGS" and min_amt > monthly_surplus:
                logger.debug(f"Filtered (Amount/SAVINGS): {pname}")
                continue

            # (1) 재정 적합도 40점
            suit_score = 0.0
            if ptype == "DEPOSIT":
                if min_amt > 0:
                    ratio = total_balance / min_amt
                    suit_score = 40.0 if 1.0 <= ratio <= 5.0 else (20.0 if ratio > 5.0 else 10.0)
            else:
                if min_amt > 0:
                    ratio = monthly_surplus / (min_amt * 2)
                    suit_score = 40.0 if ratio >= 1.0 else 20.0

            # (2) 예상 수익 30점
            rate = float(p.get("base_interest_rate") or 0)
            period = float(p.get("min_period_month") or 12)
            return_score = min(30.0, (rate * period / 48.0) * 30.0)

            # (3) 유동성 매칭 20점
            is_long_term = (p.get("min_period_month") or 0) >= 12
            if tx_count >= 10:
                liquidity_score = 20.0 if not is_long_term else 10.0
            elif tx_count <= 5:
                liquidity_score = 20.0 if is_long_term else 10.0
            else:
                liquidity_score = 15.0

            # (4) 부가 혜택 10점: 비과세 7점 + 중도해지 3점
            benefit_score = 0.0
            if p.get("is_tax_benefit_available"):
                benefit_score += 7.0
            if p.get("is_early_termination_allowed"):
                benefit_score += 3.0

            raw_total = suit_score + return_score + liquidity_score + benefit_score

            # 저축 성장형 → 적금 1.3배 가중치
            total_score = raw_total
            if is_accumulate_type and ptype == "SAVINGS":
                total_score = min(100.0, raw_total * 1.3)

            p.update({
                "suit_score":      round(suit_score, 1),
                "return_score":    round(return_score, 1),
                "liquidity_score": round(liquidity_score, 1),
                "benefit_score":   round(benefit_score, 1),
                "raw_score":       round(raw_total, 1),
                "total_score":     round(total_score, 1)
            })
            scored.append(p)

        scored.sort(key=lambda x: x["total_score"], reverse=True)

        # Diversity Rule: 저축 성장형이면 top3에 적금 최소 1개 보장
        if is_accumulate_type and len(scored) >= 3:
            top3_types = [s["deposit_product_type"] for s in scored[:3]]
            if "SAVINGS" not in top3_types:
                best_savings = next(
                    (s for s in scored[3:] if s["deposit_product_type"] == "SAVINGS" and s["total_score"] >= 60.0),
                    None
                )
                if best_savings:
                    idx = scored.index(best_savings)
                    scored[2], scored[idx] = scored[idx], scored[2]

        return scored

    def _build_recommendation_message(self, name: str, cf: dict[str, Any], top3: list[dict[str, Any]]) -> str:
        total_balance = float(cf.get("total_balance", 0))
        monthly_surplus = float(cf.get("monthly_surplus", 0))
        is_accumulate_type = total_balance < (monthly_surplus * 12)

        diag_name = "저축 성장형 (Accumulate Type)" if is_accumulate_type else "자산 관리형 (Asset Management Type)"
        if is_accumulate_type:
            diag_reason = f"현재 잔액({total_balance/10000:,.0f}만 원)보다 연간 저축 가능 금액({monthly_surplus*12/10000:,.0f}만 원)이 훨씬 큽니다."
        else:
            diag_reason = "보유 잔액을 효율적으로 운용하는 것이 유리합니다."

        lines = [
            "1. 100점 만점 채점 기준 (Scoring Model)",
            "시스템은 각 상품에 대해 다음 4가지 항목을 점수화하여 합산합니다.",
            "",
            " * 재정 적합도 (40점): 고객의 현재 자금으로 가입하기 가장 적절한 금액대인지 판단합니다.",
            "     * 예금: 잔액 / 최소가입금액 비율이 적정한지.",
            "     * 적금: 월 잉여자금 / (최소납입액×2) 비율이 여유로운지.",
            " * 예상 수익 (30점): 가입 기간과 금리를 고려했을 때 실제로 받게 될 세전 이자액을 계산해 상대 평가합니다.",
            " * 유동성 매칭 (20점): 거래 빈도를 분석해 돈이 묶여도 괜찮은지 판단합니다.",
            f"     * {name} 고객님처럼 입출금이 잦지 않은 경우, 금리가 높은 중장기 상품에 더 높은 점수를 줍니다.",
            " * 부가 혜택 (10점): 비과세 혜택 가능 여부, 중도해지 가능 여부 등을 반영합니다.",
            " * 가중치 보정: '저축 성장형' 고객인 경우 적금 상품 최종 점수에 1.3배 가중치를 부여합니다. (최대 100점)",
            "",
            "---",
            "",
            f"2. {name} 고객님 맞춤형 진단 결과",
            f"이 기준에 따라 {name} 고객님은 다음과 같이 분류되어 채점되었습니다.",
            "",
            f" * 진단명: '{diag_name}'",
            f"     * 근거: {diag_reason}",
        ]
        if is_accumulate_type:
            lines.append("     * 영향: 적금 상품에 가중치가 적용되어 순위가 조정되었습니다.")
        else:
            lines.append("     * 영향: 보유 잔액을 활용한 수익률 위주의 예금 상품이 유리할 수 있습니다.")

        lines += [
            "",
            "---",
            "",
            "3. 가입 불가 상품 필터링 (Filtering Rules)",
            "점수를 매기기 전, 아래 조건에 해당하는 상품은 추천 후보에서 즉시 제외했습니다.",
            "",
            " * 유형 제외: 사용자 요청에 따라 '청약' 유형 전체 제외.",
            " * 가입 대상: '군인', '장병', '군무원' 등의 키워드가 포함된 특수 대상 상품 제외.",
            f" * 자격 미달: {name} 고객님의 실제 생년월일 기준 연령 제한을 초과하는 상품 제외.",
            " * 최소 금액: 상품의 최소 가입 금액이 고객님의 현재 여유 자금보다 큰 상품 제외.",
            "",
            "---",
            "",
            "[추천 결과]"
        ]

        if not top3:
            lines.append("조건에 맞는 추천 상품을 찾지 못했습니다.")
        else:
            for i, p in enumerate(top3, 1):
                pname = p["deposit_product_name"]
                ptype = "예금" if p["deposit_product_type"] == "DEPOSIT" else "적금"
                rate = p["base_interest_rate"]
                raw = p["raw_score"]
                total = p["total_score"]
                if total != raw:
                    score_str = f"종합점수 {total}점 (기본 {raw}점 × 1.3 가중치)"
                else:
                    score_str = f"종합점수 {total}점"
                lines.append(f"{i}위. {pname} ({ptype}) — 기본금리 {rate}%, {score_str}")

        lines.append("\n더 자세한 상담은 '상담원 연결'을 이용해 주세요.")
        return "\n".join(lines)

    def _rule_based_recommend(
        self, cf: dict[str, Any], products: list[dict[str, Any]]
    ) -> str:
        """(Deprecated) 이전 버전 호환용."""
        return "신규 추천 로직이 적용되었습니다."
