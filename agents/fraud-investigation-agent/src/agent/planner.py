"""Planner — 조사 계획 노드 (§16-1 P, §16-4 혼합형).

``plan_next_tool`` 은 Tool Matrix 를 LLM 에 **프롬프트 컨텍스트로 넘겨** 다음 도구를
고르게 하고, 반환된 ``{tool, reason}`` 의 **이유를 반드시 tool_log 에 기록**한다
(CLAUDE.md 원칙 2: 설명가능성·감사·재현).

여기서 실제 API 를 직접 치지 않는다 — LLMClient(기본 MockLLMClient)에 위임하며
Stage 6까지 목만 동작한다(원칙 5).
"""

from __future__ import annotations

from .llm import LLMClient
from .models import AgentState, ToolLogEntry
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
    # 선택 이유는 예외 없이 기록 (감사 구멍 방지)
    state.tool_log.append(ToolLogEntry(tool=tool, reason=reason))
    return tool


__all__ = ["plan_next_tool", "render_matrix"]
