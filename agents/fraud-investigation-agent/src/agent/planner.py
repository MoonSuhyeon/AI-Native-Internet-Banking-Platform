"""Planner — 조사 계획 노드 (§16-1 P, §16-4 혼합형).

``plan_next_tool`` 은 Tool Matrix 를 LLM 에 **프롬프트 컨텍스트로 넘겨** 다음 도구를
고르게 하고, 반환된 ``{tool, reason}`` 의 **이유를 반드시 tool_log 에 기록**한다
(CLAUDE.md 원칙 2: 설명가능성·감사·재현).

여기서 실제 API 를 직접 치지 않는다 — LLMClient(기본 MockLLMClient)에 위임하며
Stage 6까지 목만 동작한다(원칙 5).
"""

from __future__ import annotations

from .llm import LLMClient
from .hypotheses import CLOSE_THRESHOLD
from .models import AgentState, AttackScenario, ToolLogEntry
from .tool_matrix import TOOL_MATRIX, render_matrix


def plan_next_tool(
    state: AgentState,
    llm: LLMClient,
    matrix: dict | None = None,
) -> str:
    """다음 도구를 고르고 선택 이유를 tool_log 에 남긴 뒤 도구 이름을 반환.

    matrix 는 LLM 에 프롬프트 컨텍스트로 전달된다(render_matrix 로 렌더 가능).
    """
    matrix = matrix or TOOL_MATRIX
    # render_matrix(matrix) 가 실제 클라이언트의 프롬프트 컨텍스트가 된다.
    decision = llm.select_next_tool(state, matrix)

    tool = decision["tool"]
    reason = decision["reason"]
    # 선택 이유는 예외 없이 기록 (감사 구멍 방지).
    #
    # 경합 후보와 켜진 태그를 같이 남긴다. reason 은 LLM 이 쓴 문장이라 그것만으로는
    # 타당했는지 확인할 수 없다 — 무엇을 두고 고른 것인지가 있어야 사후에 채점된다.
    state.tool_log.append(
        ToolLogEntry(
            tool=tool,
            reason=reason,
            competing=competing_scenarios(state),
            tags_on=[t for t, on in (state.tags or {}).items() if on],
        )
    )
    return tool


def competing_scenarios(state: AgentState) -> list[AttackScenario]:
    """아직 경합 중인 시나리오. 닫힌 후보(≤0.15)는 뺀다.

    분포가 아직 없으면(첫 계획) 전부 경합으로 본다 — 균등 사전분포이므로 맞다.
    """
    if not state.scenarios:
        return list(AttackScenario)
    return [s for s, v in state.scenarios.items() if v > CLOSE_THRESHOLD]


__all__ = ["competing_scenarios", "plan_next_tool", "render_matrix"]
