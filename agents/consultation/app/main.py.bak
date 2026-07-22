import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import Base, engine, get_db
from app.kafka import KafkaEventConsumer, KafkaEventPublisher
from app.llm import LlmAdapter, LlmHandoffAdapter
from app.schemas import (
    AgentConnectRequest,
    AgentQueueResponse,
    ChatbotCategoryResponse,
    ChatbotFeatureExecuteRequest,
    ChatbotFeatureExecuteResponse,
    ChatbotFeatureResponse,
    ChatbotMessageRequest,
    ChatbotMessageResponse,
    ChatbotStartRequest,
    ChatbotStartResponse,
    ChatConsultationResponse,
    ChatEndRequest,
    ChatMessageHistoryResponse,
    ChatSendMessageRequest,
    ScenarioSeedResponse,
)
from app.rag import OpenAIEmbeddingProvider, ProductRagEngine
from app.services import ChatbotService, ChatService, _chat_status, _SENDER_LABEL

logger = logging.getLogger(__name__)

settings = get_settings()
events = KafkaEventPublisher(settings)
consumer = KafkaEventConsumer(settings)
llm = LlmHandoffAdapter()
llm_adapter = LlmAdapter(api_key=settings.openai_api_key, model=settings.openai_model) if settings.openai_api_key else None
static_dir = Path(__file__).resolve().parents[1] / "static"

# RAG 엔진: OpenAI API 키가 있을 때만 활성화
rag_engine: ProductRagEngine | None = (
    ProductRagEngine(OpenAIEmbeddingProvider(settings.openai_api_key))
    if settings.openai_api_key else None
)


async def _handle_contract_created(payload: dict) -> None:
    """deposit-api 에서 ContractCreated 이벤트 수신 시 처리.

    고객이 상품에 가입했음을 기록하고, RAG 인덱스가 살아있으면 재빌드를 예약한다.
    (같은 DB를 공유하므로 신규 계약 데이터가 즉시 조회 가능)
    """
    customer_id  = payload.get("customerId", "")
    product_id   = payload.get("productId", "")
    contract_id  = payload.get("contractId", "")
    join_amount  = payload.get("joinAmount", 0)

    logger.info(
        "[Kafka] ContractCreated 처리 — customer=%s product=%s contract=%s amount=%s",
        customer_id, product_id, contract_id, join_amount,
    )

    # RAG 인덱스 재빌드: 신규 상품 데이터 반영
    if rag_engine is not None:
        await _build_rag_index()
        logger.info("[Kafka] RAG 인덱스 재빌드 완료 (ContractCreated 트리거)")


async def _kafka_consume_loop() -> None:
    """카프카 이벤트를 수신해 비즈니스 로직을 처리하는 백그라운드 루프.

    처리 이벤트:
      - ContractCreated (deposit.contract.events): 고객 계약 완료 → RAG 재빌드
      - 그 외 consultation 이벤트: 구조화된 로그 출력
    """
    try:
        async for message in consumer:
            event_type = message.get("eventType", "UNKNOWN")
            payload    = message.get("payload", {})

            if event_type == "ContractCreated":
                await _handle_contract_created(payload)
            else:
                logger.info("[Kafka] event=%s payload=%s", event_type, payload)

    except asyncio.CancelledError:
        pass
    except Exception as exc:
        logger.exception("[Kafka] consumer loop 오류: %s", exc)


async def _build_rag_index() -> None:
    """DB에서 상품 + 약관 데이터를 읽어 RAG 인덱스를 빌드한다."""
    if rag_engine is None:
        logger.info("[RAG] OpenAI API 키 없음 → RAG 비활성화")
        return

    from sqlalchemy import text as sa_text
    from app.database import SessionLocal

    db = SessionLocal()
    try:
        products = [
            dict(row._mapping)
            for row in db.execute(sa_text(
                "SELECT * FROM deposit_banking_products WHERE deposit_product_status = 'SELLING'"
            ))
        ]
        terms = [
            dict(row._mapping)
            for row in db.execute(sa_text(
                "SELECT * FROM deposit_special_terms WHERE status = 'ACTIVE'"
            ))
        ]
        rag_engine.build_from_db(products, terms)
        logger.info("[RAG] 인덱스 빌드 완료: 상품 %d개, 약관 %d개", len(products), len(terms))
    except Exception as exc:
        logger.warning("[RAG] 인덱스 빌드 실패 (DB 미연결 등): %s", exc)
    finally:
        db.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    await events.start()
    await consumer.start(
        topics=[
            settings.kafka_topic_chatbot_events,
            settings.kafka_topic_chat_events,
            settings.kafka_topic_deposit_events,   # deposit-api 계약 이벤트 수신
        ],
        group_id="consultation-service",
    )
    consume_task = asyncio.create_task(_kafka_consume_loop())
    await _build_rag_index()
    try:
        yield
    finally:
        consume_task.cancel()
        await consumer.stop()
        await events.stop()


app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan)

if static_dir.exists():
    app.mount("/static", StaticFiles(directory=static_dir), name="static")


# ── 의존성 ──────────────────────────────────────────────────────────────────

def get_chatbot_service(db: Session = Depends(get_db)) -> ChatbotService:
    return ChatbotService(db, events, llm, llm_adapter, rag_engine)


def get_chat_service(db: Session = Depends(get_db)) -> ChatService:
    return ChatService(db, events)


# ── 공통 ────────────────────────────────────────────────────────────────────

@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


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


@app.post("/chatbot/features/{feature_code}/execute", response_model=ChatbotFeatureExecuteResponse)
def execute_chatbot_feature(
    feature_code: str,
    request: ChatbotFeatureExecuteRequest,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotFeatureExecuteResponse:
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
    try:
        return await service.start(request.customer_no, request.entry_screen, request.app_version)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/chatbot/consultations/{chatbot_consultation_id}/messages", response_model=ChatbotMessageResponse)
async def send_chatbot_message(
    chatbot_consultation_id: int,
    request: ChatbotMessageRequest,
    service: ChatbotService = Depends(get_chatbot_service),
) -> ChatbotMessageResponse:
    try:
        return await service.handle_message(
            chatbot_consultation_id,
            request.message,
            request.button_value,
        )
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


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
    "/chat/queue",
    response_model=list[AgentQueueResponse],
    summary="상담사 대기열 조회",
    description="수락 대기 중인 채팅 상담 목록을 반환합니다. (직원 전용)",
)
def get_agent_queue(service: ChatService = Depends(get_chat_service)) -> list[AgentQueueResponse]:
    return service.get_waiting_queue()


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
    sender_code = 3 if request.sender_type == "AGENT" else 1
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
        return _to_chat_response(chat)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
