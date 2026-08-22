"""조사 에이전트 성능 지표.

**무엇을 재는가.** "에이전트가 잘 하고 있는가"는 응답 속도가 아니라
**사람이 그 권고를 받아들이는가**로 드러난다. 이 에이전트는 HITL 이라 분석가가
승인/거부를 명시적으로 누르므로, 그 결과가 곧 채택률이다.

레포의 다른 에이전트도 같은 축을 쓴다 — auto-loan-review 의
``ai_agent_disagreement_total`` 은 심사역 결정이 AI 권고와 갈린 비율이다.
이름은 서비스별 접두사를 붙이는 집 스타일을 따른다(chatbot_*, ai_agent_*).

**왜 status 를 라벨로 두는가.** 종료 유형(CONFIRMED/HOLD/PROVISIONAL/...)마다
채택률이 다른 것이 정상이다. 확정 건은 대부분 승인되고 보류 건은 갈린다.
합쳐 놓으면 "채택률이 떨어졌다"가 품질 저하인지 사건 구성이 바뀐 것인지 구분되지 않는다.

라벨에는 사건 ID·분석가 ID 를 넣지 않는다 — 카디널리티가 터지고, 개인 단위 추적은
지표가 아니라 감사로그(harness_audit_log)가 할 일이다.
"""

from prometheus_client import Counter, Histogram

# ── 조사 실행 ────────────────────────────────────────────────────────────────
fraud_investigation_total = Counter(
    "fraud_investigation_total",
    "조사 실행 수",
    ["status"],  # RecommendationStatus: CONFIRMED / FAIL_CLOSED / PROVISIONAL / HOLD / BENIGN
)
fraud_investigation_duration_seconds = Histogram(
    "fraud_investigation_duration_seconds",
    "조사 루프 소요 시간 (초)",
    buckets=[0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0],
)
fraud_investigation_failed_total = Counter(
    "fraud_investigation_failed_total",
    "조사 루프가 예외로 끝난 수",
)

# ── 도구 호출 ────────────────────────────────────────────────────────────────
fraud_tool_calls_total = Counter(
    "fraud_tool_calls_total",
    "조사 중 호출한 도구 수",
    ["tool"],
)

# ── HITL 채택 ────────────────────────────────────────────────────────────────
#
# 이 서비스의 핵심 지표다. 채택률 = approved / (approved + rejected).
fraud_recommendation_reviewed_total = Counter(
    "fraud_recommendation_reviewed_total",
    "분석가가 판단한 권고 수 (HITL)",
    ["status", "decision"],  # decision: approved | rejected
)

# 승인했지만 권한이 없어 실행되지 못한 동작. 채택률만 보면 안 보이는 구멍이다 —
# "승인됐는데 아무 일도 안 일어남"이 조용히 지나가지 않게 따로 센다.
fraud_action_blocked_total = Counter(
    "fraud_action_blocked_total",
    "승인됐으나 권한 부족으로 차단된 동작 수",
    ["action_type"],
)


def observe_recommendation(status: str, approved: bool) -> None:
    """HITL 판단 결과를 기록한다."""
    fraud_recommendation_reviewed_total.labels(
        status=status, decision="approved" if approved else "rejected"
    ).inc()


# ── 소비자 안내 ──────────────────────────────────────────────────────────────
#
# **왜 이 둘을 재는가.** 안내는 실패해도 조용히 규칙 결과로 내려간다 — 화면이
# 깨지지 않게 일부러 그렇게 만들었다. 그래서 계측이 없으면 맞춤이 사실상 하나도
# 안 되고 있어도 아무도 모른다. 조용한 실패는 재야 보인다.
#
# audience 는 값의 종류가 다섯이라 라벨로 안전하다(카디널리티). 고객 ID 는 넣지
# 않는다 — 개인 단위 추적은 지표가 아니라 감사로그가 할 일이다.
fraud_guidance_total = Counter(
    "fraud_guidance_total",
    "고객 안내 생성 수",
    ["audience", "refined"],  # audience: SENIOR/YOUNG_ADULT/GENERAL/MINOR/UNKNOWN
)
fraud_guidance_audience_lookup_failed_total = Counter(
    "fraud_guidance_audience_lookup_failed_total",
    "연령대 조회에 실패해 UNKNOWN 으로 내려간 수",
)


def record_guidance(audience: str, refined: bool) -> None:
    """안내 한 건. ``refined`` 가 false 면 규칙만으로 만든 것이다."""
    fraud_guidance_total.labels(audience=audience, refined=str(refined).lower()).inc()
