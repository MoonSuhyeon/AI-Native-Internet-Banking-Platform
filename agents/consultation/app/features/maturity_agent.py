"""Maturity management agent for deposit/savings contracts."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timezone
from typing import Any

from app.features.base import FeatureExecutorBase
from app.schemas import ChatbotFeatureExecuteRequest, ChatbotFeatureExecuteResponse


FEATURE_CODE = "MATURITY_MANAGEMENT"


@dataclass(frozen=True)
class MaturityTarget:
    contract: dict[str, Any]
    maturity_date: date
    days_to_maturity: int


class MaturityManagementAgent(FeatureExecutorBase):
    """Recommend what to do with maturing deposit/savings contracts."""

    def execute(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required(FEATURE_CODE, "재투자 추천에는 고객번호와 본인 인증이 필요합니다.")
        customer_no = request.customer_no
        targets = self._maturity_targets(customer_no)
        if not targets:
            return self._data_response(
                FEATURE_CODE,
                [],
                "",
                "조회된 만기 예정 예금/적금 계약이 없습니다.",
            )

        cash_flow = self._analyze_customer_cash_flow_compatible(customer_no)
        products = self._selling_products()
        rows = [self._recommend_for_target(target, cash_flow, products) for target in targets]
        display_rows = rows[:3]

        return ChatbotFeatureExecuteResponse(
            feature_code=FEATURE_CODE,
            status="OK",
            message=self._format_message(display_rows, cash_flow),
            data=[
                {
                    "row_type": "cash_flow_summary",
                    "total_balance": cash_flow["total_balance"],
                    "monthly_surplus": cash_flow["monthly_surplus"],
                    "monthly_tx_count": cash_flow["monthly_tx_count"],
                    "has_data": cash_flow["has_data"],
                },
                *display_rows,
            ],
        )

    def _maturity_targets(self, customer_no: str) -> list[MaturityTarget]:
        today = date.today()
        rows = self._rows(
            """
            SELECT c.contract_id,
                   c.contract_number AS contract_no,
                   c.customer_id AS customer_no,
                   c.banking_product_id AS product_id,
                   p.deposit_product_name AS product_name,
                   p.deposit_product_type AS product_type,
                   c.join_amount,
                   c.contract_interest_rate,
                   c.started_at,
                   c.maturity_at,
                   c.contract_status
              FROM deposit_contracts c
              LEFT JOIN deposit_banking_products p ON p.banking_product_id = c.banking_product_id
             WHERE c.customer_id = :customer_no
               AND c.maturity_at IS NOT NULL
               AND COALESCE(c.contract_status, 'ACTIVE') IN ('ACTIVE', 'NORMAL', 'OPEN')
             ORDER BY c.maturity_at
             LIMIT 20
            """,
            {"customer_no": customer_no},
        )

        targets: list[MaturityTarget] = []
        for row in rows:
            maturity_date = self._parse_date(row.get("maturity_at"))
            if not maturity_date:
                continue
            days_to_maturity = (maturity_date - today).days
            if days_to_maturity >= -7:
                targets.append(MaturityTarget(row, maturity_date, days_to_maturity))
        return targets

    def _selling_products(self) -> list[dict[str, Any]]:
        return self._rows(
            """
            SELECT banking_product_id AS product_id,
                   deposit_product_name AS product_name,
                   deposit_product_type AS product_type,
                   base_interest_rate,
                   min_join_amount,
                   max_join_amount,
                   min_period_month,
                   max_period_month,
                   is_early_termination_allowed,
                   is_tax_benefit_available
              FROM deposit_banking_products
             WHERE deposit_product_status = 'SELLING'
             ORDER BY base_interest_rate DESC
             LIMIT 30
            """
        )

    def _recommend_for_target(
        self,
        target: MaturityTarget,
        cash_flow: dict[str, Any],
        products: list[dict[str, Any]],
    ) -> dict[str, Any]:
        contract = target.contract
        amount = float(contract.get("join_amount") or 0)
        monthly_surplus = float(cash_flow.get("monthly_surplus") or 0)
        has_data = bool(cash_flow.get("has_data"))
        product_type = contract.get("product_type") or "DEPOSIT"

        if has_data and monthly_surplus < 0:
            action = "TERMINATE_OR_KEEP_LIQUID"
            target_product = None
            reason = "최근 현금흐름이 부족해 만기자금을 유동성 자금으로 보유하는 방안이 우선입니다."
        elif amount < 100_000:
            action = "TERMINATE_OR_KEEP_LIQUID"
            target_product = None
            reason = "소액은 재예치보다 입출금 통장에 두고 필요 시 활용하는 것이 유리합니다."
        elif product_type in ("SAVINGS", "SUBSCRIPTION") and monthly_surplus >= 500_000:
            action = "SWITCH_PRODUCT"
            target_product = self._best_product(products, amount, preferred_types=("SAVINGS", "SUBSCRIPTION"), exclude_product_id=contract.get("product_id"))
            reason = "월 잉여자금이 충분하므로 납입형 적금으로 전환해 저축 흐름을 이어가기 좋습니다."
        elif amount >= 1_000_000:
            action = "REDEPOSIT"
            target_product = self._best_product(products, amount, preferred_types=("DEPOSIT",), exclude_product_id=contract.get("product_id"))
            reason = "목돈은 기간을 나누어 정기예금으로 재예치하면 금리와 유동성을 함께 관리할 수 있습니다."
        else:
            action = "REDEPOSIT"
            target_product = self._best_product(products, amount, preferred_types=("DEPOSIT", "SAVINGS"), exclude_product_id=contract.get("product_id"))
            reason = "만기 후 다른 상품으로 재가입하면 더 유리한 금리 혜택을 받을 수 있습니다."

        period_month = self._recommended_period_month(target, monthly_surplus)
        product_name = target_product.get("product_name") if target_product else None
        rate = target_product.get("base_interest_rate") if target_product else None

        return {
            "row_type": "maturity_recommendation",
            "contract_id": contract.get("contract_id"),
            "contract_no": contract.get("contract_no"),
            "product_name": contract.get("product_name"),
            "product_type": product_type,
            "join_amount": amount,
            "contract_interest_rate": contract.get("contract_interest_rate"),
            "maturity_at": contract.get("maturity_at"),
            "days_to_maturity": target.days_to_maturity,
            "recommended_action": action,
            "recommended_period_month": period_month,
            "target_product_id": target_product.get("product_id") if target_product else None,
            "target_product_name": product_name,
            "target_base_interest_rate": rate,
            "reason": reason,
        }

    _RESTRICTED_PRODUCTS = ("장병내일준비적금", "청년도약계좌", "청년 주택드림", "주택청약종합저축")

    def _best_product(
        self,
        products: list[dict[str, Any]],
        amount: float,
        preferred_types: tuple[str, ...],
        exclude_product_id: Any = None,
    ) -> dict[str, Any] | None:
        general = [
            p for p in products
            if not any(r in (p.get("product_name") or "") for r in self._RESTRICTED_PRODUCTS)
            and (exclude_product_id is None or p.get("product_id") != exclude_product_id)
        ]
        eligible = [
            p for p in general
            if (p.get("product_type") in preferred_types)
            and self._amount_eligible(p, amount)
        ]
        if not eligible:
            eligible = [p for p in general if self._amount_eligible(p, amount)]
        return max(eligible, key=self._product_score, default=None)

    def _product_score(self, product: dict[str, Any]) -> float:
        rate = float(product.get("base_interest_rate") or 0)
        min_period = int(product.get("min_period_month") or 0)
        max_period = int(product.get("max_period_month") or 0)
        six_month_fit = 5 if min_period <= 6 <= max_period else 0
        return rate * 10 + six_month_fit

    def _amount_eligible(self, product: dict[str, Any], amount: float) -> bool:
        min_amount = float(product.get("min_join_amount") or 0)
        max_amount = float(product.get("max_join_amount") or 0)
        return amount >= min_amount and (max_amount == 0 or amount <= max_amount)

    def _recommended_period_month(self, target: MaturityTarget, monthly_surplus: float) -> int:
        # 만기 임박(45일 이내)이면 현금흐름과 무관하게 단기 6개월로 고정
        # — _recommend_for_target에서 이미 TERMINATE_OR_KEEP_LIQUID를 권고하므로
        #   이 period 값은 REDEPOSIT/SWITCH_PRODUCT 경로에서만 실질 사용됨
        if target.days_to_maturity <= 45:
            return 6
        if monthly_surplus < 0:
            return 1
        return 12

    def _analyze_customer_cash_flow_compatible(self, customer_no: str) -> dict[str, Any]:
        try:
            analyzed = self._analyze_customer_cash_flow(customer_no)
        except Exception:
            analyzed = None
        if analyzed is not None:
            return analyzed

        accounts = self._rows(
            "SELECT account_id, balance FROM deposit_accounts WHERE customer_id = :customer_no",
            {"customer_no": customer_no},
        )
        total_balance = sum(float(account.get("balance") or 0) for account in accounts)
        return {
            "total_balance": total_balance,
            "monthly_surplus": 0.0,
            "monthly_tx_count": 0.0,
            "has_data": False,
        }

    def _parse_date(self, value: Any) -> date | None:
        if value is None:
            return None
        if isinstance(value, datetime):
            return value.date()
        if isinstance(value, date):
            return value

        text_value = str(value).strip()
        for fmt in ("%Y%m%d", "%Y-%m-%d", "%Y-%m-%d %H:%M:%S"):
            try:
                return datetime.strptime(text_value, fmt).date()
            except ValueError:
                continue
        try:
            return datetime.fromisoformat(text_value).date()
        except ValueError:
            pass
        return None

    @staticmethod
    def _josa(word: str, josa_pair: tuple[str, str]) -> str:
        if not word:
            return josa_pair[0]
        code = ord(word[-1])
        if 0xAC00 <= code <= 0xD7A3:
            has_batchim = (code - 0xAC00) % 28 != 0
            batchim_idx = (code - 0xAC00) % 28
            if josa_pair == ("으로", "로"):
                return "로" if (not has_batchim or batchim_idx == 8) else "으로"
            return josa_pair[0] if has_batchim else josa_pair[1]
        return josa_pair[0]

    def _format_message(self, rows: list[dict[str, Any]], cash_flow: dict[str, Any]) -> str:
        lines = ["[재투자 추천]"]
        if cash_flow.get("has_data"):
            lines.append(f"최근 월평균 잉여자금은 {float(cash_flow.get('monthly_surplus') or 0):,.0f}원입니다.")
        else:
            lines.append("거래 데이터가 부족해 보수적인 만기 운용 전략으로 안내합니다.")

        for row in rows:
            amount = float(row.get("join_amount") or 0)
            product_name = row.get("product_name") or "만기 상품"
            period = row.get("recommended_period_month")
            target_name = row.get("target_product_name") or ""
            action = row.get("recommended_action", "REDEPOSIT")

            if action == "TERMINATE_OR_KEEP_LIQUID":
                lines.append(f"- {product_name} {amount:,.0f}원은 만기 후 입출금 통장에 유동성으로 보유하시길 권장합니다.")
            else:
                action_label = "재예치" if action == "REDEPOSIT" else "상품 전환"
                display_name = target_name or "정기예금"
                euro = self._josa(display_name, ("으로", "로"))
                eul_reul = self._josa(action_label, ("을", "를"))
                lines.append(
                    f"- {product_name} {amount:,.0f}원은 {period}개월 {display_name}{euro} {action_label}{eul_reul} 추천합니다."
                )
            lines.append(f"  사유: {row.get('reason')}")

        return "\n".join(lines)
