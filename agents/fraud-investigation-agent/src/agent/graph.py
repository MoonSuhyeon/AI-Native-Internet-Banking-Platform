"""LangGraph 루프 — §16-1 7노드 + §16-5 게이트 + HITL interrupt.

노드: hypothesize → plan → act → observe → (gate) → plan(루프백) / recommend
      → [interrupt: 사람 승인] → execute_action.

게이트 우선순위 (§16-5, 위에서부터):
1. state.decisive_fact 있으면(조사 중 get_party 가 사망·후견 반환) → 즉시 recommend.
   **예산·가설 무관 fail-closed** (원칙 1).
2. 시나리오 max ≥0.75 → recommend(확정). ≤0.15 후보는 observe 에서 closed_scenarios 로 닫음.
3. budget_left == 0 → recommend(예산 소진). 잠정/보류 구분은 recommend 가 한다(fail-soft).
4. 그 외 → plan 루프백.

HITL: recommend 다음에 ``interrupt_before=["execute_action"]`` 로 멈춰 사람 승인을 기다린다.
승인(+RBAC 통과) 시에만 execute_action 이 동작을 실행(목)한다. 에이전트는 권고까지만(원칙 3).
"""

from __future__ import annotations

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from . import hypotheses
from . import tools as tools_mod
from .llm import LLMClient, get_llm_client
from .models import ActionType, AgentState, Case
from .planner import plan_next_tool
from .recommend import build_recommendation
from .tool_matrix import TOOL_MATRIX
from .tracing import trace_node

CONFIRM_THRESHOLD = 0.75
CLOSE_THRESHOLD = 0.15
ACCOUNT_TOOLS = {"get_device_fingerprint", "get_related_accounts"}

# 동작별 필요 RBAC 역할 (목). 실서비스면 BankRole·hasAnyRole 게이팅.
_REQUIRED_ROLE = "FRAUD_OFFICER"
_GATED_ACTIONS = {ActionType.FREEZE_PAYMENT, ActionType.FILE_STR}


def build_graph(llm: LLMClient, case: Case, matrix: dict | None = None):
    """7노드 그래프 + execute_action 을 컴파일. recommend 뒤 HITL interrupt."""
    matrix = matrix or TOOL_MATRIX

    def hypothesize(state: AgentState) -> dict:
        return {
            "scenarios": hypotheses.init_scenarios(),
            "tags": hypotheses.init_tags(),
        }

    def plan(state: AgentState) -> dict:
        # 선택 이유를 tool_log 에 기록(원칙 2). 예산 차감.
        with trace_node("plan", state):
            plan_next_tool(state, llm, matrix)
            return {"tool_log": list(state.tool_log), "budget_left": state.budget_left - 1}

    def act(state: AgentState) -> dict:
        with trace_node("act", state):
            tool = state.tool_log[-1].tool
            fn = tools_mod.TOOLS[tool]
            ident = state.alert.account if tool in ACCOUNT_TOOLS else state.alert.customer_id
            result = fn(case, ident)
            updates: dict = {"evidence": state.evidence + [result.to_evidence()]}
            if result.decisive_fact:  # 결정적 사실은 게이트가 가로챔 (fail-closed)
                updates["decisive_fact"] = result.decisive_fact
            return updates

    def observe(state: AgentState) -> dict:
        with trace_node("observe", state):
            scenarios, tags = hypotheses.observe(state)
            # §16-5 2번: ≤0.15 후보를 닫는다(추가 도구를 안 씀). 분포는 미변경.
            closed = [s for s, v in scenarios.items() if v <= CLOSE_THRESHOLD]
            return {"scenarios": scenarios, "tags": tags, "closed_scenarios": closed}

    def gate(state: AgentState) -> str:
        if state.decisive_fact:  # 1. fail-closed (예산·가설 무관)
            return "recommend"
        if state.scenarios and max(state.scenarios.values()) >= CONFIRM_THRESHOLD:
            return "recommend"  # 2. 확정
        if state.budget_left <= 0:
            return "recommend"  # 3. 예산 소진 (잠정/보류는 recommend 에서)
        return "plan"  # 4. 경합 → 루프백

    def recommend(state: AgentState) -> dict:
        rationale = llm.generate_recommendation(state)
        return {"recommendation": build_recommendation(state, rationale)}

    def execute_action(state: AgentState) -> dict:
        """승인+RBAC 통과 시에만 동작 실행 (목). 에이전트가 직접 안 함(원칙 3)."""
        if not state.hitl_approved:
            return {"executed_actions": ["거부됨: HITL 미승인 — 권고까지만"]}
        rec = state.recommendation
        if rec is None:
            return {"executed_actions": []}
        done: list[str] = []
        for a in rec.actions:
            if a.type in _GATED_ACTIONS and _REQUIRED_ROLE not in state.actor_roles:
                done.append(f"거부됨(RBAC): {a.type.value} — 필요 역할 {_REQUIRED_ROLE}")
                continue
            if a.type == ActionType.NONE:
                continue
            # 실서비스: FDS BLOCK / STR 보고 API 호출 자리. PoC 는 목.
            done.append(f"실행(목): {a.type.value}")
        return {"executed_actions": done}

    g = StateGraph(AgentState)
    g.add_node("hypothesize", hypothesize)
    g.add_node("plan", plan)
    g.add_node("act", act)
    g.add_node("observe", observe)
    g.add_node("recommend", recommend)
    g.add_node("execute_action", execute_action)

    g.set_entry_point("hypothesize")
    g.add_edge("hypothesize", "plan")
    g.add_edge("plan", "act")
    g.add_edge("act", "observe")
    g.add_conditional_edges("observe", gate, {"plan": "plan", "recommend": "recommend"})
    g.add_edge("recommend", "execute_action")
    g.add_edge("execute_action", END)

    # recommend 직후 멈춰 사람 승인 대기 (HITL). 체크포인터 필요.
    return g.compile(checkpointer=MemorySaver(), interrupt_before=["execute_action"])


def _as_state(values) -> AgentState:
    return values if isinstance(values, AgentState) else AgentState.model_validate(values)


def _config(thread_id: str) -> dict:
    return {"configurable": {"thread_id": thread_id}, "recursion_limit": 50}


def investigate(
    case: Case,
    llm: LLMClient | None = None,
    budget: int | None = None,
    thread_id: str | None = None,
):
    """그래프를 권고까지 돌리고 HITL interrupt 에서 멈춘다.

    반환: (graph, config, state) — state.recommendation 은 준비되고 동작은 미실행.
    """
    llm = llm or get_llm_client()
    graph = build_graph(llm, case)
    config = _config(thread_id or f"inv-{case.alert.id}")
    init = AgentState(alert=case.alert)
    if budget is not None:
        init.budget_left = budget
    graph.invoke(init, config)  # execute_action 직전에서 정지
    return graph, config, _as_state(graph.get_state(config).values)


def run_investigation(
    case: Case,
    llm: LLMClient | None = None,
    budget: int | None = None,
) -> AgentState:
    """권고까지 돌린 최종 상태 반환 (동작은 HITL 보류 — 에이전트는 권고까지만)."""
    _, _, state = investigate(case, llm=llm, budget=budget)
    return state


def approve_and_execute(graph, config, actor_roles: list[str], approved: bool = True) -> AgentState:
    """사람 승인 후 재개. 승인 + RBAC 통과 시에만 execute_action 이 동작(목) 실행."""
    graph.update_state(config, {"hitl_approved": approved, "actor_roles": actor_roles})
    graph.invoke(None, config)  # interrupt 지점부터 재개 → execute_action
    return _as_state(graph.get_state(config).values)
