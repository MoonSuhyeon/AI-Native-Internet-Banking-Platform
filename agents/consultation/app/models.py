from datetime import datetime

from sqlalchemy import BigInteger, Boolean, DateTime, Float, ForeignKey, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base

ID_TYPE = BigInteger().with_variant(Integer, "sqlite")


class AuditMixin:
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    created_by: Mapped[int | None] = mapped_column(BigInteger)
    updated_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
    updated_by: Mapped[int | None] = mapped_column(BigInteger)


class Consultation(AuditMixin, Base):
    __tablename__ = "consultation"

    consultation_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    customer_no: Mapped[str] = mapped_column(String(30), nullable=False)
    reception_method_code_id: Mapped[int | None] = mapped_column(BigInteger)
    inquiry_type_code_id: Mapped[int | None] = mapped_column(BigInteger)
    reception_channel_code_id: Mapped[int | None] = mapped_column(BigInteger)
    content_summary: Mapped[str | None] = mapped_column(String(200))
    status_code_id: Mapped[int | None] = mapped_column(BigInteger)
    answer_summary: Mapped[str | None] = mapped_column(String(200))
    consulted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    previous_consultation_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("consultation.consultation_id")
    )
    active_yn: Mapped[str] = mapped_column(String(1), default="Y", nullable=False)


class ChatbotScenario(AuditMixin, Base):
    __tablename__ = "chatbot_scenario"

    scenario_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    scenario_name: Mapped[str] = mapped_column(String(100), nullable=False)
    scenario_desc: Mapped[str | None] = mapped_column(String(500))
    scenario_type_code_id: Mapped[int | None] = mapped_column(BigInteger)
    consultation_category_code_id: Mapped[int | None] = mapped_column(BigInteger)
    reception_channel_code_id: Mapped[int | None] = mapped_column(BigInteger)
    test_yn: Mapped[str] = mapped_column(String(1), default="N", nullable=False)
    active_yn: Mapped[str] = mapped_column(String(1), default="Y", nullable=False)
    nodes: Mapped[list["ChatbotNode"]] = relationship(back_populates="scenario")


class ChatbotIntent(AuditMixin, Base):
    __tablename__ = "chatbot_intent"

    intent_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    fallback_intent_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("chatbot_intent.intent_id"))
    scenario_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("chatbot_scenario.scenario_id"))
    intent_name: Mapped[str] = mapped_column(String(100), nullable=False)
    intent_desc: Mapped[str | None] = mapped_column(String(500))
    process_method_code_id: Mapped[int | None] = mapped_column(BigInteger)
    confidence_threshold: Mapped[int | None] = mapped_column(Integer)
    priority: Mapped[int | None] = mapped_column(Integer)
    test_yn: Mapped[str] = mapped_column(String(1), default="N", nullable=False)
    active_yn: Mapped[str] = mapped_column(String(1), default="Y", nullable=False)


class ChatbotNode(AuditMixin, Base):
    __tablename__ = "chatbot_node"

    node_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    next_node_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("chatbot_node.node_id"))
    scenario_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("chatbot_scenario.scenario_id"))
    node_type_code_id: Mapped[int | None] = mapped_column(BigInteger)
    node_name: Mapped[str] = mapped_column(String(100), nullable=False)
    response_message: Mapped[str] = mapped_column(Text, nullable=False)
    condition_expression: Mapped[str | None] = mapped_column(Text)
    error_move_node_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("chatbot_node.node_id"))
    timeout_seconds: Mapped[int | None] = mapped_column(Integer)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    exposure_count: Mapped[int | None] = mapped_column(Integer)
    active_yn: Mapped[str] = mapped_column(String(1), default="Y", nullable=False)
    scenario: Mapped[ChatbotScenario] = relationship(back_populates="nodes")
    buttons: Mapped[list["ChatbotNodeButton"]] = relationship(back_populates="node")


class ChatbotNodeButton(AuditMixin, Base):
    __tablename__ = "chatbot_node_button"

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    node_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("chatbot_node.node_id"), nullable=False)
    button_text: Mapped[str] = mapped_column(String(50), nullable=False)
    button_value: Mapped[str] = mapped_column(String(20), nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    active_yn: Mapped[str] = mapped_column(String(1), default="Y", nullable=False)
    node: Mapped[ChatbotNode] = relationship(back_populates="buttons")


class ChatbotNodeFlow(AuditMixin, Base):
    __tablename__ = "chatbot_node_flow"

    current_node_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("chatbot_node.node_id"), primary_key=True
    )
    next_node_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("chatbot_node.node_id"), primary_key=True
    )
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False)
    chatbot_flow_type_cd: Mapped[str] = mapped_column(String(20), nullable=False)
    branch_criteria_cd: Mapped[str | None] = mapped_column(String(20))
    branch_value: Mapped[str | None] = mapped_column(String(50))
    active_yn: Mapped[str] = mapped_column(String(1), default="Y", nullable=False)


class ChatbotConsultation(AuditMixin, Base):
    __tablename__ = "chatbot_consultation"

    chatbot_consultation_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    consultation_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("consultation.consultation_id"))
    scenario_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("chatbot_scenario.scenario_id"))
    intent_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("chatbot_intent.intent_id"))
    process_method_code_id: Mapped[int | None] = mapped_column(BigInteger)
    initial_intent: Mapped[str | None] = mapped_column(String(100))
    entry_screen: Mapped[str | None] = mapped_column(String(50))
    app_version: Mapped[str | None] = mapped_column(String(20))
    session_started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())
    session_ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    total_turn_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    resolved_yn: Mapped[str] = mapped_column(String(1), default="N", nullable=False)
    agent_connected_yn: Mapped[str] = mapped_column(String(1), default="N", nullable=False)
    end_type_code_id: Mapped[int | None] = mapped_column(BigInteger)
    error_occurred_yn: Mapped[str] = mapped_column(String(1), default="N", nullable=False)
    consultation: Mapped[Consultation] = relationship()


class ChatbotGoalSession(Base):
    """저축목표 에이전트 멀티턴 세션 상태 (DB 영속화, 재시작 후에도 유지)."""
    __tablename__ = "chatbot_goal_session"

    chatbot_consultation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("chatbot_consultation.chatbot_consultation_id"), primary_key=True
    )
    stage: Mapped[str] = mapped_column(String(20), nullable=False)
    goal_amount: Mapped[float] = mapped_column(Float, nullable=False)
    goal_months: Mapped[int] = mapped_column(Integer, nullable=False)
    customer_no: Mapped[str | None] = mapped_column(String(30))
    monthly_surplus: Mapped[float | None] = mapped_column(Float)
    monthly_payment: Mapped[float | None] = mapped_column(Float)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class ChatConsultation(AuditMixin, Base):
    __tablename__ = "chat_consultation"

    chat_consultation_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    consultation_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("consultation.consultation_id"))
    chatbot_consultation_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("chatbot_consultation.chatbot_consultation_id")
    )
    employee_id: Mapped[int | None] = mapped_column(BigInteger)
    agent_requested_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    agent_connected_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    waiting_seconds: Mapped[int | None] = mapped_column(Integer)
    waiting_abandoned_yn: Mapped[str] = mapped_column(String(1), default="N", nullable=False)
    waiting_abandoned_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    chat_started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    chat_ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    chat_seconds: Mapped[int | None] = mapped_column(Integer)
    concurrent_chat_count: Mapped[int | None] = mapped_column(Integer)
    reassignment_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    total_turn_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    end_type_code_id: Mapped[int | None] = mapped_column(BigInteger)
    agent_talk_seconds: Mapped[int | None] = mapped_column(Integer)
    satisfaction_score: Mapped[int | None] = mapped_column(Integer)
    active_yn: Mapped[str] = mapped_column(String(1), default="Y", nullable=False)


class ChatMessageHistory(AuditMixin, Base):
    __tablename__ = "chat_message_history"

    chat_message_history_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    chat_consultation_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("chat_consultation.chat_consultation_id")
    )
    chatbot_consultation_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("chatbot_consultation.chatbot_consultation_id")
    )
    node_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("chatbot_node.node_id"))
    sequence_no: Mapped[int] = mapped_column(Integer, nullable=False)
    sender_type_code_id: Mapped[int | None] = mapped_column(BigInteger)
    message_type_code_id: Mapped[int | None] = mapped_column(BigInteger)
    message_content: Mapped[str] = mapped_column(Text, nullable=False)
    button_value: Mapped[str | None] = mapped_column(String(100))
    confidence_score: Mapped[int | None] = mapped_column(Integer)
    process_method_code_id: Mapped[int | None] = mapped_column(BigInteger)
    response_time_ms: Mapped[int | None] = mapped_column(Integer)
    sentiment_result_code_id: Mapped[int | None] = mapped_column(BigInteger)
    error_type_code_id: Mapped[int | None] = mapped_column(BigInteger)
    read_yn: Mapped[str] = mapped_column(String(1), default="N", nullable=False)
    read_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Employee(Base):
    __tablename__ = "employees"

    employee_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    login_id: Mapped[str] = mapped_column(String(50), nullable=False, unique=True)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    role: Mapped[str] = mapped_column(String(20), nullable=False, default="AGENT")
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="ACTIVE")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class ChatbotDocument(AuditMixin, Base):
    __tablename__ = "chatbot_document"

    document_id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    customer_no: Mapped[str] = mapped_column(String(30), nullable=False)
    original_filename: Mapped[str] = mapped_column(String(255), nullable=False)
    stored_path: Mapped[str] = mapped_column(String(500), nullable=False)
    doc_type: Mapped[str] = mapped_column(String(50), nullable=False)
    file_size_bytes: Mapped[int | None] = mapped_column(BigInteger)
    status: Mapped[str] = mapped_column(String(20), default="UPLOADED", nullable=False)
