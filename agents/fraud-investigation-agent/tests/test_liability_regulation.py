"""조사 우선순위 등급의 근거 구조를 고정한다.

**무엇을 지키는가.** "등급이 매겨진다" 가 아니다. <b>등급과 법적 판단이 섞이지
않는 것</b>을 지킨다.

이전 판은 ``T2B-03 → L4`` 한 줄이었고 근거 문구가 "무권리자 지급으로 은행 직접
배상" 이었다. 그건 우리가 말할 자격이 없는 결론이고, 심사에서 "L4가 법에 있는
등급이냐" 는 질문에 그렇다고 답하는 모양이 된다.

그래서 여기서는 셋을 본다.

1. 층이 나뉘어 있는가 — 사실 / 근거 자료 / 확인할 사항 / 내부 등급
2. 법적 결론을 말하지 않는가 — 자료가 말하는 것까지만
3. 검증 상태를 감추지 않는가 — 미검증 근거를 법령처럼 보이게 하지 않는가
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from agent import liability
from agent.liability import SourceType
from agent.models import AttackScenario, DecisiveFactKind, LiabilityGrade

CORPUS = Path(__file__).resolve().parents[1] / "corpus_registry.md"

ALL_RULES = [
    *liability.DECISIVE_FACT_RULES.values(),
    *liability.SCENARIO_RULES.values(),
]


class TestLayersAreSeparated:
    """사실 · 근거 · 의미 · 등급이 한 줄로 뭉쳐 있지 않다."""

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_네_층이_모두_채워져_있다(self, rule):
        assert rule.fact, f"{rule.rule_id}: 어떤 사실에 붙는 규칙인지 없다"
        assert rule.sources, f"{rule.rule_id}: 근거 자료가 없다"
        assert rule.implication, f"{rule.rule_id}: 확인할 사항이 없다"
        assert rule.grade, f"{rule.rule_id}: 내부 등급이 없다"

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_근거에_원문_위치가_남는다(self, rule):
        # 되짚을 수 없는 근거는 근거가 아니다. 사람이 검증할 때 이 위치로 찾는다.
        for src in rule.sources:
            assert src.ref.strip(), f"{rule.rule_id}: 원문 위치가 비어 있다"


class TestNoLegalConclusion:
    """자료가 말하는 것까지만 적는다 — 법적 결론은 우리가 내리지 않는다."""

    # 책임의 소재·귀속을 단정하는 표현. 사건 경위·다른 법률·판례가 함께 걸리는
    # 판단이라 이 표에서 결론지을 수 없다.
    FORBIDDEN = ["직접 배상", "배상책임", "무효이다", "위법하다", "책임을 진다", "면책된다"]

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_확인할_사항에_법적_결론이_없다(self, rule):
        text = " ".join(rule.implication)
        for word in self.FORBIDDEN:
            assert word not in text, (
                f"{rule.rule_id}: '{word}' 는 법적 결론이다. "
                f"자료가 말하는 '무엇을 확인해야 하는가' 까지만 적을 것"
            )

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_자료_요약에도_법적_결론이_없다(self, rule):
        for src in rule.sources:
            for word in self.FORBIDDEN:
                assert word not in (src.note or ""), (
                    f"{rule.rule_id}: 자료 요약에 '{word}' 가 들어 있다"
                )


class TestVerificationIsVisible:
    """미검증 근거를 검증된 것처럼 보이게 하지 않는다."""

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_미검증이면_사유가_적혀_있다(self, rule):
        if not rule.verified:
            assert rule.verified_note, (
                f"{rule.rule_id}: 미검증인데 왜 미검증인지가 없다. "
                f"비어 있으면 다음 사람이 검증된 것으로 오해한다"
            )

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_미검증_규칙은_법령_판례를_근거로_대지_않는다(self, rule):
        # 사람이 확인하기 전에 법령·판례를 인용하면, 그 인용이 곧 법적 주장이 된다.
        if rule.verified:
            return
        kinds = {s.type for s in rule.sources}
        assert SourceType.STATUTE not in kinds and SourceType.PRECEDENT not in kinds, (
            f"{rule.rule_id}: 미검증인데 법령/판례를 인용한다. "
            f"사람이 확인한 뒤 verified=True 로 바꾸고 인용할 것"
        )

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_요약에_미검증_표시가_드러난다(self, rule):
        # 감사 로그는 한 줄이다. 그 줄만 보고도 검증 여부를 알 수 있어야 한다.
        if not rule.verified:
            assert "미검증" in rule.summary(), f"{rule.rule_id}: 요약에 표시가 없다"

    def test_지금_근거는_전부_업무자료다(self):
        # 현재 코퍼스는 은행업무 교재다. 법령 원문이 아니다.
        # 이 사실이 바뀌면(법령 확인 완료) 이 테스트를 고치는 것이 그 작업의 일부다.
        kinds = {s.type for r in ALL_RULES for s in r.sources}
        assert kinds == {SourceType.MANUAL}, (
            f"근거 종류가 바뀌었다: {kinds}. 검증을 마쳤다면 verified 도 함께 올릴 것"
        )


class TestGradeIsInternal:
    def test_등급은_규칙에서_나온다(self):
        assert liability.grade_of(
            liability.for_decisive_fact(DecisiveFactKind.DEATH), LiabilityGrade.L0
        ) == LiabilityGrade.L4

    def test_규칙이_없으면_부르는_쪽_등급을_쓴다(self):
        assert liability.grade_of(None, LiabilityGrade.L2) == LiabilityGrade.L2

    def test_근거가_없으면_지어내지_않는다(self):
        assert liability.for_scenario(AttackScenario.H5_BENIGN) is None
        assert liability.for_scenario(AttackScenario.H4_INSIDER) is None


class TestCorpusAgreement:
    """규칙 번호·가중치가 코퍼스 §12-3 과 어긋나지 않는다."""

    @pytest.fixture(scope="class")
    def corpus(self) -> str:
        """§12-3 구간만 잘라 온다.

        같은 ``B-01`` 이 B층 상품·지식표에도 있어, 문서 전체에서 찾으면 엉뚱한 행을
        집는다 — 처음에 그렇게 짰다가 가중치가 안 맞아 걸렸다.
        """
        assert CORPUS.exists(), f"코퍼스를 찾지 못했다: {CORPUS}"
        text = CORPUS.read_text(encoding="utf-8")
        start = text.index("### 12-3.")
        end = text.index("### 12-4.", start)
        section = text[start:end]
        assert "책임등급" in section, "§12-3 구간을 잘못 잘랐다"
        return section

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_등급표에_그_행이_있다(self, rule, corpus):
        assert re.search(rf"\|\s*{re.escape(rule.rule_id)}\s*\|", corpus), (
            f"{rule.rule_id} 이 corpus_registry §12-3 표에 없다"
        )

    @pytest.mark.parametrize("rule", ALL_RULES, ids=lambda r: r.rule_id)
    def test_가중치가_등급표와_같다(self, rule, corpus):
        row = next(l for l in corpus.split("\n")
                   if re.search(rf"\|\s*{re.escape(rule.rule_id)}\s*\|", l))
        assert re.search(rf"\|\s*{rule.weight}\s*\|", row), (
            f"{rule.rule_id} 가중치가 표와 다르다: 코드={rule.weight}"
        )


class TestRecommendationCarriesRule:
    """권고에 실제로 실려 나가는지 — 모듈에만 있으면 소용없다."""

    def test_사망_판정에_근거가_실린다(self):
        from agent.models import AgentState, Alert, DecisiveFact, TxContext
        from agent.recommend import build_recommendation

        state = AgentState(
            alert=Alert(
                id="ALT-T", account="110-1", customer_id="C-1",
                tx_context=TxContext(amount=300_000), anomaly_score=15.0,
            ),
            decisive_fact=DecisiveFact(kind=DecisiveFactKind.DEATH, source="get_party"),
        )
        rec = build_recommendation(state)

        assert rec.liability_grade == LiabilityGrade.L4
        assert rec.regulation is not None, "권고에 근거가 실리지 않았다"
        assert rec.regulation["fact"], "관찰된 사실이 없다"
        assert rec.regulation["implication"], "확인할 사항이 없다"
        assert rec.regulation["verified"] is False, "검증 상태가 잘못 실렸다"
        assert any("조사등급 근거" in line for line in rec.rationale_chain)


class TestPromotionGuard:
    """``verified=True`` 는 플래그가 아니라 조건이다.

    플래그 하나로 근거의 질이 바뀌면 "검증됨" 이 아무 의미가 없다. 나중에 실제
    법령을 찾아 올릴 때, 실수로 혹은 편의로 플래그만 켜는 것을 막는다.
    """

    def _sources(self, **over):
        base = dict(
            type=SourceType.STATUTE,
            ref="○○법 제○조",
            quote="조문 원문",
        )
        base.update(over)
        return [liability.EvidenceSource(**base)]

    def _rule(self, **over):
        base = dict(
            rule_id="T-1",
            fact="사실",
            sources=self._sources(),
            implication=["확인 필요"],
            grade=LiabilityGrade.L4,
            weight=95,
            track="즉시",
            verified=True,
            verified_by="컴플라이언스 검토자",
        )
        base.update(over)
        return liability.TriageRule(**base)

    def test_조건을_갖추면_올라간다(self):
        assert self._rule().verified is True

    def test_업무자료만으로는_못_올린다(self):
        # 교재를 법령처럼 쓰는 것이 이 프로젝트가 피하려던 바로 그것이다.
        with pytest.raises(ValueError, match="업무 자료만으로는"):
            self._rule(sources=self._sources(type=SourceType.MANUAL))

    def test_원문이_없으면_못_올린다(self):
        # 요약만 남기면 그 요약이 맞는지 나중에 확인할 방법이 없다.
        with pytest.raises(ValueError, match="원문이 없는"):
            self._rule(sources=self._sources(quote=None))

    def test_공백_원문도_원문이_아니다(self):
        with pytest.raises(ValueError, match="원문이 없는"):
            self._rule(sources=self._sources(quote="   "))

    def test_확인한_사람이_없으면_못_올린다(self):
        # 검증에는 책임자가 있어야 한다. 없으면 나중에 누구에게도 물을 수 없다.
        with pytest.raises(ValueError, match="누가 확인했는지"):
            self._rule(verified_by=None)

    def test_미검증은_조건을_따지지_않는다(self):
        # 지금 상태(업무자료·원문 없음)가 그대로 통과해야 한다 —
        # 미검증이라고 정직하게 표시한 것을 막으면 안 된다.
        rule = self._rule(
            verified=False, verified_by=None,
            sources=self._sources(type=SourceType.MANUAL, quote=None),
        )
        assert rule.verified is False

    def test_지금_규칙들은_승격_조건을_통과하지_못한다(self):
        # 현재 근거는 전부 업무자료다. 플래그만 켜면 막혀야 한다 —
        # 이것이 통과하면 승격 조건이 실제로는 안 걸린다는 뜻이다.
        for rule in ALL_RULES:
            with pytest.raises(ValueError):
                rule.model_copy(update={"verified": True}).model_validate(
                    rule.model_copy(update={"verified": True}).model_dump()
                )
