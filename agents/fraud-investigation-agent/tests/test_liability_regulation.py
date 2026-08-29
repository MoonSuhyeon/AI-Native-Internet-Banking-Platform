"""판정에 규정 근거가 붙는지 고정한다.

**무엇을 지키는가.** "등급이 매겨진다" 가 아니라 <b>"그 등급이 어느 규정에서
나왔는지 말할 수 있다"</b> 를 지킨다. 둘은 다르다 — 등급만 있으면 감사에서 "왜
L4인가" 의 답이 "결정적 사실이라서" 가 되고, 그건 등급을 정한 규칙 자신이라 순환이다.

**왜 코퍼스와 대조하는가.** 규정 근거를 코드에 적어 두면 문서가 개정될 때 조용히
어긋난다. 어긋난 쪽이 감사 기록으로 나가면 없는 근거를 댄 것이 되므로,
``corpus_registry.md`` 의 §12-3 표를 정본으로 두고 여기서 맞춰 본다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from agent import liability
from agent.models import AttackScenario, DecisiveFactKind, LiabilityGrade

CORPUS = Path(__file__).resolve().parents[1] / "corpus_registry.md"


class TestDecisiveFactRegulation:
    """사망·후견은 사실 확인이라 등급이 고정이다."""

    def test_사망은_L4_이고_규정_근거가_붙는다(self):
        ref = liability.for_decisive_fact(DecisiveFactKind.DEATH)
        assert ref is not None, "사망에 규정 근거가 없으면 L4 의 이유를 댈 수 없다"
        assert ref.clause == "T2B-03"
        assert ref.source == "1_5 p10-16"
        assert liability.grade_of(ref, LiabilityGrade.L0) == LiabilityGrade.L4

    def test_후견은_L4_이고_규정_근거가_붙는다(self):
        ref = liability.for_decisive_fact(DecisiveFactKind.GUARDIANSHIP)
        assert ref is not None
        assert ref.clause == "T2B-06"
        assert ref.source == "1_4 p13"
        assert liability.grade_of(ref, LiabilityGrade.L0) == LiabilityGrade.L4

    def test_근거_한_줄에_조항과_출처가_모두_들어간다(self):
        # 감사 로그는 한 줄로 남는다. 조항만 있으면 원문을 찾을 수 없고,
        # 출처만 있으면 우리 색인에서 되짚을 수 없다.
        label = liability.for_decisive_fact(DecisiveFactKind.DEATH).label()
        assert "T2B-03" in label and "1_5 p10-16" in label


class TestScenarioRegulation:
    def test_보이스피싱은_STR_근거로_L3(self):
        ref = liability.for_scenario(AttackScenario.H1_VOICE_PHISHING)
        assert ref is not None
        assert ref.rule_id == "B-06"
        assert liability.grade_of(ref, LiabilityGrade.L0) == LiabilityGrade.L3

    def test_자금세탁은_실명법_근거로_L3(self):
        ref = liability.for_scenario(AttackScenario.H3_LAUNDERING)
        assert ref is not None
        assert ref.rule_id == "B-05"
        assert liability.grade_of(ref, LiabilityGrade.L0) == LiabilityGrade.L3

    def test_근거가_없으면_지어내지_않는다(self):
        # 정상(H5)은 규정 위반이 아니고, 내부자(H4)는 §12-3 에 대응 행이 없다.
        # 없는 근거를 만들어 붙이면 이 표를 둔 이유가 사라진다.
        assert liability.for_scenario(AttackScenario.H5_BENIGN) is None
        assert liability.for_scenario(AttackScenario.H4_INSIDER) is None

    def test_근거가_없으면_부르는_쪽_등급을_쓴다(self):
        assert liability.grade_of(None, LiabilityGrade.L2) == LiabilityGrade.L2


class TestCorpusAgreement:
    """코드의 근거가 코퍼스 표와 어긋나지 않는다."""

    @pytest.fixture(scope="class")
    def corpus(self) -> str:
        """§12-3 책임 등급표 구간만 잘라 온다.

        같은 ``B-01`` 이 B층 상품·지식표에도 있어, 문서 전체에서 찾으면 엉뚱한 행을
        집는다 — 처음에 그렇게 짰다가 가중치가 안 맞아 걸렸다. 대조할 표를
        정확히 가리키는 것까지가 이 검사의 몫이다.
        """
        assert CORPUS.exists(), f"코퍼스를 찾지 못했다: {CORPUS}"
        text = CORPUS.read_text(encoding="utf-8")

        start = text.index("### 12-3.")
        end = text.index("### 12-4.", start)
        section = text[start:end]
        assert "책임등급" in section, "§12-3 구간을 잘못 잘랐다"
        return section

    @pytest.mark.parametrize("ref", [
        *liability.DECISIVE_FACT_REGULATION.values(),
        *liability.SCENARIO_REGULATION.values(),
    ], ids=lambda r: r.rule_id)
    def test_등급표에_그_행이_있다(self, ref, corpus):
        # §12-3 표의 행 번호(B-01 …)가 실제로 있어야 한다. 없으면 코드가
        # 존재하지 않는 규정을 인용하는 것이고, 그것이 감사 기록으로 나간다.
        assert re.search(rf"\|\s*{re.escape(ref.rule_id)}\s*\|", corpus), (
            f"{ref.rule_id} 이 corpus_registry §12-3 표에 없다"
        )

    @pytest.mark.parametrize("ref", [
        *liability.DECISIVE_FACT_REGULATION.values(),
        *liability.SCENARIO_REGULATION.values(),
    ], ids=lambda r: r.rule_id)
    def test_가중치가_등급표와_같다(self, ref, corpus):
        # 가중치는 트리아지 정렬 값이다. 코드와 표가 다르면 "사망계좌가 맨 위"
        # 라는 주장이 문서에서만 참이 된다.
        row = next(l for l in corpus.split("\n")
                   if re.search(rf"\|\s*{re.escape(ref.rule_id)}\s*\|", l))
        assert re.search(rf"\|\s*{ref.weight}\s*\|", row), (
            f"{ref.rule_id} 가중치가 표({row.strip()[:70]})와 다르다: 코드={ref.weight}"
        )


class TestRecommendationCarriesRegulation:
    """권고 응답에 실제로 실려 나가는지 — 모듈에만 있으면 소용없다."""

    def test_사망_판정에_규정이_실린다(self):
        from agent.models import AgentState, Alert, DecisiveFact, TxContext

        state = AgentState(
            alert=Alert(
                id="ALT-T", account="110-1", customer_id="C-1",
                tx_context=TxContext(amount=300_000), anomaly_score=15.0,
            ),
            decisive_fact=DecisiveFact(kind=DecisiveFactKind.DEATH, source="get_party"),
        )

        from agent.recommend import build_recommendation
        rec = build_recommendation(state)

        assert rec.liability_grade == LiabilityGrade.L4
        assert rec.regulation is not None, "권고에 규정 근거가 실리지 않았다"
        assert rec.regulation["clause"] == "T2B-03"
        assert any("규정 근거" in line for line in rec.rationale_chain), (
            "근거 사슬에도 규정이 남아야 감사에서 한 줄로 읽힌다"
        )
