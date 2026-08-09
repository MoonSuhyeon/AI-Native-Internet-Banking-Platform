import asyncio
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv
load_dotenv()  # .env → os.environ 주입 (_setup_phoenix 등 os.getenv 사용 전에 실행)

import os
import uuid
from pathlib import Path as FilePath

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from prometheus_fastapi_instrumentator import Instrumentator
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.audit import ensure_harness_audit_schema
from app import identity
from app.config import get_settings
from app.database import Base, engine, get_db
from app.kafka import KafkaEventConsumer, KafkaEventPublisher
from app.metrics import (
    chatbot_active_sessions,
    chatbot_handoff_total,
    chatbot_message_total,
    chatbot_satisfaction_score,
    chatbot_session_ended_total,
    chatbot_session_total,
)
from app.schemas import (
    AgentConnectRequest,
    AgentLoginRequest,
    AgentLoginResponse,
    AgentQueueResponse,
    ChatHistoryItem,
    ChatRequestResponse,
    ChatRequestSchema,
    ChatbotCategoryResponse,
    ChatbotFeatureExecuteRequest,
    ChatbotFeatureExecuteResponse,
    ChatbotFeatureResponse,
    ChatbotMessageRequest,
    ChatbotMessageResponse,
    ChatbotStartRequest,
    ChatbotStartResponse,
    ChatbotTransferRequest,
    ChatbotTransferResponse,
    ChatConsultationResponse,
    ChatEndRequest,
    ChatMessageHistoryResponse,
    ChatSendMessageRequest,
    DocumentUploadResponse,
    FileAnalyzeRequest,
    FileAnalyzeResponse,
    ScenarioSeedResponse,
)
from app.services import (
    CODE_SENDER_AGENT,
    CODE_SENDER_USER,
    ChatbotService,
    ChatService,
    _chat_status,
    _SENDER_LABEL,
)
from app.llm import OpenAIDocumentAnalyzer
from app.models import ChatbotDocument, Employee
from app.rag import OpenAIEmbeddingProvider, ProductRagEngine

logger = logging.getLogger(__name__)


def _setup_file_logging() -> None:
    """파일 로그 핸들러 설정. LOG_DIR 환경변수로 경로 지정 가능."""
    log_dir = os.getenv("LOG_DIR", "C:/logs/internet-banking")
    log_file = os.path.join(log_dir, "consultation-service.log")
    try:
        os.makedirs(log_dir, exist_ok=True)
        handler = logging.FileHandler(log_file, encoding="utf-8")
        handler.setFormatter(logging.Formatter(
            "%(asctime)s [%(threadName)s] %(levelname)-5s %(name)s - %(message)s"
        ))
        logging.getLogger().setLevel(logging.INFO)
        logging.getLogger().addHandler(handler)
    except Exception as e:
        logging.getLogger(__name__).warning("[logging] 파일 핸들러 설정 실패: %s", e)


_setup_file_logging()


# settings, static_dir 은 설정값이므로 모듈 수준에 유지
settings = get_settings()
static_dir = Path(__file__).resolve().parents[1] / "static"




async def _handle_contract_created(payload: dict) -> None:
    """deposit-api 에서 ContractCreated 이벤트 수신 시 처리. RAG 인덱스 재빌드."""
    logger.info(
        "[Kafka] ContractCreated 처리 — customer=%s product=%s contract=%s amount=%s",
        payload.get("customerId", ""),
        payload.get("productId", ""),
        payload.get("contractId", ""),
        payload.get("joinAmount", 0),
    )
    # 상품 계약 이벤트 수신 시 RAG 인덱스 재빌드
    rag_engine: ProductRagEngine | None = getattr(app, "state", None) and getattr(app.state, "rag_engine", None)
    if rag_engine and settings.openai_api_key:
        await asyncio.get_event_loop().run_in_executor(None, _build_rag_index, rag_engine)


async def _handle_chatbot_message_received(payload: dict) -> None:
    """consultation.chatbot.message 토픽 수신 시 로그만 출력.

    DB 저장은 handle_message()의 _record_message()에서 이미 수행하므로
    Consumer에서 중복 저장하지 않는다.
    """
    logger.info(
        "[Kafka] ChatbotMessageReceived — chatbot_consultation_id=%s sequence_no=%s message=%s",
        payload.get("chatbot_consultation_id", ""),
        payload.get("sequence_no", ""),
        payload.get("message_content", ""),
    )


async def _kafka_consume_loop(consumer: KafkaEventConsumer) -> None:
    """카프카 이벤트를 수신해 비즈니스 로직을 처리하는 백그라운드 루프.

    처리 이벤트:
      - ContractCreated (deposit.contract.events): 고객 계약 완료 → RAG 재빌드
      - ChatbotMessageReceived (consultation.chatbot.message): 수신 로그 출력
      - 그 외 이벤트: 구조화된 로그 출력
    """
    try:
        async for message in consumer:
            event_type = message.get("eventType", "UNKNOWN")
            payload    = message.get("payload", {})

            if event_type == "ContractCreated":
                await _handle_contract_created(payload)
            elif event_type == "ChatbotMessageReceived":
                await _handle_chatbot_message_received(payload)
            else:
                logger.info("[Kafka] event=%s payload=%s", event_type, payload)

    except asyncio.CancelledError:
        pass
    except Exception as exc:
        logger.exception("[Kafka] consumer loop 오류: %s", exc)



def _build_rag_index(rag_engine: ProductRagEngine) -> None:
    """DB에서 상품 목록을 조회해 RAG 인덱스를 빌드한다. 오류 시 로그만 남기고 계속 진행."""
    from sqlalchemy.orm import Session as _Session
    try:
        with _Session(engine) as db:
            rows = db.execute(text(
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
                       description
                  FROM deposit_banking_products
                 WHERE is_sold = true
                 ORDER BY banking_product_id
                """
            )).mappings().all()
            products = [dict(r) for r in rows]
        rag_engine.build_from_db(products)
        logger.info("[RAG] 인덱스 빌드 완료: 상품 %d건", len(products))
    except Exception as exc:
        logger.warning("[RAG] 인덱스 빌드 실패 (서비스는 계속 동작): %s", exc)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # ── 싱글턴 생성 및 app.state 등록 ────────────────────────────────────────
    _events = KafkaEventPublisher(settings)
    _consumer = KafkaEventConsumer(settings)

    # RAG 엔진 초기화 (API 키 없으면 엔진은 생성되지만 인덱스 빌드 실패 → 자유질문 시 상담사 연결 폴백)
    _rag_engine = ProductRagEngine(OpenAIEmbeddingProvider(settings.openai_api_key or ""))
    if settings.openai_api_key:
        await asyncio.get_event_loop().run_in_executor(None, _build_rag_index, _rag_engine)
    else:
        logger.warning("[RAG] OPENAI_API_KEY 없음 — RAG 자유질문 비활성화")

    app.state.events = _events
    app.state.consumer = _consumer
    app.state.rag_engine = _rag_engine

    # ── 기동 ─────────────────────────────────────────────────────────────────
    Base.metadata.create_all(bind=engine)
    ensure_harness_audit_schema(engine)

    consume_task: asyncio.Task | None = None
    if settings.kafka_enabled:
        await _events.start()
        await _consumer.start(
            topics=[
                settings.kafka_topic_deposit_events,
                settings.kafka_topic_chatbot_message,
            ],
            group_id="consultation-service",
        )
        consume_task = asyncio.create_task(_kafka_consume_loop(_consumer))

    try:
        yield
    finally:
        if consume_task is not None:
            consume_task.cancel()
        if settings.kafka_enabled:
            await _consumer.stop()
            await _events.stop()


app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan)


_origins = [
    o.strip()
    for o in os.getenv(
        "ALLOWED_ORIGINS",
        "http://localhost:3000,http://localhost:3001",
    ).split(",")
    if o.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)
Instrumentator(excluded_handlers=["/metrics", "/health"]).instrument(app).expose(app)

if static_dir.exists():
    app.mount("/static", StaticFiles(directory=static_dir), name="static")


# ── 의존성 ──────────────────────────────────────────────────────────────────

def get_chatbot_service(
    request: Request,
    db: Session = Depends(get_db),
) -> ChatbotService:
    return ChatbotService(
        db,
        request.app.state.events,
        rag_engine=getattr(request.app.state, "rag_engine", None),
        openai_api_key=settings.openai_api_key or "",
        openai_model=settings.openai_model,
    )


def get_chat_service(
    request: Request,
    db: Session = Depends(get_db),
) -> ChatService:
    return ChatService(db, request.app.state.events)


# ── 공통 ────────────────────────────────────────────────────────────────────

@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


# ── 상담사 인증 ──────────────────────────────────────────────────────────────

@app.post(
    "/auth/agent/login",
    response_model=AgentLoginResponse,
    summary="상담사 로그인",
    description="login_id + password 로 상담사 인증을 수행합니다.",
)
def agent_login(
    request: AgentLoginRequest,
    db: Session = Depends(get_db),
) -> AgentLoginResponse:
    import bcrypt as _bcrypt
    from sqlalchemy import select

    employee = db.scalars(
        select(Employee)
        .where(Employee.login_id == request.login_id)
        .where(Employee.status == "ACTIVE")
    ).first()

    if not employee or not _bcrypt.checkpw(request.password.encode(), employee.password_hash.encode()):
        raise HTTPException(status_code=401, detail="아이디 또는 비밀번호가 올바르지 않습니다.")

    return AgentLoginResponse(
        employee_id=employee.employee_id,
        login_id=employee.login_id,
        name=employee.name,
        role=employee.role,
    )


# ── 상담원 계정 관리 ──────────────────────────────────────────────────────────

from app.models import Employee as _Employee
from sqlalchemy import select as _select

@app.get("/agents", summary="상담원 목록 조회")
def list_agents(http_request: Request, db: Session = Depends(get_db)) -> list[dict]:
    # 상담원 계정 목록은 login_id 까지 담고 있다. 열려 있으면 어느 아이디를 노릴지
    # 고르는 데 그대로 쓰인다.
    identity.require_employee(http_request)
    rows = db.scalars(_select(_Employee).order_by(_Employee.employee_id)).all()
    return [
        {
            "employee_id": r.employee_id,
            "login_id": r.login_id,
            "name": r.name,
            "role": r.role,
            "status": r.status,
            "created_at": r.created_at.isoformat() if r.created_at else None,
        }
        for r in rows
    ]


@app.post("/agents", status_code=201, summary="상담원 계정 생성")
def create_agent(body: dict, http_request: Request, db: Session = Depends(get_db)) -> dict:
    identity.require_employee(http_request)
    import bcrypt as _bcrypt2
    if db.scalars(_select(_Employee).where(_Employee.login_id == body["login_id"])).first():
        raise HTTPException(status_code=409, detail="이미 존재하는 login_id입니다.")
    pw_hash = _bcrypt2.hashpw(body["password"].encode(), _bcrypt2.gensalt()).decode()
    emp = _Employee(
        login_id=body["login_id"],
        password_hash=pw_hash,
        name=body["name"],
        role=body.get("role", "AGENT"),
        status="ACTIVE",
    )
    db.add(emp)
    db.commit()
    db.refresh(emp)
    return {"employee_id": emp.employee_id, "login_id": emp.login_id, "name": emp.name, "role": emp.role, "status": emp.status}


@app.patch("/agents/{employee_id}", summary="상담원 정보/비밀번호 수정")
def update_agent(employee_id: int, body: dict, http_request: Request,
                 db: Session = Depends(get_db)) -> dict:
    # 이 경로가 열려 있으면 아무나 남의 상담원 비밀번호를 바꿔 계정을 가져갈 수 있다.
    identity.require_employee(http_request)
    import bcrypt as _bcrypt2
    emp = db.get(_Employee, employee_id)
    if not emp:
        raise HTTPException(status_code=404, detail="상담원을 찾을 수 없습니다.")
    if "name" in body:
        emp.name = body["name"]
    if "role" in body:
        emp.role = body["role"]
    if "status" in body:
        emp.status = body["status"]
    if "password" in body and body["password"]:
        emp.password_hash = _bcrypt2.hashpw(body["password"].encode(), _bcrypt2.gensalt()).decode()
    db.commit()
    db.refresh(emp)
    return {"employee_id": emp.employee_id, "login_id": emp.login_id, "name": emp.name, "role": emp.role, "status": emp.status}


@app.delete("/agents/{employee_id}", status_code=204, summary="상담원 계정 비활성화")
def deactivate_agent(employee_id: int, http_request: Request,
                     db: Session = Depends(get_db)) -> None:
    identity.require_employee(http_request)
    emp = db.get(_Employee, employee_id)
    if not emp:
        raise HTTPException(status_code=404, detail="상담원을 찾을 수 없습니다.")
    emp.status = "INACTIVE"
    db.commit()


@app.get("/chat")
def chat_page() -> FileResponse:
    index = static_dir / "index.html"
    if not index.exists():
        raise HTTPException(status_code=404, detail="챗 UI를 찾을 수 없습니다.")
    return FileResponse(index)


# ── 챗봇 시나리오 ─────────────────────────────────────────────────────────────

@app.post("/chatbot/scenarios/default", response_model=ScenarioSeedResponse)
def seed_default_scenario(service: ChatbotService = Depends(get_chatbot_service)) -> ScenarioSeedResponse:
    scenario_id, first_node_id = service.seed_default_scenario()
    return ScenarioSeedResponse(scenario_id=scenario_id, first_node_id=first_node_id)


# ── 챗봇 기능 ─────────────────────────────────────────────────────────────────

@app.get("/chatbot/categories", response_model=list[ChatbotCategoryResponse])
def chatbot_categories(service: ChatbotService = Depends(get_chatbot_service)) -> list[ChatbotCategoryResponse]:
    return service.categories()


@app.get("/chatbot/features", response_model=list[ChatbotFeatureResponse])
def chatbot_features(service: ChatbotService = Depends(get_chatbot_service)) -> list[ChatbotFeatureResponse]:
    return service.features()


@app.get("/chatbot/features/{feature_code}", response_model=ChatbotFeatureResponse)
def chatbot_feature_detail(
    feature_code: str,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotFeatureResponse:
    feature = service.feature_detail(feature_code)
    if not feature:
        raise HTTPException(status_code=404, detail="챗봇 기능을 찾을 수 없습니다.")
    return feature


# ── 신뢰 경계 ────────────────────────────────────────────────────────────────
#
# 신원을 읽는 방법은 app/identity.py 한 곳에만 둔다. 엔드포인트마다 헤더를 파싱하게
# 두면 어딘가는 반드시 빠지고, 그 빠진 곳이 조용히 뚫린 채 남는다 — 실제로 이
# 서비스의 엔드포인트 24개 중 인증을 요구하던 것은 직원 기능 5종뿐이었다.

@app.post("/chatbot/features/{feature_code}/execute", response_model=ChatbotFeatureExecuteResponse)
def execute_chatbot_feature(
    feature_code: str,
    request: ChatbotFeatureExecuteRequest,
    http_request: Request,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotFeatureExecuteResponse:
    # 게이트웨이가 검증한 값으로 덮어쓴다. 검증되지 않았으면 None 을 넣어
    # body 로 실려 온 값을 확실히 버린다 — 여기서 대입을 건너뛰면 사칭이 되살아난다.
    #
    # 고객 기능 9종도 마찬가지다. 예전에는 body 의 customer_no 를 그대로 믿어
    # 남의 번호를 적으면 그 사람의 상품·계약·현금흐름이 나왔다. None 이면 기존
    # "본인 인증이 필요합니다" 경로로 떨어지므로, 익명 공개 기능은 그대로 돈다.
    request.staff_id = identity.employee_id(http_request)

    # 직원이면 body 의 customer_no 를 그대로 둔다. 직원 기능에서 그 값은 신원이
    # 아니라 **조회 대상**이다 — 남의 정보를 보는 것이 기능의 목적이고, 그래서
    # "누가 열람했는가"(staff_id)만 위조를 막으면 된다.
    #
    # 고객이면 반대다. 그때 customer_no 는 곧 신원이므로 검증된 값으로 덮어쓴다.
    # 이 구분을 놓치고 한쪽으로 통일하면, 직원 조회가 통째로 막히거나
    # 고객 사칭이 그대로 열린다.
    if request.staff_id is None:
        request.customer_no = identity.customer_no(http_request)

    result = service.execute_feature(feature_code, request)
    if result.status == "NOT_FOUND":
        raise HTTPException(status_code=404, detail=result.message)
    return result


# ── 챗봇 상담 흐름 ────────────────────────────────────────────────────────────

@app.post("/chatbot/consultations/start", response_model=ChatbotStartResponse)
async def start_chatbot(
    request: ChatbotStartRequest,
    http_request: Request,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotStartResponse:
    # 게이트웨이가 검증한 고객으로 덮어쓴다. body 값은 쓰지 않는다 — 예전에는
    # 남의 번호를 적으면 그 사람 이름으로 상담이 열렸다.
    #
    # 익명이면 빈 문자열로 시작한다. 상품 검색·비교는 로그인 없이 쓰는 기능이고,
    # 본인 확인이 필요한 기능은 각자 거절한다(기존 동작). 컬럼이 NOT NULL 이라
    # None 이 아니라 빈 문자열이어야 한다.
    request.customer_no = identity.customer_no(http_request) or ""
    try:
        response = await service.start(request.customer_no, request.entry_screen, request.app_version)
        chatbot_session_total.labels(entry_screen=request.entry_screen or "UNKNOWN").inc()
        chatbot_active_sessions.inc()
        return response
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


def _require_chatbot_owner(
    http_request: Request, chatbot_consultation_id: int, service: ChatbotService
) -> None:
    """이 챗봇 상담의 주인인가.

    익명으로 시작한 상담(customer_no 가 빈 값)은 주인이 없으므로 막지 않는다 —
    로그인 없이 상품을 물어보는 흐름이 그대로 돌아야 한다. 주인이 있는 상담만
    본인인지 확인한다.
    """
    from app.models import ChatbotConsultation, Consultation

    row = service.db.get(ChatbotConsultation, chatbot_consultation_id)
    if row is None:
        raise HTTPException(status_code=404, detail="챗봇 상담을 찾을 수 없습니다.")

    consultation = service.db.get(Consultation, row.consultation_id)
    owner = (getattr(consultation, "customer_no", None) or "").strip() if consultation else ""
    if not owner:
        return

    if str(owner) != str(identity.customer_no(http_request) or ""):
        raise HTTPException(status_code=403, detail="이 상담의 당사자가 아닙니다.")


@app.post("/chatbot/consultations/{chatbot_consultation_id}/messages", response_model=ChatbotMessageResponse)
async def send_chatbot_message(
    chatbot_consultation_id: int,
    request: ChatbotMessageRequest,
    http_request: Request,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotMessageResponse:
    # 상담 ID 는 순번이라 추측하기 쉽다. 소유자 확인이 없으면 남의 대화에
    # 이어붙이고 그 답변까지 받아 볼 수 있다.
    _require_chatbot_owner(http_request, chatbot_consultation_id, service)
    try:
        response = await service.handle_message(
            chatbot_consultation_id,
            request.message,
            request.button_value,
        )
        chatbot_message_total.labels(process_method=response.process_method).inc()
        if response.agent_transfer_required:
            chatbot_handoff_total.inc()
        return response
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post("/chatbot/transfer", response_model=ChatbotTransferResponse)
def chatbot_transfer(
    request: ChatbotTransferRequest,
    http_request: Request,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotTransferResponse:
    """챗봇 이체 — 실제로 돈이 나간다.

    <b>여기가 가장 위험했다.</b> 출금 계좌 소유권 검사는 있었지만
    ``WHERE account_id = :aid AND customer_id = :cno`` 의 ``cno`` 가 요청 body 에서
    온 값이었다. 즉 남의 고객번호를 적으면 그 사람 계좌가 조회되고, 검사는
    자기가 적은 값과 대조하므로 통과한다.

    게다가 core-banking 호출에 ``X-Customer-Id: req.customer_no`` 를 그대로 실어
    보내므로, 자금이동 경로의 소유권 검증까지 같은 위조 값을 근거로 판단하게 된다.
    보호 장치가 여러 겹 있었는데 전부 같은 거짓말을 입력으로 받고 있었다.

    이제 신원은 게이트웨이가 검증한 값으로만 정하고 body 값은 버린다.
    """
    request.customer_no = identity.require_customer(http_request)
    return service.execute_transfer(request)


# ── 상담사 채팅 ───────────────────────────────────────────────────────────────

def _to_chat_response(chat) -> ChatConsultationResponse:
    return ChatConsultationResponse(
        chat_consultation_id=chat.chat_consultation_id,
        consultation_id=chat.consultation_id,
        chatbot_consultation_id=chat.chatbot_consultation_id,
        status=_chat_status(chat),
        employee_id=chat.employee_id,
        agent_requested_at=chat.agent_requested_at,
        agent_connected_at=chat.agent_connected_at,
        chat_started_at=chat.chat_started_at,
        chat_ended_at=chat.chat_ended_at,
        active_yn=chat.active_yn,
        satisfaction_score=chat.satisfaction_score,
    )


@app.get(
    "/chat/history",
    response_model=list[ChatHistoryItem],
    summary="상담 이력 조회",
    description="종료/진행 중인 채팅 상담 이력을 반환합니다.",
)
def get_chat_history(
    http_request: Request,
    limit: int = 50,
    customer_no: str | None = None,
    service: ChatService = Depends(get_chat_service),
) -> list[ChatHistoryItem]:
    # customer_no 를 빼면 전 고객의 상담 이력이 나온다. 어드민 이력·통계 화면이
    # 그렇게 쓰므로 기능 자체는 남기되, 직원임을 확인한 뒤에만 연다.
    identity.require_employee(http_request)
    return service.get_history(limit=limit, customer_no=customer_no)


@app.post(
    "/chat/request",
    response_model=ChatRequestResponse,
    summary="고객 상담사 연결 요청",
    description="고객이 상담사와의 실시간 채팅을 요청합니다. WAITING 상태의 ChatConsultation을 생성합니다.",
)
def request_agent_chat(
    request: ChatRequestSchema,
    http_request: Request,
    service: ChatService = Depends(get_chat_service),
) -> ChatRequestResponse:
    # 남의 이름으로 상담사를 부를 수 있으면, 상담사는 엉뚱한 사람과 대화하며
    # 그 고객의 정보를 꺼내게 된다.
    #
    # 가드를 인자 안에 넣지 않는다 — 파이썬은 인자를 평가하기 전에 service 의
    # 속성을 먼저 찾으므로, 그쪽에서 나는 오류가 가드를 앞질러 버린다.
    customer_no = identity.require_customer(http_request)
    chat = service.request_agent_chat(customer_no)
    return ChatRequestResponse(
        chat_consultation_id=chat.chat_consultation_id,
        consultation_id=chat.consultation_id,
        status="WAITING",
        agent_requested_at=chat.agent_requested_at.isoformat() if chat.agent_requested_at else None,
    )


@app.get(
    "/chat/queue",
    response_model=list[AgentQueueResponse],
    summary="상담사 대기열 조회",
    description="수락 대기 중인 채팅 상담 목록을 반환합니다. (직원 전용)",
)
def get_agent_queue(http_request: Request,
                    service: ChatService = Depends(get_chat_service)) -> list[AgentQueueResponse]:
    # 설명에 "(직원 전용)" 이라고 적혀 있었지만 막혀 있지 않았다.
    identity.require_employee(http_request)
    return service.get_waiting_queue()


@app.get(
    "/chat/consultations/{chat_consultation_id}",
    response_model=ChatConsultationResponse,
    summary="채팅 상담 상태 조회",
)
def get_chat_consultation(
    chat_consultation_id: int,
    http_request: Request,
    service: ChatService = Depends(get_chat_service),
) -> ChatConsultationResponse:
    _require_chat_participant(http_request, chat_consultation_id, service)
    try:
        chat = service.get_consultation(chat_consultation_id)
        return _to_chat_response(chat)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post(
    "/chat/consultations/{chat_consultation_id}/connect",
    response_model=ChatConsultationResponse,
    summary="상담사 연결 수락",
    description="상담사가 대기 중인 상담을 수락합니다. Kafka: AgentConnected",
)
async def connect_agent(
    chat_consultation_id: int,
    request: AgentConnectRequest,
    http_request: Request,
    service: ChatService = Depends(get_chat_service),
) -> ChatConsultationResponse:
    identity.require_employee(http_request)
    try:
        chat = await service.connect_agent(chat_consultation_id, request.employee_id)
        return _to_chat_response(chat)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


def _require_chat_participant(
    http_request: Request, chat_consultation_id: int, service: ChatService
) -> str:
    """이 상담의 당사자인가. 상담사면 "AGENT", 본인 고객이면 "USER".

    <b>발신자 구분을 body 로 받으면 안 된다.</b> 예전에는 sender_type 을 그대로
    믿어서, 아무나 "AGENT" 라고 적으면 상담사 말풍선으로 대화에 끼어들 수 있었다.
    고객 입장에서는 은행 직원이 하는 말과 구별되지 않는다 — 계좌번호를 불러 달라고
    해도 의심할 이유가 없다.

    상담 ID 는 순번이라 추측하기도 쉽다. 그래서 "참여자인가" 를 따로 확인한다.
    """
    try:
        chat = service.get_consultation(chat_consultation_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    if identity.employee_id(http_request) is not None:
        return "AGENT"

    cno = identity.customer_no(http_request)
    owner = getattr(chat.consultation, "customer_no", None) if chat.consultation else None
    if cno is not None and owner is not None and str(owner) == str(cno):
        return "USER"

    raise HTTPException(
        status_code=403,
        detail="이 상담의 당사자가 아닙니다.",
    )


@app.post(
    "/chat/consultations/{chat_consultation_id}/messages",
    response_model=ChatMessageHistoryResponse,
    summary="채팅 메시지 전송",
    description="상담사(AGENT) 또는 고객(USER)이 메시지를 전송합니다. Kafka: ChatMessageSent",
)
async def send_chat_message(
    chat_consultation_id: int,
    request: ChatSendMessageRequest,
    http_request: Request,
    service: ChatService = Depends(get_chat_service),
) -> ChatMessageHistoryResponse:
    # 발신자를 body 가 아니라 검증된 신원으로 정한다.
    sender = _require_chat_participant(http_request, chat_consultation_id, service)
    sender_code = CODE_SENDER_AGENT if sender == "AGENT" else CODE_SENDER_USER
    try:
        msg = await service.send_message(chat_consultation_id, request.message, sender_code)
        return ChatMessageHistoryResponse(
            message_id=msg.chat_message_history_id,
            sender_type=request.sender_type,
            message=msg.message_content,
            sent_at=msg.created_at,
            read_yn=msg.read_yn,
        )
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.get(
    "/chat/consultations/{chat_consultation_id}/messages",
    response_model=list[ChatMessageHistoryResponse],
    summary="채팅 메시지 이력 조회",
    description="챗봇 메시지 + 상담사 메시지를 통합하여 시간 순으로 반환합니다.",
)
def get_chat_messages(
    chat_consultation_id: int,
    http_request: Request,
    service: ChatService = Depends(get_chat_service),
) -> list[ChatMessageHistoryResponse]:
    _require_chat_participant(http_request, chat_consultation_id, service)
    try:
        messages = service.get_messages(chat_consultation_id)
        return [
            ChatMessageHistoryResponse(
                message_id=m.chat_message_history_id,
                sender_type=_SENDER_LABEL.get(m.sender_type_code_id or 0, "UNKNOWN"),
                message=m.message_content,
                sent_at=m.created_at,
                read_yn=m.read_yn,
            )
            for m in messages
        ]
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post(
    "/chat/consultations/{chat_consultation_id}/end",
    response_model=ChatConsultationResponse,
    summary="상담 종료",
    description="상담을 종료하고 만족도를 기록합니다. Kafka: ChatEnded",
)
async def end_chat(
    chat_consultation_id: int,
    request: ChatEndRequest,
    http_request: Request,
    service: ChatService = Depends(get_chat_service),
) -> ChatConsultationResponse:
    # 남의 상담을 임의로 끊으면 대화가 중단되고 만족도까지 대신 남는다.
    _require_chat_participant(http_request, chat_consultation_id, service)
    try:
        chat = await service.end_chat(chat_consultation_id, request.satisfaction_score)
        chatbot_active_sessions.dec()
        chatbot_session_ended_total.inc()
        if request.satisfaction_score is not None:
            chatbot_satisfaction_score.observe(request.satisfaction_score)
        return _to_chat_response(chat)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


# ── 파일 분석 / 서류 제출 ─────────────────────────────────────────────────────

@app.post(
    "/chatbot/file/analyze",
    response_model=FileAnalyzeResponse,
    summary="파일 내용 분석 (타행 거래내역 / 약관 / 상품 설명서)",
    description="프론트엔드에서 PDF를 파싱한 텍스트를 받아 OpenAI로 분석 결과를 반환합니다.",
)
async def analyze_file(request: FileAnalyzeRequest) -> FileAnalyzeResponse:
    try:
        analyzer = OpenAIDocumentAnalyzer(settings.openai_api_key or "")
        result = analyzer.analyze(request.text, request.analyze_type)
        return FileAnalyzeResponse(analyze_type=request.analyze_type, result=result)
    except Exception as exc:
        logger.exception("[file/analyze] 분석 오류: %s", exc)
        raise HTTPException(status_code=500, detail="파일 분석 중 오류가 발생했습니다.") from exc


@app.post(
    "/chatbot/documents/upload",
    response_model=DocumentUploadResponse,
    summary="서류 제출 (비과세 서류 등)",
    description="고객이 서류를 업로드하면 서버에 저장하고 DB에 기록합니다.",
)
async def upload_document(
    http_request: Request,
    file: UploadFile = File(...),
    doc_type: str = Form(default="ENROLLMENT"),
    db: Session = Depends(get_db),
) -> DocumentUploadResponse:
    # 제출자를 폼 값으로 받으면 남의 계정 앞으로 서류를 올릴 수 있다.
    # 조회와 달리 흔적이 남는 동작이라 익명으로 물러설 자리가 없다.
    customer_no = identity.require_customer(http_request)
    # 허용 MIME 타입 및 확장자 검증
    ALLOWED_MIME = {"application/pdf", "image/jpeg", "image/png"}
    ALLOWED_EXT = {".pdf", ".jpg", ".jpeg", ".png"}
    MAX_SIZE_BYTES = 10 * 1024 * 1024  # 10MB

    ext = FilePath(file.filename or "document").suffix.lower()
    if ext not in ALLOWED_EXT:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail=f"허용되지 않는 파일 형식입니다. (허용: PDF, JPG, PNG)")
    if file.content_type and file.content_type not in ALLOWED_MIME:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail=f"허용되지 않는 MIME 타입입니다.")

    uploads_dir = FilePath(os.getenv("UPLOADS_DIR", "./uploads"))
    uploads_dir.mkdir(parents=True, exist_ok=True)

    stored_name = f"{uuid.uuid4()}{ext}"
    stored_path = uploads_dir / stored_name

    contents = await file.read()
    if len(contents) > MAX_SIZE_BYTES:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail="파일 크기가 10MB를 초과합니다.")
    stored_path.write_bytes(contents)

    doc = ChatbotDocument(
        customer_no=customer_no,
        original_filename=file.filename or "document",
        stored_path=str(stored_path),
        doc_type=doc_type,
        file_size_bytes=len(contents),
        status="UPLOADED",
    )
    db.add(doc)
    db.commit()
    db.refresh(doc)

    return DocumentUploadResponse(
        document_id=doc.document_id,
        filename=doc.original_filename,
        doc_type=doc.doc_type,
        status=doc.status,
        message="서류가 성공적으로 제출되었습니다. 영업일 기준 1~3일 내 처리될 예정입니다.",
    )
