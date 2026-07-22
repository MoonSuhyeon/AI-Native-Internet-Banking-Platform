"""
Tool Calling 기반 만기 알림 및 재투자 추천 에이전트

흐름:
  1. 고객의 만기 예정 계약 조회
  2. 만기 수령액(원금+이자-세금) 계산
  3. 현재 판매 상품 조회 및 재투자 시나리오 비교
  4. 고객 재무 상태 파악 후 최적 재투자 전략 추천
  5. 정보 부족 시 추가 질문 반환
"""

import json
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_DOWN

import anthropic
from langfuse.decorators import langfuse_context, observe
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import settings
from app import models
from app import agent_maturity as maturity_rule


# ──────────────────────────────────────────────
# 날짜 변환 헬퍼 (agent_maturity 내부 함수 의존 제거)
# ──────────────────────────────────────────────

def _to_date(value) -> "date | None":
    if value is None:
        return None
    if isinstance(value, date):
        return value
    text = str(value)
    if "-" in text:
        return datetime.strptime(text, "%Y-%m-%d").date()
    return datetime.strptime(text, "%Y%m%d").date()


def _date_to_str(value) -> "str | None":
    if value is None:
        return None
    d = _to_date(value)
    return d.strftime("%Y%m%d") if d else None


# ──────────────────────────────────────────────
# Tool 정의 (JSON Schema)
# ──────────────────────────────────────────────

TOOLS: list[dict] = [
    {
        "name": "ask_clarification",
        "description": (
            "contract_id 또는 고객이 원하는 재투자 방향이 불분명할 때 "
            "추가 질문을 생성합니다. 분석 전 필요한 정보가 없을 때 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "question": {
                    "type": "string",
                    "description": "사용자에게 물어볼 구체적인 질문"
                },
                "missing_fields": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "누락된 필드 목록 (예: ['contract_id'])"
                }
            },
            "required": ["question", "missing_fields"]
        }
    },
    {
        "name": "get_maturing_contracts",
        "description": (
            "고객의 만기 예정 계약 목록을 조회합니다. "
            "contract_id가 명시되지 않았을 때 먼저 호출하여 어떤 계약이 있는지 파악하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {
                    "type": "string",
                    "description": "고객 ID"
                },
                "days_threshold": {
                    "type": "integer",
                    "description": "조회 기간(일). 기본 30일",
                    "default": 30
                }
            },
            "required": ["customer_id"]
        }
    },
    {
        "name": "calculate_maturity_proceeds",
        "description": (
            "만기 수령 예상액(원금 + 이자 - 세금)을 계산합니다. "
            "get_maturing_contracts 또는 contract_id 확보 후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "contract_id": {
                    "type": "integer",
                    "description": "계산할 계약 ID"
                }
            },
            "required": ["contract_id"]
        }
    },
    {
        "name": "get_customer_financial_state",
        "description": (
            "고객의 전체 계좌 잔액과 월 잉여자금(최근 3개월 평균)을 조회합니다. "
            "재투자 규모 결정을 위해 참조하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {
                    "type": "string",
                    "description": "고객 ID"
                }
            },
            "required": ["customer_id"]
        }
    },
    {
        "name": "get_available_products",
        "description": (
            "현재 판매 중인 예금·적금 상품 목록을 금리 내림차순으로 조회합니다. "
            "재투자 후보 상품 파악을 위해 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": []
        }
    },
    {
        "name": "compare_reinvestment_options",
        "description": (
            "만기 수령액을 기준으로 주요 기간(12·24·36개월)별 재투자 예상 수익을 비교합니다. "
            "calculate_maturity_proceeds 및 get_available_products 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "contract_id": {
                    "type": "integer",
                    "description": "대상 계약 ID"
                },
                "candidate_period_months": {
                    "type": "array",
                    "items": {"type": "integer"},
                    "description": "비교할 기간 목록(개월). 기본 [12, 24, 36]",
                    "default": [12, 24, 36]
                }
            },
            "required": ["contract_id"]
        }
    },
    {
        "name": "build_maturity_scenarios",
        "description": (
            "AUTO_RENEWAL / REINVEST_NEW / SPLIT_INVEST / WITHDRAW_HOLD 4가지 시나리오를 "
            "실수치 기반으로 구성합니다. compare_reinvestment_options 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "contract_id": {
                    "type": "integer",
                    "description": "대상 계약 ID"
                }
            },
            "required": ["contract_id"]
        }
    },
]


# ──────────────────────────────────────────────
# 내부 플래너 함수
# ──────────────────────────────────────────────

ANALYSIS_MONTHS = 3


def _get_contract(db: Session, contract_id: int) -> models.Contract | None:
    return db.get(models.Contract, contract_id)


def _get_account_by_contract(db: Session, contract_id: int) -> models.Account | None:
    return db.scalar(
        select(models.Account).where(models.Account.contract_id == contract_id)
    )


def _estimate_interest(principal: Decimal, rate: Decimal, period_months: int) -> Decimal:
    """단리 기준 이자 계산."""
    return (principal * rate / Decimal("100") * Decimal(str(period_months)) / Decimal("12")).quantize(
        Decimal("1"), rounding=ROUND_DOWN
    )


def _calc_proceeds(principal: Decimal, interest: Decimal, tax_rate: Decimal) -> Decimal:
    """세후 수령액 = 원금 + 이자 × (1 - 세율/100)."""
    tax = (interest * tax_rate / Decimal("100")).quantize(Decimal("1"), rounding=ROUND_DOWN)
    return principal + interest - tax


def _planner_get_maturing_contracts(db: Session, customer_id: str, days: int = 30) -> list[dict]:
    """고객의 만기 예정 계약만 필터링하여 반환."""
    all_maturities = maturity_rule.get_upcoming_maturities(db, days=days)
    return [c for c in all_maturities if str(c.get("customer_id", "")) == str(customer_id)]


def _planner_calculate_proceeds(db: Session, contract_id: int) -> dict:
    contract = _get_contract(db, contract_id)
    if not contract:
        return {"error": f"contract {contract_id} not found"}

    account = _get_account_by_contract(db, contract_id)
    principal = Decimal(str(account.balance)) if account else Decimal("0")
    rate = Decimal(str(contract.final_interest_rate))
    tax_rate = Decimal(str(contract.applied_tax_rate)) if contract.applied_tax_rate else Decimal("15.4")
    period = contract.contract_period_month

    interest = _estimate_interest(principal, rate, period)
    proceeds = _calc_proceeds(principal, interest, tax_rate)
    tax = (interest * tax_rate / Decimal("100")).quantize(Decimal("1"), rounding=ROUND_DOWN)

    maturity_date = _to_date(contract.maturity_at)
    days_left = (maturity_date - date.today()).days

    return {
        "contract_id": contract_id,
        "principal": float(principal),
        "estimated_interest": float(interest),
        "tax_amount": float(tax),
        "tax_rate": float(tax_rate),
        "net_proceeds": float(proceeds),
        "maturity_at": _date_to_str(contract.maturity_at),
        "days_until_maturity": days_left,
        "contract_period_month": period,
        "final_interest_rate": float(rate),
    }


def _planner_get_financial_state(db: Session, customer_id: str) -> dict:
    from datetime import date as _date
    from app import agent_goal_planner as _planner

    accounts = _planner.get_customer_accounts(db, customer_id)
    total_balance = _planner.get_total_balance(accounts)
    account_ids = [a.account_id for a in accounts]

    txns = _planner.get_recent_transactions(db, account_ids, ANALYSIS_MONTHS)
    tx_analysis = _planner.analyze_transactions(txns, ANALYSIS_MONTHS)

    return {
        "total_balance": float(total_balance),
        "account_count": len(accounts),
        "monthly_avg_surplus": tx_analysis["monthly_avg_surplus"],
        "monthly_avg_in": tx_analysis["monthly_avg_in"],
        "monthly_avg_out": tx_analysis["monthly_avg_out"],
        "analysis_months": ANALYSIS_MONTHS,
    }


def _planner_get_products(db: Session) -> list[dict]:
    products = db.scalars(
        select(models.BankingProduct).where(
            models.BankingProduct.deposit_product_status == models.DepositProductStatus.SELLING
        ).order_by(models.BankingProduct.base_interest_rate.desc())
    ).all()
    return [
        {
            "banking_product_id": p.banking_product_id,
            "product_name": p.deposit_product_name,
            "product_type": str(p.deposit_product_type),
            "base_interest_rate": float(p.base_interest_rate),
            "min_period_month": p.min_period_month,
            "max_period_month": p.max_period_month,
            "min_join_amount": float(p.min_join_amount) if p.min_join_amount else None,
            "max_join_amount": float(p.max_join_amount) if p.max_join_amount else None,
            "is_auto_renewal_available": p.is_auto_renewal_available,
            "is_tax_benefit_available": p.is_tax_benefit_available,
        }
        for p in products
    ]


def _planner_compare_options(db: Session, contract_id: int, periods: list[int]) -> list[dict]:
    proceeds_info = _planner_calculate_proceeds(db, contract_id)
    if "error" in proceeds_info:
        return []

    principal = Decimal(str(proceeds_info["net_proceeds"]))
    products = _planner_get_products(db)

    options = []
    for period in periods:
        # 해당 기간을 지원하는 최고금리 상품 찾기
        best = None
        for p in products:
            min_p = p["min_period_month"] or 0
            max_p = p["max_period_month"] or 9999
            if min_p <= period <= max_p:
                best = p
                break  # 이미 금리 내림차순 정렬됨

        if not best:
            continue

        rate = Decimal(str(best["base_interest_rate"]))
        interest = _estimate_interest(principal, rate, period)
        net = _calc_proceeds(principal, interest, Decimal("15.4"))

        options.append({
            "period_months": period,
            "product_name": best["product_name"],
            "banking_product_id": best["banking_product_id"],
            "rate": float(rate),
            "reinvest_principal": float(principal),
            "estimated_interest": float(interest),
            "net_amount": float(net),
            "gain_vs_withdraw": float(net - principal),
        })

    options.sort(key=lambda x: x["net_amount"], reverse=True)
    return options


def _planner_build_scenarios(db: Session, contract_id: int, ctx: dict) -> list[dict]:
    proceeds_info = ctx.get("proceeds_info", _planner_calculate_proceeds(db, contract_id))
    products = ctx.get("products", _planner_get_products(db))
    options = ctx.get("reinvest_options", _planner_compare_options(db, contract_id, [12, 24, 36]))

    net = Decimal(str(proceeds_info.get("net_proceeds", 0)))
    current_rate = Decimal(str(proceeds_info.get("final_interest_rate", 0)))
    period = proceeds_info.get("contract_period_month", 12)

    scenarios = []

    # 1. AUTO_RENEWAL — 동일 조건 재가입
    renewal_interest = _estimate_interest(net, current_rate, period)
    renewal_net = _calc_proceeds(net, renewal_interest, Decimal("15.4"))
    scenarios.append({
        "scenario": "AUTO_RENEWAL",
        "label": "동일 조건 자동 갱신",
        "description": f"현재 금리 {float(current_rate):.2f}%로 {period}개월 재가입",
        "reinvest_amount": float(net),
        "rate": float(current_rate),
        "period_months": period,
        "expected_net": float(renewal_net),
        "gain": float(renewal_net - net),
    })

    # 2. REINVEST_NEW — 최고금리 신상품
    if options:
        best = options[0]
        scenarios.append({
            "scenario": "REINVEST_NEW",
            "label": "최고금리 신상품 전액 재투자",
            "description": f"{best['product_name']} (연 {best['rate']:.2f}%, {best['period_months']}개월)",
            "reinvest_amount": float(net),
            "rate": best["rate"],
            "period_months": best["period_months"],
            "expected_net": best["net_amount"],
            "gain": best["gain_vs_withdraw"],
        })

    # 3. SPLIT_INVEST — 절반 재투자 + 절반 보유
    half = (net / Decimal("2")).quantize(Decimal("1"), rounding=ROUND_DOWN)
    if options:
        best = options[0]
        half_interest = _estimate_interest(half, Decimal(str(best["rate"])), best["period_months"])
        half_net = _calc_proceeds(half, half_interest, Decimal("15.4"))
        scenarios.append({
            "scenario": "SPLIT_INVEST",
            "label": "절반 재투자 + 절반 현금 보유",
            "description": f"{float(half):,.0f}원 재투자, {float(net - half):,.0f}원 자유 입출금",
            "reinvest_amount": float(half),
            "rate": best["rate"],
            "period_months": best["period_months"],
            "expected_net": float(half_net + (net - half)),
            "gain": float(half_net - half),
        })

    # 4. WITHDRAW_HOLD — 전액 출금
    scenarios.append({
        "scenario": "WITHDRAW_HOLD",
        "label": "전액 출금 후 보유",
        "description": "만기 수령 후 자유 입출금 계좌 보관",
        "reinvest_amount": 0,
        "rate": 0,
        "period_months": 0,
        "expected_net": float(net),
        "gain": 0,
    })

    return scenarios


# ──────────────────────────────────────────────
# Tool 실행기
# ──────────────────────────────────────────────

def execute_tool(tool_name: str, tool_input: dict, db: Session, ctx: dict) -> dict:
    """tool_name에 해당하는 플래너 함수를 실행하고 결과를 ctx에 저장한다."""
    customer_id = tool_input.get("customer_id") or ctx.get("customer_id", "")

    if tool_name == "ask_clarification":
        ctx["need_more_info"] = True
        ctx["follow_up_question"] = tool_input["question"]
        ctx["missing_fields"] = tool_input.get("missing_fields", [])
        return {"need_more_info": True, "question": tool_input["question"]}

    elif tool_name == "get_maturing_contracts":
        days = int(tool_input.get("days_threshold", 30))
        contracts = _planner_get_maturing_contracts(db, customer_id, days)
        ctx["maturing_contracts"] = contracts
        # 첫 번째 계약을 기본 대상으로 설정
        if contracts and not ctx.get("contract_id"):
            ctx["contract_id"] = contracts[0]["contract_id"]
        return {
            "contract_count": len(contracts),
            "contracts": [
                {
                    "contract_id": c["contract_id"],
                    "contract_number": c["contract_number"],
                    "product_name": c["product_name"],
                    "maturity_at": c["maturity_at"],
                    "days_until_maturity": c["days_until_maturity"],
                    "current_balance": c["current_balance"],
                    "urgency": c["urgency"],
                }
                for c in contracts
            ],
        }

    elif tool_name == "calculate_maturity_proceeds":
        contract_id = int(tool_input["contract_id"])
        ctx["contract_id"] = contract_id
        result = _planner_calculate_proceeds(db, contract_id)
        ctx["proceeds_info"] = result
        return result

    elif tool_name == "get_customer_financial_state":
        result = _planner_get_financial_state(db, customer_id)
        ctx["financial_state"] = result
        return result

    elif tool_name == "get_available_products":
        products = _planner_get_products(db)
        ctx["products"] = products
        return {
            "product_count": len(products),
            "top_rate": products[0]["base_interest_rate"] if products else 0,
            "top_product": products[0]["product_name"] if products else None,
        }

    elif tool_name == "compare_reinvestment_options":
        contract_id = int(tool_input["contract_id"])
        ctx["contract_id"] = contract_id
        periods = tool_input.get("candidate_period_months", [12, 24, 36])
        options = _planner_compare_options(db, contract_id, periods)
        ctx["reinvest_options"] = options
        return {
            "option_count": len(options),
            "best_option": options[0] if options else None,
        }

    elif tool_name == "build_maturity_scenarios":
        contract_id = int(tool_input["contract_id"])
        ctx["contract_id"] = contract_id
        scenarios = _planner_build_scenarios(db, contract_id, ctx)
        ctx["scenarios"] = scenarios
        return {
            "scenario_count": len(scenarios),
            "best_scenario": max(scenarios, key=lambda s: s["gain"])["scenario"] if scenarios else None,
        }

    else:
        return {"error": f"unknown tool: {tool_name}"}


# ──────────────────────────────────────────────
# 시스템 프롬프트
# ──────────────────────────────────────────────

MAX_AGENT_ITERATIONS = 14  # 도구 수(7) × 2

SYSTEM_PROMPT = """당신은 만기 알림 및 재투자 추천 에이전트입니다.

역할:
- 고객의 만기 예정 예금·적금 계약을 분석하고 최적 재투자 전략을 추천합니다.
- 반드시 제공된 도구(tools)를 호출하여 데이터를 수집하고 분석해야 합니다.
- 절대로 숫자를 직접 계산하거나 추측하지 마세요. 모든 계산은 도구가 수행합니다.

필수 정보:
- customer_id: 항상 시스템이 제공합니다.
- contract_id: 사용자 메시지에서 명시되거나, get_maturing_contracts로 조회합니다.

정보 수집 전략:
- contract_id가 메시지에 없으면 먼저 get_maturing_contracts를 호출하세요.
- 계약이 없으면 ask_clarification으로 안내하세요.
- 계약이 여럿이면 가장 긴급한(days_until_maturity 최소) 계약부터 분석하세요.

분석 순서:
1. get_maturing_contracts → 만기 예정 계약 파악
2. calculate_maturity_proceeds → 만기 수령 예상액 계산
3. get_customer_financial_state → 고객 재무 상태 파악
4. get_available_products → 재투자 후보 상품 조회
5. compare_reinvestment_options → 기간별 수익 비교
6. build_maturity_scenarios → 4가지 시나리오 구성

주의사항:
- 도구 결과에 포함된 수치를 그대로 사용하세요. 절대 직접 계산하지 마세요.
- 모든 분석이 완료되면 end_turn으로 종료하세요.
"""


# ──────────────────────────────────────────────
# 메인 에이전트 루프
# ──────────────────────────────────────────────

@observe(name="maturity-agent", capture_input=False)
def _run_maturity_agent_claude(db: Session, customer_id: str, message: str) -> dict:
    """Tool Calling 기반 만기 재투자 에이전트 메인 함수."""
    langfuse_context.update_current_observation(
        input={"customer_id": customer_id, "message": message},
        metadata={"model": "claude-opus-4-8"},
    )
    client = anthropic.Anthropic(api_key=settings.anthropic_api_key)

    ctx: dict = {"customer_id": customer_id}
    agent_steps: list[dict] = []

    messages = [
        {
            "role": "user",
            "content": f"[customer_id: {customer_id}]\n\n{message}",
        }
    ]

    for iteration in range(MAX_AGENT_ITERATIONS):
        response = client.messages.create(
            model=settings.llm_model,
            max_tokens=4096,
            thinking={"type": "adaptive"},
            system=SYSTEM_PROMPT,
            tools=TOOLS,
            messages=messages,
        )

        messages.append({"role": "assistant", "content": response.content})

        tool_use_blocks = [b for b in response.content if b.type == "tool_use"]

        if not tool_use_blocks:
            break

        tool_results = []
        for block in tool_use_blocks:
            step = {"tool": block.name, "input": block.input}
            result = execute_tool(block.name, block.input, db, ctx)
            step["result_summary"] = _summarize_result(block.name, result)
            agent_steps.append(step)

            tool_results.append({
                "type": "tool_result",
                "tool_use_id": block.id,
                "content": json.dumps(result, ensure_ascii=False, default=str),
            })

            if block.name == "ask_clarification":
                break

        if ctx.get("need_more_info"):
            break

        messages.append({"role": "user", "content": tool_results})
    else:
        ctx["warning"] = "최대 에이전트 반복 횟수에 도달하여 실행을 종료했습니다."

    return _build_response(ctx, agent_steps)


def _summarize_result(tool_name: str, result: dict) -> str:
    if tool_name == "ask_clarification":
        return f"추가 질문: {result.get('question', '')[:50]}"
    elif tool_name == "get_maturing_contracts":
        return f"만기 예정 계약 {result.get('contract_count', 0)}건"
    elif tool_name == "calculate_maturity_proceeds":
        return f"수령 예상액 {result.get('net_proceeds', 0):,.0f}원"
    elif tool_name == "get_customer_financial_state":
        return f"총잔액 {result.get('total_balance', 0):,.0f}원, 월잉여 {result.get('monthly_avg_surplus', 0):,.0f}원"
    elif tool_name == "get_available_products":
        return f"판매 상품 {result.get('product_count', 0)}개, 최고금리 {result.get('top_rate', 0):.2f}%"
    elif tool_name == "compare_reinvestment_options":
        best = result.get("best_option") or {}
        return f"비교 {result.get('option_count', 0)}개, 최적 {best.get('period_months', '-')}개월 {best.get('rate', 0):.2f}%"
    elif tool_name == "build_maturity_scenarios":
        return f"시나리오 {result.get('scenario_count', 0)}개, 추천: {result.get('best_scenario', '-')}"
    return str(result)[:80]


def _build_response(ctx: dict, agent_steps: list[dict]) -> dict:
    need_more_info = ctx.get("need_more_info", False)
    return {
        "agent_type": "MATURITY_REINVESTMENT_AGENT",
        "need_more_info": need_more_info,
        "follow_up_question": ctx.get("follow_up_question") if need_more_info else None,
        "agent_steps": agent_steps,
        "maturing_contracts": ctx.get("maturing_contracts", []),
        "proceeds_info": ctx.get("proceeds_info", {}),
        "financial_state": ctx.get("financial_state", {}),
        "reinvest_options": ctx.get("reinvest_options", []),
        "scenarios": ctx.get("scenarios", []),
        "warning": ctx.get("warning"),
    }


# ──────────────────────────────────────────────
# Mock 모드 (API Key 없이 동작)
# ──────────────────────────────────────────────

def run_maturity_agent_mock(db: Session, customer_id: str, message: str) -> dict:
    """
    Claude API 없이 동작하는 Mock Agent.
    플래너 함수를 직접 순차 호출하여 결과를 조립한다.
    """
    ctx: dict = {"customer_id": customer_id}
    agent_steps: list[dict] = []
    warning = None

    try:
        # Step 1: 만기 예정 계약 조회
        contracts = _planner_get_maturing_contracts(db, customer_id, days=30)
        agent_steps.append({"tool": "get_maturing_contracts", "result_summary": f"계약 {len(contracts)}건"})
        ctx["maturing_contracts"] = contracts

        if not contracts:
            # 30일 범위에 없으면 90일로 확장
            contracts = _planner_get_maturing_contracts(db, customer_id, days=90)
            agent_steps[0]["result_summary"] = f"계약 {len(contracts)}건 (90일 범위)"
            ctx["maturing_contracts"] = contracts

        if not contracts:
            return {
                "agent_type": "MATURITY_REINVESTMENT_AGENT_MOCK",
                "need_more_info": False,
                "follow_up_question": None,
                "agent_steps": agent_steps,
                "maturing_contracts": [],
                "proceeds_info": {},
                "financial_state": {},
                "reinvest_options": [],
                "scenarios": [],
                "message": "[만기 알림 Mock] 현재 30일 이내 만기 예정인 계약이 없습니다.",
                "warning": None,
            }

        # 가장 긴급한 계약 선택
        target = min(contracts, key=lambda c: c["days_until_maturity"])
        contract_id = target["contract_id"]
        ctx["contract_id"] = contract_id

        # Step 2: 만기 수령액 계산
        proceeds = _planner_calculate_proceeds(db, contract_id)
        ctx["proceeds_info"] = proceeds
        agent_steps.append({
            "tool": "calculate_maturity_proceeds",
            "result_summary": f"수령 예상액 {proceeds.get('net_proceeds', 0):,.0f}원",
        })

        # Step 3: 고객 재무 상태
        fin_state = _planner_get_financial_state(db, customer_id)
        ctx["financial_state"] = fin_state
        agent_steps.append({
            "tool": "get_customer_financial_state",
            "result_summary": f"총잔액 {fin_state.get('total_balance', 0):,.0f}원",
        })

        # Step 4: 상품 조회
        products = _planner_get_products(db)
        ctx["products"] = products
        agent_steps.append({
            "tool": "get_available_products",
            "result_summary": f"상품 {len(products)}개",
        })

        # Step 5: 재투자 옵션 비교
        options = _planner_compare_options(db, contract_id, [12, 24, 36])
        ctx["reinvest_options"] = options
        agent_steps.append({
            "tool": "compare_reinvestment_options",
            "result_summary": f"비교 {len(options)}개",
        })

        # Step 6: 시나리오 구성
        scenarios = _planner_build_scenarios(db, contract_id, ctx)
        ctx["scenarios"] = scenarios
        agent_steps.append({
            "tool": "build_maturity_scenarios",
            "result_summary": f"시나리오 {len(scenarios)}개",
        })

    except Exception as e:
        warning = f"[Mock] 데이터 조회 실패 ({e}), 기본 응답으로 대체합니다."
        contracts = ctx.get("maturing_contracts", [])
        target = contracts[0] if contracts else {}

    # 메시지 조립
    target = ctx.get("maturing_contracts", [{}])[0] if ctx.get("maturing_contracts") else {}
    proceeds = ctx.get("proceeds_info", {})
    scenarios = ctx.get("scenarios", [])

    days_left = target.get("days_until_maturity", 0)
    product_name = target.get("product_name", "예금")
    net = proceeds.get("net_proceeds", 0)

    best_scenario = max(scenarios, key=lambda s: s["gain"]) if scenarios else None

    lines = [f"[만기 알림 Mock] '{product_name}' 만기가 {days_left}일 후입니다!"]
    lines.append(f"만기 수령 예상액: {net:,.0f}원")

    if best_scenario and best_scenario["scenario"] != "WITHDRAW_HOLD":
        lines.append("")
        lines.append(f"추천 시나리오: {best_scenario['label']}")
        lines.append(f"예상 이자 수익: {best_scenario['gain']:,.0f}원")

    if scenarios:
        lines.append("")
        lines.append("선택 가능한 시나리오:")
        for s in scenarios:
            lines.append(f"  • {s['label']}: 예상 수령 {s['expected_net']:,.0f}원 (이익 {s['gain']:,.0f}원)")

    message_text = "\n".join(lines)

    return {
        "agent_type": "MATURITY_REINVESTMENT_AGENT_MOCK",
        "need_more_info": False,
        "follow_up_question": None,
        "agent_steps": agent_steps,
        "maturing_contracts": ctx.get("maturing_contracts", []),
        "proceeds_info": ctx.get("proceeds_info", {}),
        "financial_state": ctx.get("financial_state", {}),
        "reinvest_options": ctx.get("reinvest_options", []),
        "scenarios": ctx.get("scenarios", []),
        "message": message_text,
        "warning": warning,
    }


def run_maturity_agent(db: Session, customer_id: str, message: str) -> dict:
    """
    API Key가 있으면 Claude Tool Calling Agent, 없으면 Mock으로 자동 전환.
    MATURITY_AGENT_ENABLED가 false이면 호출하지 않아야 하며,
    이 함수는 호출된 경우에만 실행된다.
    """
    if not settings.anthropic_api_key:
        return run_maturity_agent_mock(db, customer_id, message)
    return _run_maturity_agent_claude(db, customer_id, message)
