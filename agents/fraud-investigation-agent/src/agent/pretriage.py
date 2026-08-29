"""사전 트리아지 — 조사 전에 값싼 사실만 확인해 줄을 세운다.

**왜 필요한가.** FDS 알림은 이상도로 정렬된다. 그러면 "이상하지 않지만 규정상
확인할 것이 많은" 거래가 아래로 묻힌다 — 사망계좌 30만 원 인출은 금액도 평범하고
평소 쓰던 ATM 이라 이상도가 낮게 나온다.

**왜 조사로 해결되지 않는가.** 조사 에이전트는 사건 하나를 깊게 파는 도구다.
수백 건을 전부 조사할 수는 없으므로 **무엇을 먼저 조사할지**를 정하는 단계가
따로 필요하다. 그 선택을 이상도에 맡기면 위 사건은 영영 순서가 오지 않는다.

**왜 조사 결과로 정렬할 수 없는가.** 사망 여부는 ``get_party`` 를 불러야 안다.
조사해서 알게 된 사실로 조사 대상을 고르면 순환이다. 그래서 이 단계는 조사가
아니라 <b>한 번의 값싼 조회</b>다 — 가설도, 재계획도, LLM 도 없다.

**무엇을 보는가.** 지금은 권리자 적격성(사망·후견)뿐이다. 계좌 상태·AML 플래그
같은 것을 나중에 더할 수 있게 프로브를 목록으로 두었다. 다만 늘릴 때마다 알림
수만큼 조회가 늘어난다는 것을 기억할 것 — 이 단계의 값은 <b>싸다</b>는 데 있다.

**조사 결과와 섞지 않는다.** 프로브가 찾은 사실은 ``PreTriageResult`` 에 따로 담는다.
같은 ``DecisiveFact`` 를 쓰더라도 "조사 전에 안 것" 과 "조사해서 안 것" 은 다른
사건이고, 감사에서 그 둘을 구분하지 못하면 순서를 어떻게 정했는지 설명할 수 없다.
"""

from __future__ import annotations

from pydantic import BaseModel, Field

from . import liability
from .models import Alert, Case, DecisiveFact, LiabilityGrade
from .tools import get_party


class PreTriageResult(BaseModel):
    """알림 한 건의 사전 판정.

    ``grade`` 는 **내부** 조사 우선순위다. 법정 등급이 아니다(liability 참조).
    """

    alert_id: str
    customer_id: str
    anomaly_score: float

    # 조사 전에 확인된 사실. 비어 있으면 규정상 걸리는 것이 없다는 뜻이다.
    decisive_facts: list[DecisiveFact] = Field(default_factory=list)

    grade: LiabilityGrade = LiabilityGrade.L0
    track: str = "이상도만"
    # 정렬 값. 즉시 밴드는 책임 가중치 그대로, 가중 밴드는 이상도를 섞는다.
    priority: float = 0.0
    # 등급의 근거(liability.TriageRule). 없으면 규정상 걸리는 것이 없었다.
    basis: dict | None = None

    @property
    def immediate(self) -> bool:
        return self.track == "즉시"


def probe(case: Case) -> PreTriageResult:
    """알림 한 건에 값싼 조회를 돌려 우선순위를 매긴다.

    조사 루프를 타지 않는다 — 도구 하나만 부르고 끝난다.
    """
    alert: Alert = case.alert
    result = PreTriageResult(
        alert_id=alert.id,
        customer_id=alert.customer_id,
        anomaly_score=alert.anomaly_score,
        priority=alert.anomaly_score,   # 걸리는 것이 없으면 이상도 순
    )

    # 권리자 적격성. 지금 프로브는 이 하나다.
    party = get_party(case, alert.customer_id)
    if party.decisive_fact is None:
        return result

    fact: DecisiveFact = party.decisive_fact
    result.decisive_facts.append(fact)

    rule = liability.for_decisive_fact(fact.kind)
    if rule is None:
        # 사실은 찾았는데 등급 규칙이 없다. 등급을 지어내지 않는다 —
        # 사실이 남아 있으므로 사람이 보면 알 수 있다.
        return result

    result.grade = rule.grade
    result.track = rule.track
    result.basis = rule.model_dump()
    result.priority = _priority(rule, alert.anomaly_score)
    return result


def _priority(rule: liability.TriageRule, anomaly_score: float) -> float:
    """정렬 값 (§12-4).

    **즉시 밴드는 이상도를 보지 않는다.** 그것이 이 단계의 요점이다 — 이상도를
    섞으면 사망계좌 30만 원(이상도 15)이 다시 아래로 내려간다.

    가중 밴드는 책임 가중치에 이상도를 절반만 얹는다. 규정상 걸리는 것이 있되
    확정 사실은 아니므로, 이상도가 순서를 뒤집지는 못하되 반영은 되게 한다.
    """
    if rule.track == "즉시":
        return float(rule.weight)
    return float(rule.weight) + anomaly_score * 0.5


# 밴드 우선순위. 숫자를 비교하기 <b>전에</b> 밴드로 가른다.
#
# 처음에는 한 숫자로 섞었다가 결함이 드러났다 — 즉시 밴드의 가중치 상한이 95인데
# 이상도는 100까지 올라가므로, 이상도 96 짜리가 사망계좌를 밀어냈다. §12-4 의
# "즉시 행은 큐 최상단 고정 · 이상도 무시" 가 그 순간 깨진다.
#
# 밴드를 먼저 보면 숫자 크기와 무관하게 순서가 보장된다. 같은 밴드 안에서만
# 가중치·이상도를 비교한다.
_BAND_RANK = {"즉시": 0, "가중": 1, "NOTIFY": 2, "이상도만": 3}


def order(results: list[PreTriageResult]) -> list[PreTriageResult]:
    """밴드 → 우선순위 → 이상도 → 알림 번호 순.

    마지막 기준(알림 번호)이 없으면 같은 입력에 매번 다른 순서가 나와 재현이 안
    되고, "왜 이 사건을 먼저 봤나" 를 설명할 수 없다.
    """
    return sorted(
        results,
        key=lambda r: (
            _BAND_RANK.get(r.track, len(_BAND_RANK)),
            -r.priority,
            -r.anomaly_score,
            r.alert_id,
        ),
    )
