"""사전 트리아지 — 이상도가 아니라 책임으로 줄을 세운다.

**무엇을 지키는가.** "정렬 함수가 돈다" 가 아니라 <b>순서가 실제로 뒤집힌다</b> 를
지킨다. 둘은 다르다 — 이상도를 조금이라도 섞으면 사망계좌 30만 원(이상도 15)이
거액 송금(이상도 80) 아래로 다시 내려가고, 그러면 이 단계를 둔 이유가 사라진다.

**왜 실제 케이스 파일을 쓰는가.** 지어낸 데이터로는 "우리 규칙이 우리 데이터에
맞는다" 만 확인된다. 조사 에이전트가 실제로 받는 입력으로 재정렬이 일어나야
데모에서도 그대로 나온다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from agent import pretriage
from agent.models import Case, DecisiveFactKind, LiabilityGrade

CASES_DIR = Path(__file__).resolve().parents[1] / "data" / "cases"


def load(name: str) -> Case:
    return Case(**json.loads((CASES_DIR / f"{name}.json").read_text(encoding="utf-8")))


class TestProbe:
    def test_사망은_조사_없이_확인된다(self):
        # 도구 하나만 부른다. 가설도 재계획도 없다 — 그것이 이 단계의 값이다.
        r = pretriage.probe(load("case_death"))

        assert [f.kind for f in r.decisive_facts] == [DecisiveFactKind.DEATH]
        assert r.grade == LiabilityGrade.L4
        assert r.track == "즉시"
        assert r.immediate

    def test_정상_건은_걸리는_것이_없다(self):
        r = pretriage.probe(load("case_h5"))

        assert r.decisive_facts == []
        assert r.grade == LiabilityGrade.L0
        assert r.basis is None
        # 걸리는 것이 없으면 이상도 순서를 그대로 쓴다.
        assert r.priority == r.anomaly_score

    def test_사전_판정에도_근거와_검증상태가_실린다(self):
        # 조사 결과에만 근거를 붙이고 트리아지는 등급만 내면, "왜 이 순서인가" 를
        # 설명할 수 없다. 순서를 정한 단계에도 근거가 있어야 한다.
        r = pretriage.probe(load("case_death"))

        assert r.basis is not None
        assert r.basis["rule_id"] == "B-01"
        assert r.basis["verified"] is False, "미검증 상태가 트리아지에도 드러나야 한다"


class TestReordering:
    """이 클래스가 이 프로젝트의 주장을 지킨다."""

    def test_사망계좌가_거액송금_위로_올라간다(self):
        # case_death  — 30만 원, 이상도 15
        # case_h1     — 800만 원, 이상도 80
        results = [pretriage.probe(load(n)) for n in ("case_h1", "case_death")]

        by_score = sorted(results, key=lambda r: -r.anomaly_score)
        assert by_score[0].alert_id == "ALT-H1", "이상도 순이면 거액 송금이 위"

        by_liability = pretriage.order(results)
        assert by_liability[0].alert_id == "ALT-DEATH", (
            "책임 순인데 사망계좌가 위로 오지 않았다 — 이 단계를 둔 이유가 사라진다"
        )

    def test_즉시_밴드는_이상도를_보지_않는다(self):
        # 이상도를 조금이라도 섞으면 이상도 15짜리가 다시 내려간다.
        death = pretriage.probe(load("case_death"))
        assert death.priority == 95.0, (
            f"즉시 밴드에 이상도가 섞였다: {death.priority}"
        )

    def test_이상도가_아무리_높아도_즉시_밴드를_넘지_못한다(self):
        # 한 숫자로 섞으면 여기서 깨진다 — 즉시 밴드의 가중치 상한은 95인데
        # 이상도는 100까지 올라간다. 밴드를 먼저 갈라야 순서가 보장된다.
        death = pretriage.probe(load("case_death"))
        loud = pretriage.probe(load("case_h1"))
        loud.anomaly_score = 100.0
        loud.priority = 100.0

        assert pretriage.order([loud, death])[0].alert_id == "ALT-DEATH", (
            "이상도 100 이 즉시 밴드를 밀어냈다"
        )

    def test_같은_우선순위면_순서가_고정된다(self):
        # 재현되지 않으면 "왜 이 사건을 먼저 봤나" 를 설명할 수 없다.
        rs = [pretriage.probe(load(n)) for n in ("case_h1", "case_h5", "case_death")]
        first = [r.alert_id for r in pretriage.order(rs)]
        second = [r.alert_id for r in pretriage.order(list(reversed(rs)))]
        assert first == second


class TestSeparationFromInvestigation:
    """조사 전에 안 것과 조사해서 안 것을 섞지 않는다."""

    def test_프로브는_조사_루프를_돌리지_않는다(self):
        # 조사 루프를 타면 이 단계가 싸다는 전제가 무너진다. 프로브 결과에는
        # 조사 산물(가설 분포·트레이스·권고)이 없어야 한다.
        r = pretriage.probe(load("case_death"))

        assert not hasattr(r, "scenarios")
        assert not hasattr(r, "steps")
        assert not hasattr(r, "recommendation")

    def test_등급_규칙이_없으면_등급을_지어내지_않는다(self, monkeypatch):
        monkeypatch.setattr(pretriage.liability, "for_decisive_fact", lambda _k: None)
        r = pretriage.probe(load("case_death"))

        # 사실은 남는다 — 사람이 보면 알 수 있다.
        assert [f.kind for f in r.decisive_facts] == [DecisiveFactKind.DEATH]
        # 등급은 올리지 않는다.
        assert r.grade == LiabilityGrade.L0
        assert r.basis is None


# --------------------------------------------------------------------------- #
# 엔드포인트가 실제로 그 순서로 내주는가
#
# 모듈이 정렬해도 API 가 파일명 순으로 내주면 화면에는 아무 변화가 없다.
# 이 프로젝트의 주장이 서는 곳은 모듈이 아니라 여기다.
# --------------------------------------------------------------------------- #
from fastapi.testclient import TestClient  # noqa: E402

from agent.api import app  # noqa: E402
from conftest import GATEWAY_HEADERS  # noqa: E402


@pytest.fixture()
def client():
    return TestClient(app)


class TestQueueEndpoint:
    def _queue(self, client):
        resp = client.get("/api/cases", headers=GATEWAY_HEADERS)
        assert resp.status_code == 200, resp.text
        return resp.json()

    def test_큐가_책임_순으로_나온다(self, client):
        q = self._queue(client)
        assert q, "큐가 비었다"

        top = q[0]
        assert top["track"] == "즉시", f"최상단이 즉시 밴드가 아니다: {top['track']}"
        assert top["grade"] == "L4"

    def test_사망계좌가_이상도_높은_건보다_위에_있다(self, client):
        q = self._queue(client)
        pos = {c["name"]: i for i, c in enumerate(q)}

        assert pos["case_death"] < pos["case_h1"], (
            f"이상도 15 짜리 사망계좌가 이상도 80 짜리 아래에 있다: "
            f"death={pos['case_death']} h1={pos['case_h1']}"
        )

    def test_파일명_순이_아니다(self, client):
        # 알파벳 순이면 case_death 가 case_h1 보다 앞이라 위 검사가 우연히 통과한다.
        # 순서가 실제로 바뀌었는지 보려면 알파벳 순과 다름을 확인해야 한다.
        q = self._queue(client)
        names = [c["name"] for c in q]
        assert names != sorted(names), "여전히 파일명 순이다"

    def test_큐에_근거와_검증상태가_실린다(self, client):
        q = self._queue(client)
        top = q[0]

        assert top["basis"] is not None, "왜 이 순서인지 설명할 근거가 없다"
        assert top["basis"]["verified"] is False, "미검증 상태가 큐에도 드러나야 한다"

    def test_알림을_잃지_않는다(self, client):
        # 정렬은 순서를 바꾸는 것이지 거르는 것이 아니다.
        q = self._queue(client)
        assert len(q) == len(list(CASES_DIR.glob("*.json")))


class TestTriageRun:
    """3층이 한 번에 도는가 — 프로브 → 정렬 → 상위만 조사.

    **무엇을 지키는가.** "조사가 돈다" 가 아니라 <b>고르는 일이 실제로 일어난다</b>
    를 지킨다. 용량이 없으면 전부 조사하게 되고, 그러면 트리아지를 둔 이유가 없다.
    """

    def _run(self, client, capacity=2):
        resp = client.post("/api/triage/run", json={"capacity": capacity},
                           headers=GATEWAY_HEADERS)
        assert resp.status_code == 200, resp.text
        return resp.json()

    def test_상위_용량만큼만_조사한다(self, client):
        r = self._run(client, capacity=2)
        done = [q for q in r["queue"] if q["investigated"]]

        assert len(done) == 2, f"용량 2인데 {len(done)}건 조사했다"
        assert [q["rank"] for q in done] == [1, 2], "상위가 아닌 것을 조사했다"

    def test_조사한_것은_책임_상위다(self, client):
        r = self._run(client, capacity=2)
        done = [q for q in r["queue"] if q["investigated"]]

        # 이상도가 아니라 책임으로 골랐는지 — 조사된 건이 즉시 밴드여야 한다.
        assert all(q["track"] == "즉시" for q in done), (
            f"이상도 순으로 골랐다: {[(q['name'], q['track']) for q in done]}"
        )

    def test_이상도가_가장_높은_건은_밀린다(self, client):
        # 이 검사가 이 단계의 주장이다. 이상도 80짜리가 조사되고 사망계좌가
        # 밀리면 트리아지가 아무 일도 하지 않은 것이다.
        r = self._run(client, capacity=2)
        loudest = max(r["queue"], key=lambda q: q["anomaly_score"])

        assert not loudest["investigated"], (
            f"이상도 최고({loudest['name']}, {loudest['anomaly_score']})가 조사됐다"
        )
        assert loudest["deferred_reason"], "밀린 이유가 없다"

    def test_밀린_건도_큐에_남는다(self, client):
        # 조사한 것만 돌려주면 고르는 단계가 화면에서 사라진다.
        r = self._run(client, capacity=1)
        assert len(r["queue"]) == len(list(CASES_DIR.glob("*.json")))

        deferred = [q for q in r["queue"] if not q["investigated"]]
        assert deferred, "밀린 건이 하나도 없다"
        assert all(q["deferred_reason"] for q in deferred), (
            "밀린 이유가 비어 있다 — '안 걸렸다'와 '순서가 밀렸다'가 구별되지 않는다"
        )

    def test_조사된_건은_HITL_로_넘어간다(self, client):
        # 에이전트는 권고까지다. 승인 없이 동작하지 않는다.
        r = self._run(client, capacity=1)
        top = r["queue"][0]

        assert top["investigated"]
        assert top["thread_id"], "승인으로 이어질 손잡이가 없다"
        assert top["recommendation"] is not None

    def test_용량을_늘리면_더_조사한다(self, client):
        assert len([q for q in self._run(client, 1)["queue"] if q["investigated"]]) == 1
        assert len([q for q in self._run(client, 3)["queue"] if q["investigated"]]) == 3

    def test_용량은_범위를_벗어날_수_없다(self, client):
        # 상한이 없으면 한 번 호출로 전부 조사할 수 있고, 그러면 비용과 처리량을
        # 아무나 소진시킬 수 있다.
        assert client.post("/api/triage/run", json={"capacity": 0},
                           headers=GATEWAY_HEADERS).status_code == 422
        assert client.post("/api/triage/run", json={"capacity": 99},
                           headers=GATEWAY_HEADERS).status_code == 422

    def test_신원_없이는_부를_수_없다(self, client):
        # 조사는 고객 이력을 끌어와 본다. /api/investigate 와 같은 기준이어야 한다.
        assert client.post("/api/triage/run", json={"capacity": 1}).status_code in (401, 403)
