"""책임 등급의 규정 근거 — corpus_registry §12-3 을 코드로 옮긴 것.

**왜 필요한가.** 권고에 ``liability_grade=L3`` 만 실려 나가면, 감사에서 "왜 L3인가"
를 물었을 때 답이 "시나리오가 H1이라서" 가 된다. 그건 순환이다 — 등급을 정한 근거가
등급을 정한 규칙 자신이기 때문이다.

규정 근거가 붙어야 "실명법 T2B-03(1_5 p10-16)에 따라 무권리자 지급이므로 L4" 처럼
**바깥의 기준으로** 설명된다. 컴플라이언스를 적용한 조사라는 말이 성립하는 지점이
정확히 거기다.

**왜 정적 표인가.** 규정은 조사 중에 바뀌지 않는다. 매번 문서를 검색해 근거를 만들면
같은 사건에 다른 근거가 나올 수 있고, 그러면 감사 기록으로 쓸 수 없다. 등급과 근거는
한 쌍으로 고정하고, 바뀌는 것은 개정 때 이 표를 고치는 것으로 한다.

**출처.** ``corpus_registry.md`` §12-3 책임 등급표. 이 파일과 그 표가 어긋나면
표가 정본이다 — 테스트가 둘의 어긋남을 잡는다.
"""

from __future__ import annotations

from pydantic import BaseModel, Field

from .models import AttackScenario, DecisiveFactKind, LiabilityGrade


class RegulationRef(BaseModel):
    """판정을 뒷받침하는 규정 한 건.

    ``clause`` 는 코퍼스 청크 ID(T2B-03)고 ``source`` 는 원문 위치(1_5 p10-16)다.
    둘을 나눠 두는 이유는, 청크 ID 는 우리 색인의 이름이고 원문 위치는 규정 자체의
    주소이기 때문이다 — 색인을 다시 만들어도 원문 위치는 그대로여야 한다.
    """

    rule_id: str        # §12-3 행 번호 (B-01 …)
    clause: str         # 코퍼스 청크 ID (T2B-03)
    source: str         # 원문 위치 (1_5 p10-16)
    basis: str          # 이 등급인 이유 — 규정 쪽 표현으로
    weight: int         # §12-3 책임 가중치. 트리아지 정렬 값
    track: str          # 처리 경로 — 즉시 · 가중 · NOTIFY · 이상도만

    def label(self) -> str:
        """감사 로그·화면에 한 줄로 남길 형태."""
        return f"{self.clause} ({self.source}) — {self.basis}"


# --------------------------------------------------------------------------- #
# 결정적 사실 → 규정 (§12-3 B-01 · B-02)
#
# 이 둘은 조사 결과가 아니라 **사실 확인**이라 등급이 고정이다. 사망·후견은
# "얼마나 이상한가" 와 무관하게 무권리자 지급이므로 L4 다 — 이상도 15점짜리
# 30만 원 인출이 이상도 75점 거액 송금보다 위로 가는 근거가 여기다.
# --------------------------------------------------------------------------- #
DECISIVE_FACT_REGULATION: dict[DecisiveFactKind, RegulationRef] = {
    DecisiveFactKind.DEATH: RegulationRef(
        rule_id="B-01",
        clause="T2B-03",
        source="1_5 p10-16",
        basis="사망계좌 상속 미완 지급 — 무권리자 지급으로 은행 직접 배상",
        weight=95,
        track="즉시",
    ),
    DecisiveFactKind.GUARDIANSHIP: RegulationRef(
        rule_id="B-02",
        clause="T2B-06",
        source="1_4 p13",
        basis="성년후견 개시 후 단독 거래 — 무능력자 단독행위는 무효",
        weight=92,
        track="즉시",
    ),
}


# --------------------------------------------------------------------------- #
# 우세 가설 → 규정 (§12-3 B-05 · B-06)
#
# 결정적 사실과 달리 이쪽은 **추정**이다. 그래서 경로가 "즉시" 가 아니라 "가중" 이고,
# 이상도가 정렬에 함께 반영된다(§12-4).
#
# H4(내부자)는 §12-3 에 대응 행이 없다. 없는 것을 지어내지 않고 비워 둔다 —
# 규정 근거 없이 등급만 붙이면 이 표를 만든 이유가 사라진다.
# --------------------------------------------------------------------------- #
SCENARIO_REGULATION: dict[AttackScenario, RegulationRef] = {
    AttackScenario.H1_VOICE_PHISHING: RegulationRef(
        rule_id="B-06",
        clause="T3-02/03",
        source="2_1 p21-22",
        basis="보이스피싱 정황 — STR 보고 대상이며 피해 책임이 따른다",
        weight=80,
        track="가중",
    ),
    AttackScenario.H3_LAUNDERING: RegulationRef(
        rule_id="B-05",
        clause="T2A-03",
        source="1_4 p20",
        basis="차명·명의대여 정황 — 실명법 위반 및 대포통장",
        weight=82,
        track="가중",
    ),
}


def for_decisive_fact(kind: DecisiveFactKind) -> RegulationRef | None:
    return DECISIVE_FACT_REGULATION.get(kind)


def for_scenario(scenario: AttackScenario) -> RegulationRef | None:
    """우세 가설의 규정 근거. 대응 행이 없으면 ``None``.

    ``None`` 은 결함이 아니라 사실이다 — 규정 위반이 아닌 사건(H5 정상)도 있고,
    아직 표에 없는 사건(H4 내부자)도 있다. 부르는 쪽은 근거 없이 등급만 실어
    보내되, 근거가 없다는 것 자체가 보이게 둔다.
    """
    return SCENARIO_REGULATION.get(scenario)


def grade_of(ref: RegulationRef | None, fallback: LiabilityGrade) -> LiabilityGrade:
    """규정 근거가 있으면 그 가중치 구간의 등급을, 없으면 부르는 쪽 판단을 쓴다.

    §12-3 의 가중치는 등급과 함께 정해져 있으므로, 근거가 있는 판정은 등급도
    규정에서 나온다 — 코드가 따로 정하지 않는다.
    """
    if ref is None:
        return fallback
    if ref.weight >= 85:
        return LiabilityGrade.L4
    if ref.weight >= 70:
        return LiabilityGrade.L3
    if ref.weight >= 50:
        return LiabilityGrade.L2
    if ref.weight > 0:
        return LiabilityGrade.L1
    return LiabilityGrade.L0
