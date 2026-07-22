"""도메인 모델 (pydantic v2).

corpus_registry.md §16-2(2축 가설 모델)·§16-5(종료조건)·§12(책임 등급)에 대응하는
데이터 구조. 모든 런타임 상태는 ``AgentState`` 한 곳에 모이며 LangGraph state로 쓰인다.

설계 원칙 (CLAUDE.md):
- 가설은 2축으로 분리한다. 공격 시나리오(배타·신뢰도 경쟁)와 부가 태그(공존·on/off).
- 결정적 사실(사망·후견)은 ``AgentState.decisive_fact`` 에 박히면 LLM 판단과 무관하게
  즉시 종료(fail-closed)를 강제한다.
- 도구 선택 이유는 ``tool_log`` 에 매번 남긴다(설명가능성·감사·재현).
"""

from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field


# --------------------------------------------------------------------------- #
# 가설 축 1 · 공격 시나리오 (배타 — 신뢰도 경쟁, 합 ≈ 1)
# --------------------------------------------------------------------------- #
class AttackScenario(str, Enum):
    """§16-2 축 1. 상호배타적이라 신뢰도가 합 ≈ 1 로 경쟁한다."""

    H1_VOICE_PHISHING = "H1_VOICE_PHISHING"      # 보이스피싱 — 고객이 조종당해 송금
    H2_ACCOUNT_TAKEOVER = "H2_ACCOUNT_TAKEOVER"  # 계정탈취 — 탈취자가 송금
    H3_LAUNDERING = "H3_LAUNDERING"              # 자금세탁 — 구조화·다단 경유
    H4_INSIDER = "H4_INSIDER"                    # 내부자 부정 — 직원 권한 오남용
    H5_BENIGN = "H5_BENIGN"                      # 정상(오탐) — 의심 해소


# --------------------------------------------------------------------------- #
# 가설 축 2 · 부가 태그 (공존 — 독립 on/off)
# --------------------------------------------------------------------------- #
class Tag(str, Enum):
    """§16-2 축 2. 시나리오와 독립적으로 켜지는 정황 플래그."""

    T1_MULE = "T1_MULE"                # 머니뮬
    T2_ORGANIZED = "T2_ORGANIZED"      # 조직 연관
    T3_NEW_ACCOUNT = "T3_NEW_ACCOUNT"  # 신규개설계좌


# --------------------------------------------------------------------------- #
# 책임 등급 (§12-1)
# --------------------------------------------------------------------------- #
class LiabilityGrade(str, Enum):
    """§12-1 책임 등급 척도. 권고 우선순위의 근거."""

    L4 = "L4"  # 권리자 적격성 위반 — 은행 직접 배상 (최상위)
    L3 = "L3"  # 법규 위반 제재 — AML·실명법
    L2 = "L2"  # 권한·동의 누락 — 절차로 회복 가능
    L1 = "L1"  # 소비자보호 — 사후 분쟁·과태료
    L0 = "L0"  # 규정 무관 — 이상도 점수만


class DecisiveFactKind(str, Enum):
    """fail-closed 를 유발하는 확정 사실의 종류 (§16-5 1번)."""

    DEATH = "DEATH"              # 사망계좌
    GUARDIANSHIP = "GUARDIANSHIP"  # 성년후견 개시


class ActionType(str, Enum):
    """권고 동작. 에이전트는 *제안*만 하고 실행은 HITL+RBAC (CLAUDE.md 원칙 3)."""

    FREEZE_PAYMENT = "FREEZE_PAYMENT"  # 지급정지
    FILE_STR = "FILE_STR"              # STR 보고
    ESCALATE = "ESCALATE"              # 분석가 에스컬레이션
    NONE = "NONE"                      # 조치 불요(오탐)


class RecommendationStatus(str, Enum):
    """조사 종료 유형 (§16-5)."""

    CONFIRMED = "CONFIRMED"      # 시나리오 max ≥0.75 확정
    FAIL_CLOSED = "FAIL_CLOSED"  # 결정적 사실(사망·후견) — 예산·가설 무관 즉시 종료
    PROVISIONAL = "PROVISIONAL"  # 예산 소진 + 중간 신뢰 → 잠정 가설+미확인 항목(fail-soft)
    HOLD = "HOLD"                # 예산 소진 + 전부 낮음 → 판단 보류+수집 증거
    BENIGN = "BENIGN"            # 정상(오탐) 결론


# --------------------------------------------------------------------------- #
# 입력 · 증거 · 출력
# --------------------------------------------------------------------------- #
class TxContext(BaseModel):
    """알림이 가리키는 거래 맥락."""

    amount: int                       # 금액(원)
    payee: str | None = None          # 수취인
    time: datetime | None = None      # 거래 시각
    channel: str | None = None        # 채널(모바일·ATM·인터넷 등)


class Alert(BaseModel):
    """트리아지 큐 최상단에서 내려온 조사 대상 알림 (에이전트 입력)."""

    id: str
    account: str
    customer_id: str
    tx_context: TxContext
    anomaly_score: float = 0.0        # FDS 이상도 점수


class Evidence(BaseModel):
    """도구 실행으로 얻은 증거 한 조각."""

    tool: str                         # 어느 도구가 만들었나
    signal: str                       # 사람이 읽는 신호 요약
    raw: dict = Field(default_factory=dict)  # 도구 원응답
    ts: datetime = Field(default_factory=datetime.now)


class DecisiveFact(BaseModel):
    """확정 사실. 박히면 fail-closed 로 즉시 종료 (LLM 판단 우회)."""

    kind: DecisiveFactKind
    source: str                       # 근거 도구/출처
    detail: str | None = None


class ToolResult(BaseModel):
    """조사 도구 1회 호출의 표준 출력 (§16-3).

    조회 전용·부작용 없음. ``decisive_fact`` 가 채워지면 게이트가 fail-closed 한다.
    ``to_evidence()`` 로 AgentState.evidence 에 넣을 Evidence 로 변환한다.
    """

    tool: str
    signal: str                       # 사람이 읽는 신호 요약
    data: dict = Field(default_factory=dict)  # 도구 원응답(구조화)
    decisive_fact: DecisiveFact | None = None
    ts: datetime = Field(default_factory=datetime.now)

    def to_evidence(self) -> "Evidence":
        return Evidence(tool=self.tool, signal=self.signal, raw=self.data, ts=self.ts)


class ToolLogEntry(BaseModel):
    """도구 선택 1회의 감사 기록 (CLAUDE.md 원칙 2: 선택 이유 매번 로그)."""

    tool: str
    reason: str                       # 왜 이 도구를 골랐나 (Matrix 참고 + LLM 판단)
    ts: datetime = Field(default_factory=datetime.now)


class ProposedAction(BaseModel):
    """권고 동작 제안. 실행 아님 — HITL 승인 대상."""

    type: ActionType
    target: str | None = None
    reason: str | None = None


class Recommendation(BaseModel):
    """조사 종료 후 분석가에게 전달하는 권고 (§16-1 R 노드 산출)."""

    scenario: AttackScenario          # 최종 우세 가설(경합 축). fail-closed 면 미확정이라 헤드라인 근거 아님
    status: RecommendationStatus      # 종료 유형 (§16-5)
    tags: list[Tag] = Field(default_factory=list)
    rationale_chain: list[str] = Field(default_factory=list)  # 근거 사슬
    liability_grade: LiabilityGrade
    actions: list[ProposedAction] = Field(default_factory=list)
    decisive_fact: DecisiveFact | None = None  # 사망·후견 등 결정적 사실(fail-closed 헤드라인 근거)


# --------------------------------------------------------------------------- #
# LangGraph state — 모든 런타임 상태의 단일 출처
# --------------------------------------------------------------------------- #
class AgentState(BaseModel):
    """§16-1 루프가 단계 간 공유하는 상태.

    ``scenarios`` 는 합 ≈ 1 로 경쟁하고, ``tags`` 는 독립 on/off.
    ``decisive_fact`` 가 채워지면 게이트는 예산과 무관하게 즉시 종료한다(§16-5 1번).
    """

    alert: Alert
    scenarios: dict[AttackScenario, float] = Field(default_factory=dict)  # 합 ≈ 1
    tags: dict[Tag, bool] = Field(default_factory=dict)
    evidence: list[Evidence] = Field(default_factory=list)
    budget_left: int = 6              # 조사 예산 상한 (§16-5 3번, CLAUDE.md 원칙 4)
    decisive_fact: DecisiveFact | None = None
    tool_log: list[ToolLogEntry] = Field(default_factory=list)
    closed_scenarios: list[AttackScenario] = Field(default_factory=list)  # ≤0.15 닫힌 후보 (§16-5 2번)
    recommendation: Recommendation | None = None  # recommend 노드 산출 (§16-1 R)

    # HITL — recommend 다음 interrupt 로 사람 승인 대기 (원칙 3)
    hitl_approved: bool = False
    actor_roles: list[str] = Field(default_factory=list)  # 승인자 RBAC 역할
    executed_actions: list[str] = Field(default_factory=list)  # 실행 결과(목)


# --------------------------------------------------------------------------- #
# 목 케이스 — PoC 데이터 원천 (data/cases/*.json)
# --------------------------------------------------------------------------- #
class Case(BaseModel):
    """시나리오별 목 케이스. alert + 도구별 응답을 담는다.

    조회 도구(tools.py)는 실서비스라면 Java `/internal` API 를 칠 자리에서
    이 케이스의 ``tool_responses`` 를 읽어 ToolResult 로 감싼다(Stage 6까지 목).
    """

    name: str
    description: str | None = None
    alert: Alert
    tool_responses: dict[str, dict] = Field(default_factory=dict)
