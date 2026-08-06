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

# ── 하네스 감사 ───────────────────────────────────────────────────────────────
# 감사 기록은 fail-soft 다 — 실패해도 고객 응답을 막지 않는다. 그래서 지금까지 남는
# 것은 로그 한 줄뿐이었고, 감사가 조용히 멈춰도 알아챌 방법이 없었다.
#
# decision_kind 로 나누는 것은 의도적이다. 실행 확정(ACTION_EXECUTION)이 안 남는 것과
# 판단 시도(DECISION)가 안 남는 것은 심각도가 다르다 — 전자는 "무엇을 했는지"의 기록이
# 사라진 것이다.
harness_audit_failure_total = Counter(
    "harness_audit_failure_total",
    "하네스 감사 기록 실패 수",
    ["agent", "decision_kind"],
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
