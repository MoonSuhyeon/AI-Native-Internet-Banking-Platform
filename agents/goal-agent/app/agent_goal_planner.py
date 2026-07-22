"""
금융 목표 달성 플래너 에이전트 (Goal-Based Agent)

흐름:
  1. 목표 입력                (goal_amount, goal_months, customer_id)
  2. 계좌 조회                (_get_customer_accounts)
  3. 거래내역 분석            (_analyze_transactions)
  4. 목표 달성 가능성 판단    (_evaluate_feasibility)
  5. 실패 원인 분석           (_analyze_failure_reasons)   ← v2
  6. 대안 시나리오 생성       (_generate_alternatives)      ← v2 실계산
  7. 복수 전략 생성           (_build_strategies)
  8. 상품 조회 및 추천        (_recommend_combinations)
  9. 추천 사유 상세화         (_build_recommendation_reason) ← v2 구조화
  10. 월별 납입 계획          (_build_monthly_plan)
"""

from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_DOWN
import math

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models


# ──────────────────────────────────────────────
# 상수
# ──────────────────────────────────────────────

ANALYSIS_MONTHS = 3      # 거래 분석 기간 (3개월)
MAX_RECOMMENDATIONS = 5  # 최대 추천 상품 수
MAX_PERIOD_MONTHS = 120  # 기간 연장 상한


# ──────────────────────────────────────────────
# 1. 계좌 조회
# ──────────────────────────────────────────────

def get_customer_accounts(db: Session, customer_id: str) -> list[models.Account]:
    return db.scalars(
        select(models.Account).where(
            models.Account.customer_id == customer_id,
            models.Account.account_status == models.AccountStatus.ACTIVE,
        )
    ).all()


def get_total_balance(accounts: list[models.Account]) -> Decimal:
    return sum((Decimal(str(a.balance)) for a in accounts), Decimal("0"))


# ──────────────────────────────────────────────
# 2. 거래내역 분석 (최근 N개월)
# ──────────────────────────────────────────────

def get_recent_transactions(db: Session, account_ids: list[int], months: int) -> list[models.Transaction]:
    since = datetime.utcnow() - timedelta(days=months * 30)
    if not account_ids:
        return []
    return db.scalars(
        select(models.Transaction).where(
            models.Transaction.account_id.in_(account_ids),
            models.Transaction.transaction_at >= since,
            models.Transaction.status == models.TransactionStatus.SUCCESS,
        )
    ).all()


def analyze_transactions(transactions: list[models.Transaction], months: int) -> dict:
    """월 평균 입금/출금/잉여자금 계산."""
    total_in = Decimal("0")
    total_out = Decimal("0")

    for tx in transactions:
        if tx.transaction_type in (
            models.TransactionType.INTEREST,
            models.TransactionType.REVERSAL,
        ):
            continue
        amt = Decimal(str(tx.amount))
        if tx.direction_type == models.DirectionType.IN:
            total_in += amt
        else:
            total_out += amt

    divisor = Decimal(str(max(months, 1)))
    monthly_in = (total_in / divisor).quantize(Decimal("1"), rounding=ROUND_DOWN)
    monthly_out = (total_out / divisor).quantize(Decimal("1"), rounding=ROUND_DOWN)
    monthly_surplus = max(monthly_in - monthly_out, Decimal("0"))

    return {
        "total_in": float(total_in),
        "total_out": float(total_out),
        "monthly_avg_in": float(monthly_in),
        "monthly_avg_out": float(monthly_out),
        "monthly_avg_surplus": float(monthly_surplus),
        "transaction_count": len(transactions),
        "analysis_months": months,
    }


# ──────────────────────────────────────────────
# 3. 목표 달성 가능성 판단
# ──────────────────────────────────────────────

def _evaluate_feasibility(
    goal_amount: Decimal,
    goal_months: int,
    current_balance: Decimal,
    monthly_surplus: Decimal,
) -> dict:
    remaining = max(goal_amount - current_balance, Decimal("0"))
    required_monthly = (
        (remaining / goal_months).quantize(Decimal("1"), rounding=ROUND_DOWN)
        if goal_months > 0 else remaining
    )

    if monthly_surplus <= 0:
        ratio = 0.0
        feasible = False
        verdict = "IMPOSSIBLE"
    else:
        ratio = float(monthly_surplus / required_monthly) if required_monthly > 0 else 99.0
        if ratio >= 1.2:
            feasible = True
            verdict = "ACHIEVABLE"
        elif ratio >= 0.9:
            feasible = True
            verdict = "TIGHT"
        elif ratio >= 0.6:
            feasible = False
            verdict = "DIFFICULT"
        else:
            feasible = False
            verdict = "IMPOSSIBLE"

    if monthly_surplus > 0 and remaining > 0:
        estimated_months = int(math.ceil(float(remaining / monthly_surplus)))
    else:
        estimated_months = None

    return {
        "goal_amount": float(goal_amount),
        "goal_months": goal_months,
        "current_balance": float(current_balance),
        "remaining_amount": float(remaining),
        "required_monthly_saving": float(required_monthly),
        "monthly_surplus": float(monthly_surplus),
        "surplus_to_required_ratio": round(ratio, 2),
        "feasibility": verdict,
        "is_feasible": feasible,
        "estimated_months_at_current_rate": estimated_months,
    }


# ──────────────────────────────────────────────
# 4. 실패 원인 분석 (v2 신규)
# ──────────────────────────────────────────────

def _analyze_failure_reasons(feasibility: dict, tx_analysis: dict) -> list[str]:
    """
    TIGHT / DIFFICULT / IMPOSSIBLE 판정 시 원인을 구체적으로 분석한다.
    ACHIEVABLE 이면 빈 리스트를 반환한다.
    """
    verdict = feasibility["feasibility"]
    if verdict == "ACHIEVABLE":
        return []

    reasons = []
    surplus = Decimal(str(feasibility["monthly_surplus"]))
    required = Decimal(str(feasibility["required_monthly_saving"]))
    remaining = Decimal(str(feasibility["remaining_amount"]))
    goal_amount = Decimal(str(feasibility["goal_amount"]))
    goal_months = feasibility["goal_months"]
    current_balance = Decimal(str(feasibility["current_balance"]))
    monthly_in = Decimal(str(tx_analysis["monthly_avg_in"]))
    monthly_out = Decimal(str(tx_analysis["monthly_avg_out"]))

    # 원인 1: 월 저축 가능 금액 부족
    if surplus < required:
        shortfall = required - surplus
        reasons.append(
            f"월 저축 가능 금액 부족: 목표 달성에 월 {int(required):,}원이 필요하지만 "
            f"현재 잉여자금은 {int(surplus):,}원으로 {int(shortfall):,}원이 부족합니다."
        )

    # 원인 2: 목표 기간이 너무 짧음 (잉여자금이 있어도 기간 내 적립 불가)
    if surplus > 0 and remaining > 0:
        min_months_needed = int(math.ceil(float(remaining / surplus)))
        if min_months_needed > goal_months:
            reasons.append(
                f"목표 기간 부족: 현재 잉여자금({int(surplus):,}원/월)으로 목표를 달성하려면 "
                f"최소 {min_months_needed}개월이 필요하나 설정 기간은 {goal_months}개월입니다."
            )

    # 원인 3: 현재 자산 규모 부족 (현재 잔액이 목표의 10% 미만)
    if current_balance < goal_amount * Decimal("0.1"):
        reasons.append(
            f"초기 자산 부족: 현재 보유 잔액({int(current_balance):,}원)이 목표 금액의 "
            f"{float(current_balance / goal_amount * 100):.1f}% 수준으로 초기 기반이 낮습니다."
        )

    # 원인 4: 지출 비율 과다 (지출이 수입의 85% 초과)
    if monthly_in > 0 and monthly_out / monthly_in > Decimal("0.85"):
        out_ratio = float(monthly_out / monthly_in * 100)
        reasons.append(
            f"지출 비율 과다: 최근 {tx_analysis['analysis_months']}개월 평균 지출이 "
            f"수입의 {out_ratio:.1f}%로 저축 여력이 매우 제한적입니다."
        )

    # 원인 5: 잉여자금이 0인 경우 (수입 자체가 없거나 지출이 수입을 초과)
    if surplus <= 0:
        if monthly_in <= 0:
            reasons.append("거래 내역 없음: 분석 기간 내 수입 거래가 확인되지 않아 저축 가능 금액을 산정할 수 없습니다.")
        else:
            reasons.append(
                f"잉여자금 없음: 최근 평균 지출({int(monthly_out):,}원)이 수입({int(monthly_in):,}원)과 같거나 초과하여 저축 여력이 없습니다."
            )

    return reasons


# ──────────────────────────────────────────────
# 5. 대안 시나리오 생성 (v2 실계산 기반)
# ──────────────────────────────────────────────

def _project_amount(
    monthly_payment: Decimal,
    months: int,
    sv_rate: Decimal,
    current_balance: Decimal,
    dp_rate: Decimal,
    dp_period: int,
) -> Decimal:
    """적금 + 예금 조합의 만기 예상금액을 단리 기준으로 계산한다."""
    savings_maturity = monthly_payment * months * (1 + sv_rate / 100 * Decimal(months) / 24)
    deposit_interest = current_balance * dp_rate / 100 * Decimal(dp_period) / 12
    return savings_maturity + current_balance + deposit_interest


def _generate_alternatives(
    feasibility: dict,
    products: dict[str, list[models.BankingProduct]],
) -> list[dict]:
    """
    TIGHT / DIFFICULT / IMPOSSIBLE 일 때 3가지 대안을 실제 계산 기반으로 생성한다.
      - EXTEND_PERIOD           : 이자 포함 시뮬레이션으로 최소 달성 개월 수 탐색
      - INCREASE_MONTHLY_SAVING : 목표 달성에 필요한 월 저축액을 역산
      - CHANGE_PRODUCT_MIX      : 최고금리 조합 적용 시 예상 달성액·달성률 계산
    ACHIEVABLE 이면 빈 리스트를 반환한다.
    """
    verdict = feasibility["feasibility"]
    if verdict == "ACHIEVABLE":
        return []

    goal_amount = Decimal(str(feasibility["goal_amount"]))
    goal_months = feasibility["goal_months"]
    current_balance = Decimal(str(feasibility["current_balance"]))
    monthly_surplus = Decimal(str(feasibility["monthly_surplus"]))

    best_savings = products["savings"][0] if products["savings"] else None
    best_deposit = products["deposits"][0] if products["deposits"] else None

    sv_rate = Decimal(str(best_savings.base_interest_rate)) if best_savings else Decimal("0")
    dp_rate = Decimal(str(best_deposit.base_interest_rate)) if best_deposit else Decimal("0")

    # 월 납입액: 잉여자금의 80% (조합 시), 0이면 1원으로 대체해 나눗셈 오류 방지
    monthly_payment = max(
        (monthly_surplus * Decimal("0.8")).quantize(Decimal("1"), rounding=ROUND_DOWN),
        Decimal("1"),
    )

    alternatives = []

    # ── 대안 1: 기간 연장 (이자 포함 시뮬레이션) ──────────────
    if monthly_surplus > 0:
        # 1개월씩 늘려가며 처음으로 목표를 초과하는 월 수 탐색
        found_months = None
        for m in range(goal_months + 1, MAX_PERIOD_MONTHS + 1):
            dp_period = min(m, best_deposit.max_period_month or m) if best_deposit else m
            projected = _project_amount(monthly_payment, m, sv_rate, current_balance, dp_rate, dp_period)
            if projected >= goal_amount:
                found_months = m
                break

        if found_months:
            extra_months = found_months - goal_months
            dp_period = min(found_months, best_deposit.max_period_month or found_months) if best_deposit else found_months
            projected = _project_amount(monthly_payment, found_months, sv_rate, current_balance, dp_rate, dp_period)
            new_required = (
                (goal_amount - current_balance)
                / Decimal(str(found_months))
            ).quantize(Decimal("1"), rounding=ROUND_DOWN)
            alternatives.append({
                "type": "EXTEND_PERIOD",
                "original_goal_months": goal_months,
                "suggested_goal_months": found_months,
                "extra_months": extra_months,
                "monthly_savings_payment": float(monthly_payment),
                "projected_final_amount": float(projected.quantize(Decimal("1"), rounding=ROUND_DOWN)),
                "required_monthly_saving": float(new_required),
                "reason": (
                    f"기간을 {extra_months}개월 연장({goal_months}개월 → {found_months}개월)하면 "
                    f"현재 잉여자금({int(monthly_surplus):,}원/월) 유지 시 "
                    f"약 {int(projected):,}원 달성이 가능합니다."
                ),
            })
        else:
            alternatives.append({
                "type": "EXTEND_PERIOD",
                "original_goal_months": goal_months,
                "suggested_goal_months": None,
                "extra_months": None,
                "monthly_savings_payment": float(monthly_payment),
                "projected_final_amount": None,
                "required_monthly_saving": None,
                "reason": (
                    f"현재 잉여자금({int(monthly_surplus):,}원/월)으로는 "
                    f"{MAX_PERIOD_MONTHS}개월 내 달성이 어렵습니다. 저축액 증가가 필요합니다."
                ),
            })

    # ── 대안 2: 월 저축액 증가 (역산) ────────────────────────
    # 목표 = monthly_required * goal_months * (1 + sv_rate/100 * goal_months/24) + deposit_maturity
    # monthly_required = (goal_amount - deposit_maturity) / (goal_months * multiplier)
    dp_period_for_goal = min(goal_months, best_deposit.max_period_month or goal_months) if best_deposit else goal_months
    deposit_maturity = current_balance + current_balance * dp_rate / 100 * Decimal(dp_period_for_goal) / 12
    savings_multiplier = Decimal(str(goal_months)) * (
        1 + sv_rate / 100 * Decimal(str(goal_months)) / 24
    )

    if savings_multiplier > 0:
        required_monthly_with_interest = (
            (goal_amount - deposit_maturity) / savings_multiplier
        ).quantize(Decimal("1"), rounding=ROUND_DOWN) + Decimal("1")
        additional = max(required_monthly_with_interest - monthly_surplus, Decimal("0"))
        # 1만원 단위 올림
        additional_rounded = (
            (additional / Decimal("10000")).to_integral_value(rounding=ROUND_DOWN) + 1
        ) * Decimal("10000")

        projected_with_additional = _project_amount(
            (monthly_surplus + additional_rounded) * Decimal("0.8"),
            goal_months, sv_rate, current_balance, dp_rate, dp_period_for_goal,
        )
        alternatives.append({
            "type": "INCREASE_MONTHLY_SAVING",
            "current_monthly_surplus": float(monthly_surplus),
            "additional_amount": float(additional_rounded),
            "new_monthly_saving": float(monthly_surplus + additional_rounded),
            "projected_final_amount": float(projected_with_additional.quantize(Decimal("1"), rounding=ROUND_DOWN)),
            "achievement_rate": round(
                float(projected_with_additional / goal_amount * 100), 1
            ) if goal_amount > 0 else 0,
            "reason": (
                f"월 {int(additional_rounded):,}원 추가 저축 시 "
                f"월 총 저축액 {int(monthly_surplus + additional_rounded):,}원으로 "
                f"목표 기간({goal_months}개월) 내 약 {int(projected_with_additional):,}원 달성 가능합니다."
            ),
        })

    # ── 대안 3: 최고금리 상품 조합 변경 ──────────────────────
    if best_savings:
        mix_payment = max(
            (monthly_surplus * Decimal("0.8")).quantize(Decimal("1"), rounding=ROUND_DOWN),
            Decimal("1"),
        )
        dp_period_mix = min(goal_months, best_deposit.max_period_month or goal_months) if best_deposit else goal_months
        projected_mix = _project_amount(mix_payment, goal_months, sv_rate, current_balance, dp_rate, dp_period_mix)
        achievement_rate_mix = round(float(projected_mix / goal_amount * 100), 1) if goal_amount > 0 else 0

        # 현재 잉여자금으로만 했을 때와 비교해 이자 개선분 계산
        base_projected = monthly_surplus * goal_months + current_balance
        interest_gain = projected_mix - base_projected

        alt_payload: dict = {
            "type": "CHANGE_PRODUCT_MIX",
            "monthly_savings_payment": float(mix_payment),
            "projected_final_amount": float(projected_mix.quantize(Decimal("1"), rounding=ROUND_DOWN)),
            "achievement_rate": achievement_rate_mix,
            "estimated_interest_gain": float(interest_gain.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        }

        if best_deposit:
            alt_payload.update({
                "savings_product_id": best_savings.banking_product_id,
                "savings_product_name": best_savings.deposit_product_name,
                "savings_rate": float(sv_rate),
                "deposit_product_id": best_deposit.banking_product_id,
                "deposit_product_name": best_deposit.deposit_product_name,
                "deposit_rate": float(dp_rate),
                "reason": (
                    f"최고금리 적금({best_savings.deposit_product_name}, {float(sv_rate):.2f}%) + "
                    f"예금({best_deposit.deposit_product_name}, {float(dp_rate):.2f}%) 조합 적용 시 "
                    f"예상 달성률 {achievement_rate_mix:.1f}% (약 {int(projected_mix):,}원), "
                    f"이자 수익 약 {int(interest_gain):,}원 개선 효과가 있습니다."
                ),
            })
        else:
            alt_payload.update({
                "savings_product_id": best_savings.banking_product_id,
                "savings_product_name": best_savings.deposit_product_name,
                "savings_rate": float(sv_rate),
                "deposit_product_id": None,
                "deposit_product_name": None,
                "deposit_rate": None,
                "reason": (
                    f"최고금리 적금({best_savings.deposit_product_name}, {float(sv_rate):.2f}%) 단독 전략 시 "
                    f"예상 달성률 {achievement_rate_mix:.1f}% (약 {int(projected_mix):,}원)입니다."
                ),
            })
        alternatives.append(alt_payload)

    return alternatives


# ──────────────────────────────────────────────
# 5. 복수 전략 생성 (신규)
# ──────────────────────────────────────────────

def _build_strategies(
    products: dict[str, list[models.BankingProduct]],
    goal_amount: Decimal,
    goal_months: int,
    monthly_surplus: Decimal,
    current_balance: Decimal,
) -> list[dict]:
    """
    3가지 전략 유형 생성:
      - 안정형  : 원금 보존 우선 (예금 비중 ↑, 적금 비중 ↓)
      - 수익형  : 예상 수익 최대화 (고금리 적금 비중 ↑)
      - 균형형  : 수익성과 유동성 균형 (50 : 50)
    """
    strategies = []

    savings_list = products["savings"]
    deposit_list = products["deposits"]

    best_savings = savings_list[0] if savings_list else None
    best_deposit = deposit_list[0] if deposit_list else None
    safe_savings = savings_list[-1] if savings_list else None   # 금리 낮은 = 안정
    safe_deposit = deposit_list[-1] if deposit_list else None

    # ── 안정형 ──────────────────────────────────
    stable = _make_strategy(
        name="안정형",
        strategy_type="STABLE",
        description="원금 보존을 최우선으로 하며, 예금 비중을 높여 확정 수익을 추구합니다.",
        savings=safe_savings,
        deposit=best_deposit,
        savings_ratio=Decimal("0.4"),
        goal_amount=goal_amount,
        goal_months=goal_months,
        monthly_surplus=monthly_surplus,
        current_balance=current_balance,
    )
    if stable:
        strategies.append(stable)

    # ── 수익형 ──────────────────────────────────
    growth = _make_strategy(
        name="수익형",
        strategy_type="GROWTH",
        description="고금리 적금 비중을 최대화하여 기간 내 예상 수익을 극대화합니다.",
        savings=best_savings,
        deposit=safe_deposit,
        savings_ratio=Decimal("0.85"),
        goal_amount=goal_amount,
        goal_months=goal_months,
        monthly_surplus=monthly_surplus,
        current_balance=current_balance,
    )
    if growth:
        strategies.append(growth)

    # ── 균형형 ──────────────────────────────────
    balanced = _make_strategy(
        name="균형형",
        strategy_type="BALANCED",
        description="적금과 예금을 균등 배분하여 수익성과 유동성을 함께 추구합니다.",
        savings=best_savings,
        deposit=best_deposit,
        savings_ratio=Decimal("0.6"),
        goal_amount=goal_amount,
        goal_months=goal_months,
        monthly_surplus=monthly_surplus,
        current_balance=current_balance,
    )
    if balanced:
        strategies.append(balanced)

    return strategies


def _make_strategy(
    name: str,
    strategy_type: str,
    description: str,
    savings: models.BankingProduct | None,
    deposit: models.BankingProduct | None,
    savings_ratio: Decimal,
    goal_amount: Decimal,
    goal_months: int,
    monthly_surplus: Decimal,
    current_balance: Decimal,
) -> dict | None:
    if not savings and not deposit:
        return None

    monthly_savings = (monthly_surplus * savings_ratio).quantize(Decimal("1"), rounding=ROUND_DOWN)
    sv_rate = Decimal(str(savings.base_interest_rate)) if savings else Decimal("0")
    dp_rate = Decimal(str(deposit.base_interest_rate)) if deposit else Decimal("0")

    # 적금 만기금 (단리)
    if savings and monthly_savings > 0:
        savings_maturity = monthly_savings * goal_months * (
            1 + sv_rate / 100 * Decimal(goal_months) / 24
        )
    else:
        savings_maturity = Decimal("0")

    # 예금 만기금
    if deposit and current_balance > 0:
        deposit_period = min(goal_months, deposit.max_period_month or goal_months)
        deposit_interest = current_balance * dp_rate / 100 * Decimal(deposit_period) / 12
        deposit_maturity = current_balance + deposit_interest
    else:
        deposit_maturity = current_balance
        deposit_period = None

    total_projected = savings_maturity + deposit_maturity
    # 원금 합계 = 적금 납입 원금 + 예금 원금
    total_principal = monthly_savings * goal_months + current_balance
    expected_interest = max(total_projected - total_principal, Decimal("0"))
    achievement_rate = round(float(total_projected / goal_amount * 100), 1) if goal_amount > 0 else 0

    pros, cons = _strategy_pros_cons(strategy_type, achievement_rate)

    return {
        "name": name,
        "strategy_type": strategy_type,
        "description": description,
        "savings_product": _product_to_dict(savings) if savings else None,
        "deposit_product": _product_to_dict(deposit) if deposit else None,
        "monthly_savings_payment": float(monthly_savings),
        "deposit_amount": float(current_balance),
        "deposit_period_month": deposit_period if deposit else None,
        "savings_maturity_amount": float(savings_maturity.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "deposit_maturity_amount": float(deposit_maturity.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        # ── 전략별 예상 결과 수치 (v2) ──
        "expected_final_amount": float(total_projected.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "expected_interest": float(expected_interest.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "total_principal": float(total_principal.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "achievement_rate": achievement_rate,
        "pros": pros,
        "cons": cons,
    }


def _strategy_pros_cons(strategy_type: str, achievement_rate: float) -> tuple[list[str], list[str]]:
    if strategy_type == "STABLE":
        pros = ["확정 수익으로 원금 손실 없음", "만기 후 유동성 확보 용이"]
        cons = ["적금 비중이 낮아 수익 극대화에 불리"]
        if achievement_rate < 100:
            cons.append(f"현재 설정으로 목표 대비 {achievement_rate:.1f}% 달성 예상")
    elif strategy_type == "GROWTH":
        pros = ["높은 금리로 수익 극대화", "목표 달성 가능성 최대화"]
        cons = ["잉여자금 대부분이 적금에 묶여 유동성 부족 가능"]
        if achievement_rate < 100:
            cons.append(f"현재 설정으로 목표 대비 {achievement_rate:.1f}% 달성 예상")
    else:
        pros = ["수익성과 유동성을 균형 있게 확보", "리스크 분산 효과"]
        cons = ["전략별 최대 효과는 낮을 수 있음"]
        if achievement_rate < 100:
            cons.append(f"현재 설정으로 목표 대비 {achievement_rate:.1f}% 달성 예상")
    return pros, cons


# ──────────────────────────────────────────────
# 6. 상품 조회
# ──────────────────────────────────────────────

def _get_available_products(db: Session) -> dict[str, list[models.BankingProduct]]:
    products = db.scalars(
        select(models.BankingProduct).where(
            models.BankingProduct.deposit_product_status == models.DepositProductStatus.SELLING
        )
    ).all()

    deposits = [p for p in products if p.deposit_product_type == models.DepositProductType.DEPOSIT]
    savings = [p for p in products if p.deposit_product_type == models.DepositProductType.SAVINGS]

    deposits.sort(key=lambda p: float(p.base_interest_rate), reverse=True)
    savings.sort(key=lambda p: float(p.base_interest_rate), reverse=True)

    return {"deposits": deposits, "savings": savings}


def _product_to_dict(p: models.BankingProduct) -> dict:
    return {
        "banking_product_id": p.banking_product_id,
        "product_name": p.deposit_product_name,
        "product_type": p.deposit_product_type,
        "base_interest_rate": float(p.base_interest_rate),
        "min_period_month": p.min_period_month,
        "max_period_month": p.max_period_month,
        "min_join_amount": float(p.min_join_amount) if p.min_join_amount else None,
        "max_join_amount": float(p.max_join_amount) if p.max_join_amount else None,
        "is_tax_benefit_available": p.is_tax_benefit_available,
        "is_auto_renewal_available": p.is_auto_renewal_available,
        "description": p.description,
    }


def _recommend_combinations(
    products: dict[str, list[models.BankingProduct]],
    goal_amount: Decimal,
    goal_months: int,
    monthly_surplus: Decimal,
    current_balance: Decimal,
) -> list[dict]:
    """적금 + 예금 조합 추천 리스트 생성."""
    combinations = []

    for sv in products["savings"][:3]:
        for dp in products["deposits"][:3]:
            combo = _build_combination(sv, dp, goal_amount, goal_months, monthly_surplus, current_balance)
            if combo:
                combinations.append(combo)

    for sv in products["savings"][:3]:
        combo = _build_savings_only(sv, goal_amount, goal_months, monthly_surplus, current_balance)
        if combo:
            combinations.append(combo)

    combinations.sort(key=lambda x: x["score"], reverse=True)
    return combinations[:MAX_RECOMMENDATIONS]


def _build_combination(
    savings: models.BankingProduct,
    deposit: models.BankingProduct,
    goal_amount: Decimal,
    goal_months: int,
    monthly_surplus: Decimal,
    current_balance: Decimal,
) -> dict | None:
    monthly_savings_payment = (monthly_surplus * Decimal("0.7")).quantize(Decimal("1"), rounding=ROUND_DOWN)
    if savings.min_join_amount and monthly_savings_payment < Decimal(str(savings.min_join_amount)):
        monthly_savings_payment = Decimal(str(savings.min_join_amount))
    if monthly_savings_payment > monthly_surplus:
        return None

    savings_rate = Decimal(str(savings.base_interest_rate))
    deposit_rate = Decimal(str(deposit.base_interest_rate))
    savings_maturity = monthly_savings_payment * goal_months * (
        1 + savings_rate / 100 * Decimal(goal_months) / 24
    )
    deposit_amount = current_balance
    deposit_period = min(goal_months, deposit.max_period_month or goal_months)
    deposit_interest = deposit_amount * deposit_rate / 100 * Decimal(deposit_period) / 12
    total_projected = savings_maturity + deposit_amount + deposit_interest
    achievement_rate = float(total_projected / goal_amount * 100) if goal_amount > 0 else 0

    score = (
        float(savings_rate) * 3
        + float(deposit_rate) * 2
        + (10 if achievement_rate >= 100 else achievement_rate / 10)
        + (3 if savings.is_tax_benefit_available == "Y" else 0)
        + (3 if deposit.is_tax_benefit_available == "Y" else 0)
    )

    return {
        "combination_type": "SAVINGS_AND_DEPOSIT",
        "savings_product": _product_to_dict(savings),
        "deposit_product": _product_to_dict(deposit),
        "monthly_savings_payment": float(monthly_savings_payment),
        "deposit_amount": float(deposit_amount),
        "deposit_period_month": deposit_period,
        "savings_maturity_amount": float(savings_maturity.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "deposit_maturity_amount": float((deposit_amount + deposit_interest).quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "total_projected_amount": float(total_projected.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "achievement_rate": round(achievement_rate, 1),
        "score": round(score, 2),
        "summary": (
            f"월 {int(monthly_savings_payment):,}원 적금({savings.deposit_product_name}, {float(savings_rate):.2f}%) + "
            f"현재 잔액 {int(deposit_amount):,}원 예금({deposit.deposit_product_name}, {float(deposit_rate):.2f}%) 조합으로 "
            f"{goal_months}개월 후 약 {int(total_projected):,}원 달성 예상"
        ),
    }


def _build_savings_only(
    savings: models.BankingProduct,
    goal_amount: Decimal,
    goal_months: int,
    monthly_surplus: Decimal,
    current_balance: Decimal,
) -> dict | None:
    monthly_payment = (monthly_surplus * Decimal("0.9")).quantize(Decimal("1"), rounding=ROUND_DOWN)
    if savings.min_join_amount and monthly_payment < Decimal(str(savings.min_join_amount)):
        monthly_payment = Decimal(str(savings.min_join_amount))
    if monthly_payment > monthly_surplus:
        return None

    savings_rate = Decimal(str(savings.base_interest_rate))
    savings_maturity = monthly_payment * goal_months * (1 + savings_rate / 100 * Decimal(goal_months) / 24)
    total_projected = savings_maturity + current_balance
    achievement_rate = float(total_projected / goal_amount * 100) if goal_amount > 0 else 0

    score = (
        float(savings_rate) * 3
        + (10 if achievement_rate >= 100 else achievement_rate / 10)
        + (3 if savings.is_tax_benefit_available == "Y" else 0)
    )

    return {
        "combination_type": "SAVINGS_ONLY",
        "savings_product": _product_to_dict(savings),
        "deposit_product": None,
        "monthly_savings_payment": float(monthly_payment),
        "deposit_amount": float(current_balance),
        "deposit_period_month": None,
        "savings_maturity_amount": float(savings_maturity.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "deposit_maturity_amount": float(current_balance),
        "total_projected_amount": float(total_projected.quantize(Decimal("1"), rounding=ROUND_DOWN)),
        "achievement_rate": round(achievement_rate, 1),
        "score": round(score, 2),
        "summary": (
            f"월 {int(monthly_payment):,}원 적금({savings.deposit_product_name}, {float(savings_rate):.2f}%)으로 "
            f"{goal_months}개월 후 약 {int(total_projected):,}원 달성 예상"
        ),
    }


# ──────────────────────────────────────────────
# 7. 월별 납입 계획
# ──────────────────────────────────────────────

def _build_monthly_plan(
    combination: dict,
    goal_amount: Decimal,
    goal_months: int,
) -> list[dict]:
    monthly_payment = Decimal(str(combination["monthly_savings_payment"]))
    savings_rate = Decimal(str(combination["savings_product"]["base_interest_rate"]))
    deposit_value = Decimal(str(combination["deposit_amount"]))
    deposit_rate = Decimal("0")
    deposit_period_limit = goal_months
    if combination.get("deposit_product"):
        deposit_rate = Decimal(str(combination["deposit_product"]["base_interest_rate"]))
        deposit_period_limit = combination.get("deposit_period_month") or goal_months

    today = date.today()
    plan = []
    cumulative_savings = Decimal("0")

    for month in range(1, goal_months + 1):
        cumulative_savings += monthly_payment
        # 적금 누적 이자 (월별 단리 근사)
        savings_interest = (
            monthly_payment * savings_rate / 100
            * Decimal(month * (month + 1) / 2) / 12
        )
        # 예금 누적 이자
        active_deposit_months = min(month, deposit_period_limit)
        dep_interest = deposit_value * deposit_rate / 100 / 12 * active_deposit_months

        projected = cumulative_savings + savings_interest + deposit_value + dep_interest
        remaining = max(goal_amount - projected, Decimal("0"))
        month_date = today + timedelta(days=month * 30)

        plan.append({
            "month": month,
            "year_month": month_date.strftime("%Y-%m"),
            "monthly_payment": float(monthly_payment),
            "cumulative_savings": float(cumulative_savings.quantize(Decimal("1"), rounding=ROUND_DOWN)),
            "projected_total": float(projected.quantize(Decimal("1"), rounding=ROUND_DOWN)),
            "remaining_to_goal": float(remaining.quantize(Decimal("1"), rounding=ROUND_DOWN)),
            "achievement_rate": round(float(projected / goal_amount * 100), 1) if goal_amount > 0 else 0,
        })

    return plan


# ──────────────────────────────────────────────
# 9. 추천 사유 상세화 (v2 구조화)
# ──────────────────────────────────────────────

def _build_recommendation_reason(
    feasibility: dict,
    tx_analysis: dict,
    combinations: list[dict],
    strategies: list[dict],
) -> dict:
    """
    4개 항목으로 구조화된 추천 사유를 반환한다.

    반환 형태:
        {
            "cash_flow_status"        : str,  # 현금흐름 상태
            "goal_feasibility"        : str,  # 목표 달성 가능성
            "product_selection_reason": str,  # 추천 상품 선택 이유
            "strategy_selection_reason": str, # 선택 전략 이유
            "summary"                 : str,  # 위 4개를 합친 한 문단 요약
        }
    """
    surplus = feasibility["monthly_surplus"]
    required = feasibility["required_monthly_saving"]
    verdict = feasibility["feasibility"]
    goal_months = feasibility["goal_months"]
    goal_amount = feasibility["goal_amount"]
    analysis_months = tx_analysis["analysis_months"]
    monthly_in = tx_analysis["monthly_avg_in"]
    monthly_out = tx_analysis["monthly_avg_out"]

    # ── 1. 현금흐름 상태 ──────────────────────────────────
    if surplus > 0 and monthly_in > 0:
        surplus_ratio = surplus / monthly_in
        if surplus_ratio >= 0.3:
            cash_flow_label = "안정적"
            cash_flow_status = (
                f"고객님의 최근 {analysis_months}개월 평균 잉여자금은 {int(surplus):,}원으로 안정적인 수준입니다. "
                f"수입 대비 지출 비율이 {float(monthly_out / monthly_in * 100):.1f}%로 저축 여력이 충분합니다."
            )
        elif surplus_ratio >= 0.1:
            cash_flow_label = "보통"
            cash_flow_status = (
                f"고객님의 최근 {analysis_months}개월 평균 잉여자금은 {int(surplus):,}원으로 보통 수준입니다. "
                f"수입 대비 지출 비율이 {float(monthly_out / monthly_in * 100):.1f}%로 절약을 통해 저축을 늘릴 여지가 있습니다."
            )
        else:
            cash_flow_label = "부족"
            cash_flow_status = (
                f"고객님의 최근 {analysis_months}개월 평균 잉여자금은 {int(surplus):,}원으로 다소 부족한 수준입니다. "
                f"수입 대비 지출 비율이 {float(monthly_out / monthly_in * 100):.1f}%로 지출 구조 개선이 필요합니다."
            )
    else:
        cash_flow_label = "미확인"
        cash_flow_status = (
            f"최근 {analysis_months}개월 거래 내역이 부족하여 현금흐름을 정확히 분석하기 어렵습니다."
        )

    # ── 2. 목표 달성 가능성 ───────────────────────────────
    if verdict == "ACHIEVABLE":
        goal_feasibility = (
            f"목표 금액 {int(goal_amount):,}원은 설정 기간({goal_months}개월) 내 달성이 충분히 가능합니다. "
            f"월 필요 저축액({int(required):,}원)을 현재 잉여자금({int(surplus):,}원)으로 충당할 수 있습니다."
        )
    elif verdict == "TIGHT":
        goal_feasibility = (
            f"목표 기간({goal_months}개월) 내 달성이 가능하나 빠듯합니다. "
            f"월 필요 저축액({int(required):,}원)과 현재 잉여자금({int(surplus):,}원)의 차이가 작아 꾸준한 납입이 중요합니다."
        )
    elif verdict == "DIFFICULT":
        goal_feasibility = (
            f"현재 잉여자금({int(surplus):,}원/월)으로는 {goal_months}개월 내 달성이 어렵습니다. "
            f"월 {int(required - surplus):,}원의 추가 저축이 필요합니다. 아래 대안 시나리오를 참고하세요."
        )
    else:
        goal_feasibility = (
            f"현재 소비 패턴으로는 {goal_months}개월 내 {int(goal_amount):,}원 달성이 불가능합니다. "
            f"소비 구조 개선 및 대안 시나리오 검토가 필요합니다."
        )

    # ── 3. 추천 상품 선택 이유 ────────────────────────────
    if combinations:
        best = combinations[0]
        sv = best.get("savings_product")
        dp = best.get("deposit_product")
        if sv and dp:
            product_selection_reason = (
                f"현재 판매 중인 상품 중 적금 '{sv['product_name']}'(금리 {sv['base_interest_rate']:.2f}%)과 "
                f"예금 '{dp['product_name']}'(금리 {dp['base_interest_rate']:.2f}%) 조합이 "
                f"예상 달성률 {best['achievement_rate']:.1f}%로 가장 높은 수익을 제공합니다."
            )
        elif sv:
            product_selection_reason = (
                f"현재 판매 중인 적금 중 '{sv['product_name']}'(금리 {sv['base_interest_rate']:.2f}%)이 "
                f"예상 달성률 {best['achievement_rate']:.1f}%로 최적의 선택입니다."
            )
        else:
            product_selection_reason = "현재 판매 중인 적합 상품이 없습니다."
    else:
        product_selection_reason = "현재 판매 중인 적합 상품이 없습니다."

    # ── 4. 선택 전략 이유 ─────────────────────────────────
    if strategies:
        # 달성률이 가장 높은 전략을 우선 추천
        best_strategy = max(strategies, key=lambda s: s["achievement_rate"])
        strategy_selection_reason = (
            f"전략 비교 결과 '{best_strategy['name']}'({best_strategy['strategy_type']})가 "
            f"예상 달성률 {best_strategy['achievement_rate']:.1f}%로 가장 유리합니다. "
            f"예상 최종 금액 {int(best_strategy['expected_final_amount']):,}원, "
            f"예상 이자 수익 {int(best_strategy['expected_interest']):,}원입니다."
        )
    elif verdict == "ACHIEVABLE":
        strategy_selection_reason = "목표 달성이 가능하므로 안정적인 정기적금 중심 전략을 유지하세요."
    else:
        strategy_selection_reason = "상품 데이터가 부족하여 전략 비교가 어렵습니다."

    summary = (
        f"{cash_flow_status} "
        f"{goal_feasibility} "
        f"{product_selection_reason} "
        f"{strategy_selection_reason}"
    )

    return {
        "cash_flow_status": cash_flow_status,
        "goal_feasibility": goal_feasibility,
        "product_selection_reason": product_selection_reason,
        "strategy_selection_reason": strategy_selection_reason,
        "summary": summary,
    }


# ──────────────────────────────────────────────
# 공개 API
# ──────────────────────────────────────────────

def analyze_goal(db: Session, customer_id: str, goal_amount: float, goal_months: int) -> dict:
    """
    금융 목표 달성 플래너 메인 함수.

    Parameters
    ----------
    customer_id  : 고객 ID
    goal_amount  : 목표 금액 (원)
    goal_months  : 목표 기간 (개월)

    Returns
    -------
    {
        customer_id, goal_amount, goal_months,
        accounts,
        transaction_analysis,
        feasibility,
        failure_reasons,       ← v2
        alternatives,
        strategies,
        recommended_combinations,
        recommendation_reason, ← v2 구조화
        monthly_plan,
        summary,
    }
    """
    if goal_amount <= 0:
        raise HTTPException(status_code=400, detail="goal_amount must be positive")
    if goal_months <= 0 or goal_months > MAX_PERIOD_MONTHS:
        raise HTTPException(status_code=400, detail=f"goal_months must be between 1 and {MAX_PERIOD_MONTHS}")

    goal = Decimal(str(goal_amount))

    # 1. 계좌 조회
    accounts = get_customer_accounts(db, customer_id)
    if not accounts:
        raise HTTPException(status_code=404, detail="no active accounts found for customer")

    account_ids = [a.account_id for a in accounts]
    current_balance = get_total_balance(accounts)

    # 2. 거래 내역 분석
    txns = get_recent_transactions(db, account_ids, ANALYSIS_MONTHS)
    tx_analysis = analyze_transactions(txns, ANALYSIS_MONTHS)
    monthly_surplus = Decimal(str(tx_analysis["monthly_avg_surplus"]))

    # 3. 목표 달성 가능성 판단
    feasibility = _evaluate_feasibility(goal, goal_months, current_balance, monthly_surplus)

    # 4. 실패 원인 분석 (TIGHT / DIFFICULT / IMPOSSIBLE 시 원인 구체화)
    failure_reasons = _analyze_failure_reasons(feasibility, tx_analysis)

    # 5. 상품 조회
    products = _get_available_products(db)

    # 6. 대안 시나리오 생성 (실계산 기반)
    alternatives = _generate_alternatives(feasibility, products)

    # 7. 복수 전략 생성
    strategies = _build_strategies(products, goal, goal_months, monthly_surplus, current_balance)

    # 8. 상품 조합 추천
    combinations = _recommend_combinations(products, goal, goal_months, monthly_surplus, current_balance)

    # 9. 추천 사유 (구조화)
    recommendation_reason = _build_recommendation_reason(feasibility, tx_analysis, combinations, strategies)

    # 10. 월별 납입 계획 (최우선 조합 기준)
    monthly_plan = []
    if combinations:
        monthly_plan = _build_monthly_plan(combinations[0], goal, goal_months)

    return {
        "customer_id": customer_id,
        "goal_amount": goal_amount,
        "goal_months": goal_months,
        "accounts": [
            {
                "account_id": a.account_id,
                "account_number": a.account_number,
                "account_type": a.account_type,
                "balance": float(a.balance),
            }
            for a in accounts
        ],
        "transaction_analysis": tx_analysis,
        "feasibility": feasibility,
        "failure_reasons": failure_reasons,
        "alternatives": alternatives,
        "strategies": strategies,
        "recommended_combinations": combinations,
        "recommendation_reason": recommendation_reason,
        "monthly_plan": monthly_plan,
        "summary": _build_summary_message(feasibility, combinations, tx_analysis),
    }


def _build_summary_message(feasibility: dict, combinations: list[dict], tx_analysis: dict) -> str:
    surplus = feasibility["monthly_surplus"]
    required = feasibility["required_monthly_saving"]
    verdict = feasibility["feasibility"]
    goal_months = feasibility["goal_months"]
    goal_amount = feasibility["goal_amount"]

    if verdict == "ACHIEVABLE":
        status = (
            f"현재 잔액과 최근 {tx_analysis['analysis_months']}개월 잉여자금을 기준으로 "
            f"월 {int(surplus):,}원 저축이 가능합니다."
        )
    elif verdict == "TIGHT":
        status = (
            f"월 {int(surplus):,}원 잉여자금으로 빠듯하지만 달성 가능합니다. "
            f"월 {int(required):,}원 이상 저축이 필요합니다."
        )
    elif verdict == "DIFFICULT":
        status = (
            f"현재 월 잉여자금({int(surplus):,}원)이 필요 금액({int(required):,}원)에 부족합니다. "
            f"지출 절감이 필요합니다."
        )
    else:
        status = "현재 소비 패턴으로는 목표 달성이 어렵습니다. 소비 구조 개선이 필요합니다."

    if combinations:
        best = combinations[0]
        plan_desc = (
            f" {goal_months}개월 안에 {int(goal_amount):,}원 달성을 위해 "
            f"{best['summary']}"
        )
    else:
        plan_desc = " 현재 판매 중인 추천 상품이 없습니다."

    return status + plan_desc
