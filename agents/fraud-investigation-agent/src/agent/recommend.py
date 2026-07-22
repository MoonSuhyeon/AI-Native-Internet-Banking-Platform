"""권고 생성 — 최종 가설 + 근거 사슬 + 책임 등급 + 종료 유형 (§16-1 R, §16-5, §12).

종료된 AgentState 를 받아 Recommendation 을 만든다:
- scenario: 우세 시나리오 (결정적 사실이 있으면 그 사실이 우선)
- status: §16-5 종료 유형 (CONFIRMED / FAIL_CLOSED / PROVISIONAL / HOLD / BENIGN)
- rationale_chain: 도구 선택 이유 + 증거 신호를 엮은 근거 사슬 (감사용)
- liability_grade: §12 책임 등급 매핑 (결정적 사실 → L4)
- actions: ProposedAction 목록 — **제안만**, 실행은 HITL+RBAC (원칙 3)
"""

from __future__ import annotations

from .models import (
    ActionType,
    AgentState,
    AttackScenario,
    LiabilityGrade,
    ProposedAction,
    Recommendation,
    RecommendationStatus,
    Tag,
)

CONFIRM = 0.75
PROVISIONAL_FLOOR = 0.4  # 이 미만이면 판단 보류(HOLD)

# 시나리오 → 책임 등급 (§12). 결정적 사실은 별도로 L4 강제.
_GRADE_BY_SCENARIO: dict[AttackScenario, LiabilityGrade] = {
    AttackScenario.H1_VOICE_PHISHING: LiabilityGrade.L3,   # STR 대상·피해 책임
    AttackScenario.H3_LAUNDERING: LiabilityGrade.L3,       # AML 제재
    AttackScenario.H4_INSIDER: LiabilityGrade.L3,          # 내부통제·형사
    AttackScenario.H2_ACCOUNT_TAKEOVER: LiabilityGrade.L2,  # 권한 하자, 절차 회복
    AttackScenario.H5_BENIGN: LiabilityGrade.L0,           # 규정 무관
}


def _top(state: AgentState) -> tuple[AttackScenario, float]:
    if not state.scenarios:
        return AttackScenario.H5_BENIGN, 0.0
    top = max(state.scenarios, key=state.scenarios.get)
    return top, state.scenarios[top]


def _classify(state: AgentState, top: AttackScenario, maxv: float) -> RecommendationStatus:
    """§16-5 종료 유형 판정."""
    if state.decisive_fact:
        return RecommendationStatus.FAIL_CLOSED                # 1. 예산·가설 무관
    if maxv >= CONFIRM:
        return (
            RecommendationStatus.BENIGN
            if top == AttackScenario.H5_BENIGN
            else RecommendationStatus.CONFIRMED                # 2. 확정
        )
    # 여기까지 왔으면 게이트가 예산 소진으로 보냄 (§16-5 3)
    if top == AttackScenario.H5_BENIGN and maxv >= PROVISIONAL_FLOOR:
        return RecommendationStatus.BENIGN                     # 정상이 우세 → 오탐 결론
    if maxv < PROVISIONAL_FLOOR:
        return RecommendationStatus.HOLD                       # 전부 낮음 → 판단 보류
    return RecommendationStatus.PROVISIONAL                    # 중간 신뢰 → 잠정(fail-soft)


def _rationale_chain(state: AgentState, rationale_text: str) -> list[str]:
    chain: list[str] = []
    for i, log in enumerate(state.tool_log):
        chain.append(f"선택: {log.tool} — {log.reason}")
        if i < len(state.evidence):
            chain.append(f"증거: {state.evidence[i].signal}")
    if rationale_text:
        chain.append(f"요약: {rationale_text}")
    return chain


def _open_candidates(state: AgentState) -> list[str]:
    """아직 닫히지 않은(>0.15) 경합 시나리오 — 잠정 권고의 '미확인 항목'."""
    return [
        f"{s.value}={v:.2f}"
        for s, v in sorted(state.scenarios.items(), key=lambda kv: -kv[1])
        if s not in state.closed_scenarios
    ]


def _actions(grade: LiabilityGrade, scenario: AttackScenario) -> list[ProposedAction]:
    # 전부 *제안*. 실제 발동은 HITL 승인 + RBAC (원칙 3).
    if grade in (LiabilityGrade.L4, LiabilityGrade.L3):
        acts = [
            ProposedAction(type=ActionType.FREEZE_PAYMENT, reason="고위험 — 지급정지 검토"),
            ProposedAction(type=ActionType.ESCALATE, reason="분석가 승인 필요(HITL)"),
        ]
        if scenario in (AttackScenario.H1_VOICE_PHISHING, AttackScenario.H3_LAUNDERING):
            acts.insert(1, ProposedAction(type=ActionType.FILE_STR, reason="STR 검토"))
        return acts
    if grade == LiabilityGrade.L2:
        return [ProposedAction(type=ActionType.ESCALATE, reason="권한 하자 — 분석가 확인")]
    return [ProposedAction(type=ActionType.NONE, reason="조치 불요(오탐)")]


def build_recommendation(state: AgentState, rationale_text: str = "") -> Recommendation:
    top, maxv = _top(state)
    status = _classify(state, top, maxv)
    chain = _rationale_chain(state, rationale_text)
    tags: list[Tag] = [t for t, on in state.tags.items() if on]

    # 1. fail-closed: 확정 사실은 LLM 가설과 무관하게 L4 + 지급정지 제안.
    #    경합 가설(top)은 미확정이라 헤드라인 근거가 아니다 — decisive_fact 를 따로 실어
    #    소비자(프론트)가 "사망/후견"을 헤드라인으로 쓰게 한다.
    if status == RecommendationStatus.FAIL_CLOSED:
        return Recommendation(
            scenario=top,
            status=status,
            tags=tags,
            decisive_fact=state.decisive_fact,
            rationale_chain=chain
            + [f"결정적 사실: {state.decisive_fact.kind.value} (fail-closed, 예산 무관)"],
            liability_grade=LiabilityGrade.L4,
            actions=[
                ProposedAction(
                    type=ActionType.FREEZE_PAYMENT,
                    reason=f"{state.decisive_fact.kind.value} 확정 — 즉시 차단 권고",
                ),
                ProposedAction(type=ActionType.ESCALATE, reason="HITL 승인 필요"),
            ],
        )

    grade = _GRADE_BY_SCENARIO.get(top, LiabilityGrade.L0)

    # 3. 예산 소진 — fail-soft: 빈손이 아니라 부분결과를 넘긴다
    if status == RecommendationStatus.PROVISIONAL:
        chain.append("잠정(fail-soft): 예산 소진 — 확정 못 함")
        chain.append("미확인 경합: " + ", ".join(_open_candidates(state)))
    elif status == RecommendationStatus.HOLD:
        chain.append("판단 보류: 예산 소진 + 전부 낮은 신뢰 — 수집 증거만 인계")

    return Recommendation(
        scenario=top,
        status=status,
        tags=tags,
        rationale_chain=chain,
        liability_grade=grade,
        actions=_actions(grade, top),
    )
