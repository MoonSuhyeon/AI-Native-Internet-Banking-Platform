"""
Tool Calling 기반 지출 패턴 관리 에이전트

흐름:
  1. 거래내역 조회
  2. 카테고리별 지출 집계
  3. 이상 지출 탐지 (전월 대비 급증)
  4. 절약 가능 항목 탐색
  5. 개선 방안 생성
  6. 다음 달 소비 목표 설정
"""

import json
from collections import defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_DOWN

import anthropic
from langfuse.decorators import langfuse_context, observe
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.config import settings
from app import models


# ──────────────────────────────────────────────
# 카테고리 정의 (기존 spending_pattern_agent.py와 동일)
# ──────────────────────────────────────────────

_CATEGORIES: list[tuple[str, list[str]]] = [
    ("식비",    ["식당", "음식", "카페", "편의점", "배달", "마트", "식비", "쿠팡이츠", "배민", "요기요", "점심", "저녁", "아침", "베이커리", "분식", "치킨", "피자", "햄버거"]),
    ("교통",    ["교통", "버스", "지하철", "택시", "카카오택시", "주유", "기름", "ktx", "고속버스", "톨", "주차", "철도"]),
    ("쇼핑",    ["쇼핑", "의류", "패션", "신발", "백화점", "아울렛", "온라인쇼핑", "쿠팡", "11번가", "지마켓", "옥션", "위메프", "티몬", "네이버쇼핑"]),
    ("의료",    ["병원", "의원", "약국", "한의원", "치과", "안과", "의료", "건강검진", "약값"]),
    ("공과금",  ["전기", "수도", "가스", "통신", "인터넷", "핸드폰", "휴대폰", "요금", "관리비"]),
    ("문화/여가", ["영화", "ott", "넷플릭스", "유튜브", "게임", "여행", "숙박", "호텔", "헬스", "운동", "피트니스", "스포츠", "독서", "책"]),
    ("금융",    ["보험", "대출", "이자", "연금", "증권", "투자", "펀드"]),
]

_FIXED_COST_CATEGORIES = {"공과금", "금융"}
_VARIABLE_COST_CATEGORIES = {"식비", "쇼핑", "문화/여가", "교통"}

_ALERT_RATIO = 1.5
_MIN_AMOUNT_FOR_ALERT = 10_000


def _classify_category(text_value: str) -> str:
    t = text_value.lower()
    for category_name, keywords in _CATEGORIES:
        if any(kw in t for kw in keywords):
            return category_name
    return "기타"


def _parse_date(value) -> date | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    s = str(value).strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d"):
        try:
            return datetime.strptime(s[:len(fmt)], fmt).date()
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(s).date()
    except (ValueError, TypeError):
        return None


# ──────────────────────────────────────────────
# Tool 정의 (JSON Schema)
# ──────────────────────────────────────────────

TOOLS: list[dict] = [
    {
        "name": "ask_clarification",
        "description": (
            "분석 기간이나 집중 분석할 카테고리가 불명확할 때 추가 질문을 생성합니다. "
            "분석 시작 전 필요한 정보가 없을 때 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "question": {"type": "string", "description": "사용자에게 물어볼 구체적인 질문"},
                "missing_fields": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "누락된 필드 목록 (예: ['analysis_months'])"
                }
            },
            "required": ["question", "missing_fields"]
        }
    },
    {
        "name": "get_transaction_history",
        "description": (
            "고객의 출금 거래내역을 조회합니다. 본인 계좌 간 이체는 제외됩니다. "
            "모든 분석의 첫 번째 단계로 반드시 먼저 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string", "description": "고객 ID"},
                "analysis_months": {
                    "type": "integer",
                    "description": "분석 기간(개월). 기본 3, 최대 6",
                    "default": 3
                }
            },
            "required": ["customer_id"]
        }
    },
    {
        "name": "analyze_spending_by_category",
        "description": (
            "거래내역을 카테고리별·월별로 집계합니다. "
            "get_transaction_history 이후에 호출하세요."
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
        "name": "detect_anomalies",
        "description": (
            "이번 달 카테고리별 지출이 직전 N개월 평균 대비 1.5배 이상 급증한 항목을 탐지합니다. "
            "analyze_spending_by_category 이후에 호출하세요."
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
        "name": "find_saving_opportunities",
        "description": (
            "변동비(식비·쇼핑·문화여가·교통) 상위 지출 항목에서 절약 가능 금액을 추정합니다. "
            "analyze_spending_by_category 이후에 호출하세요."
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
        "name": "generate_improvement_plan",
        "description": (
            "이상 지출 카테고리와 절약 가능 항목을 기반으로 구체적인 개선 행동 방안을 생성합니다. "
            "detect_anomalies 및 find_saving_opportunities 이후에 호출하세요."
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
        "name": "set_monthly_spending_goal",
        "description": (
            "현재 카테고리별 지출 평균에 절감 목표율을 적용해 다음 달 소비 목표를 설정합니다. "
            "generate_improvement_plan 이후에 호출하세요."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"},
                "reduction_rate": {
                    "type": "number",
                    "description": "절감 목표율 (0.0~1.0). 기본 0.1 (10%)",
                    "default": 0.1
                }
            },
            "required": ["customer_id"]
        }
    },
]


# ──────────────────────────────────────────────
# 내부 플래너 함수
# ──────────────────────────────────────────────

def _planner_fetch_transactions(db: Session, customer_id: str, analysis_months: int = 3) -> list[dict]:
    """출금 트랜잭션 조회 (본인 계좌 간 이체 제외)."""
    account_rows = db.execute(
        text("SELECT account_id FROM deposit_accounts WHERE customer_id = :cno"),
        {"cno": customer_id},
    ).mappings().all()

    if not account_rows:
        return []

    all_ids = list({int(r["account_id"]) for r in account_rows})

    today = date.today()
    this_month_start = today.replace(day=1)
    cutoff = (this_month_start - timedelta(days=1)).replace(day=1)
    for _ in range(analysis_months - 1):
        cutoff = (cutoff - timedelta(days=1)).replace(day=1)

    from sqlalchemy import bindparam
    rows = db.execute(
        text("""
            SELECT t.transaction_type,
                   t.direction_type,
                   t.amount,
                   t.transaction_summary,
                   t.transaction_memo,
                   t.transaction_at,
                   t.created_at,
                   t.counterparty_account_id,
                   t.status
              FROM deposit_transactions t
             WHERE t.account_id IN :account_ids
               AND COALESCE(t.transaction_at, t.created_at) >= :cutoff
               AND t.direction_type = 'OUT'
             ORDER BY COALESCE(t.transaction_at, t.created_at)
        """).bindparams(bindparam("account_ids", expanding=True)),
        {"account_ids": all_ids, "cutoff": cutoff.strftime("%Y-%m-%d")},
    ).mappings().all()

    filtered = []
    for r in rows:
        status = str(r.get("status") or "").upper()
        if status and status not in ("SUCCESS", "COMPLETED", "NORMAL", ""):
            continue
        cp_id = r.get("counterparty_account_id")
        if cp_id is not None and int(cp_id) in all_ids:
            continue
        filtered.append(dict(r))
    return filtered


def _planner_aggregate_by_category(transactions: list[dict]) -> dict:
    """월별×카테고리별 집계. ctx["monthly_category"]에 저장할 구조 반환."""
    monthly_category: dict[tuple[str, str], float] = defaultdict(float)

    for row in transactions:
        tx_date = _parse_date(row.get("transaction_at") or row.get("created_at"))
        if tx_date is None:
            continue
        amount = float(row.get("amount") or 0)
        summary = (str(row.get("transaction_summary") or "") + " " + str(row.get("transaction_memo") or "")).lower()
        category = _classify_category(summary)
        ym = tx_date.strftime("%Y-%m")
        monthly_category[(ym, category)] += amount

    today = date.today()
    this_ym = today.strftime("%Y-%m")
    all_months = sorted({ym for ym, _ in monthly_category.keys()})
    prev_months = [m for m in all_months if m != this_ym]

    this_month_summary = {
        cat: monthly_category.get((this_ym, cat), 0.0)
        for cat in set(c for _, c in monthly_category.keys())
        if monthly_category.get((this_ym, cat), 0.0) > 0
    }
    this_month_total = sum(this_month_summary.values())

    prev_monthly_totals = {}
    for ym in prev_months:
        total = sum(v for (m, _), v in monthly_category.items() if m == ym)
        prev_monthly_totals[ym] = total
    prev_avg_total = (
        sum(prev_monthly_totals.values()) / len(prev_monthly_totals)
        if prev_monthly_totals else 0.0
    )

    return {
        "monthly_category": {f"{ym}|{cat}": amt for (ym, cat), amt in monthly_category.items()},
        "this_ym": this_ym,
        "prev_months": prev_months,
        "this_month_summary": this_month_summary,
        "this_month_total": this_month_total,
        "prev_avg_total": prev_avg_total,
    }


def _planner_detect_anomalies(aggregation: dict) -> list[dict]:
    """이상 지출 탐지."""
    raw = aggregation.get("monthly_category", {})
    monthly_category: dict[tuple[str, str], float] = {}
    for key, val in raw.items():
        ym, cat = key.split("|", 1)
        monthly_category[(ym, cat)] = val

    this_ym = aggregation["this_ym"]
    prev_months = aggregation["prev_months"]

    if not prev_months:
        return []

    alerts = []
    all_categories = set(cat for _, cat in monthly_category.keys())

    for category in all_categories:
        this_amount = monthly_category.get((this_ym, category), 0.0)
        if this_amount < _MIN_AMOUNT_FOR_ALERT:
            continue

        prev_amounts = [monthly_category.get((ym, category), 0.0) for ym in prev_months]
        prev_avg = sum(prev_amounts) / len(prev_amounts)

        if prev_avg < _MIN_AMOUNT_FOR_ALERT:
            if this_amount >= _MIN_AMOUNT_FOR_ALERT * 5:
                alerts.append({
                    "category": category,
                    "this_month_amount": this_amount,
                    "prev_avg_amount": prev_avg,
                    "ratio": None,
                    "alert_type": "NEW_SPENDING",
                    "message": f"이번 달 {category} 지출({this_amount:,.0f}원)이 새로 발생했습니다.",
                })
            continue

        ratio = this_amount / prev_avg
        if ratio >= _ALERT_RATIO:
            ratio_text = f"{ratio:.1f}배" if ratio < 10 else "10배 이상"
            alerts.append({
                "category": category,
                "this_month_amount": this_amount,
                "prev_avg_amount": prev_avg,
                "ratio": round(ratio, 2),
                "alert_type": "SPIKE",
                "message": (
                    f"이번 달 {category} 지출({this_amount:,.0f}원)이 "
                    f"평소({prev_avg:,.0f}원)의 {ratio_text}입니다."
                ),
            })

    alerts.sort(key=lambda a: (a.get("ratio") or 99), reverse=True)
    return alerts


def _planner_find_saving_opportunities(aggregation: dict) -> list[dict]:
    """변동비 카테고리에서 절약 가능 항목 추출."""
    this_month_summary = aggregation.get("this_month_summary", {})
    prev_months = aggregation.get("prev_months", [])
    raw = aggregation.get("monthly_category", {})

    monthly_category: dict[tuple[str, str], float] = {}
    for key, val in raw.items():
        ym, cat = key.split("|", 1)
        monthly_category[(ym, cat)] = val

    opportunities = []
    for cat in _VARIABLE_COST_CATEGORIES:
        this_amt = this_month_summary.get(cat, 0.0)
        if this_amt < _MIN_AMOUNT_FOR_ALERT:
            continue

        if prev_months:
            prev_amounts = [monthly_category.get((ym, cat), 0.0) for ym in prev_months]
            prev_avg = sum(prev_amounts) / len(prev_amounts)
        else:
            prev_avg = this_amt

        # 현재 지출의 20%를 절약 가능으로 추정
        saveable = this_amt * 0.2
        benchmark = max(prev_avg * 0.9, this_amt * 0.8)  # 목표 = 이전 평균의 90% 또는 현재의 80%

        if saveable >= _MIN_AMOUNT_FOR_ALERT:
            opportunities.append({
                "category": cat,
                "current_amount": this_amt,
                "prev_avg_amount": prev_avg,
                "estimated_saving": round(saveable),
                "target_amount": round(benchmark),
                "cost_type": "변동비",
            })

    # 고정비도 포함 (절감 가능성 낮지만 참고용)
    for cat in _FIXED_COST_CATEGORIES:
        this_amt = this_month_summary.get(cat, 0.0)
        if this_amt >= _MIN_AMOUNT_FOR_ALERT * 5:
            opportunities.append({
                "category": cat,
                "current_amount": this_amt,
                "prev_avg_amount": this_amt,
                "estimated_saving": round(this_amt * 0.05),
                "target_amount": round(this_amt * 0.95),
                "cost_type": "고정비",
            })

    opportunities.sort(key=lambda x: x["estimated_saving"], reverse=True)
    return opportunities


_IMPROVEMENT_ACTIONS: dict[str, list[str]] = {
    "식비": [
        "배달앱 주문 횟수를 주 3회 → 주 1회로 줄이면 월 약 {saving:,.0f}원 절약 가능",
        "카페 외출 횟수를 줄이고 텀블러 활용을 권장합니다",
        "장보기 시 주간 식단을 미리 계획하면 충동 구매를 줄일 수 있습니다",
    ],
    "쇼핑": [
        "온라인 장바구니 24시간 대기 후 구매하면 충동 구매를 줄일 수 있습니다",
        "구매 전 '필요 vs 원하는 것' 체크리스트 활용을 권장합니다",
        "월 쇼핑 예산 {target:,.0f}원을 설정하고 초과 시 다음 달로 이월하세요",
    ],
    "문화/여가": [
        "OTT 서비스 중 실제 사용 중인 것만 유지하세요 (월 {saving:,.0f}원 절약 가능)",
        "헬스장 등 정기 구독은 실제 이용 빈도를 확인 후 유지 여부를 결정하세요",
    ],
    "교통": [
        "택시 대신 대중교통 이용 시 월 {saving:,.0f}원 절약 가능",
        "출퇴근 경로 카풀 앱 활용을 고려해 보세요",
    ],
    "공과금": [
        "통신 요금제를 실제 사용량에 맞게 조정하면 월 {saving:,.0f}원 절약 가능",
        "전기 절약 습관(대기전력 차단, 냉난방 온도 조절)으로 관리비를 줄일 수 있습니다",
    ],
    "의료": [
        "정기 건강검진을 통한 예방 관리로 큰 지출을 예방하세요",
    ],
    "금융": [
        "보험료는 연간 리밸런싱으로 불필요한 중복 보장을 제거하세요",
    ],
    "기타": [
        "기타 지출 {current:,.0f}원 중 정기적으로 발생하는 항목을 파악해 관리하세요",
    ],
}


def _planner_generate_plan(anomalies: list[dict], opportunities: list[dict]) -> list[dict]:
    """개선 방안 생성."""
    plan_items = []
    handled = set()

    # 이상 지출 카테고리 우선
    for alert in anomalies[:4]:
        cat = alert["category"]
        if cat in handled:
            continue
        handled.add(cat)
        actions = _IMPROVEMENT_ACTIONS.get(cat, _IMPROVEMENT_ACTIONS["기타"])
        saving_est = round(alert.get("this_month_amount", 0) * 0.2)
        target = round(alert.get("prev_avg_amount", 0) or alert.get("this_month_amount", 0) * 0.8)
        action_texts = [
            a.format(saving=saving_est, target=target, current=alert.get("this_month_amount", 0))
            for a in actions[:2]
        ]
        plan_items.append({
            "category": cat,
            "alert_type": alert.get("alert_type", "SPIKE"),
            "current_amount": alert.get("this_month_amount", 0),
            "estimated_saving": saving_est,
            "actions": action_texts,
        })

    # 절약 가능 항목 중 미처리 카테고리 추가
    for opp in opportunities[:3]:
        cat = opp["category"]
        if cat in handled:
            continue
        handled.add(cat)
        actions = _IMPROVEMENT_ACTIONS.get(cat, _IMPROVEMENT_ACTIONS["기타"])
        action_texts = [
            a.format(saving=opp["estimated_saving"], target=opp["target_amount"], current=opp["current_amount"])
            for a in actions[:1]
        ]
        plan_items.append({
            "category": cat,
            "alert_type": "OPPORTUNITY",
            "current_amount": opp["current_amount"],
            "estimated_saving": opp["estimated_saving"],
            "actions": action_texts,
        })

    return plan_items


def _planner_set_goals(aggregation: dict, plan_items: list[dict], reduction_rate: float = 0.1) -> list[dict]:
    """다음 달 카테고리별 소비 목표 설정."""
    this_month_summary = aggregation.get("this_month_summary", {})
    prev_months = aggregation.get("prev_months", [])
    raw = aggregation.get("monthly_category", {})

    monthly_category: dict[tuple[str, str], float] = {}
    for key, val in raw.items():
        ym, cat = key.split("|", 1)
        monthly_category[(ym, cat)] = val

    plan_categories = {p["category"] for p in plan_items}
    goals = []

    all_categories = set(this_month_summary.keys())
    for cat in sorted(all_categories, key=lambda c: this_month_summary.get(c, 0), reverse=True)[:8]:
        current = this_month_summary.get(cat, 0.0)
        if current < _MIN_AMOUNT_FOR_ALERT:
            continue

        if prev_months:
            prev_amounts = [monthly_category.get((ym, cat), 0.0) for ym in prev_months]
            prev_avg = sum(prev_amounts) / len(prev_amounts)
        else:
            prev_avg = current

        # 이상 지출 카테고리는 이전 평균으로 복귀 목표
        if cat in plan_categories and prev_avg > 0:
            target = round(prev_avg * (1 - reduction_rate / 2))
        else:
            target = round(current * (1 - reduction_rate))

        goals.append({
            "category": cat,
            "current_amount": round(current),
            "prev_avg_amount": round(prev_avg),
            "target_amount": target,
            "reduction_rate": round((current - target) / current * 100, 1) if current > 0 else 0,
            "cost_type": "고정비" if cat in _FIXED_COST_CATEGORIES else "변동비",
        })

    total_current = sum(g["current_amount"] for g in goals)
    total_target = sum(g["target_amount"] for g in goals)

    return {
        "goals": goals,
        "total_current": total_current,
        "total_target": total_target,
        "total_estimated_saving": total_current - total_target,
    }


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

    elif tool_name == "get_transaction_history":
        months = int(tool_input.get("analysis_months", 3))
        months = min(max(months, 1), 6)
        transactions = _planner_fetch_transactions(db, customer_id, months)
        ctx["transactions"] = transactions
        ctx["analysis_months"] = months
        return {
            "transaction_count": len(transactions),
            "analysis_months": months,
            "has_data": len(transactions) > 0,
        }

    elif tool_name == "analyze_spending_by_category":
        transactions = ctx.get("transactions", [])
        aggregation = _planner_aggregate_by_category(transactions)
        ctx["aggregation"] = aggregation
        summary = aggregation.get("this_month_summary", {})
        return {
            "this_month_total": aggregation.get("this_month_total", 0),
            "prev_avg_total": aggregation.get("prev_avg_total", 0),
            "category_count": len(summary),
            "top_category": max(summary, key=summary.get) if summary else None,
            "top_amount": max(summary.values()) if summary else 0,
        }

    elif tool_name == "detect_anomalies":
        aggregation = ctx.get("aggregation", {})
        anomalies = _planner_detect_anomalies(aggregation)
        ctx["anomalies"] = anomalies
        return {
            "anomaly_count": len(anomalies),
            "spike_count": sum(1 for a in anomalies if a.get("alert_type") == "SPIKE"),
            "max_ratio_category": anomalies[0]["category"] if anomalies else None,
            "max_ratio": anomalies[0].get("ratio") if anomalies else None,
        }

    elif tool_name == "find_saving_opportunities":
        aggregation = ctx.get("aggregation", {})
        opportunities = _planner_find_saving_opportunities(aggregation)
        ctx["opportunities"] = opportunities
        total_saving = sum(o["estimated_saving"] for o in opportunities)
        return {
            "opportunity_count": len(opportunities),
            "total_estimated_monthly_saving": total_saving,
            "top_opportunity_category": opportunities[0]["category"] if opportunities else None,
        }

    elif tool_name == "generate_improvement_plan":
        anomalies = ctx.get("anomalies", [])
        opportunities = ctx.get("opportunities", [])
        plan_items = _planner_generate_plan(anomalies, opportunities)
        ctx["plan_items"] = plan_items
        return {
            "plan_item_count": len(plan_items),
            "categories_covered": [p["category"] for p in plan_items],
            "total_estimated_saving": sum(p["estimated_saving"] for p in plan_items),
        }

    elif tool_name == "set_monthly_spending_goal":
        aggregation = ctx.get("aggregation", {})
        plan_items = ctx.get("plan_items", [])
        reduction_rate = float(tool_input.get("reduction_rate", 0.1))
        goal_result = _planner_set_goals(aggregation, plan_items, reduction_rate)
        ctx["spending_goals"] = goal_result
        return {
            "goal_count": len(goal_result.get("goals", [])),
            "total_current": goal_result.get("total_current", 0),
            "total_target": goal_result.get("total_target", 0),
            "total_estimated_saving": goal_result.get("total_estimated_saving", 0),
        }

    else:
        return {"error": f"unknown tool: {tool_name}"}


# ──────────────────────────────────────────────
# 시스템 프롬프트
# ──────────────────────────────────────────────

MAX_AGENT_ITERATIONS = 14  # 도구 수(7) × 2

SYSTEM_PROMPT = """너는 사용자의 월간 지출 데이터를 분석하는 금융 소비 패턴 분석 AI이다.

너의 역할은 "분석"이며, 계산/추정/기억/학습을 하지 않는다.
오직 입력 데이터(도구 결과)만 해석한다.

---

# 분석 목표

다음을 수행하라:

1. 전체 소비 구조 요약
2. 카테고리별 증감 분석 (이번 달 vs 직전 2개월 평균)
3. 이상 지출 탐지
4. 배달 / 카페 / 편의점 / 쇼핑 / 고정비 분리 분석
5. 행동 기반 원인 분석 (습관 / 환경 / 심리)
6. 절약 시나리오 제안
7. 다음 달 소비 목표 설정

---

# 도구 사용 순서 (반드시 이 순서로 호출)
1. get_transaction_history → 거래내역 조회 (항상 먼저)
2. analyze_spending_by_category → 카테고리별 집계 및 직전 2개월 평균 비교
3. detect_anomalies → 이상 지출 탐지
4. find_saving_opportunities → 절약 가능 항목 탐색
5. generate_improvement_plan → 개선 방안 생성
6. set_monthly_spending_goal → 다음 달 목표 설정

도구 결과가 비어 있어도 분석을 중단하지 않는다.
거래 데이터가 없으면 사용자 메시지에서 소비 행동 패턴을 추출하여 분석을 수행한다.

---

# 핵심 규칙

- 이전 대화/이전 결과 절대 사용 금지
- 입력 데이터(도구 결과)만 사용
- 평균 계산 금지
- 수치 생성/추정 금지
- 없는 카테고리 생성 금지
- 상태/기억 유지 금지

---

# 출력 형식 (고정)

### 1. 소비 구조 분석

### 2. 증감 분석 (수치 기반)

### 3. 카테고리 분석 (입력에 존재하는 것만)

### 4. 원인 분석

### 5. 절약 시나리오

### 6. 다음 달 목표

### 7. 한 줄 요약

---

# 금지

- 과거 데이터 재사용
- 캐시/메모리 의존
- 가짜 수치 생성
- 존재하지 않는 항목 추가
- 추정값 사실화
- UI/상담/시스템 문구 출력
"""


# ──────────────────────────────────────────────
# 메인 에이전트 루프
# ──────────────────────────────────────────────

@observe(name="spending-agent", capture_input=False)
def _run_spending_agent_claude(db: Session, customer_id: str, message: str) -> dict:
    """Tool Calling 기반 지출 패턴 관리 에이전트 메인 함수."""
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
    elif tool_name == "get_transaction_history":
        return f"거래 {result.get('transaction_count', 0)}건 ({result.get('analysis_months', 3)}개월)"
    elif tool_name == "analyze_spending_by_category":
        return f"이번 달 총 {result.get('this_month_total', 0):,.0f}원, 상위: {result.get('top_category', '-')}"
    elif tool_name == "detect_anomalies":
        return f"이상 지출 {result.get('anomaly_count', 0)}건, 최대: {result.get('max_ratio_category', '-')}"
    elif tool_name == "find_saving_opportunities":
        return f"절약 가능 {result.get('total_estimated_monthly_saving', 0):,.0f}원/월"
    elif tool_name == "generate_improvement_plan":
        return f"개선 방안 {result.get('plan_item_count', 0)}개, 예상 절약 {result.get('total_estimated_saving', 0):,.0f}원"
    elif tool_name == "set_monthly_spending_goal":
        return f"목표 설정 완료: {result.get('total_target', 0):,.0f}원/월 (절약 {result.get('total_estimated_saving', 0):,.0f}원)"
    return str(result)[:80]


def _build_response(ctx: dict, agent_steps: list[dict]) -> dict:
    need_more_info = ctx.get("need_more_info", False)
    aggregation = ctx.get("aggregation", {})
    spending_goals = ctx.get("spending_goals", {})

    return {
        "agent_type": "SPENDING_PATTERN_AGENT",
        "need_more_info": need_more_info,
        "follow_up_question": ctx.get("follow_up_question") if need_more_info else None,
        "agent_steps": agent_steps,
        "this_month_summary": aggregation.get("this_month_summary", {}),
        "this_month_total": aggregation.get("this_month_total", 0),
        "prev_avg_total": aggregation.get("prev_avg_total", 0),
        "anomalies": ctx.get("anomalies", []),
        "opportunities": ctx.get("opportunities", []),
        "plan_items": ctx.get("plan_items", []),
        "spending_goals": spending_goals.get("goals", []),
        "total_estimated_saving": spending_goals.get("total_estimated_saving", 0),
        "warning": ctx.get("warning"),
    }


# ──────────────────────────────────────────────
# 자연어 입력 행동 기반 분석 (데이터 없을 때)
# ──────────────────────────────────────────────

_BEHAVIOR_KEYWORDS: dict[str, list[str]] = {
    "배달": ["배달", "배민", "쿠팡이츠", "요기요", "배달앱", "시켜먹", "주문"],
    "카페": ["카페", "커피", "스타벅스", "아메리카노", "라떼", "카페라떼"],
    "편의점": ["편의점", "세븐", "씨유", "gs25", "미니스톱", "편의"],
    "쇼핑": ["쇼핑", "쿠팡", "옷", "구매", "온라인", "택배"],
    "구독": ["구독", "넷플릭스", "유튜브", "ott", "멤버십", "월정액"],
    "식비": ["밥", "식비", "점심", "저녁", "외식", "밥값"],
}

_BEHAVIOR_CAUSE: dict[str, list[str]] = {
    "배달": ["피로 또는 귀찮음으로 인한 조리 회피 가능성", "배달 앱 할인/프로모션으로 인한 루틴화 가능성", "외출 감소에 따른 배달 의존도 증가 가능성"],
    "카페": ["업무 중 카페인 의존 루틴화 가능성", "카페를 작업/휴식 공간으로 활용하는 패턴 가능성"],
    "편의점": ["식사 대체 또는 간식 소비 빈도 증가 가능성", "이동 중 즉각 소비 패턴 가능성"],
    "쇼핑": ["스트레스성 충동 구매 가능성", "온라인 쇼핑 알고리즘 노출 증가 가능성"],
    "구독": ["미사용 구독 서비스 자동 결제 지속 가능성", "구독 서비스 누적으로 월 고정비 증가 가능성"],
}

_BEHAVIOR_SAVING: dict[str, tuple[int, int]] = {
    "배달": (50_000, 120_000),
    "카페": (30_000, 60_000),
    "편의점": (20_000, 40_000),
    "쇼핑": (40_000, 100_000),
    "구독": (15_000, 30_000),
    "식비": (30_000, 70_000),
}

_BEHAVIOR_ACTIONS: dict[str, list[str]] = {
    "배달": ["배달 주문을 주 2회로 제한, 초과 시 다음 주로 이월", "주문 전 10분 대기 규칙 적용 (충동 주문 방지)", "주 1회 장보기로 식재료 비축 후 직접 조리"],
    "카페": ["카페 방문을 주 3회로 제한", "텀블러 지참 시 할인 적용 카페 우선 이용", "사무실/집에서 대체 음료 준비"],
    "편의점": ["편의점 방문 시 구매 목록 미리 작성 후 목록 외 구매 금지", "식사 대체 편의점 이용 주 2회 이하로 제한"],
    "쇼핑": ["구매 전 24시간 대기 규칙 적용", "월 쇼핑 예산 상한선 설정 후 초과 시 다음 달로 이월"],
    "구독": ["현재 구독 서비스 전수 점검 후 월 1회 미만 이용 항목 해지", "구독 갱신일 캘린더 등록으로 사전 확인"],
    "식비": ["주간 식단 계획 수립으로 충동 외식 감소", "외식 횟수를 주 2회로 제한"],
}

_BEHAVIOR_GOAL: dict[str, str] = {
    "배달": "배달비 포함 월 N원 이하 (현재 추정 대비 30% 감축)",
    "카페": "카페 지출 월 N원 이하 (주 3회 제한 기준)",
    "편의점": "편의점 지출 월 N원 이하 (일평균 1,000원 기준)",
    "쇼핑": "쇼핑 지출 월 N원 이하 (예산 상한선 준수)",
    "구독": "구독 서비스 월 N원 이하 (불필요 항목 해지 후)",
    "식비": "식비 월 N원 이하 (외식 주 2회 이하 기준)",
}


def _extract_behavior_keywords(message: str) -> list[str]:
    """메시지에서 소비 관련 행동 키워드를 추출한다."""
    msg_lower = message.lower()
    detected = []
    for category, keywords in _BEHAVIOR_KEYWORDS.items():
        if any(kw in msg_lower for kw in keywords):
            detected.append(category)
    return detected if detected else ["배달", "편의점"]  # 기본값


def _build_behavior_analysis(detected: list[str]) -> str:
    """행동 기반 추정 분석 결과를 8단계 형식으로 조립한다."""
    primary = detected[0]
    secondary = detected[1] if len(detected) > 1 else None
    categories_str = " + ".join(detected)

    causes = _BEHAVIOR_CAUSE.get(primary, ["소비 빈도 증가 가능성"])[:2]
    if secondary:
        causes += _BEHAVIOR_CAUSE.get(secondary, [])[:1]

    saving_range = _BEHAVIOR_SAVING.get(primary, (30_000, 80_000))
    saving_est = (saving_range[0] + saving_range[1]) // 2
    total_saving = saving_est
    secondary_saving = 0
    if secondary:
        s2 = _BEHAVIOR_SAVING.get(secondary, (20_000, 40_000))
        secondary_saving = (s2[0] + s2[1]) // 2
        total_saving += secondary_saving

    actions = _BEHAVIOR_ACTIONS.get(primary, [])[:2]
    if secondary:
        actions += _BEHAVIOR_ACTIONS.get(secondary, [])[:1]

    risk = "🔴 높음" if primary in ("배달", "쇼핑") else "🟡 보통"

    lines = [
        "### 1. 소비 구조 분석",
        f"- 주요 소비 집중 영역: {categories_str} (행동 패턴 기반 추정)",
        "- 이번 달 총 지출: 산출 불가 (거래 데이터 없음)",
        "- 거래 데이터가 없어 자연어 기반 행동 추정 분석으로 진행합니다",
        "",
        "### 2. 증감 분석 (수치 기반)",
        f"- 감지 카테고리: [{categories_str}] 빈도 증가 추정",
        f"- 비교 기준: 직전 2개월 평균 대비 (추정) | 위험도: {risk}",
        f"- {primary} 소비가 루틴화되어 지출 비중이 편중된 가능성",
        "",
        "### 3. 카테고리 분석",
    ]
    for cat in detected:
        sr = _BEHAVIOR_SAVING.get(cat, (20_000, 50_000))
        lines.append(f"- [{cat}] 추정 월 지출 {sr[0]:,}~{sr[1]:,}원 / 빈도 증가 상태로 추정 (데이터 없음)")
    for cat in ["배달", "카페", "편의점", "쇼핑", "구독"]:
        if cat not in detected:
            lines.append(f"- [{cat}] 데이터 없음 (미감지)")

    lines += ["", "### 4. 원인 분석"]
    for c in causes:
        lines.append(f"- {c}")

    lines += ["", "### 5. 절약 시뮬레이션"]
    lines.append(f"- [{primary}] 주 2회 제한 → 월 약 {saving_est:,}원 절약 가능 (추정)")
    if secondary:
        lines.append(f"- [{secondary}] 빈도 50% 감소 → 월 약 {secondary_saving:,}원 절약 가능 (추정)")
    lines.append(f"- 총 절약 가능: 월 약 {total_saving:,}원 (거래 데이터 없음, 추정값)")

    lines += [
        "",
        "### 6. 다음 달 목표",
        "- 총 소비 목표: 현재 추정 대비 20% 감축 (거래 데이터 연동 시 정밀 목표 설정 가능)",
    ]
    for cat in detected[:2]:
        goal = _BEHAVIOR_GOAL.get(cat, f"{cat} 지출 현재 대비 20% 감축")
        lines.append(f"- [{cat}] {goal}")

    lines += [
        "",
        "### 7. 한 줄 요약",
        f"- {primary}{'·' + secondary if secondary else ''} 중심 소비 패턴 — 빈도 조절만으로 월 {total_saving:,}원 절약 가능 (추정)",
    ]

    return "\n".join(lines)


# ──────────────────────────────────────────────
# Mock 모드 (API Key 없이 동작)
# ──────────────────────────────────────────────

def _parse_negated_categories(message: str) -> set[str]:
    """
    "쇼핑은 거의 안 했어", "배달 별로 안 써" 등에서 사용자가 부정한 카테고리를 추출한다.
    이 카테고리는 plan/anomaly 생성에서 제외한다.
    """
    negation_patterns = [
        ("배달", ["배달 안", "배달은 안", "배달 별로", "배달 거의 안", "배달 많이 안"]),
        ("카페", ["카페 안", "카페는 안", "카페 거의 안", "커피 안"]),
        ("편의점", ["편의점 안", "편의점 거의 안"]),
        ("쇼핑", ["쇼핑 안", "쇼핑은 안", "쇼핑 거의 안", "쇼핑 별로", "쇼핑은 거의 안"]),
        ("구독", ["구독 안", "구독 거의"]),
    ]
    msg_lower = message.lower().replace(" ", "")
    negated = set()
    for cat, patterns in negation_patterns:
        for p in patterns:
            if p.replace(" ", "") in msg_lower:
                negated.add(cat)
                break
    return negated


def run_spending_agent_mock(db: Session, customer_id: str, message: str) -> dict:
    """
    Claude API 없이 동작하는 Mock Agent.
    - 거래 데이터 있음: DB 집계 + 자연어 힌트 결합 분석
    - 거래 데이터 없음: 자연어 메시지 기반 행동 추정 분석
    """
    ctx: dict = {"customer_id": customer_id}
    agent_steps: list[dict] = []
    warning = None

    # 자연어에서 카테고리 힌트 추출 (거래 데이터 유무와 무관하게 항상 수행)
    msg_detected = _extract_behavior_keywords(message)
    negated_cats = _parse_negated_categories(message)
    ctx["_msg_detected"] = msg_detected
    ctx["_negated_cats"] = negated_cats

    try:
        # Step 1: 거래내역 조회
        transactions = _planner_fetch_transactions(db, customer_id, analysis_months=3)
        agent_steps.append({
            "tool": "get_transaction_history",
            "result_summary": f"거래 {len(transactions)}건 (3개월)",
        })
        ctx["transactions"] = transactions

        if not transactions:
            # 데이터 없음 → 자연어 행동 기반 추정 분석
            detected = msg_detected if msg_detected else ["배달", "편의점"]
            behavior_text = _build_behavior_analysis(detected)
            return {
                "agent_type": "SPENDING_PATTERN_AGENT_MOCK",
                "need_more_info": False,
                "follow_up_question": None,
                "agent_steps": agent_steps,
                "this_month_summary": {},
                "this_month_total": 0,
                "prev_avg_total": 0,
                "anomalies": [],
                "opportunities": [],
                "plan_items": [],
                "spending_goals": [],
                "total_estimated_saving": 0,
                "behavior_analysis": behavior_text,
                "behavior_categories": detected,
                "message": None,
                "warning": "거래 데이터가 없어 메시지 기반 행동 추정 분석을 수행했습니다.",
            }

        # Step 2: 카테고리별 집계
        aggregation = _planner_aggregate_by_category(transactions)
        ctx["aggregation"] = aggregation
        agent_steps.append({
            "tool": "analyze_spending_by_category",
            "result_summary": f"이번 달 총 {aggregation.get('this_month_total', 0):,.0f}원",
        })

        # Step 3: 이상 지출 탐지
        anomalies = _planner_detect_anomalies(aggregation)
        # 사용자가 부정한 카테고리 제거
        anomalies = [a for a in anomalies if a["category"] not in negated_cats]
        # 자연어로 감지됐지만 DB에 없는 카테고리 → 행동 기반 추정으로 보완
        db_cats = {a["category"] for a in anomalies}
        for cat in msg_detected:
            if cat not in db_cats and cat not in negated_cats:
                sr = _BEHAVIOR_SAVING.get(cat, (30_000, 80_000))
                anomalies.append({
                    "category": cat,
                    "this_month_amount": (sr[0] + sr[1]) // 2,
                    "prev_avg_amount": sr[0],
                    "ratio": round((sr[0] + sr[1]) / 2 / sr[0], 1),
                    "message": f"{cat} 지출 증가 체감 (메시지 기반 추정)",
                })
        ctx["anomalies"] = anomalies
        agent_steps.append({
            "tool": "detect_anomalies",
            "result_summary": f"이상 지출 {len(anomalies)}건",
        })

        # Step 4: 절약 가능 항목
        opportunities = _planner_find_saving_opportunities(aggregation)
        opportunities = [o for o in opportunities if o.get("category") not in negated_cats]
        ctx["opportunities"] = opportunities
        agent_steps.append({
            "tool": "find_saving_opportunities",
            "result_summary": f"절약 가능 {sum(o['estimated_saving'] for o in opportunities):,.0f}원/월",
        })

        # Step 5: 개선 방안
        plan_items = _planner_generate_plan(anomalies, opportunities)
        plan_items = [p for p in plan_items if p.get("category") not in negated_cats]
        ctx["plan_items"] = plan_items
        agent_steps.append({
            "tool": "generate_improvement_plan",
            "result_summary": f"개선 방안 {len(plan_items)}개",
        })

        # Step 6: 목표 설정
        goal_result = _planner_set_goals(aggregation, plan_items, reduction_rate=0.1)
        ctx["spending_goals"] = goal_result
        agent_steps.append({
            "tool": "set_monthly_spending_goal",
            "result_summary": f"목표 설정 완료 ({goal_result.get('total_estimated_saving', 0):,.0f}원 절약 예상)",
        })

    except Exception as e:
        warning = f"[Mock] 데이터 조회 실패 ({e}), 기본 응답으로 대체합니다."

    # 6단계 구조화 메시지 조립
    aggregation = ctx.get("aggregation", {})
    anomalies = ctx.get("anomalies", [])
    plan_items = ctx.get("plan_items", [])
    goal_result = ctx.get("spending_goals", {})
    this_ym = aggregation.get("this_ym", date.today().strftime("%Y-%m"))
    this_total = aggregation.get("this_month_total", 0)
    prev_avg = aggregation.get("prev_avg_total", 0)
    change_pct = ((this_total - prev_avg) / prev_avg * 100) if prev_avg > 0 else 0
    this_month_summary = aggregation.get("this_month_summary", {})
    top_cat = max(this_month_summary, key=this_month_summary.get) if this_month_summary else "분석 불가"

    _msg_detected = msg_detected
    _negated_cats = negated_cats

    lines = [
        "### 1. 소비 구조 분석",
        f"- {this_ym} 기준 {top_cat} 중심 소비 구조, 직전 2개월 평균 대비 {change_pct:+.1f}% 변화",
        f"- 직전 2개월 평균: {prev_avg:,.0f}원 / 이번 달: {this_total:,.0f}원 / 변화율: {change_pct:+.1f}%",
        "- 주요 지출 집중 영역: " + ", ".join(list(this_month_summary.keys())[:3]) if this_month_summary else "- 주요 지출 집중 영역: 분석 불가",
        "",
        "### 2. 증감 분석 (수치 기반)",
    ]

    if anomalies:
        for a in anomalies[:4]:
            ratio_str = f" (평균 대비 {a['ratio']:.1f}배)" if a.get("ratio") else ""
            risk = "🔴 높음" if a.get("ratio", 1) >= 2 else "🟡 보통"
            lines.append(f"- [{a['category']}] {a['this_month_amount']:,.0f}원{ratio_str} — {a['message']} | 위험도: {risk}")
    else:
        lines.append("- 이상 지출 없음 — 직전 2개월 평균 대비 소비 패턴 유사")

    # 카테고리 분석
    lines += ["", "### 3. 카테고리 분석"]
    total = this_total if this_total > 0 else 1
    for cat, amt in list(this_month_summary.items())[:6]:
        if cat in _negated_cats:
            continue
        pct = amt / total * 100
        lines.append(f"- [{cat}] {amt:,.0f}원 ({pct:.1f}%)")
    # 자연어 감지 카테고리 중 DB에 없는 것 추정으로 표기
    db_summary_cats = set(this_month_summary.keys())
    for cat in _msg_detected:
        if cat not in db_summary_cats and cat not in _negated_cats:
            sr = _BEHAVIOR_SAVING.get(cat, (30_000, 80_000))
            lines.append(f"- [{cat}] 추정 {(sr[0]+sr[1])//2:,}원 (거래 분류 없음, 메시지 기반 추정)")
    if _negated_cats:
        for cat in _negated_cats:
            lines.append(f"- [{cat}] 데이터 없음 (미해당 — 사용자 확인)")
    lines.append("- [고정비] 분석 대상 외 (변동 불가 항목)")

    # 원인 분석 (행동 기반)
    lines += ["", "### 4. 원인 분석"]
    if anomalies:
        for a in anomalies[:3]:
            cat = a["category"]
            causes = _BEHAVIOR_CAUSE.get(cat, ["소비 빈도 증가 가능성"])
            for c in causes[:1]:
                lines.append(f"- [{cat}] {c}")
    else:
        lines.append("- 소비 구조 변화 없음, 패턴 안정적")

    # 절약 시뮬레이션 (도구 계산값 기반)
    lines += ["", "### 5. 절약 시뮬레이션"]
    if plan_items:
        for p in plan_items[:4]:
            actions = p.get("actions", [])
            action_str = actions[0] if actions else "빈도 조정"
            lines.append(f"- [{p['category']}] {p['current_amount']:,.0f}원 → {action_str}: 월 {p['estimated_saving']:,.0f}원 절약 가능")
        total_saving = sum(p["estimated_saving"] for p in plan_items)
        lines.append(f"- 총 절약 가능: 월 {total_saving:,.0f}원")
    else:
        lines.append("- 절약 시나리오 데이터 없음")
        total_saving = 0

    # 다음 달 목표
    lines += ["", "### 6. 다음 달 목표"]
    goals = goal_result.get("goals", [])
    goal_total = goal_result.get("total_target", 0)
    goal_saving = goal_result.get("total_estimated_saving", 0)
    if goals:
        if goal_total > 0:
            lines.append(f"- 총 소비 목표: {goal_total:,.0f}원 이하 (월 {goal_saving:,.0f}원 절약)")
        for g in goals[:5]:
            lines.append(f"- [{g['category']}] {g['target_amount']:,.0f}원 이하 (현재 대비 {g['reduction_rate']}% 감축)")
    else:
        lines.append("- 목표 설정 데이터 부족")

    # 한 줄 요약
    top_anomaly = anomalies[0]["category"] if anomalies else top_cat
    lines += [
        "",
        "### 7. 한 줄 요약",
        f"- {top_anomaly} 중심 소비 집중 — 절약 시나리오 적용 시 월 {total_saving:,.0f}원 감축 가능",
    ]

    message_text = "\n".join(lines)

    return {
        "agent_type": "SPENDING_PATTERN_AGENT_MOCK",
        "need_more_info": False,
        "follow_up_question": None,
        "agent_steps": agent_steps,
        "this_month_summary": aggregation.get("this_month_summary", {}),
        "this_month_total": this_total,
        "prev_avg_total": prev_avg,
        "anomalies": anomalies,
        "opportunities": opportunities,
        "plan_items": plan_items,
        "spending_goals": goals,
        "total_estimated_saving": goal_saving,
        "message": message_text,
        "warning": warning,
    }


def run_spending_agent(db: Session, customer_id: str, message: str) -> dict:
    """
    API Key가 있으면 Claude Tool Calling Agent, 없으면 Mock으로 자동 전환.
    SPENDING_AGENT_ENABLED가 false이면 호출하지 않아야 하며,
    이 함수는 호출된 경우에만 실행된다.
    """
    if not settings.anthropic_api_key:
        return run_spending_agent_mock(db, customer_id, message)
    return _run_spending_agent_claude(db, customer_id, message)
