"""
Tool Calling 기반 Goal-Based Financial Agent

흐름:
  1. 사용자 메시지 파싱 (customer_id, goal_amount, goal_months 추출)
  2. Claude가 필요한 도구를 선택하여 순서대로 호출
  3. 각 도구 결과를 context 누산기에 저장
  4. 정보 부족 시 follow-up 질문 반환
  5. 전체 결과 구조체 반환
"""

import json
import re
from decimal import Decimal, ROUND_DOWN

from langfuse.decorators import langfuse_context, observe

import anthropic
from sqlalchemy.orm import Session

from app.config import settings
from app import agent_goal_planner as planner


# ──────────────────────────────────────────────
# Tool 정의 (JSON Schema)
# ──────────────────────────────────────────────

TOOLS: list[dict] = [
    {
        "name": "ask_follow_up",
        "description": (
            "사용자로부터 목표 금액(goal_amount) 또는 목표 기간(goal_months)이 누락되거나 "
            "불분명할 때 추가 질문을 생성합니다. 분석을 시작하기 전에 이 도구를 호출하세요."
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
                    "description": "누락된 필드 목록 (예: ['goal_amount', 'goal_months'])"
                }
            },
            "required": ["question", "missing_fields"]
        }
    },
    {
        "name": "get_customer_accounts",
        "description": "고객의 활성 계좌 목록과 총 잔액을 조회합니다.",
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {
                    "type": "string",
                    "description": "조회할 고객 ID"
                }
            },
            "required": ["customer_id"]
        }
    },
    {
        "name": "get_recent_transactions",
        "description": "고객의 최근 3개월 거래 내역을 조회합니다. get_customer_accounts 이후에 호출하세요.",
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
        "description": "현재 판매 중인 예금·적금 상품 목록을 금리 내림차순으로 조회합니다.",
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": []
        }
    },
    {
        "name": "analyze_cash_flow",
        "description": (
            "거래 내역을 분석하여 월 평균 수입·지출·잉여자금을 계산합니다. "
            "get_recent_transactions 이후에 호출하세요."
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
        "name": "evaluate_goal_feasibility",
        "description": (
            "목표 금액·기간과 현금흐름을 기반으로 달성 가능성을 판단합니다. "
            "(ACHIEVABLE / TIGHT / DIFFICULT / IMPOSSIBLE) "
            "analyze_cash_flow 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"},
                "goal_amount": {"type": "number", "description": "목표 금액 (원)"},
                "goal_months": {"type": "integer", "description": "목표 기간 (개월)"}
            },
            "required": ["customer_id", "goal_amount", "goal_months"]
        }
    },
    {
        "name": "generate_failure_reasons",
        "description": (
            "TIGHT / DIFFICULT / IMPOSSIBLE 판정 시 구체적인 실패 원인을 분석합니다. "
            "evaluate_goal_feasibility 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"}
            },
            "required": ["customer_id"]
        }
    },
    {
        "name": "generate_alternatives",
        "description": (
            "TIGHT / DIFFICULT / IMPOSSIBLE 판정 시 3가지 대안 시나리오를 실계산 기반으로 생성합니다. "
            "(기간 연장 / 월 저축액 증가 / 상품 조합 변경) "
            "evaluate_goal_feasibility 및 get_available_products 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"}
            },
            "required": ["customer_id"]
        }
    },
    {
        "name": "generate_strategies",
        "description": (
            "안정형·수익형·균형형 3가지 투자 전략을 생성하고 각 전략별 예상 최종금액·이자수익·달성률을 계산합니다. "
            "get_available_products 및 evaluate_goal_feasibility 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"},
                "goal_amount": {"type": "number"},
                "goal_months": {"type": "integer"}
            },
            "required": ["customer_id", "goal_amount", "goal_months"]
        }
    },
    {
        "name": "build_monthly_plan",
        "description": (
            "최우선 추천 상품 조합 기준으로 월별 납입 계획표를 생성합니다. "
            "generate_strategies 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"},
                "goal_amount": {"type": "number"},
                "goal_months": {"type": "integer"}
            },
            "required": ["customer_id", "goal_amount", "goal_months"]
        }
    },
    {
        "name": "build_recommendation_reason",
        "description": (
            "현금흐름 상태·목표 달성 가능성·추천 상품·전략 선택 이유를 4개 항목으로 구조화합니다. "
            "generate_strategies 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"}
            },
            "required": ["customer_id"]
        }
    },
]


# ──────────────────────────────────────────────
# Tool 실행기
# ──────────────────────────────────────────────

def execute_tool(tool_name: str, tool_input: dict, db: Session, ctx: dict) -> dict:
    """
    tool_name에 해당하는 Python 함수를 실행하고 결과를 ctx에 저장한다.
    Claude는 절대 수치를 계산하지 않으며, 모든 계산은 이 함수에서 수행된다.
    """
    customer_id = tool_input.get("customer_id") or ctx.get("customer_id", "")

    if tool_name == "ask_follow_up":
        ctx["need_more_info"] = True
        ctx["follow_up_question"] = tool_input["question"]
        ctx["missing_fields"] = tool_input.get("missing_fields", [])
        return {"need_more_info": True, "question": tool_input["question"]}

    elif tool_name == "get_customer_accounts":
        accounts = planner.get_customer_accounts(db, customer_id)
        balance = planner.get_total_balance(accounts)
        result = {
            "account_count": len(accounts),
            "total_balance": float(balance),
            "accounts": [
                {
                    "account_id": a.account_id,
                    "account_number": a.account_number,
                    "account_type": str(a.account_type),
                    "balance": float(a.balance),
                }
                for a in accounts
            ],
        }
        ctx["accounts"] = accounts
        ctx["account_ids"] = [a.account_id for a in accounts]
        ctx["current_balance"] = balance
        ctx["accounts_result"] = result
        return result

    elif tool_name == "get_recent_transactions":
        account_ids = ctx.get("account_ids", [])
        txns = planner.get_recent_transactions(db, account_ids, planner.ANALYSIS_MONTHS)
        ctx["transactions"] = txns
        return {"transaction_count": len(txns), "analysis_months": planner.ANALYSIS_MONTHS}

    elif tool_name == "get_available_products":
        products = planner._get_available_products(db)
        ctx["products"] = products
        return {
            "deposit_count": len(products["deposits"]),
            "savings_count": len(products["savings"]),
            "top_deposit_rate": float(products["deposits"][0].base_interest_rate) if products["deposits"] else 0,
            "top_savings_rate": float(products["savings"][0].base_interest_rate) if products["savings"] else 0,
        }

    elif tool_name == "analyze_cash_flow":
        txns = ctx.get("transactions", [])
        tx_analysis = planner.analyze_transactions(txns, planner.ANALYSIS_MONTHS)
        ctx["tx_analysis"] = tx_analysis
        ctx["monthly_surplus"] = Decimal(str(tx_analysis["monthly_avg_surplus"]))
        return tx_analysis

    elif tool_name == "evaluate_goal_feasibility":
        goal_amount = Decimal(str(tool_input["goal_amount"]))
        goal_months = int(tool_input["goal_months"])
        ctx["goal_amount"] = goal_amount
        ctx["goal_months"] = goal_months
        current_balance = ctx.get("current_balance", Decimal("0"))
        monthly_surplus = ctx.get("monthly_surplus", Decimal("0"))
        feasibility = planner._evaluate_feasibility(goal_amount, goal_months, current_balance, monthly_surplus)
        ctx["feasibility"] = feasibility
        return feasibility

    elif tool_name == "generate_failure_reasons":
        feasibility = ctx.get("feasibility", {})
        tx_analysis = ctx.get("tx_analysis", {})
        reasons = planner._analyze_failure_reasons(feasibility, tx_analysis)
        ctx["failure_reasons"] = reasons
        return {"failure_reasons": reasons, "count": len(reasons)}

    elif tool_name == "generate_alternatives":
        feasibility = ctx.get("feasibility", {})
        products = ctx.get("products", {"deposits": [], "savings": []})
        alternatives = planner._generate_alternatives(feasibility, products)
        ctx["alternatives"] = alternatives
        return {"alternatives_count": len(alternatives), "types": [a["type"] for a in alternatives]}

    elif tool_name == "generate_strategies":
        products = ctx.get("products", {"deposits": [], "savings": []})
        goal_amount = ctx.get("goal_amount", Decimal(str(tool_input.get("goal_amount", 0))))
        goal_months = ctx.get("goal_months", int(tool_input.get("goal_months", 1)))
        monthly_surplus = ctx.get("monthly_surplus", Decimal("0"))
        current_balance = ctx.get("current_balance", Decimal("0"))

        strategies = planner._build_strategies(products, goal_amount, goal_months, monthly_surplus, current_balance)
        combinations = planner._recommend_combinations(products, goal_amount, goal_months, monthly_surplus, current_balance)
        ctx["strategies"] = strategies
        ctx["combinations"] = combinations
        return {
            "strategy_count": len(strategies),
            "combination_count": len(combinations),
            "best_achievement_rate": max((s["achievement_rate"] for s in strategies), default=0),
        }

    elif tool_name == "build_monthly_plan":
        goal_amount = ctx.get("goal_amount", Decimal(str(tool_input.get("goal_amount", 0))))
        goal_months = ctx.get("goal_months", int(tool_input.get("goal_months", 1)))
        combinations = ctx.get("combinations", [])
        monthly_plan = []
        if combinations:
            monthly_plan = planner._build_monthly_plan(combinations[0], goal_amount, goal_months)
        ctx["monthly_plan"] = monthly_plan
        return {"monthly_plan_months": len(monthly_plan)}

    elif tool_name == "build_recommendation_reason":
        feasibility = ctx.get("feasibility", {})
        tx_analysis = ctx.get("tx_analysis", {})
        combinations = ctx.get("combinations", [])
        strategies = ctx.get("strategies", [])
        reason = planner._build_recommendation_reason(feasibility, tx_analysis, combinations, strategies)
        ctx["recommendation_reason"] = reason
        return reason

    else:
        return {"error": f"unknown tool: {tool_name}"}


# ──────────────────────────────────────────────
# 시스템 프롬프트
# ──────────────────────────────────────────────

MAX_AGENT_ITERATIONS = 20


SYSTEM_PROMPT = """당신은 금융 목표 달성 플래너 에이전트입니다.

역할:
- 사용자의 금융 목표를 분석하고 달성 계획을 수립합니다.
- 반드시 제공된 도구(tools)를 호출하여 데이터를 수집하고 분석해야 합니다.
- 절대로 숫자를 직접 계산하거나 추측하지 마세요. 모든 계산은 도구가 수행합니다.

필수 정보:
- customer_id: 항상 시스템이 제공합니다.
- goal_amount: 목표 금액 (원 단위, 양수)
- goal_months: 목표 기간 (개월 단위, 1~120)

정보가 부족할 때:
- goal_amount 또는 goal_months가 없으면 반드시 ask_follow_up 도구를 먼저 호출하세요.
- 두 값이 모두 있으면 분석을 시작하세요.

분석 순서 (모든 정보가 있을 때):
1. get_customer_accounts → 계좌 조회
2. get_recent_transactions → 거래 내역 조회
3. get_available_products → 상품 조회
4. analyze_cash_flow → 현금흐름 분석
5. evaluate_goal_feasibility → 달성 가능성 판단
6. feasibility가 ACHIEVABLE이 아니면: generate_failure_reasons, generate_alternatives
7. generate_strategies → 전략 생성
8. build_monthly_plan → 월별 계획
9. build_recommendation_reason → 추천 사유

주의사항:
- 도구 결과에 포함된 수치를 그대로 사용하세요. 절대 직접 계산하지 마세요.
- 모든 분석이 완료되면 end_turn으로 종료하세요.
"""


# ──────────────────────────────────────────────
# 메인 에이전트 루프
# ──────────────────────────────────────────────

@observe(name="goal-agent", capture_input=False)
def _run_goal_agent_claude(db: Session, customer_id: str, message: str) -> dict:
    """
    Tool Calling 기반 Goal-Based Financial Agent 메인 함수.
    Claude가 사용자 메시지를 보고 필요한 도구를 선택·실행한다.
    """
    langfuse_context.update_current_observation(
        input={"customer_id": customer_id, "message": message},
        metadata={"model": "claude-opus-4-8"},
    )
    client = anthropic.Anthropic(api_key=settings.anthropic_api_key)

    # 서버 사이드 context 누산기 (Claude는 이 dict를 직접 보지 못함)
    ctx: dict = {"customer_id": customer_id}

    agent_steps: list[dict] = []

    messages = [
        {
            "role": "user",
            "content": (
                f"[customer_id: {customer_id}]\n\n"
                f"{message}"
            ),
        }
    ]

    # ── 에이전트 루프 ──────────────────────────────────
    for iteration in range(MAX_AGENT_ITERATIONS):
        response = client.messages.create(
            model="claude-opus-4-8",
            max_tokens=4096,
            thinking={"type": "adaptive"},
            system=SYSTEM_PROMPT,
            tools=TOOLS,
            messages=messages,
        )

        # assistant 메시지 누적
        messages.append({"role": "assistant", "content": response.content})

        # tool_use 블록 추출
        tool_use_blocks = [b for b in response.content if b.type == "tool_use"]

        if not tool_use_blocks:
            # end_turn: 루프 종료
            break

        # 도구 실행 및 결과 수집
        tool_results = []
        for block in tool_use_blocks:
            tool_name = block.name
            tool_input = block.input

            step = {"tool": tool_name, "input": tool_input}
            result = execute_tool(tool_name, tool_input, db, ctx)
            step["result_summary"] = _summarize_result(tool_name, result)
            agent_steps.append(step)

            tool_results.append({
                "type": "tool_result",
                "tool_use_id": block.id,
                "content": json.dumps(result, ensure_ascii=False, default=str),
            })

            # ask_follow_up 호출 시 즉시 루프 종료
            if tool_name == "ask_follow_up":
                break

        if ctx.get("need_more_info"):
            break

        messages.append({"role": "user", "content": tool_results})
    else:
        ctx["warning"] = "최대 에이전트 반복 횟수에 도달하여 실행을 종료했습니다."

    # ── 응답 구조 조립 ─────────────────────────────────
    return _build_response(ctx, agent_steps)


def _summarize_result(tool_name: str, result: dict) -> str:
    """에이전트 로그용 한 줄 요약."""
    if tool_name == "ask_follow_up":
        return f"추가 질문 생성: {result.get('question', '')[:50]}"
    elif tool_name == "get_customer_accounts":
        return f"계좌 {result.get('account_count', 0)}개, 총 잔액 {result.get('total_balance', 0):,.0f}원"
    elif tool_name == "get_recent_transactions":
        return f"거래 {result.get('transaction_count', 0)}건 조회"
    elif tool_name == "get_available_products":
        return f"예금 {result.get('deposit_count', 0)}개, 적금 {result.get('savings_count', 0)}개"
    elif tool_name == "analyze_cash_flow":
        return f"월 잉여자금 {result.get('monthly_avg_surplus', 0):,.0f}원"
    elif tool_name == "evaluate_goal_feasibility":
        return f"달성 가능성: {result.get('feasibility', '-')}"
    elif tool_name == "generate_failure_reasons":
        return f"실패 원인 {result.get('count', 0)}건"
    elif tool_name == "generate_alternatives":
        return f"대안 시나리오 {result.get('alternatives_count', 0)}개"
    elif tool_name == "generate_strategies":
        return f"전략 {result.get('strategy_count', 0)}개, 최고 달성률 {result.get('best_achievement_rate', 0):.1f}%"
    elif tool_name == "build_monthly_plan":
        return f"월별 계획 {result.get('monthly_plan_months', 0)}개월"
    elif tool_name == "build_recommendation_reason":
        return "추천 사유 구조화 완료"
    return str(result)[:80]


def _build_response(ctx: dict, agent_steps: list[dict]) -> dict:
    """context 누산기에서 최종 응답 구조를 조립한다."""
    need_more_info = ctx.get("need_more_info", False)
    feasibility = ctx.get("feasibility", {})
    accounts_result = ctx.get("accounts_result", {})

    return {
        "agent_type": "GOAL_BASED_FINANCIAL_AGENT",
        "need_more_info": need_more_info,
        "follow_up_question": ctx.get("follow_up_question") if need_more_info else None,
        "agent_steps": agent_steps,
        "feasibility": feasibility,
        "failure_reasons": ctx.get("failure_reasons", []),
        "alternatives": ctx.get("alternatives", []),
        "strategies": ctx.get("strategies", []),
        "recommendation_reason": ctx.get("recommendation_reason", {}),
        "monthly_plan": ctx.get("monthly_plan", []),
        "accounts": accounts_result.get("accounts", []),
        "transaction_analysis": ctx.get("tx_analysis", {}),
        "recommended_combinations": ctx.get("combinations", []),
        "warning": ctx.get("warning"),
    }


# ──────────────────────────────────────────────
# Mock 모드 (API Key 없이 동작)
# ──────────────────────────────────────────────

_KOR_YEAR = {"일": 1, "이": 2, "삼": 3, "사": 4, "오": 5, "육": 6, "칠": 7, "팔": 8, "구": 9, "십": 10}
_KOR_CHEONMAN = {
    "구천만": 9000, "팔천만": 8000, "칠천만": 7000, "육천만": 6000,
    "오천만": 5000, "사천만": 4000, "삼천만": 3000, "이천만": 2000,
    "일천만": 1000, "천만": 1000,
}


def _mock_parse_amount(text: str) -> float | None:
    t = text.replace(" ", "").replace(",", "")
    for k, v in _KOR_CHEONMAN.items():
        t = t.replace(k, f"{v}만")
    m = re.search(r"(\d+(?:\.\d+)?)\s*억", t)
    if m:
        return float(m.group(1)) * 1_0000_0000
    m = re.search(r"(\d+(?:\.\d+)?)\s*만", t)
    if m:
        return float(m.group(1)) * 10_000
    m = re.search(r"(\d+(?:\.\d+)?)\s*천", t)
    if m:
        return float(m.group(1)) * 1_000
    m = re.search(r"(\d+)", t)
    if m:
        n = float(m.group(1))
        return n if n >= 10_000 else n * 10_000
    return None


def _mock_parse_months(text: str) -> int | None:
    m = re.search(r"(\d+)\s*년", text)
    if m:
        return int(m.group(1)) * 12
    m = re.search(r"([일이삼사오육칠팔구십])\s*년", text)
    if m:
        return (_KOR_YEAR.get(m.group(1)) or 0) * 12 or None
    m = re.search(r"(\d+)\s*개월", text)
    if m:
        return int(m.group(1))
    m = re.search(r"([일이삼사오육칠팔구십])\s*개월", text)
    if m:
        return _KOR_YEAR.get(m.group(1))
    return None


def _period_str(months: int) -> str:
    if months % 12 == 0:
        return f"{months // 12}년"
    return f"{months}개월"


def run_goal_agent_mock(db: Session, customer_id: str, message: str) -> dict:
    """
    Claude API 없이 동작하는 Mock Agent.
    메시지에서 goal_amount/goal_months를 파싱하여 플래너 도구를 직접 실행한다.
    API Key가 없을 때 자동으로 사용된다.
    """
    goal_amount = _mock_parse_amount(message)
    goal_months = _mock_parse_months(message)

    missing = []
    if goal_amount is None:
        missing.append("goal_amount")
    if goal_months is None:
        missing.append("goal_months")

    if missing:
        questions = {
            "goal_amount": "목표 금액이 얼마인지 알려주세요. (예: 500만원, 천만원)",
            "goal_months": "기간은 얼마나 생각하고 계세요? (예: 1년, 6개월)",
        }
        ask = " 그리고 ".join(questions[k] for k in missing)
        return {
            "agent_type": "GOAL_BASED_FINANCIAL_AGENT_MOCK",
            "need_more_info": True,
            "follow_up_question": f"목표 달성을 도와드릴게요! {ask}",
            "message": f"목표 달성을 도와드릴게요! {ask}",
            "agent_steps": [],
        }

    # planner.analyze_goal() 로 전체 파이프라인 실행 (DB 조회 포함)
    agent_steps: list[dict] = [{"tool": "analyze_goal", "result_summary": "플래너 실행"}]
    warning = None
    plan_data: dict = {}

    try:
        plan_data = planner.analyze_goal(db, customer_id, float(goal_amount), goal_months)
        agent_steps[0]["result_summary"] = f"플래너 완료 (달성가능성: {plan_data.get('feasibility', {}).get('feasibility_status', '-')})"
    except Exception as e:
        warning = f"[Mock] 데이터 조회 실패 ({e}), 기본 응답으로 대체합니다."

    # 응답 메시지 조립
    period = _period_str(goal_months)
    amount_str = f"{goal_amount:,.0f}원"
    feasibility_status = plan_data.get("feasibility", {}).get("feasibility_status", "")
    combinations = plan_data.get("recommended_combinations", [])

    if feasibility_status == "ACHIEVABLE" and combinations:
        best = combinations[0]
        product_name = best.get("product_name", "추천 상품")
        rate = best.get("interest_rate", 0)
        monthly = best.get("monthly_deposit", 0)
        msg_lines = [
            f"[Goal Agent Mock] {period} 동안 {amount_str}을 모으는 목표군요!",
            "",
            f"달성 가능합니다! 추천 상품: {product_name} (연 {rate:.1f}%)",
            f"매달 {monthly:,.0f}원 납입하시면 목표 달성이 가능합니다.",
        ]
        strategies = plan_data.get("strategies", [])
        if strategies:
            best_s = strategies[0]
            msg_lines += ["", f"전략: {best_s.get('strategy_name', '')} - {best_s.get('description', '')}"]
    elif feasibility_status in ("TIGHT", "DIFFICULT", "IMPOSSIBLE"):
        msg_lines = [
            f"[Goal Agent Mock] {period} 동안 {amount_str}을 모으는 목표군요!",
            "",
            "현재 현금흐름으로는 목표 달성이 쉽지 않을 수 있습니다.",
            "납입 가능한 금액을 알려주시면 더 자세히 분석해 드릴게요.",
            "매달 얼마 정도 납입하실 수 있으세요?",
        ]
    else:
        msg_lines = [
            f"[Goal Agent Mock] {period} 동안 {amount_str}을 모으는 목표군요!",
            "",
            "납입 가능한 금액을 알려주시면 달성 가능한 상품을 찾아드릴게요.",
            "(목돈을 한 번에 넣으실 계획이라면 '목돈 OOO만원' 처럼 알려주세요.)",
        ]
        pass  # warning은 response.warning 필드로만 전달

    message_text = "\n".join(msg_lines)

    return {
        "agent_type": "GOAL_BASED_FINANCIAL_AGENT_MOCK",
        "need_more_info": False,
        "follow_up_question": None,
        "agent_steps": agent_steps,
        "feasibility": plan_data.get("feasibility", {}),
        "failure_reasons": plan_data.get("failure_reasons", []),
        "alternatives": plan_data.get("alternatives", []),
        "strategies": plan_data.get("strategies", []),
        "recommendation_reason": plan_data.get("recommendation_reason", {}),
        "monthly_plan": plan_data.get("monthly_plan", []),
        "recommended_combinations": combinations,
        "message": message_text,
        "warning": warning,
    }


def run_goal_agent(db: Session, customer_id: str, message: str) -> dict:
    """
    API Key가 있으면 Claude Tool Calling Agent, 없으면 Mock으로 자동 전환.
    """
    if not settings.anthropic_api_key:
        return run_goal_agent_mock(db, customer_id, message)
    return _run_goal_agent_claude(db, customer_id, message)
