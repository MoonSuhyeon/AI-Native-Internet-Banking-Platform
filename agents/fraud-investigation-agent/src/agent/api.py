"""HTTP 경계 — Investigation Agent 를 어드민 콘솔(web/admin)에 노출하는 FastAPI 사이드카.

CLI(run_investigation.py)와 **동일한 빌딩블록**(hypotheses·planner·tools·recommend)을
같은 순서로 호출해 조사 루프를 한 번 펼치고, rich 렌더링 대신 **구조화 JSON**(단계별
분포·도구·이유·게이트 + 최종 권고)을 돌려준다. 프론트는 이 트레이스를 그대로 그린다.

엔드포인트:
  GET  /api/cases               — data/cases/*.json 목록(알림 요약)
  POST /api/investigate         — 한 사건 조사 → 트레이스 + 권고 + thread_id (HITL 대기)
  POST /api/approve             — 분석가 승인(+RBAC) → 동작 실행(목)

설계 원칙(CLAUDE.md)은 그대로다:
- 결정적 사실(사망·후견)은 게이트가 가로채 즉시 종료(fail-closed). LLM 무관.
- 동작(지급정지·STR)은 **여기서 실행하지 않는다** — recommend 는 제안만, 실행은 approve 가
  HITL 승인 + RBAC 통과를 확인한 뒤에만(목).
- 기본 mock LLM 이라 키 없이 동작. TRIAGE_LLM_PROVIDER 설정 시에만 실호출.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from . import hypotheses
from .audit import record_action_execution, record_investigation
from .graph import (
    ACCOUNT_TOOLS,
    CLOSE_THRESHOLD,
    CONFIRM_THRESHOLD,
    _GATED_ACTIONS,
    _REQUIRED_ROLE,
)
from .llm import get_llm_client
from .models import (
    ActionType,
    AgentState,
    Case,
    Recommendation,
)
from .planner import plan_next_tool
from .recommend import build_recommendation
from .tool_matrix import TOOL_MATRIX
from .tools import CASES_DIR, TOOLS, load_case

app = FastAPI(
    title="Fraud Investigation Agent API",
    description="이상거래 조사 에이전트 — 어드민 콘솔 연동 사이드카",
    version="0.1.0",
)

# 어드민 콘솔(Next.js dev: 3000/3001)에서 직접 호출. 운영이면 게이트웨이 뒤로.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# HITL — 권고는 서버가 보관하고, approve 는 thread_id 로만 참조한다(클라이언트가 보낸
# 동작을 신뢰하지 않음). 단일 프로세스 PoC 라 인메모리. 실서비스면 체크포인터/DB.
_PENDING: dict[str, Recommendation] = {}


# --------------------------------------------------------------------------- #
# 응답 스키마
# --------------------------------------------------------------------------- #
class CaseSummary(BaseModel):
    name: str
    description: str | None = None
    alert_id: str
    account: str
    customer_id: str
    amount: int
    payee: str | None = None
    channel: str | None = None
    anomaly_score: float


class TraceStep(BaseModel):
    loop: int
    tool: str
    reason: str
    signal: str
    source: str | None = None  # "real"=실 백엔드 호출(get_auth_events 토글) / None=목
    decisive_fact: str | None = None
    scenarios: dict[str, float]
    closed_scenarios: list[str]
    budget_left: int
    gate: str  # "plan"(루프백) | "recommend"(종료)


class InvestigateResponse(BaseModel):
    case: str
    description: str | None = None
    alert: dict
    initial_scenarios: dict[str, float]
    steps: list[TraceStep]
    recommendation: dict
    thread_id: str
    hitl_pending: bool


class InvestigateRequest(BaseModel):
    case: str


class ApproveRequest(BaseModel):
    thread_id: str
    actor_roles: list[str] = []
    approved: bool = True
    # 승인자 신원(actor_id)은 **본문에 받지 않는다.**
    # 클라이언트가 보내는 신원은 위조할 수 있고, 지급정지 승인 기록에서 위조 가능한
    # 신원은 NULL 보다 나쁘다 — 비어 있는 게 아니라 채워진 것처럼 보이기 때문이다.
    # 레포 관례대로 게이트웨이가 JWT 에서 주입하는 X-Employee-Id 헤더로만 받는다.


class ApproveResponse(BaseModel):
    thread_id: str
    approved: bool
    executed_actions: list[str]


# --------------------------------------------------------------------------- #
# 내부 — 조사 루프를 한 번 펼쳐 구조화 트레이스로 (graph 와 동일 로직)
# --------------------------------------------------------------------------- #
def _scen_dict(scenarios) -> dict[str, float]:
    return {s.value: round(v, 4) for s, v in scenarios.items()}


def _gate(state: AgentState) -> str:
    """graph.gate 와 동일한 §16-5 우선순위."""
    if state.decisive_fact:
        return "recommend"
    if state.scenarios and max(state.scenarios.values()) >= CONFIRM_THRESHOLD:
        return "recommend"
    if state.budget_left <= 0:
        return "recommend"
    return "plan"


def _run_trace(case: Case) -> tuple[list[TraceStep], Recommendation, AgentState]:
    llm = get_llm_client()
    state = AgentState(alert=case.alert)
    state.scenarios = hypotheses.init_scenarios()
    state.tags = hypotheses.init_tags()

    steps: list[TraceStep] = []
    loop = 0
    while True:
        loop += 1

        tool = plan_next_tool(state, llm, TOOL_MATRIX)
        state.budget_left -= 1
        reason = state.tool_log[-1].reason

        fn = TOOLS[tool]
        ident = state.alert.account if tool in ACCOUNT_TOOLS else state.alert.customer_id
        result = fn(case, ident)
        state.evidence.append(result.to_evidence())
        if result.decisive_fact:
            state.decisive_fact = result.decisive_fact

        scenarios, tags = hypotheses.observe(state)
        state.scenarios, state.tags = scenarios, tags
        state.closed_scenarios = [s for s, v in scenarios.items() if v <= CLOSE_THRESHOLD]

        decision = _gate(state)
        steps.append(
            TraceStep(
                loop=loop,
                tool=tool,
                reason=reason,
                signal=result.signal,
                source=result.data.get("_source"),  # 실연결 도구만 "real" (그 외 None=목)
                decisive_fact=(
                    result.decisive_fact.kind.value if result.decisive_fact else None
                ),
                scenarios=_scen_dict(state.scenarios),
                closed_scenarios=[s.value for s in state.closed_scenarios],
                budget_left=state.budget_left,
                gate=decision,
            )
        )
        if decision == "recommend":
            break

    rec = build_recommendation(state, llm.generate_recommendation(state))
    state.recommendation = rec
    return steps, rec, state


# --------------------------------------------------------------------------- #
# 엔드포인트
# --------------------------------------------------------------------------- #
@app.get("/api/cases", response_model=list[CaseSummary])
def list_cases() -> list[CaseSummary]:
    """data/cases/*.json 을 알림 요약으로 나열 (트리아지 큐 대용 — 조사 입력 선택)."""
    out: list[CaseSummary] = []
    for path in sorted(Path(CASES_DIR).glob("*.json")):
        try:
            case = load_case(path.stem)
        except Exception:
            continue
        a = case.alert
        out.append(
            CaseSummary(
                name=case.name,
                description=case.description,
                alert_id=a.id,
                account=a.account,
                customer_id=a.customer_id,
                amount=a.tx_context.amount,
                payee=a.tx_context.payee,
                channel=a.tx_context.channel,
                anomaly_score=a.anomaly_score,
            )
        )
    return out


@app.post("/api/investigate", response_model=InvestigateResponse)
def investigate(req: InvestigateRequest) -> InvestigateResponse:
    """한 사건을 조사 루프에 태워 단계별 트레이스 + 권고를 반환. 동작은 HITL 대기."""
    try:
        case = load_case(req.case)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"케이스 없음: {req.case}")

    steps, rec, state = _run_trace(case)
    thread_id = f"inv-{case.alert.id}"
    _PENDING[thread_id] = rec  # HITL — approve 가 참조

    # 권고를 감사에 남긴다. 실행은 아직이다 — 사람 승인 뒤 별도로 남는다.
    record_investigation(state, case_name=case.name)

    return InvestigateResponse(
        case=case.name,
        description=case.description,
        alert=case.alert.model_dump(mode="json"),
        initial_scenarios=_scen_dict(hypotheses.init_scenarios()),
        steps=steps,
        recommendation=rec.model_dump(mode="json"),
        thread_id=thread_id,
        hitl_pending=True,
    )


# --------------------------------------------------------------------------- #
# 신뢰 경계 — 헤더를 믿어도 되는가
# --------------------------------------------------------------------------- #
#: 게이트웨이와 나눠 갖는 시크릿. **설정되지 않으면 신원 헤더를 전혀 믿지 않는다**
#: (fail-closed). 로컬 데모는 이 상태가 정상이고, 그때 감사의 actor_id 는 NULL 이다.
_GATEWAY_SECRET_ENV = "FRAUD_GATEWAY_SHARED_SECRET"


def _gateway_verified(presented: str | None) -> bool:
    """이 요청이 게이트웨이를 거쳐 왔는지."""
    import hmac
    import os

    expected = os.getenv(_GATEWAY_SECRET_ENV, "").strip()
    if not expected or not presented:
        return False
    # 타이밍 공격 회피 — 시크릿 비교에 == 를 쓰지 않는다.
    return hmac.compare_digest(expected, presented)


def _split_roles(raw: str | None) -> list[str]:
    """게이트웨이가 주입한 X-User-Role 을 목록으로. 다른 서비스와 같은 규약(콤마 구분)."""
    if not raw:
        return []
    return [r.strip() for r in raw.split(",") if r.strip()]


@app.post("/api/approve", response_model=ApproveResponse)
def approve(
    req: ApproveRequest,
    x_employee_id: str | None = Header(default=None, alias="X-Employee-Id"),
    x_user_role: str | None = Header(default=None, alias="X-User-Role"),
    x_gateway_auth: str | None = Header(default=None, alias="X-Gateway-Auth"),
) -> ApproveResponse:
    """분석가 승인(HITL) + RBAC 확인 후에만 동작 실행(목). graph.execute_action 과 동일 게이팅.

    **헤더를 무조건 믿지 않는다.** 이 사이드카는 지금 8090 포트로 브라우저에 직접
    열려 있어서, 헤더만 신뢰하면 게이트웨이를 우회해 ``X-Employee-Id`` 를 손으로
    붙이면 그만이다. 그러면 위조 가능성은 그대로인데 감사 기록은 더 믿음직해 보인다 —
    체크박스로 위조하던 것이 헤더로 위조하는 것으로 형태만 바뀔 뿐이다.

    그래서 게이트웨이에서 왔다는 것이 확인될 때만 신원 헤더를 채택한다
    (:func:`_gateway_verified`). 확인되지 않으면 ``actor_id`` 는 NULL 로 남고
    자칭 역할은 감사의 ``claimed_roles`` 로 따로 기록된다.

    .. warning::
       공유 시크릿은 **네트워크 격리의 대체재가 아니라 최소 방어선**이다.
       완결된 형태는 프론트가 게이트웨이 경로로 나가고, 게이트웨이가 JWT 에서
       신원을 주입하며, 8090 이 브라우저에서 아예 닿지 않는 것이다.
       docs/decisions/agent-harness-consolidation.md 참조.
    """
    verified = _gateway_verified(x_gateway_auth)

    # 검증된 경우에만 신원·역할이 컬럼으로 간다.
    actor_id = x_employee_id if verified else None
    verified_roles = _split_roles(x_user_role) if verified else []

    # RBAC 판정에는 **검증된 역할만** 쓴다. 본문 값으로 되돌아가지 않는다.
    #
    # 예전에는 미검증 시 req.actor_roles 로 fallback 했다. 네트워크를 막아도
    # 이 경로가 남아 있으면 "죽었지만 살아있는" 우회로가 된다 — 설정 실수 하나로
    # 다시 뚫린다. 감사 기록만 정직해지고 인가는 여전히 자칭 역할로 통과하던 상태였다.
    #
    # 결과: 게이트웨이를 거치지 않은 요청은 게이팅 동작(지급정지·STR)을 실행하지 못한다.
    # 자칭 역할은 감사의 claimed_roles 로만 남는다.
    effective_roles = verified_roles

    rec = _PENDING.get(req.thread_id)
    if rec is None:
        raise HTTPException(status_code=404, detail="조사 세션 없음 — 먼저 조사를 실행하세요.")

    alert_id = req.thread_id.removeprefix("inv-")

    if not req.approved:
        refused = ["거부됨: HITL 미승인 — 권고까지만"]
        # 거부도 남긴다. 승인만 기록하면 "아무 일도 없었다"와
        # "사람이 막았다"가 구별되지 않는다.
        record_action_execution(
            alert_id=alert_id,
            approved=False,
            actor_id=actor_id,
            actor_roles=verified_roles,
            claimed_roles=req.actor_roles,
            executed_actions=refused,
            thread_id=req.thread_id,
        )
        return ApproveResponse(
            thread_id=req.thread_id,
            approved=False,
            executed_actions=refused,
        )

    done: list[str] = []
    for a in rec.actions:
        if a.type in _GATED_ACTIONS and _REQUIRED_ROLE not in effective_roles:
            done.append(f"거부됨(RBAC): {a.type.value} — 필요 역할 {_REQUIRED_ROLE}")
            continue
        if a.type == ActionType.NONE:
            continue
        # 실서비스: FDS BLOCK / STR 보고 API 호출 자리. PoC 는 목.
        done.append(f"실행(목): {a.type.value}")

    record_action_execution(
        alert_id=alert_id,
        approved=True,
        actor_id=actor_id,
        actor_roles=verified_roles,
        claimed_roles=req.actor_roles,
        executed_actions=done,
        thread_id=req.thread_id,
    )

    return ApproveResponse(
        thread_id=req.thread_id, approved=True, executed_actions=done
    )


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "fraud-investigation-agent"}
