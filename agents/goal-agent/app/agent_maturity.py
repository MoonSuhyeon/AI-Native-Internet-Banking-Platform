"""
만기 알림 및 재투자 추천 에이전트

흐름:
  1. 만기 예정 계약 감지  (get_upcoming_maturities)
  2. 재투자 가능 상품 추천 (get_reinvestment_recommendations)
  3. 시나리오 실행         (process_maturity_scenario)
"""

from datetime import date, datetime, timedelta
from decimal import Decimal
from enum import Enum

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.services import get_account, record_transaction, pay_interest, is_sqlite, next_id
from app.utils import new_number, today_text


# ──────────────────────────────────────────────
# 날짜 호환 헬퍼
# ──────────────────────────────────────────────

def _to_date(value) -> date | None:
    """DB date 객체, 'YYYY-MM-DD' 문자열, 'YYYYMMDD' 문자열 모두 date로 변환."""
    if value is None:
        return None
    if isinstance(value, date):
        return value
    text = str(value)
    if "-" in text:
        return datetime.strptime(text, "%Y-%m-%d").date()
    return datetime.strptime(text, "%Y%m%d").date()


def _date_to_str(value) -> str | None:
    """date 객체 또는 YYYYMMDD 문자열을 YYYYMMDD 문자열로 반환."""
    if value is None:
        return None
    d = _to_date(value)
    return d.strftime("%Y%m%d") if d else None


# ──────────────────────────────────────────────
# 상수
# ──────────────────────────────────────────────

DEFAULT_LOOKAHEAD_DAYS = 30  # 기본 조회 기간 (만기 D-30)


# ──────────────────────────────────────────────
# 시나리오 타입
# ──────────────────────────────────────────────

class MaturityScenario(str, Enum):
    AUTO_RENEWAL = "AUTO_RENEWAL"          # 동일 상품 자동 갱신
    REINVEST_NEW = "REINVEST_NEW"          # 추천 신상품으로 재투자
    WITHDRAW_ALL = "WITHDRAW_ALL"          # 전액 출금 (해지)
    PARTIAL_REINVEST = "PARTIAL_REINVEST"  # 일부 재투자 + 일부 출금


# ──────────────────────────────────────────────
# 1. 만기 예정 계약 감지
# ──────────────────────────────────────────────

def get_upcoming_maturities(db: Session, days: int = DEFAULT_LOOKAHEAD_DAYS) -> list[dict]:
    """days 일 이내 만기 예정인 ACTIVE 계약 목록을 반환."""
    today = date.today()
    deadline = today + timedelta(days=days)

    contracts = db.scalars(
        select(models.Contract).where(
            models.Contract.contract_status == models.ContractStatus.ACTIVE,
            models.Contract.maturity_at >= today,
            models.Contract.maturity_at <= deadline,
        )
    ).all()

    result = []
    for c in contracts:
        maturity_date = _to_date(c.maturity_at)
        days_left = (maturity_date - today).days

        account = db.scalar(
            select(models.Account).where(models.Account.contract_id == c.contract_id)
        )
        product = db.get(models.BankingProduct, c.banking_product_id)

        result.append({
            "contract_id": c.contract_id,
            "contract_number": c.contract_number,
            "customer_id": c.customer_id,
            "banking_product_id": c.banking_product_id,
            "product_name": product.deposit_product_name if product else None,
            "product_type": product.deposit_product_type if product else None,
            "final_interest_rate": float(c.final_interest_rate),
            "contract_period_month": c.contract_period_month,
            "maturity_at": _date_to_str(c.maturity_at),
            "days_until_maturity": days_left,
            "current_balance": float(account.balance) if account else 0,
            "account_id": account.account_id if account else None,
            "is_auto_renewal": c.is_auto_renewal,
            "urgency": _urgency_label(days_left),
        })

    result.sort(key=lambda x: x["days_until_maturity"])
    return result


def _urgency_label(days_left: int) -> str:
    if days_left <= 7:
        return "URGENT"
    if days_left <= 14:
        return "HIGH"
    if days_left <= 30:
        return "MEDIUM"
    return "LOW"


# ──────────────────────────────────────────────
# 2. 재투자 가능 상품 추천
# ──────────────────────────────────────────────

def get_reinvestment_recommendations(db: Session, contract_id: int) -> dict:
    """특정 계약에 대한 재투자 추천 상품 + 4가지 시나리오 설명을 반환."""
    contract = db.get(models.Contract, contract_id)
    if not contract:
        raise HTTPException(status_code=404, detail="contract not found")
    if contract.contract_status != models.ContractStatus.ACTIVE:
        raise HTTPException(status_code=400, detail="contract is not active")

    account = db.scalar(
        select(models.Account).where(models.Account.contract_id == contract_id)
    )
    if not account:
        raise HTTPException(status_code=404, detail="account not found")

    current_product = db.get(models.BankingProduct, contract.banking_product_id)
    balance = Decimal(account.balance)
    current_rate = Decimal(contract.final_interest_rate)

    # 판매 중인 상품 전체 조회
    active_products = db.scalars(
        select(models.BankingProduct).where(
            models.BankingProduct.deposit_product_status == models.DepositProductStatus.SELLING
        )
    ).all()

    recommendations = []
    for p in active_products:
        if p.banking_product_id == contract.banking_product_id:
            continue  # 현재 동일 상품은 별도 갱신 시나리오로 처리

        # 가입 가능 금액 체크
        if p.min_join_amount and balance < Decimal(p.min_join_amount):
            continue
        if p.max_join_amount and balance > Decimal(p.max_join_amount):
            continue

        rate_diff = float(p.base_interest_rate) - float(current_rate)
        reason = _build_recommendation_reason(p, rate_diff, current_product)

        recommendations.append({
            "banking_product_id": p.banking_product_id,
            "product_name": p.deposit_product_name,
            "product_type": p.deposit_product_type,
            "base_interest_rate": float(p.base_interest_rate),
            "rate_difference_vs_current": round(rate_diff, 2),
            "min_period_month": p.min_period_month,
            "max_period_month": p.max_period_month,
            "min_join_amount": float(p.min_join_amount) if p.min_join_amount else None,
            "max_join_amount": float(p.max_join_amount) if p.max_join_amount else None,
            "is_auto_renewal_available": p.is_auto_renewal_available,
            "is_tax_benefit_available": p.is_tax_benefit_available,
            "description": p.description,
            "recommendation_reason": reason,
            "recommendation_score": _score(p, rate_diff, balance),
        })

    recommendations.sort(key=lambda x: x["recommendation_score"], reverse=True)

    maturity_date = _to_date(contract.maturity_at)
    days_left = (maturity_date - date.today()).days

    # 예상 이자 계산 (단리 기준)
    estimated_interest = _estimate_maturity_interest(balance, current_rate, contract.contract_period_month)

    return {
        "contract_id": contract_id,
        "contract_number": contract.contract_number,
        "customer_id": contract.customer_id,
        "current_product_name": current_product.deposit_product_name if current_product else None,
        "current_balance": float(balance),
        "current_rate": float(current_rate),
        "maturity_at": _date_to_str(contract.maturity_at),
        "days_until_maturity": days_left,
        "estimated_maturity_interest": float(estimated_interest),
        "estimated_maturity_total": float(balance + estimated_interest),
        "recommendations": recommendations[:5],  # 상위 5개
        "scenarios": _build_scenarios(contract, account, current_product, recommendations),
    }


def _build_recommendation_reason(product: models.BankingProduct, rate_diff: float, current_product) -> str:
    parts = []
    if rate_diff > 0:
        parts.append(f"현재 상품 대비 금리 {rate_diff:+.2f}% 우대")
    elif rate_diff < 0:
        parts.append(f"현재 상품 대비 금리 {rate_diff:.2f}%")
    else:
        parts.append("동일 금리 수준")

    if product.is_tax_benefit_available == "Y":
        parts.append("세제 혜택 가능")
    if product.is_auto_renewal_available == "Y":
        parts.append("자동 갱신 지원")
    if current_product and product.deposit_product_type == current_product.deposit_product_type:
        parts.append("동일 상품 유형")

    return " · ".join(parts)


def _score(product: models.BankingProduct, rate_diff: float, balance: Decimal) -> float:
    score = rate_diff * 10  # 금리 차이 가중
    if product.is_tax_benefit_available == "Y":
        score += 5
    if product.is_auto_renewal_available == "Y":
        score += 2
    return round(score, 2)


def _estimate_maturity_interest(balance: Decimal, rate: Decimal, period_month: int) -> Decimal:
    """단리 기준 만기 이자 추정."""
    return balance * rate / 100 * Decimal(period_month) / 12


def _build_scenarios(contract, account, current_product, recommendations: list) -> list[dict]:
    balance = float(account.balance)
    rate = float(contract.final_interest_rate)
    period = contract.contract_period_month

    best_new = recommendations[0] if recommendations else None

    scenarios = [
        {
            "scenario": MaturityScenario.AUTO_RENEWAL,
            "title": "동일 상품 자동 갱신",
            "description": (
                f"현재 가입 중인 '{current_product.deposit_product_name if current_product else '상품'}'으로 "
                f"{period}개월 동일 조건 재가입합니다."
            ),
            "expected_interest_rate": rate,
            "expected_period_month": period,
            "expected_reinvest_amount": balance,
            "pros": ["별도 절차 없이 자동 처리", "익숙한 상품 유지"],
            "cons": ["더 높은 금리 상품으로 갱신 기회 놓칠 수 있음"],
            "available": contract.is_auto_renewal == "Y" or (current_product and current_product.is_auto_renewal_available == "Y"),
        },
        {
            "scenario": MaturityScenario.REINVEST_NEW,
            "title": "추천 신상품으로 재투자",
            "description": (
                f"'{best_new['product_name']}'({best_new['base_interest_rate']}%) 상품으로 재투자합니다."
                if best_new else "현재 추천 가능한 다른 상품이 없습니다."
            ),
            "expected_interest_rate": best_new["base_interest_rate"] if best_new else rate,
            "expected_period_month": best_new["min_period_month"] if best_new else period,
            "expected_reinvest_amount": balance,
            "recommended_product": best_new,
            "pros": ["더 높은 금리 상품 활용 가능", "세제 혜택 상품 선택 가능"],
            "cons": ["신규 계약 절차 필요"],
            "available": best_new is not None,
        },
        {
            "scenario": MaturityScenario.WITHDRAW_ALL,
            "title": "전액 출금 (해지)",
            "description": "만기 시 원금과 이자를 전액 출금하여 자유롭게 활용합니다.",
            "expected_interest_rate": rate,
            "expected_period_month": None,
            "expected_reinvest_amount": 0,
            "pros": ["즉시 자금 활용 가능", "다른 투자처로 이동 자유"],
            "cons": ["재투자 시 새 계약 절차 필요", "이자 소득 중단"],
            "available": True,
        },
        {
            "scenario": MaturityScenario.PARTIAL_REINVEST,
            "title": "일부 재투자 + 일부 출금",
            "description": "만기 금액의 일부는 재투자하고, 나머지는 즉시 활용하는 혼합 전략입니다.",
            "expected_interest_rate": rate,
            "expected_period_month": period,
            "expected_reinvest_amount": balance * 0.5,  # 기본 50% 예시
            "pros": ["유동성 확보 + 이자 수익 동시에", "리스크 분산"],
            "cons": ["재투자 금액에 따라 최소 가입 금액 확인 필요"],
            "available": True,
        },
    ]
    return scenarios


# ──────────────────────────────────────────────
# 3. 만기 시나리오 실행
# ──────────────────────────────────────────────

def process_maturity_scenario(db: Session, contract_id: int, payload: dict) -> dict:
    """
    payload:
        scenario: MaturityScenario
        # REINVEST_NEW / AUTO_RENEWAL
        new_banking_product_id: int (optional)
        new_contract_period_month: int (optional)
        # PARTIAL_REINVEST
        reinvest_amount: float (optional, 기본 전액)
        withdraw_amount: float (optional)
        # 공통
        channel_type: str (optional)
    """
    contract = db.get(models.Contract, contract_id)
    if not contract:
        raise HTTPException(status_code=404, detail="contract not found")
    if contract.contract_status != models.ContractStatus.ACTIVE:
        raise HTTPException(status_code=400, detail="contract is not active")

    account = db.scalar(
        select(models.Account).where(models.Account.contract_id == contract_id)
    )
    if not account:
        raise HTTPException(status_code=404, detail="account not found")

    scenario = MaturityScenario(payload["scenario"])

    # 만기 이자 지급 (공통)
    maturity_interest = _estimate_maturity_interest(
        Decimal(account.balance),
        Decimal(contract.final_interest_rate),
        contract.contract_period_month,
    )
    if maturity_interest > 0:
        _pay_maturity_interest(db, contract, account, maturity_interest)

    total_balance = Decimal(account.balance)

    if scenario == MaturityScenario.AUTO_RENEWAL:
        result = _do_auto_renewal(db, contract, account, payload)

    elif scenario == MaturityScenario.REINVEST_NEW:
        result = _do_reinvest_new(db, contract, account, payload, total_balance)

    elif scenario == MaturityScenario.WITHDRAW_ALL:
        result = _do_withdraw_all(db, contract, account, payload, total_balance)

    elif scenario == MaturityScenario.PARTIAL_REINVEST:
        result = _do_partial_reinvest(db, contract, account, payload, total_balance)

    else:
        raise HTTPException(status_code=400, detail=f"unknown scenario: {scenario}")

    # 원계약 만기 처리
    contract.contract_status = models.ContractStatus.MATURED
    contract.terminated_at = today_text()
    account.account_status = models.AccountStatus.CLOSED
    account.closed_at = today_text()

    db.commit()
    return result


def _pay_maturity_interest(db: Session, contract, account, interest: Decimal):
    """만기 이자를 잔액에 반영."""
    before = Decimal(account.balance)
    account.balance = before + interest
    account.total_interest_amount = Decimal(account.total_interest_amount) + interest
    account.last_interest_paid_at = datetime.utcnow()

    history = models.InterestHistory(
        interest_id=next_id(db, models.InterestHistory, "interest_id") if is_sqlite(db) else None,
        contract_id=contract.contract_id,
        account_id=account.account_id,
        applied_interest_rate=contract.final_interest_rate,
        interest_before_tax=interest,
        interest_tax_amount=Decimal("0"),
        local_income_tax_amount=Decimal("0"),
        interest_after_tax=interest,
        interest_reason=models.InterestReason.MATURITY_INTEREST,
        interest_paid_at=datetime.utcnow(),
    )
    db.add(history)
    db.flush()


def _do_auto_renewal(db: Session, contract, account, payload: dict) -> dict:
    """동일 상품으로 동일 기간 재가입."""
    from app.services import create_contract_with_account

    new_contract = create_contract_with_account(db, {
        "customer_id": contract.customer_id,
        "banking_product_id": contract.banking_product_id,
        "contract_interest_rate": contract.contract_interest_rate,
        "total_preferential_rate": contract.total_preferential_rate,
        "final_interest_rate": contract.final_interest_rate,
        "tax_benefit_type": contract.tax_benefit_type,
        "applied_tax_rate": contract.applied_tax_rate,
        "contract_period_month": payload.get("new_contract_period_month", contract.contract_period_month),
        "started_at": today_text(),
        "maturity_at": _add_months(today_text(), payload.get("new_contract_period_month", contract.contract_period_month)),
        "join_channel": contract.join_channel,
        "branch_id": contract.branch_id,
        "manager_id": contract.manager_id,
        "is_auto_renewal": contract.is_auto_renewal,
        "initial_balance": float(account.balance),
    })
    return {
        "scenario": MaturityScenario.AUTO_RENEWAL,
        "message": "동일 상품으로 자동 갱신 완료",
        "original_contract_id": contract.contract_id,
        "new_contract_id": new_contract.contract_id,
        "new_contract_number": new_contract.contract_number,
        "reinvest_amount": float(account.balance),
    }


def _do_reinvest_new(db: Session, contract, account, payload: dict, total_balance: Decimal) -> dict:
    """추천 신상품으로 재투자."""
    from app.services import create_contract_with_account

    new_product_id = payload.get("new_banking_product_id")
    if not new_product_id:
        raise HTTPException(status_code=400, detail="new_banking_product_id is required for REINVEST_NEW")

    new_product = db.get(models.BankingProduct, new_product_id)
    if not new_product:
        raise HTTPException(status_code=404, detail="new banking_product not found")

    period = payload.get("new_contract_period_month", new_product.min_period_month or 12)

    new_contract = create_contract_with_account(db, {
        "customer_id": contract.customer_id,
        "banking_product_id": new_product_id,
        "contract_period_month": period,
        "started_at": today_text(),
        "maturity_at": _add_months(today_text(), period),
        "join_channel": payload.get("join_channel", contract.join_channel),
        "initial_balance": float(total_balance),
    })
    return {
        "scenario": MaturityScenario.REINVEST_NEW,
        "message": f"'{new_product.deposit_product_name}' 신상품으로 재투자 완료",
        "original_contract_id": contract.contract_id,
        "new_contract_id": new_contract.contract_id,
        "new_contract_number": new_contract.contract_number,
        "new_product_name": new_product.deposit_product_name,
        "new_interest_rate": float(new_product.base_interest_rate),
        "reinvest_amount": float(total_balance),
    }


def _do_withdraw_all(db: Session, contract, account, payload: dict, total_balance: Decimal) -> dict:
    """전액 출금."""
    tx = record_transaction(
        db, account, {
            "contract_id": contract.contract_id,
            "transaction_summary": "만기 출금",
            "channel_type": payload.get("channel_type", models.TransactionChannel.SYSTEM),
        },
        tx_type=models.TransactionType.WITHDRAW,
        direction=models.DirectionType.OUT,
        amount=total_balance,
    )
    db.flush()
    return {
        "scenario": MaturityScenario.WITHDRAW_ALL,
        "message": "만기 전액 출금 완료",
        "original_contract_id": contract.contract_id,
        "withdrawn_amount": float(total_balance),
        "transaction_id": tx.transaction_id,
        "transaction_number": tx.transaction_number,
    }


def _do_partial_reinvest(db: Session, contract, account, payload: dict, total_balance: Decimal) -> dict:
    """일부 재투자 + 일부 출금."""
    from app.services import create_contract_with_account

    reinvest_amount = Decimal(str(payload.get("reinvest_amount", float(total_balance) * 0.5)))
    withdraw_amount = total_balance - reinvest_amount

    if reinvest_amount <= 0 or withdraw_amount < 0:
        raise HTTPException(status_code=400, detail="invalid reinvest_amount")

    new_product_id = payload.get("new_banking_product_id", contract.banking_product_id)
    period = payload.get("new_contract_period_month", contract.contract_period_month)

    # 출금 먼저
    if withdraw_amount > 0:
        record_transaction(
            db, account, {
                "contract_id": contract.contract_id,
                "transaction_summary": "만기 부분 출금",
                "channel_type": payload.get("channel_type", models.TransactionChannel.SYSTEM),
            },
            tx_type=models.TransactionType.WITHDRAW,
            direction=models.DirectionType.OUT,
            amount=withdraw_amount,
        )
        db.flush()

    new_contract = create_contract_with_account(db, {
        "customer_id": contract.customer_id,
        "banking_product_id": new_product_id,
        "contract_period_month": period,
        "started_at": today_text(),
        "maturity_at": _add_months(today_text(), period),
        "join_channel": payload.get("join_channel", contract.join_channel),
        "initial_balance": float(reinvest_amount),
    })

    return {
        "scenario": MaturityScenario.PARTIAL_REINVEST,
        "message": "일부 재투자 + 일부 출금 완료",
        "original_contract_id": contract.contract_id,
        "reinvest_amount": float(reinvest_amount),
        "withdraw_amount": float(withdraw_amount),
        "new_contract_id": new_contract.contract_id,
        "new_contract_number": new_contract.contract_number,
    }


# ──────────────────────────────────────────────
# 유틸
# ──────────────────────────────────────────────

def _add_months(date_str: str, months: int) -> str:
    """'YYYYMMDD' 문자열에 months 개월을 더해 반환."""
    dt = datetime.strptime(date_str, "%Y%m%d")
    month = dt.month - 1 + months
    year = dt.year + month // 12
    month = month % 12 + 1
    import calendar
    day = min(dt.day, calendar.monthrange(year, month)[1])
    return datetime(year, month, day).strftime("%Y%m%d")
