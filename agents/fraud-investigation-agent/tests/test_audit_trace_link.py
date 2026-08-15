"""감사 기록과 추적의 연결 — "왜 그 권고가 나왔나" 를 되짚을 수 있는가.

`AgentAuditEntry.trace_id` 는 계약에 **"감사와 추적을 잇는 유일한 연결고리"** 라고
적혀 있다. 그런데 실제로는 이랬다.

- 권고 기록(`record_investigation`)은 이 값을 **아예 안 넣었다** → 항상 NULL
- 실행 기록은 LangGraph **스레드 id** 를 넣었다 → 그 값으로는 Phoenix 의 어떤 추적도
  못 찾는다. 링크가 있는 것처럼 보이지만 아무 데도 닿지 않는다

빈 값보다 틀린 값이 나쁘다. 빈 값은 "없다" 를 말하지만, 틀린 값은 있다고 말한 뒤
따라가면 없다. 여기 테스트는 그 구분을 못박는다.
"""

from __future__ import annotations

import pytest

from agent import audit as audit_mod
from agent.graph import approve_and_execute, investigate, run_investigation
from agent.tools import load_case


class _RecordingAuditLog:
    """기록을 모아 두는 감사 저장소. harness_core 저장소와 같은 모양만 갖춘다."""

    def __init__(self) -> None:
        self.entries: list = []

    def record(self, entry) -> None:
        self.entries.append(entry)

    def of_kind(self, kind: str):
        return [e for e in self.entries if e.decision_kind == kind]


@pytest.fixture()
def audit_log(monkeypatch) -> _RecordingAuditLog:
    log = _RecordingAuditLog()
    monkeypatch.setattr(audit_mod, "get_audit_log", lambda: log)
    return log


class TestTracingOff:
    """추적이 꺼져 있을 때. 기본값이라 이쪽이 평소 상태다."""

    def test_추적이_없으면_NULL_이지_가짜_id_가_아니다(self, audit_log, monkeypatch):
        # 예전에는 스레드 id 가 들어갔다. 그 값으로 Phoenix 를 뒤지면 아무것도 없다.
        monkeypatch.delenv("PHOENIX_ENABLED", raising=False)

        run_investigation(load_case("case_h1"))

        entry = audit_log.entries[0]
        assert entry.trace_id is None, f"추적이 꺼졌는데 {entry.trace_id!r} 가 남았다"

    def test_실행_기록에도_스레드_id_가_새어들지_않는다(self, audit_log, monkeypatch):
        monkeypatch.delenv("PHOENIX_ENABLED", raising=False)

        graph, config, _ = investigate(load_case("case_h1"), thread_id="inv-link-1")
        approve_and_execute(graph, config, actor_roles=["FRAUD_OFFICER"], actor_id="9001")

        execution = audit_log.of_kind(audit_mod.KIND_ACTION_EXECUTION)
        assert execution, "실행 기록이 없다"
        assert execution[0].trace_id is None

    def test_스레드_id_는_버리지_않고_다른_자리에_남긴다(self, audit_log, monkeypatch):
        # 추적 id 로는 못 쓰지만 LangGraph 재개에는 쓰인다. 컬럼만 안 섞는다.
        monkeypatch.delenv("PHOENIX_ENABLED", raising=False)

        graph, config, _ = investigate(load_case("case_h1"), thread_id="inv-link-2")
        approve_and_execute(graph, config, actor_roles=["FRAUD_OFFICER"], actor_id="9001")

        request = audit_log.of_kind(audit_mod.KIND_ACTION_EXECUTION)[0].request_json
        assert "inv-link-2" in request


class TestTraceIdShape:
    """추적 id 를 실을 때 지켜야 하는 모양."""

    def test_상태에_실린_값이_그대로_감사에_간다(self, audit_log):
        # 조사 상태의 trace_id 가 기록으로 이어지는지. 중간에 끊기면 추적이 켜져도
        # 감사에는 안 남는다.
        state = run_investigation(load_case("case_h1"))
        entry = audit_log.entries[0]
        assert entry.trace_id == state.trace_id

    def test_권고와_실행이_같은_추적을_가리킨다(self, audit_log):
        # 사건 하나에 기록이 둘이고 사이에 사람이 있다. 둘이 다른 추적을 가리키면
        # "이 승인이 어느 조사에 대한 것인가" 를 잇지 못한다.
        graph, config, _ = investigate(load_case("case_h1"), thread_id="inv-link-3")
        state = approve_and_execute(
            graph, config, actor_roles=["FRAUD_OFFICER"], actor_id="9001"
        )
        audit_mod.record_investigation(state, case_name="case_h1")

        kinds = {e.decision_kind: e.trace_id for e in audit_log.entries}
        assert len(set(kinds.values())) == 1, f"기록마다 추적이 다르다: {kinds}"
