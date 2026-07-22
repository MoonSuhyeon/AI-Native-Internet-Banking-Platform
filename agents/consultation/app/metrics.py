from prometheus_client import Counter, Gauge, Histogram

# ── Session ───────────────────────────────────────────────────────────────────
chatbot_session_total = Counter(
    "chatbot_session_total",
    "챗봇 상담 세션 시작 수",
    ["entry_screen"],
)
chatbot_active_sessions = Gauge(
    "chatbot_active_sessions",
    "현재 활성 챗봇 세션 수",
)
chatbot_session_ended_total = Counter(
    "chatbot_session_ended_total",
    "챗봇 상담 세션 종료 수",
)

# ── Message routing ───────────────────────────────────────────────────────────
chatbot_message_total = Counter(
    "chatbot_message_total",
    "챗봇 메시지 처리 수",
    ["process_method"],
)
chatbot_handoff_total = Counter(
    "chatbot_handoff_total",
    "상담사 이관 요청 수",
)

# ── Satisfaction ──────────────────────────────────────────────────────────────
chatbot_satisfaction_score = Histogram(
    "chatbot_satisfaction_score",
    "챗봇 상담 만족도 점수 분포",
    buckets=[1, 2, 3, 4, 5],
)

# ── LLM ───────────────────────────────────────────────────────────────────────
chatbot_llm_duration_seconds = Histogram(
    "chatbot_llm_duration_seconds",
    "LLM 응답 시간 (초)",
    ["method"],
    buckets=[0.5, 1.0, 2.0, 5.0, 10.0, 30.0],
)
chatbot_llm_error_total = Counter(
    "chatbot_llm_error_total",
    "LLM 호출 오류 수",
    ["method"],
)

# ── LLM 토큰 사용량 ───────────────────────────────────────────────────────────
chatbot_llm_prompt_tokens = Histogram(
    "chatbot_llm_prompt_tokens",
    "LLM 호출당 입력 토큰 수",
    ["method"],
    buckets=[50, 100, 200, 300, 500, 1000, 2000],
)
chatbot_llm_completion_tokens = Histogram(
    "chatbot_llm_completion_tokens",
    "LLM 호출당 출력 토큰 수",
    ["method"],
    buckets=[50, 100, 200, 300, 500],
)

# ── Fallback ──────────────────────────────────────────────────────────────────
chatbot_fallback_total = Counter(
    "chatbot_fallback_total",
    "LLM 없이 상담사 이관 안내로 대체된 응답 수 (LlmHandoffAdapter)",
)

# ── Kafka ─────────────────────────────────────────────────────────────────────
chatbot_kafka_publish_total = Counter(
    "chatbot_kafka_publish_total",
    "Kafka 이벤트 발행 수",
    ["topic", "status"],
)
chatbot_kafka_consume_total = Counter(
    "chatbot_kafka_consume_total",
    "Kafka 이벤트 소비 수",
    ["event_type", "status"],
)

# ── 레이블 사전 초기화 (rate() 정상 동작 보장) ────────────────────────────────
# 레이블 있는 Counter는 첫 .inc() 전까지 /metrics에 미노출 → Prometheus가 기준값 0을
# 못 보고 rate()가 항상 0을 반환하는 문제 방지
for _screen in ["WEB_PERSONAL", "CHAT", "HOME", "PRODUCT_TAB", "TRANSFER_TAB", "UNKNOWN"]:
    chatbot_session_total.labels(entry_screen=_screen)

for _method in [
    "SCENARIO", "STAFF_REQUEST",
    "FEATURE_PRODUCT_GUIDE", "FEATURE_RATE_GUIDE", "FEATURE_JOIN_CONDITION",
    "FEATURE_PRODUCT_COMPARE", "FEATURE_TERMS_RAG", "FEATURE_CASH_FLOW_RECOMMEND",
    "FEATURE_FAQ", "FEATURE_MY_ACCOUNTS", "FEATURE_INTEREST_HISTORY", "FEATURE_SAVINGS_GOAL",
]:
    chatbot_message_total.labels(process_method=_method)
