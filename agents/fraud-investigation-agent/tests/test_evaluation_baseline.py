"""저장된 사건 7건의 채점 기준선 — 회귀 게이트.

`scripts/evaluate_cases.py` 가 사람이 보는 표라면, 이 파일은 **빌드가 보는 표**다.
플래너·도구 표·가설 갱신 계수를 건드렸을 때 조사 품질이 나빠지면 여기서 걸린다.

**여기 적힌 숫자는 목표가 아니라 현재값이다.** 지금 기본 플래너는 목(mock)이고
7건 중 4건이 예산의 절반을 판별에 못 쓰고 있다. 그 사실을 고정해 두는 이유는,
고쳐서 좋아지면 이 테스트가 실패하며 "좋아졌으니 기준선을 올려라" 라고 알려주기
때문이다. 나빠져도 실패한다. 어느 쪽이든 조용히 넘어가지 않는 것이 목적이다.

**불변식과 기준선은 다르다.** 아래 :class:`TestInvariants` 는 어떤 플래너를 쓰든
지켜져야 하는 것이고, :class:`TestBaseline` 은 지금 이 플래너의 성적표다.
"""

from __future__ import annotations

import pytest

from agent.evaluation import ToolChoiceVerdict, score_investigation
from agent.graph import run_investigation
from agent.tools import CASES_DIR, load_case

CASE_NAMES = sorted(p.stem for p in CASES_DIR.glob("*.json"))

# 사건 → 판별에 쓴 예산 비율. 목 플래너 기준 현재값이다.
BASELINE = {
    "case_death": 1.0,
    "case_deceased": 1.0,
    "case_h1": 1.0,
    "case_h1_flipped": 0.5,
    "case_h2": 0.5,
    "case_h5": 0.5,
    "case_provisional": 0.5,
}


@pytest.fixture(scope="module")
def scores():
    return {name: score_investigation(run_investigation(load_case(name))) for name in CASE_NAMES}


class TestInvariants:
    """플래너를 바꿔도 지켜져야 하는 것."""

    def test_모든_사건이_fail_closed_를_지킨다(self, scores):
        # 결정적 사실이 나온 뒤 더 뒤지면 통제가 뚫린 것이다. 성능 문제가 아니다.
        violated = [n for n, s in scores.items() if not s.fail_closed_respected]
        assert violated == [], f"fail-closed 위반: {violated}"

    def test_같은_도구를_두_번_부르지_않는다(self, scores):
        # 중복 호출은 어떤 상황에서도 얻는 게 없다. 나오면 루프 제어 결함이다.
        offenders = {n: s.count(ToolChoiceVerdict.REDUNDANT) for n, s in scores.items()}
        assert all(c == 0 for c in offenders.values()), f"중복 호출: {offenders}"

    def test_결정적_사실이_있는_사건은_한_번에_끝난다(self, scores):
        # 사망 사건에서 예산을 더 쓰면 fail-closed 가 이름뿐이라는 뜻이다.
        for name in ("case_death", "case_deceased"):
            assert scores[name].budget_used == 1, f"{name} 이 {scores[name].budget_used}회 조사했다"

    def test_예산_상한을_넘지_않는다(self, scores):
        # 기본 예산 6. 넘으면 게이트가 안 막은 것이다.
        for name, score in scores.items():
            assert score.budget_used <= 6, f"{name} 이 예산 {score.budget_used} 을 썼다"


class TestBaseline:
    """지금 플래너의 성적표. 좋아져도 나빠져도 실패한다."""

    @pytest.mark.parametrize("name", CASE_NAMES)
    def test_판별_비율이_기준선과_같다(self, scores, name):
        assert name in BASELINE, f"새 사건 {name} — BASELINE 에 현재값을 적을 것"
        assert scores[name].productive_ratio == pytest.approx(BASELINE[name]), (
            f"{name} 판별 비율이 바뀌었다. 좋아졌으면 BASELINE 을 올리고, "
            f"나빠졌으면 원인을 찾을 것"
        )

    def test_채점이_결정적이다(self):
        # 두 번 돌려 다르면 회귀 비교 자체가 성립하지 않는다.
        a = score_investigation(run_investigation(load_case("case_h5")))
        b = score_investigation(run_investigation(load_case("case_h5")))
        assert a.verdicts == b.verdicts
