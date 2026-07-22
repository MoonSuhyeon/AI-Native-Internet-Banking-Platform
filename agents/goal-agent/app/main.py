import base64
import json as _json

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

import logging

from app.config import settings
from app.database import get_db

logger = logging.getLogger(__name__)


def _setup_langfuse() -> None:
    if not settings.langfuse_enabled:
        return
    from langfuse import Langfuse
    Langfuse(
        secret_key=settings.langfuse_secret_key,
        public_key=settings.langfuse_public_key,
        host=settings.langfuse_host,
    )
    logger.info("[Langfuse] LLM 추적 활성화 → %s", settings.langfuse_host)


_setup_langfuse()
from app.registry import TABLES
from app import services
from app import agent_maturity
from app import agent_maturity_chat
from app import agent_spending_chat
from app import agent_goal_planner
from app import agent_goal_chat
from app.utils import clean_payload, model_to_dict


app = FastAPI(title=settings.app_name)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def _require_api_key() -> None:
    """GOAL_AGENT_API_KEY 미설정 시 서비스 기동을 거부한다."""
    if not settings.api_key:
        raise RuntimeError(
            "GOAL_AGENT_API_KEY environment variable must be set. "
            "Refusing to start without authentication."
        )


@app.middleware("http")
async def verify_internal_token(request: Request, call_next):
    """내부 서비스 인증. /health 를 제외한 모든 요청에 X-Internal-Token 헤더를 검증한다."""
    if request.url.path != "/health":
        token = request.headers.get("X-Internal-Token", "")
        if token != settings.api_key:
            return JSONResponse(status_code=401, content={"detail": "Unauthorized"})
    return await call_next(request)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "tables": len(TABLES)}


@app.get("/api/meta/tables")
def table_meta() -> dict:
    return {"tables": list(TABLES.keys())}


@app.get("/api/{table_name}")
def list_rows(table_name: str, limit: int = 100, offset: int = 0, db: Session = Depends(get_db)) -> list[dict]:
    model, pk = get_table(table_name)
    rows = db.scalars(select(model).order_by(getattr(model, pk)).limit(limit).offset(offset)).all()
    return [model_to_dict(row) for row in rows]


@app.post("/api/{table_name}", status_code=201)
def create_row(table_name: str, payload: dict, db: Session = Depends(get_db)) -> dict:
    model, _pk = get_table(table_name)
    row = model(**clean_payload(model, payload))
    db.add(row)
    db.commit()
    db.refresh(row)
    return model_to_dict(row)


@app.get("/api/{table_name}/{row_id}")
def get_row(table_name: str, row_id: int, db: Session = Depends(get_db)) -> dict:
    model, _pk = get_table(table_name)
    row = db.get(model, row_id)
    if not row:
        raise HTTPException(status_code=404, detail="row not found")
    return model_to_dict(row)


@app.patch("/api/{table_name}/{row_id}")
def update_row(table_name: str, row_id: int, payload: dict, db: Session = Depends(get_db)) -> dict:
    model, _pk = get_table(table_name)
    row = db.get(model, row_id)
    if not row:
        raise HTTPException(status_code=404, detail="row not found")
    for key, value in clean_payload(model, payload, partial=True).items():
        setattr(row, key, value)
    db.commit()
    db.refresh(row)
    return model_to_dict(row)


@app.delete("/api/{table_name}/{row_id}", status_code=204)
def delete_row(table_name: str, row_id: int, db: Session = Depends(get_db)) -> None:
    model, _pk = get_table(table_name)
    row = db.get(model, row_id)
    if not row:
        raise HTTPException(status_code=404, detail="row not found")
    db.delete(row)
    db.commit()


@app.post("/deposit_contracts", status_code=201)
def create_contract(payload: dict, db: Session = Depends(get_db)) -> dict:
    return model_to_dict(services.create_contract_with_account(db, payload))


@app.post("/deposit_transactions/deposit", status_code=201)
def deposit(payload: dict, db: Session = Depends(get_db)) -> dict:
    return model_to_dict(services.deposit(db, payload))


@app.post("/deposit_transactions/withdraw", status_code=201)
def withdraw(payload: dict, db: Session = Depends(get_db)) -> dict:
    return model_to_dict(services.withdraw(db, payload))


@app.post("/deposit_transactions/transfer", status_code=201)
def transfer(payload: dict, db: Session = Depends(get_db)) -> dict:
    return model_to_dict(services.transfer(db, payload))


@app.post("/deposit_transactions/payment", status_code=201)
def payment(payload: dict, db: Session = Depends(get_db)) -> dict:
    return model_to_dict(services.payment(db, payload))


@app.post("/deposit_transactions/savings-payment", status_code=201)
def savings_payment(payload: dict, db: Session = Depends(get_db)) -> dict:
    return model_to_dict(services.savings_payment(db, payload))


@app.post("/deposit_transactions/{transaction_id}/reversal", status_code=201)
def reversal(transaction_id: int, payload: dict, db: Session = Depends(get_db)) -> dict:
    return model_to_dict(services.reverse_transaction(db, transaction_id, payload))


@app.post("/interests/pay", status_code=201)
def pay_interest(payload: dict, db: Session = Depends(get_db)) -> dict:
    return model_to_dict(services.pay_interest(db, payload))


@app.patch("/deposit_banking_products/{banking_product_id}/status")
def change_deposit_product_status(banking_product_id: int, payload: dict, db: Session = Depends(get_db)) -> dict:
    from app.models import BankingProduct

    product = db.get(BankingProduct, banking_product_id)
    if not product:
        raise HTTPException(status_code=404, detail="banking_product not found")
    product.deposit_product_status = payload["deposit_product_status"]
    db.commit()
    db.refresh(product)
    return model_to_dict(product)


# ── 금융 목표 달성 플래너 에이전트 ─────────────────────────────────────────

@app.post("/agent/goal/analyze")
def goal_analyze(payload: dict, db: Session = Depends(get_db)) -> dict:
    """
    고객의 목표 금액·기간을 입력받아 최적 금융 계획을 수립합니다.

    body:
        customer_id : str
        goal_amount : float  (목표 금액, 원)
        goal_months : int    (목표 기간, 개월)
    """
    customer_id = payload.get("customer_id")
    goal_amount = payload.get("goal_amount")
    goal_months = payload.get("goal_months")

    if not customer_id or goal_amount is None or goal_months is None:
        raise HTTPException(status_code=400, detail="customer_id, goal_amount, goal_months are required")

    return agent_goal_planner.analyze_goal(
        db,
        customer_id=str(customer_id),
        goal_amount=float(goal_amount),
        goal_months=int(goal_months),
    )


@app.post("/agent/goal/chat")
def goal_chat(payload: dict, db: Session = Depends(get_db)) -> dict:
    """
    Tool Calling 기반 Goal-Based Financial Agent.
    자연어 메시지를 받아 LLM이 필요한 도구를 선택·실행하여 분석 결과를 반환합니다.

    body:
        customer_id : str
        message     : str  (사용자 메시지, 예: "3년 안에 5000만원 모으고 싶어")
    """
    customer_id = payload.get("customer_id")
    message = payload.get("message")

    if not customer_id or not message:
        raise HTTPException(status_code=400, detail="customer_id and message are required")

    return agent_goal_chat.run_goal_agent(
        db,
        customer_id=str(customer_id),
        message=str(message),
    )


# ── 만기 알림 및 재투자 추천 에이전트 ──────────────────────────────────────

@app.get("/agent/maturity/upcoming")
def maturity_upcoming(days: int = 30, db: Session = Depends(get_db)) -> list[dict]:
    """days 일 이내 만기 예정 계약 목록 조회."""
    return agent_maturity.get_upcoming_maturities(db, days=days)


@app.get("/agent/maturity/{contract_id}/recommendations")
def maturity_recommendations(contract_id: int, db: Session = Depends(get_db)) -> dict:
    """특정 계약의 재투자 추천 상품 + 4가지 시나리오 반환."""
    return agent_maturity.get_reinvestment_recommendations(db, contract_id)


@app.post("/agent/maturity/{contract_id}/process", status_code=201)
def maturity_process(contract_id: int, payload: dict, db: Session = Depends(get_db)) -> dict:
    """만기 시나리오 실행 (AUTO_RENEWAL / REINVEST_NEW / WITHDRAW_ALL / PARTIAL_REINVEST)."""
    return agent_maturity.process_maturity_scenario(db, contract_id, payload)


@app.post("/agent/maturity/chat")
def maturity_chat(payload: dict, db: Session = Depends(get_db)) -> dict:
    """
    Tool Calling 기반 만기 알림 및 재투자 추천 에이전트.
    MATURITY_AGENT_ENABLED=true 환경변수가 설정된 경우에만 LLM 에이전트가 활성화됩니다.
    비활성화 시 룰 기반 fallback으로 동작합니다.

    body:
        customer_id : str
        message     : str  (사용자 메시지, 예: "만기 예정 계약 확인해줘")
    """
    customer_id = payload.get("customer_id")
    message = payload.get("message", "만기 예정 계약을 확인하고 재투자 추천을 해주세요.")

    if not customer_id:
        raise HTTPException(status_code=400, detail="customer_id is required")

    if not settings.maturity_agent_enabled:
        # 룰 기반 fallback
        maturities = agent_maturity.get_upcoming_maturities(db, days=30)
        customer_maturities = [m for m in maturities if str(m.get("customer_id", "")) == str(customer_id)]
        if not customer_maturities:
            return {
                "agent_type": "MATURITY_RULE_BASED_FALLBACK",
                "maturing_contracts": [],
                "message": "30일 이내 만기 예정인 계약이 없습니다.",
            }
        target = customer_maturities[0]
        recommendations = agent_maturity.get_reinvestment_recommendations(db, target["contract_id"])
        return {
            "agent_type": "MATURITY_RULE_BASED_FALLBACK",
            "maturing_contracts": customer_maturities,
            "recommendations": recommendations,
        }

    return agent_maturity_chat.run_maturity_agent(
        db,
        customer_id=str(customer_id),
        message=str(message),
    )


@app.post("/agent/spending/chat")
def spending_chat(payload: dict, db: Session = Depends(get_db)) -> dict:
    """
    Tool Calling 기반 지출 패턴 관리 에이전트.
    SPENDING_AGENT_ENABLED=true 환경변수가 설정된 경우에만 LLM 에이전트가 활성화됩니다.
    비활성화 시 룰 기반 fallback으로 동작합니다.

    body:
        customer_id : str
        message     : str  (사용자 메시지, 예: "지출 패턴 분석해줘")
    """
    customer_id = payload.get("customer_id")
    message = payload.get("message", "지출 패턴을 분석하고 개선 방안을 알려주세요.")

    if not customer_id:
        raise HTTPException(status_code=400, detail="customer_id is required")

    if not settings.spending_agent_enabled:
        # SPENDING_AGENT_ENABLED=false: Mock 플래너로 fallback (LLM API 불필요)
        return agent_spending_chat.run_spending_agent_mock(
            db,
            customer_id=str(customer_id),
            message=str(message),
        )

    return agent_spending_chat.run_spending_agent(
        db,
        customer_id=str(customer_id),
        message=str(message),
    )


# ───────────────────────────────────────────────────────────────────────────


def get_table(table_name: str):
    if table_name not in TABLES:
        raise HTTPException(status_code=404, detail=f"unknown table: {table_name}")
    return TABLES[table_name]
