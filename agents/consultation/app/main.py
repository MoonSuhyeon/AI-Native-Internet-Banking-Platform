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
def list_agents(db: Session = Depends(get_db)) -> list[dict]:
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
def create_agent(body: dict, db: Session = Depends(get_db)) -> dict:
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
def update_agent(employee_id: int, body: dict, db: Session = Depends(get_db)) -> dict:
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
def deactivate_agent(employee_id: int, db: Session = Depends(get_db)) -> None:
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


def _extract_staff_id_from_token(authorization: str | None) -> str | None:
    """Authorization: Bearer 헤더에서 staff_id(employee_id)를 추출한다.

    서명 검증은 API Gateway(Java)에서 담당하므로 여기서는 페이로드 디코딩만 수행.
    클라이언트가 body로 전달한 staff_id 대신 이 값을 사용해 사칭을 방지한다.
    """
    import base64
    import json

    if not authorization or not authorization.startswith("Bearer "):
        return None
    parts = authorization[7:].split(".")
    if len(parts) < 2:
        return None
    try:
        padding = "=" * (-len(parts[1]) % 4)
        payload = json.loads(base64.b64decode(parts[1] + padding).decode())
        sid = (
            payload.get("staff_id")
            or payload.get("staffId")
            or payload.get("employee_id")
            or payload.get("employeeId")
            or payload.get("sub")
        )
        return str(sid) if sid is not None else None
    except Exception:
        return None


@app.post("/chatbot/features/{feature_code}/execute", response_model=ChatbotFeatureExecuteResponse)
def execute_chatbot_feature(
    feature_code: str,
    request: ChatbotFeatureExecuteRequest,
    http_request: Request,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotFeatureExecuteResponse:
    # staff_id를 클라이언트 body 값 대신 JWT 토큰에서 추출해 사칭을 방지한다.
    token_staff_id = _extract_staff_id_from_token(http_request.headers.get("Authorization"))
    if token_staff_id is not None:
        request.staff_id = token_staff_id
    result = service.execute_feature(feature_code, request)
    if result.status == "NOT_FOUND":
        raise HTTPException(status_code=404, detail=result.message)
    return result


# ── 챗봇 상담 흐름 ────────────────────────────────────────────────────────────

@app.post("/chatbot/consultations/start", response_model=ChatbotStartResponse)
async def start_chatbot(
    request: ChatbotStartRequest,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotStartResponse:
    # TODO: JWT 토큰 기반 인증 미들웨어 도입 후
    #       토큰에서 추출한 customer_no 와 request.customer_no 일치 여부를 검증해야 함.
    #       현재는 body 의 customer_no 를 그대로 사용하므로 IDOR 취약점 존재.
    #       ex) Depends(require_customer_matches(request.customer_no))
    try:
        response = await service.start(request.customer_no, request.entry_screen, request.app_version)
        chatbot_session_total.labels(entry_screen=request.entry_screen or "UNKNOWN").inc()
        chatbot_active_sessions.inc()
        return response
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/chatbot/consultations/{chatbot_consultation_id}/messages", response_model=ChatbotMessageResponse)
async def send_chatbot_message(
    chatbot_consultation_id: int,
    request: ChatbotMessageRequest,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotMessageResponse:
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
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotTransferResponse:
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
    limit: int = 50,
    customer_no: str | None = None,
    service: ChatService = Depends(get_chat_service),
) -> list[ChatHistoryItem]:
    return service.get_history(limit=limit, customer_no=customer_no)


@app.post(
    "/chat/request",
    response_model=ChatRequestResponse,
    summary="고객 상담사 연결 요청",
    description="고객이 상담사와의 실시간 채팅을 요청합니다. WAITING 상태의 ChatConsultation을 생성합니다.",
)
def request_agent_chat(
    request: ChatRequestSchema,
    service: ChatService = Depends(get_chat_service),
) -> ChatRequestResponse:
    chat = service.request_agent_chat(request.customer_no)
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
def get_agent_queue(service: ChatService = Depends(get_chat_service)) -> list[AgentQueueResponse]:
    return service.get_waiting_queue()


@app.get(
    "/chat/consultations/{chat_consultation_id}",
    response_model=ChatConsultationResponse,
    summary="채팅 상담 상태 조회",
)
def get_chat_consultation(
    chat_consultation_id: int,
    service: ChatService = Depends(get_chat_service),
) -> ChatConsultationResponse:
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
    service: ChatService = Depends(get_chat_service),
) -> ChatConsultationResponse:
    try:
        chat = await service.connect_agent(chat_consultation_id, request.employee_id)
        return _to_chat_response(chat)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post(
    "/chat/consultations/{chat_consultation_id}/messages",
    response_model=ChatMessageHistoryResponse,
    summary="채팅 메시지 전송",
    description="상담사(AGENT) 또는 고객(USER)이 메시지를 전송합니다. Kafka: ChatMessageSent",
)
async def send_chat_message(
    chat_consultation_id: int,
    request: ChatSendMessageRequest,
    service: ChatService = Depends(get_chat_service),
) -> ChatMessageHistoryResponse:
    sender_code = CODE_SENDER_AGENT if request.sender_type == "AGENT" else CODE_SENDER_USER
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
    service: ChatService = Depends(get_chat_service),
) -> list[ChatMessageHistoryResponse]:
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
    service: ChatService = Depends(get_chat_service),
) -> ChatConsultationResponse:
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
    file: UploadFile = File(...),
    customer_no: str = Form(...),
    doc_type: str = Form(default="ENROLLMENT"),
    db: Session = Depends(get_db),
) -> DocumentUploadResponse:
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
