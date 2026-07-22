from datetime import datetime

from pydantic import BaseModel, Field


# ──────────────────────────────────────────────────────────────────────────────
# 챗봇 상담
# ──────────────────────────────────────────────────────────────────────────────

class ChatbotStartRequest(BaseModel):
    customer_no: str = Field(default="CUST001")
    entry_screen: str = Field(default="HOME")
    app_version: str = Field(default="0.1.0")
    initial_message: str | None = None


class ButtonResponse(BaseModel):
    id: int
    text: str
    value: str


class ChatbotStartResponse(BaseModel):
    consultation_id: int
    chatbot_consultation_id: int
    node_id: int
    message: str
    buttons: list[ButtonResponse] = Field(default_factory=list)


class ChatbotMessageRequest(BaseModel):
    message: str = Field(default="")
    button_value: str | None = None


class ChatbotMessageResponse(BaseModel):
    consultation_id: int
    chatbot_consultation_id: int
    node_id: int
    message: str
    buttons: list[ButtonResponse] = Field(default_factory=list)
    process_method: str = "SCENARIO"
    agent_transfer_required: bool = False
    feature_code: str | None = None
    feature_data: list[dict] = Field(default_factory=list)


class ScenarioSeedResponse(BaseModel):
    scenario_id: int
    first_node_id: int


# ──────────────────────────────────────────────────────────────────────────────
# 챗봇 기능 (카테고리 / 피처 / 실행)
# ──────────────────────────────────────────────────────────────────────────────

class ChatbotCategoryResponse(BaseModel):
    code: str
    name: str
    description: str
    features: list[str] = Field(default_factory=list)


class ChatbotFeatureResponse(BaseModel):
    code: str
    category_code: str
    name: str
    summary: str
    sample_questions: list[str] = Field(default_factory=list)
    api_status: str


class ChatbotFeatureExecuteRequest(BaseModel):
    customer_no: str | None = None
    query: str | None = None
    product_id: int | None = None
    compare_product_ids: list[int] = Field(default_factory=list)
    staff_id: str | None = None
    chatbot_consultation_id: int | None = None
    # PRODUCT_SEARCH 전용
    amount: float | None = None
    period: int | None = None
    product_type: str | None = None   # DEPOSIT / SAVINGS / SUBSCRIPTION
    purpose: str | None = None        # lump_sum / monthly / subscription


class ChatbotFeatureExecuteResponse(BaseModel):
    feature_code: str
    status: str
    message: str
    data: list[dict] = Field(default_factory=list)
    requires_auth: bool = False
    requires_staff_auth: bool = False


# ──────────────────────────────────────────────────────────────────────────────
# 상담사 채팅 (인간 상담원)
# ──────────────────────────────────────────────────────────────────────────────

class AgentConnectRequest(BaseModel):
    """상담사가 대기 중인 상담을 수락할 때."""
    employee_id: int


class ChatSendMessageRequest(BaseModel):
    """상담사 또는 고객이 메시지를 전송할 때."""
    message: str
    sender_type: str = Field(default="AGENT", description="AGENT | USER")


class ChatEndRequest(BaseModel):
    """상담 종료 요청."""
    satisfaction_score: int | None = Field(default=None, ge=1, le=5)


class AgentQueueResponse(BaseModel):
    """상담사 대기열 항목."""
    chat_consultation_id: int
    consultation_id: int
    customer_no: str
    chatbot_consultation_id: int | None = None
    waiting_since: datetime | None = None


class ChatConsultationResponse(BaseModel):
    """채팅 상담 상태 응답."""
    chat_consultation_id: int
    consultation_id: int
    chatbot_consultation_id: int | None = None
    status: str  # WAITING | CONNECTED | ENDED
    employee_id: int | None = None
    agent_requested_at: datetime | None = None
    agent_connected_at: datetime | None = None
    chat_started_at: datetime | None = None
    chat_ended_at: datetime | None = None
    active_yn: str
    satisfaction_score: int | None = None


class ChatMessageHistoryResponse(BaseModel):
    """채팅 메시지 이력 항목."""
    message_id: int
    sender_type: str  # USER | BOT | AGENT
    message: str
    sent_at: datetime | None = None
    read_yn: str = "N"


class ChatbotTransferRequest(BaseModel):
    customer_no: str
    from_account_id: int
    to_account_number: str
    amount: int
    memo: str = "이체"


class ChatbotTransferResponse(BaseModel):
    status: str          # OK | ERROR
    message: str
    transaction_id: int | None = None
    balance_after: int | None = None


# ──────────────────────────────────────────────────────────────────────────────
# 파일 분석 / 서류 제출
# ──────────────────────────────────────────────────────────────────────────────

class FileAnalyzeRequest(BaseModel):
    text: str = Field(..., description="프론트엔드에서 PDF를 파싱한 텍스트")
    analyze_type: str = Field(..., description="CASH_FLOW | TERMS | PRODUCT")
    customer_no: str | None = None


class FileAnalyzeResponse(BaseModel):
    analyze_type: str
    result: str


class DocumentUploadResponse(BaseModel):
    document_id: int
    filename: str
    doc_type: str
    status: str
    message: str


# ──────────────────────────────────────────────────────────────────────────────
# 상담사 인증
# ──────────────────────────────────────────────────────────────────────────────

class AgentLoginRequest(BaseModel):
    login_id: str
    password: str


class AgentLoginResponse(BaseModel):
    employee_id: int
    login_id: str
    name: str
    role: str


class ChatHistoryItem(BaseModel):
    chat_consultation_id: int
    consultation_id: int
    customer_no: str
    employee_id: int | None = None
    status: str
    agent_requested_at: datetime | None = None
    agent_connected_at: datetime | None = None
    chat_ended_at: datetime | None = None
    satisfaction_score: int | None = None
    message_count: int = 0


class ChatRequestSchema(BaseModel):
    customer_no: str


class ChatRequestResponse(BaseModel):
    chat_consultation_id: int
    consultation_id: int
    status: str
    agent_requested_at: str | None
