"""조사 우선순위 등급과 그 근거 — 층을 나눠 둔다.

**무엇이 문제였나.** 이전 판은 ``T2B-03 → L4`` 한 줄이었다. 그 한 줄에 서로 다른
셋이 뭉쳐 있었다 — 관찰된 사실, 자료에 적힌 내용, 그리고 <b>법적 결론</b>.
``basis="무권리자 지급으로 은행 직접 배상"`` 같은 문구가 그 결과다. 이건 우리가
말할 자격이 없는 판단이고, 심사에서 "L4가 법에 있는 등급이냐" 는 질문에 그렇다고
답하는 모양이 된다.

**어떻게 나눴나.** 네 층이다.

.. code-block:: text

    ① 사실        END_REASON=DEATH            도구가 관찰
    ② 근거 자료   문서·조항·페이지            원문 위치 보존
    ③ 규정상 의미 "권리자 확인이 필요하다"    자료가 말하는 것까지만
    ④ 내부 등급   L4 · 즉시                   **우리가 정한** 조사 우선순위

③에서 멈추는 것이 요점이다. "확인이 필요하다" 까지는 자료에 적혀 있지만,
"그러므로 은행이 배상한다" 는 사건 경위·다른 법률·판례가 함께 걸리는 판단이라
여기서 결론짓지 않는다.

**L4 는 법에 있는 등급이 아니다.** 우리 시스템의 조사 우선순위다. 그 등급을 매기는
기준을 자료에서 뽑아 결정 테이블로 고정했을 뿐이다. 이 구분이 흐려지면 없는 권위를
빌리는 것이 된다.

**검증 상태를 감추지 않는다.** 지금 근거는 대부분 은행업무 교재다 — 법령 원문이
아니다. ``verified=False`` 로 두어 그 사실이 응답과 화면에 그대로 드러난다.
법령·판례로 확인되면 그때 ``STATUTE``/``PRECEDENT`` 출처를 더하고 참으로 바꾼다.
비워 두는 것이 아니라 **채울 자리를 만들어 두는** 쪽이다.

**왜 사람이 확정하나.** 후보를 찾고 구조화하는 일은 자동화할 수 있지만, "이 문서가
이 법적 의미를 갖는다" 는 확정은 사람이 한다. 이 에이전트가 조사하고 사람이
승인하는 것과 같은 구조다 — 근거 수집은 기계, 최종 판단은 사람.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field, model_validator

from .models import AttackScenario, DecisiveFactKind, LiabilityGrade


class SourceType(str, Enum):
    """근거 자료의 종류. 무게가 다르므로 섞지 않는다."""

    STATUTE = "STATUTE"        # 법령 조문
    PRECEDENT = "PRECEDENT"    # 판례
    GUIDANCE = "GUIDANCE"      # 금융당국 자료·행정지도
    MANUAL = "MANUAL"          # 은행업무 교재·실무 자료 — 법적 권위는 없다


class EvidenceSource(BaseModel):
    """근거 자료 한 건. 원문 위치를 반드시 남긴다.

    되짚을 수 없는 근거는 근거가 아니다. 나중에 사람이 검증할 때 이 위치로
    원문을 찾는다.
    """

    type: SourceType
    ref: str                      # 원문 위치 — 조/항 또는 문서·페이지
    note: str | None = None       # 자료에 적힌 내용 요약 (해석 아님)

    # 원문 그대로. 검증된 근거에는 반드시 있어야 한다.
    #
    # 요약만 남기면 나중에 그 요약이 맞는지 확인할 방법이 없고, 요약이 틀린 채로
    # 감사에 나가면 없는 근거를 댄 것이 된다. 원문을 함께 두면 다음 사람이
    # 요약과 대조할 수 있다.
    quote: str | None = None

    def label(self) -> str:
        return f"[{self.type.value}] {self.ref}"

    def is_authoritative(self) -> bool:
        """업무 자료가 아니라 공식 근거인가."""
        return self.type in (SourceType.STATUTE, SourceType.PRECEDENT, SourceType.GUIDANCE)


class TriageRule(BaseModel):
    """사실 → 근거 → 규정상 의미 → 내부 등급.

    ``grade`` 는 **우리가 정의한** 조사 우선순위다. 법적 등급이 아니다.
    """

    rule_id: str                       # 내부 규칙 번호 (B-01 …)
    fact: str                          # ① 어떤 사실이 관찰됐을 때인가
    sources: list[EvidenceSource]      # ② 근거 자료 (원문 위치 보존)
    implication: list[str]             # ③ 자료가 말하는 것 — 확인이 필요한 사항
    grade: LiabilityGrade              # ④ 내부 조사 우선순위 등급
    weight: int                        # ④ 정렬 값
    track: str                         # ④ 처리 경로 — 즉시 · 가중 · NOTIFY

    # 법령·판례로 확인됐는가. 거짓이면 아직 업무 자료 수준의 근거다.
    # 사람이 확인한 뒤에만 참으로 바꾼다 — 자동으로 올리지 않는다.
    #
    # 참으로 올리는 조건은 아래 검사기가 강제한다. 플래그만 바꿔서는 올릴 수 없다 —
    # 그렇게 둘 수 있으면 "검증됨" 이 아무 의미가 없어진다.
    verified: bool = False
    verified_note: str | None = None
    verified_by: str | None = None   # 누가 확인했나. 검증에는 책임자가 있어야 한다

    @model_validator(mode="after")
    def _check_promotion(self) -> "TriageRule":
        """``verified=True`` 로 올릴 수 있는 조건.

        플래그 하나로 근거의 질이 바뀌지 않는다. 올리려면 실제로 갖춰야 한다 —
        공식 출처, 원문, 그리고 확인한 사람.
        """
        if not self.verified:
            return self

        official = [s for s in self.sources if s.is_authoritative()]
        if not official:
            raise ValueError(
                f"{self.rule_id}: 업무 자료만으로는 검증됨으로 올릴 수 없다. "
                f"법령·판례·당국 자료가 있어야 한다"
            )
        missing = [s.ref for s in official if not (s.quote or "").strip()]
        if missing:
            raise ValueError(
                f"{self.rule_id}: 원문이 없는 근거가 있다({missing}). "
                f"요약만 남기면 나중에 그 요약이 맞는지 확인할 수 없다"
            )
        if not (self.verified_by or "").strip():
            raise ValueError(
                f"{self.rule_id}: 누가 확인했는지가 없다. 검증에는 책임자가 있어야 한다"
            )
        return self

    def summary(self) -> str:
        """감사 로그 한 줄. 등급이 내부 기준임을 문장 안에 남긴다."""
        srcs = " · ".join(s.label() for s in self.sources)
        mark = "" if self.verified else " (미검증)"
        return (
            f"{self.rule_id}{mark} — 근거 {srcs} → "
            f"{'; '.join(self.implication)} → 내부 조사등급 {self.grade.value}"
        )


# --------------------------------------------------------------------------- #
# 규칙 표
#
# 출처가 지금은 은행업무 교재(MANUAL)뿐이라 전부 verified=False 다. 이것을 감추면
# 교재를 법령처럼 보이게 하는 것이라, 드러내 두고 검증 자리를 비워 둔다.
#
# corpus_registry.md §12-3 이 이 표의 원본이다. 다만 그 표의 "책임등급·근거" 칸에
# 적힌 법적 결론(무권리자 지급 = 직접 배상 등)은 여기 옮기지 않는다 — 확인되지
# 않은 판단이기 때문이다. 옮기는 것은 사실·자료 위치·확인할 사항까지다.
# --------------------------------------------------------------------------- #
DECISIVE_FACT_RULES: dict[DecisiveFactKind, TriageRule] = {
    DecisiveFactKind.DEATH: TriageRule(
        rule_id="B-01",
        fact="계좌 명의인 사망 확인 (END_REASON=DEATH)",
        sources=[
            EvidenceSource(
                type=SourceType.MANUAL,
                ref="은행업무 자료 1_5 p10-16 (코퍼스 T2B-03)",
                note="상속인 확인·유언검인·상속포기/한정승인·분할 전 공유 관련",
            )
        ],
        implication=[
            "상속인 등 정당한 권리자인지 확인이 필요",
            "지급 적정성 검토가 필요",
        ],
        grade=LiabilityGrade.L4,
        weight=95,
        track="즉시",
        verified=False,
        verified_note="법령·판례 확인 전. 은행업무 자료 기준의 내부 우선순위다.",
    ),
    DecisiveFactKind.GUARDIANSHIP: TriageRule(
        rule_id="B-02",
        fact="성년후견 개시 확인 + 단독 거래",
        sources=[
            EvidenceSource(
                type=SourceType.MANUAL,
                ref="은행업무 자료 1_4 p13 (코퍼스 T2B-06)",
                note="제한능력자 구분 · 피한정후견은 원칙적으로 행위능력자이며 "
                     "일상행위는 취소 대상이 아님",
            )
        ],
        implication=[
            "후견 유형(성년/한정)과 법원이 정한 범위 확인이 필요",
            "일상적 거래인지 여부에 따라 취급이 달라짐 — 일률 거부 아님",
        ],
        grade=LiabilityGrade.L4,
        weight=92,
        track="즉시",
        verified=False,
        verified_note="법령·판례 확인 전. 후견 유형별 차등 취급은 자료에 근거.",
    ),
}


SCENARIO_RULES: dict[AttackScenario, TriageRule] = {
    AttackScenario.H1_VOICE_PHISHING: TriageRule(
        rule_id="B-06",
        fact="보이스피싱 정황 (신규 수취인 + 수취 네트워크 신호)",
        sources=[
            EvidenceSource(
                type=SourceType.MANUAL,
                ref="은행업무 자료 2_1 p21-22 (코퍼스 T3-02/03)",
                note="STR·CTR 보고 및 CDD/EDD 관련",
            )
        ],
        implication=["의심거래보고 대상인지 검토가 필요"],
        grade=LiabilityGrade.L3,
        weight=80,
        track="가중",
        verified=False,
        verified_note="법령·판례 확인 전.",
    ),
    AttackScenario.H3_LAUNDERING: TriageRule(
        rule_id="B-05",
        fact="차명·명의대여 정황 (신규계좌 즉시 대량이체 등)",
        sources=[
            EvidenceSource(
                type=SourceType.MANUAL,
                ref="은행업무 자료 1_4 p20 (코퍼스 T2A-03)",
                note="타인명의·차명 거래 및 예금주 확정 관련",
            )
        ],
        implication=["실지명의 확인 및 계좌 실소유자 검토가 필요"],
        grade=LiabilityGrade.L3,
        weight=82,
        track="가중",
        verified=False,
        verified_note="법령·판례 확인 전. 코퍼스가 '실명법 미업로드'로 표시한 영역.",
    ),
}


def for_decisive_fact(kind: DecisiveFactKind) -> TriageRule | None:
    return DECISIVE_FACT_RULES.get(kind)


def for_scenario(scenario: AttackScenario) -> TriageRule | None:
    """우세 가설의 규칙. 대응 행이 없으면 ``None``.

    ``None`` 은 결함이 아니라 사실이다 — 규정과 무관한 사건(정상)도 있고, 아직
    표에 없는 사건(내부자)도 있다. 없는 근거를 지어 붙이지 않는다.
    """
    return SCENARIO_RULES.get(scenario)


def grade_of(rule: TriageRule | None, fallback: LiabilityGrade) -> LiabilityGrade:
    """규칙이 있으면 그 등급을, 없으면 부르는 쪽 판단을 쓴다."""
    return rule.grade if rule is not None else fallback
