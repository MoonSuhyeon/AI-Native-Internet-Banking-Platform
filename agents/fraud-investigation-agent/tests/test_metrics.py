"""조사 성능 지표가 실제로 기록되는지 검증한다.

**왜 필요한가.** 지표는 틀려도 아무도 에러를 내지 않는다. 계측을 빠뜨리거나 라벨을
잘못 달아도 앱은 정상 동작하고, 대시보드만 조용히 빈다. 이 레포에서 실제로 그런 일이
있었다 — auto-loan-review 는 지표를 다 만들어 놓고 compose 에서 포트를 안 열어
두 달 넘게 하나도 수집되지 않았다.

그래서 "값이 올라가는가"를 여기서 못박는다. 특히 채택률
(fraud_recommendation_reviewed_total)은 이 에이전트의 성능을 보는 핵심 축이라
승인·거부 양쪽을 모두 확인한다.
"""

from fastapi.testclient import TestClient

from agent import metrics
from agent.api import app


def _value(counter, **labels) -> float:
    """카운터의 현재 값. 없으면 0."""
    v = counter.labels(**labels)._value.get() if labels else counter._value.get()
    return float(v)


def _investigate(client: TestClient, case: str) -> tuple[str, str]:
    res = client.post("/api/investigate", json={"case": case})
    assert res.status_code == 200, res.text
    body = res.json()
    return body["thread_id"], body["recommendation"]["status"]


def test_metrics_endpoint_exposes_fraud_metrics():
    """/metrics 가 프로메테우스 형식으로 조사 지표를 낸다."""
    client = TestClient(app)
    body = client.get("/metrics").text

    assert "fraud_investigation_total" in body
    assert "fraud_recommendation_reviewed_total" in body
    assert "fraud_tool_calls_total" in body


def test_investigation_counts_by_status():
    """조사를 돌리면 종료 유형별로 세어진다."""
    client = TestClient(app)
    _, status = _investigate(client, "case_h1")

    before = _value(metrics.fraud_investigation_total, status=status)
    _investigate(client, "case_h1")
    after = _value(metrics.fraud_investigation_total, status=status)

    assert after == before + 1


def test_adoption_records_both_decisions():
    """승인과 거부가 각각 기록된다 — 채택률을 계산할 수 있어야 한다.

    한쪽만 세면 "아무도 안 봤다"와 "사람이 막았다"가 구별되지 않는다.
    """
    client = TestClient(app)

    thread, status = _investigate(client, "case_h1")
    approved_before = _value(
        metrics.fraud_recommendation_reviewed_total, status=status, decision="approved"
    )
    client.post("/api/approve", json={"thread_id": thread, "approved": True})
    assert (
        _value(metrics.fraud_recommendation_reviewed_total, status=status, decision="approved")
        == approved_before + 1
    )

    thread, status = _investigate(client, "case_h1")
    rejected_before = _value(
        metrics.fraud_recommendation_reviewed_total, status=status, decision="rejected"
    )
    client.post("/api/approve", json={"thread_id": thread, "approved": False})
    assert (
        _value(metrics.fraud_recommendation_reviewed_total, status=status, decision="rejected")
        == rejected_before + 1
    )


def test_tool_calls_are_counted():
    """도구 호출이 도구별로 세어진다."""
    client = TestClient(app)
    before = client.get("/metrics").text
    _investigate(client, "case_h1")
    after = client.get("/metrics").text

    # 최소한 한 도구는 호출된다 — 조사는 증거 없이 끝나지 않는다.
    assert "fraud_tool_calls_total{" in after
    assert after != before
