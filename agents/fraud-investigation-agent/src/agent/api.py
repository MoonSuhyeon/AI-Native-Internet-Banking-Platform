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

import json
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
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
from .guidance import Guidance, lookup_audience, refine
from .llm import get_llm_client
from .metrics import (
    fraud_action_blocked_total,
    fraud_investigation_duration_seconds,
    fraud_investigation_failed_total,
    fraud_investigation_total,
    fraud_tool_calls_total,
    observe_recommendation,
    record_guidance,
)
from .models import (
    ActionType,
    AgentState,
    Alert,
    Case,
    Recommendation,
    TxContext,
)
from .planner import plan_next_tool
from .recommend import build_recommendation
from .tool_matrix import TOOL_MATRIX
from .scope import CaseScope
from .pretriage import PreTriageResult, order, probe
from .tools import CASES_DIR, TOOLS, call_tool, load_case

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

    # ── 사전 트리아지 결과 (pretriage.probe) ────────────────────────────────
    # 조사 전에 값싼 조회 한 번으로 매긴 값이다. 조사 결과가 아니다.
    grade: str = "L0"
    track: str = "이상도만"
    priority: float = 0.0
    # 등급 근거. 없으면 규정상 걸리는 것이 없었다는 뜻이다.
    basis: dict | None = None


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


class TransactionInput(BaseModel):
    """실거래 조사 입력. 탐지기(fds-detector)가 사건화한 건을 그대로 넘긴다."""

    alert_id: str
    customer_id: str
    account: str
    amount: int
    payee: str | None = None
    time: datetime | None = None
    channel: str | None = None
    anomaly_score: float = 0.0
    # 탐지기가 왜 걸었는지. 조사 근거의 출발점이 된다.
    signals: list[str] = []


class InvestigateRequest(BaseModel):
    """조사 입력. 목 케이스 또는 실거래 중 하나.

    ``case`` 는 data/cases/*.json 을 읽는 기존 경로다. 데모와 eval 워크플로가
    이 경로로 돌기 때문에 그대로 둔다 — 실거래를 붙이면서 목을 끊으면 CI 가 죽는다.

    ``transaction`` 은 탐지기가 넘기는 실거래다. 파일이 없으므로 도구 응답도 없고,
    조회 도구는 실연결(TRIAGE_REAL_TOOLS)이 켜진 것만 값을 준다. 켜지지 않은 도구는
    빈 응답이 되어 그 축의 가설이 오르지 않는다 — 조사가 틀리는 게 아니라
    **덜 아는 상태**이며, 예산 소진 후 PROVISIONAL/HOLD 로 끝난다.
    """

    case: str | None = None
    transaction: TransactionInput | None = None

    def to_case(self) -> Case:
        """실거래를 조사 루프가 받는 Case 형태로 옮긴다."""
        tx = self.transaction
        return Case(
            name=f"txn-{tx.alert_id}",
            description="탐지 신호: " + (", ".join(tx.signals) if tx.signals else "없음"),
            alert=Alert(
                id=tx.alert_id,
                account=tx.account,
                customer_id=tx.customer_id,
                tx_context=TxContext(
                    amount=tx.amount,
                    payee=tx.payee,
                    time=tx.time,
                    channel=tx.channel,
                ),
                anomaly_score=tx.anomaly_score,
            ),
            # 목 응답 없음. 도구는 실연결이 켜진 것만 값을 준다.
            tool_responses={},
        )


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


class GuidanceRequest(BaseModel):
    """고객 화면이 받은 기본 안내. 그대로 다시 보내 다듬어 달라고 한다.

    **고객 ID 를 본문에 받지 않는다.** 받으면 아무나 남의 번호를 적어 그 사람의
    연령대를 알아낼 수 있다 — 안내문 한 줄로 새어 나가는 개인정보다.
    신원은 게이트웨이가 주입하는 ``X-Customer-Id`` 헤더로만 온다(레포 관례).
    """

    headline: str = ""
    evidence: list[str] = []
    action_steps: list[str] = []
    choices: list[str] = []


class GuidanceResponse(BaseModel):
    headline: str
    evidence: list[str]
    action_steps: list[str]
    choices: list[str]
    #: 어느 구간에 맞췄는가. 지표용 — UNKNOWN 과 GENERAL 을 섞으면 커버리지가 부푼다.
    audience: str
    #: 모델이 다듬었는가. false 면 규칙만으로 만든 것이고, 그래도 완결이다.
    llm_refined: bool


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
    scope = CaseScope.of(case.alert)
    state.scenarios = hypotheses.init_scenarios()
    state.tags = hypotheses.init_tags()

    steps: list[TraceStep] = []
    loop = 0
    while True:
        loop += 1

        tool = plan_next_tool(state, llm, TOOL_MATRIX)
        state.budget_left -= 1
        reason = state.tool_log[-1].reason

        fraud_tool_calls_total.labels(tool=str(tool)).inc()
        # graph.py 와 같은 경로를 쓴다. 예전에는 같은 계산이 두 곳에 따로 있었다.
        result = call_tool(case, tool, scope)
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


#: 탐지기(fds-detector)가 사건을 넘길 때 제시하는 시크릿.
#:
#: 게이트웨이 시크릿과 나눠 두는 이유는 뜻이 다르기 때문이다 — 게이트웨이 쪽은
#: "사람의 요청이 게이트웨이를 거쳐 왔다", 이쪽은 "기계가 직접 넘겼다". 하나로
#: 합치면 탐지기 시크릿을 아는 쪽이 사람 신원까지 위조할 수 있다.
_DISPATCH_SECRET_ENV = "FRAUD_DISPATCH_SHARED_SECRET"


def _service_verified(presented: str | None) -> bool:
    """탐지기가 직접 넘긴 요청인지."""
    import hmac
    import os

    expected = os.getenv(_DISPATCH_SECRET_ENV, "").strip()
    if not expected or not presented:
        return False
    return hmac.compare_digest(expected, presented)


def _require_investigator(
    x_gateway_auth: str | None,
    x_employee_id: str | None,
    x_service_auth: str | None,
) -> str:
    """조사를 시작할 수 있는가 — 직원이거나, 탐지기이거나.

    **왜 탐지기 경로가 따로 필요한가.** 사후 탐지가 사건을 넘길 때 부르는 것도
    ``/api/investigate`` 다. 그런데 탐지기는 사람이 아니라 ``X-Employee-Id`` 를 붙일
    수 없고, 게이트웨이를 거치지도 않는다(서비스 간 직접 호출). 그래서 직원 문지기만
    두면 **탐지기가 넘긴 사건이 전부 403 으로 되튄다** — 탐지는 도는데 조사 큐가
    영원히 비어 있는 상태가 된다. 실제로 그랬다.

    :returns: 감사에 남길 행위자. 사람이면 직원 ID, 기계면 ``system:fds-detector``.
        섞지 않는 이유는, 기계가 연 사건을 사람이 연 것으로 기록하면 나중에
        "누가 이 고객을 조사 대상으로 삼았나" 에 거짓 답이 나오기 때문이다.
    """
    if _service_verified(x_service_auth):
        return "system:fds-detector"
    return _require_employee(x_gateway_auth, x_employee_id)


def _require_customer(x_gateway_auth: str | None, x_customer_id: str | None) -> str:
    """고객 본인 전용 경로의 문지기.

    직원 경로와 같은 이유로 게이트웨이 서명을 먼저 본다. 이 사이드카는 직접
    두드릴 수 있어서, 손으로 쓴 ``X-Customer-Id`` 를 믿으면 아무나 남의 번호를 적어
    **그 사람의 연령대**를 캐낼 수 있다. 안내문 한 줄로 새는 개인정보다.

    :raises HTTPException: 403.
    """
    if not _gateway_verified(x_gateway_auth):
        raise HTTPException(
            status_code=403,
            detail="본인 확인이 필요합니다. 게이트웨이를 통해 요청해주세요.",
        )
    cid = (x_customer_id or "").strip()
    if not cid:
        raise HTTPException(status_code=403, detail="본인 확인이 필요합니다.")
    return cid


def _require_employee(x_gateway_auth: str | None, x_employee_id: str | None) -> str:
    """직원 전용 동작의 문지기.

    <p>조사 큐와 조사 실행은 고객 개인정보를 다루고 비용도 든다. 승인(/approve)만
    막고 이 둘을 열어 두면, 승인 없이도 **누가 어떤 고객을 조사 대상으로 삼았는지**가
    자유롭게 정해진다 — 조사 자체가 사찰이 될 수 있다.

    :raises HTTPException: 403. 401 이 아닌 이유는 자격 증명을 다시 보내라는 뜻이
        아니기 때문이다. 게이트웨이를 거치지 않은 요청은 무엇을 붙여도 통과할 수 없다.
    """
    if not _gateway_verified(x_gateway_auth):
        raise HTTPException(
            status_code=403,
            detail="직원 권한이 필요합니다. 게이트웨이를 통해 요청해주세요.",
        )
    eid = (x_employee_id or "").strip()
    if not eid:
        # 게이트웨이는 고객 토큰에도 헤더를 붙이되 빈 문자열을 넣는다.
        raise HTTPException(status_code=403, detail="직원 권한이 필요합니다.")
    return eid


# --------------------------------------------------------------------------- #
# 엔드포인트
# --------------------------------------------------------------------------- #
@app.get("/api/cases", response_model=list[CaseSummary])
def list_cases(
    x_employee_id: str | None = Header(default=None, alias="X-Employee-Id"),
    x_gateway_auth: str | None = Header(default=None, alias="X-Gateway-Auth"),
) -> list[CaseSummary]:
    """트리아지 큐 — 책임 우선순위 순으로 나열한다.

    <b>파일명 순이 아니다.</b> 알림마다 사전 프로브(``pretriage.probe``)를 돌려
    권리자 적격성을 확인하고 그 결과로 줄을 세운다. 이상도 순이면 "이상하지 않지만
    규정상 확인할 것이 많은" 거래가 아래로 묻힌다 — 사망계좌 30만 원이 그렇다.

    프로브는 조사가 아니다. 도구 하나만 부르고 끝난다(가설·재계획·LLM 없음).
    조사 에이전트는 이 큐의 상위 사건에만 투입한다.

    <b>직원만 볼 수 있다.</b> 응답에 고객 ID·계좌·금액·수취인이 그대로 담긴다.
    """
    _require_employee(x_gateway_auth, x_employee_id)

    scored: list[tuple[PreTriageResult, CaseSummary]] = []
    for path in sorted(Path(CASES_DIR).glob("*.json")):
        try:
            case = load_case(path.stem)
        except Exception:
            continue
        a = case.alert
        try:
            pt = probe(case)
        except Exception:
            # 프로브가 실패해도 알림을 잃지 않는다. 순서만 이상도로 떨어진다 —
            # 큐에서 사라지는 것보다 낫다.
            pt = PreTriageResult(
                alert_id=a.id, customer_id=a.customer_id,
                anomaly_score=a.anomaly_score, priority=a.anomaly_score,
            )
        scored.append((pt, CaseSummary(
            name=case.name,
            description=case.description,
            alert_id=a.id,
            account=a.account,
            customer_id=a.customer_id,
            amount=a.tx_context.amount,
            payee=a.tx_context.payee,
            channel=a.tx_context.channel,
            anomaly_score=a.anomaly_score,
            grade=pt.grade.value,
            track=pt.track,
            priority=pt.priority,
            basis=pt.basis,
        )))

    by_id = {pt.alert_id: summary for pt, summary in scored}
    return [by_id[pt.alert_id] for pt in order([pt for pt, _ in scored])]


@app.post("/api/investigate", response_model=InvestigateResponse)
def investigate(
    req: InvestigateRequest,
    x_employee_id: str | None = Header(default=None, alias="X-Employee-Id"),
    x_gateway_auth: str | None = Header(default=None, alias="X-Gateway-Auth"),
    x_service_auth: str | None = Header(default=None, alias="X-Service-Auth"),
) -> InvestigateResponse:
    """한 사건을 조사 루프에 태워 단계별 트레이스 + 권고를 반환. 동작은 HITL 대기.

    <b>직원이거나 탐지기여야 한다.</b> 사람 쪽을 막는 이유가 둘이다.

    첫째, 조사는 고객의 인증 이력·거래 내역을 끌어와 본다. 승인(/approve)만 막고
    여기를 열어 두면 동작은 못 해도 <b>열람은 자유롭다</b> — 조사가 사찰이 된다.

    둘째, 한 번 부를 때마다 LLM 호출과 도구 조회가 일어난다. 열려 있으면 비용과
    처리량을 아무나 소진시킬 수 있고, 그 사이 진짜 알림이 밀린다.
    """
    _require_investigator(x_gateway_auth, x_employee_id, x_service_auth)

    if req.transaction is not None:
        case = req.to_case()
    elif req.case:
        try:
            case = load_case(req.case)
        except FileNotFoundError:
            raise HTTPException(status_code=404, detail=f"케이스 없음: {req.case}")
    else:
        raise HTTPException(status_code=400, detail="case 또는 transaction 중 하나가 필요합니다")

    import time
    started = time.perf_counter()
    try:
        steps, rec, state = _run_trace(case)
    except Exception:
        # 실패를 세지 않으면 "조사가 준 적 없다"와 "조사가 터졌다"가 구별되지 않는다.
        fraud_investigation_failed_total.inc()
        raise
    finally:
        fraud_investigation_duration_seconds.observe(time.perf_counter() - started)
    fraud_investigation_total.labels(status=rec.status.value).inc()

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
        observe_recommendation(rec.status.value, approved=False)
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

    observe_recommendation(rec.status.value, approved=True)

    done: list[str] = []
    for a in rec.actions:
        if a.type in _GATED_ACTIONS and _REQUIRED_ROLE not in effective_roles:
            # 승인됐는데 실행되지 않은 것. 채택률만 보면 안 보이는 구멍이다.
            fraud_action_blocked_total.labels(action_type=a.type.value).inc()
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


@app.get("/metrics")
def metrics() -> Response:
    """Prometheus 스크레이프 엔드포인트.

    다른 파이썬 서비스(consultation)와 같은 경로·형식을 쓴다.
    """
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


class ConsultRequest(BaseModel):
    """이체가 막힌 고객이 상담을 요청한다.

    고객 ID 는 본문에 받지 않는다 — 게이트웨이가 ``X-Customer-Id`` 로 주입한다.
    받으면 아무나 남의 이름으로 케이스를 열 수 있다.
    """

    #: 막힌 거래의 금액·수취인. 심사원이 무엇을 보는 사건인지 알아야 한다.
    amount: int = 0
    payee: str = ""
    channel: str = "WEB"
    #: 탐지기가 든 근거. 조사 에이전트가 가설의 출발점으로 쓴다.
    evidence: list[str] = []


class ConsultResponse(BaseModel):
    case_id: str
    queued: bool


@app.post("/api/consult", response_model=ConsultResponse)
def consult(
    req: ConsultRequest,
    x_customer_id: str | None = Header(default=None, alias="X-Customer-Id"),
    x_gateway_auth: str | None = Header(default=None, alias="X-Gateway-Auth"),
) -> ConsultResponse:
    """막힌 이체를 상담원 큐에 올린다.

    **조사를 여기서 돌리지 않는다.** 큐에 올리기만 한다. 고객이 버튼을 누른 그
    순간 조사 루프를 태우면 두 가지가 나빠진다 — 고객이 수 초에서 수십 초를 기다리게
    되고, 버튼 한 번이 LLM 호출과 도구 조회를 부르니 아무나 비용을 소진시킬 수 있다.
    조사는 심사원이 케이스를 열 때 ``/api/investigate`` 로 돈다(HITL 순서 그대로).

    **왜 이 경로가 필요했나.** 지금까지 케이스는 사후 탐지(Kafka)로만 열렸다. 그래서
    사전 점검에 막힌 고객은 갈 곳이 없었다 — 화면에 "고객센터로 문의해 주세요" 만
    뜨고, 그 문의는 이 시스템 밖으로 나갔다. 기획서가 말하는 "탐지 후 조치까지의
    공백" 이 바로 이 자리다.
    """
    customer_id = _require_customer(x_gateway_auth, x_customer_id)

    case_id = f"consult-{customer_id}-{int(datetime.now().timestamp())}"
    case = {
        "name": case_id,
        "description": "고객 상담 요청 — 사전 점검에 막힌 이체. 근거: "
                       + ("; ".join(req.evidence) if req.evidence else "없음"),
        "alert": {
            "id": case_id,
            "account": "",
            "customer_id": customer_id,
            "tx_context": {
                "amount": req.amount,
                "payee": req.payee,
                "time": datetime.now().isoformat(timespec="seconds"),
                "channel": req.channel,
            },
            # 사전 점검이 막았다는 것 자체가 강한 신호다. 다만 점수를 지어내지는
            # 않는다 — 탐지기가 준 것이 없으므로 0 으로 두고 근거는 description 에.
            "anomaly_score": 0.0,
        },
        # 목 응답 없음. 조사는 실연결이 켜진 도구에서만 값을 얻는다.
        "tool_responses": {},
    }

    try:
        path = Path(CASES_DIR) / f"{case_id}.json"
        path.write_text(json.dumps(case, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError:
        # 큐에 못 올렸다. 고객에게는 실패를 알려야 한다 — 여기서 조용히 성공이라고
        # 하면 아무도 보지 않는 요청이 되고, 고객은 상담이 접수된 줄 안다.
        raise HTTPException(status_code=503, detail="상담 접수에 실패했습니다. 잠시 후 다시 시도해 주세요.")

    return ConsultResponse(case_id=case_id, queued=True)


@app.post("/api/guidance", response_model=GuidanceResponse)
def guidance(
    req: GuidanceRequest,
    x_customer_id: str | None = Header(default=None, alias="X-Customer-Id"),
    x_gateway_auth: str | None = Header(default=None, alias="X-Gateway-Auth"),
) -> GuidanceResponse:
    """이체가 멈춘 고객에게 보여 줄 안내를 연령대에 맞춰 다듬는다.

    **이 경로는 판단하지 않는다.** 무엇이 위험한지·무엇을 할지는 탐지기가 규칙으로
    이미 정했고 화면에도 이미 그려져 있다. 여기서 바뀌는 것은 *말투와 순서*뿐이다.

    그래서 실패해도 조용하다 — 연령대 조회가 안 되면 UNKNOWN 으로, 모델이 죽으면
    규칙 결과로 내려간다. 화면은 이 응답이 안 와도 성립한다.
    """
    customer_id = _require_customer(x_gateway_auth, x_customer_id)

    base = Guidance(
        headline=req.headline,
        evidence=req.evidence,
        action_steps=req.action_steps,
        choices=req.choices,
    )
    result = refine(base, lookup_audience(customer_id))
    record_guidance(result.audience, result.llm_refined)
    return GuidanceResponse(**result.model_dump())


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "fraud-investigation-agent"}
