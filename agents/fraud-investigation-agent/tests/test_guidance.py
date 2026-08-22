"""소비자 안내 다듬기.

**무엇을 지키는가.** 이 안내는 이체가 막힌 직후 고객 화면에 뜬다. 두 가지가
동시에 성립해야 한다.

1. 모델이 없어도 **반드시 문구가 나온다.** 차단만 되고 이유가 없는 화면은
   고치려던 문제보다 나쁘다.
2. 모델이 있어도 **판단을 뒤집지 못한다.** 근거와 선택지는 규칙이 정한 그대로다.

두 번째가 특히 중요하다. 여기서 모델이 "안전하니 보내세요" 를 만들어 낼 수 있으면,
보이스피싱범이 시킨 송금을 은행이 거들게 된다.
"""

from __future__ import annotations

import agent.guidance as g
from agent.guidance import AudienceBand, Guidance, refine, tailor


def _base(steps: list[str] | None = None) -> Guidance:
    return Guidance(
        headline="최근 피해 사례와 비슷한 패턴이라 이체를 멈췄습니다",
        evidence=["고액 이체 12000000원"],
        action_steps=steps if steps is not None else ["보내기 전에 받는 분에게 직접 전화해 확인하세요."],
        choices=["CANCEL", "CONSULT"],
    )


class TestRulesAlone:
    """모델 없이도 완결이다."""

    def test_고령층은_전화_확인이_첫_줄이다(self):
        out = tailor(_base(), AudienceBand.SENIOR)
        assert "전화" in out.action_steps[0]
        assert out.audience == "SENIOR"

    def test_고령층_안내는_사칭_경고로_끝난다(self):
        out = tailor(_base(), AudienceBand.SENIOR)
        assert "검찰" in out.action_steps[-1] or "사칭" in out.action_steps[-1]

    def test_사회초년생은_취업_대출_사기_수법을_덧붙인다(self):
        out = tailor(_base(), AudienceBand.YOUNG_ADULT)
        assert any("채용" in s or "대출" in s for s in out.action_steps)

    def test_미성년은_보호자_상의가_먼저다(self):
        out = tailor(_base(), AudienceBand.MINOR)
        assert "보호자" in out.action_steps[0]

    def test_일반과_모름은_원래_문구를_바꾸지_않는다(self):
        base = _base()
        for band in (AudienceBand.GENERAL, AudienceBand.UNKNOWN):
            assert tailor(base, band).action_steps == base.action_steps

    def test_구간이_무엇이든_근거와_선택지는_그대로다(self):
        """심사원이 보는 근거와 고객이 보는 근거가 갈리면 분쟁에서 설명이 어긋난다."""
        base = _base()
        for band in AudienceBand:
            out = tailor(base, band)
            assert out.evidence == base.evidence
            assert out.choices == base.choices
            assert out.headline == base.headline


class TestModelStaysInItsLane:
    """모델은 말투만 바꾼다."""

    def test_모델이_꺼져_있으면_규칙_결과가_그대로_답이다(self, monkeypatch):
        monkeypatch.delenv("FRAUD_GUIDANCE_LLM", raising=False)
        out = refine(_base(), AudienceBand.SENIOR)
        assert out.llm_refined is False
        assert out.action_steps  # 비어 있지 않다

    def test_모델이_죽어도_안내는_나간다(self, monkeypatch):
        monkeypatch.setenv("FRAUD_GUIDANCE_LLM", "1")

        def boom():
            raise RuntimeError("모델 없음")

        monkeypatch.setattr("agent.llm.get_llm_client", boom)
        out = refine(_base(), AudienceBand.SENIOR)
        assert out.llm_refined is False
        assert "전화" in out.action_steps[0]

    def test_항목_수가_달라지면_모델_결과를_통째로_버린다(self):
        """항목을 합치거나 지우는 것은 다듬기가 아니라 내용 변경이다."""
        ruled = tailor(_base(["첫째", "둘째"]), AudienceBand.GENERAL)
        merged = g._safe_merge(ruled, ["하나로 합쳤습니다"])
        assert merged.action_steps == ruled.action_steps
        assert merged.llm_refined is False

    def test_모델은_근거와_선택지를_건드리지_못한다(self):
        ruled = tailor(_base(["첫째"]), AudienceBand.GENERAL)
        merged = g._safe_merge(ruled, ["다시 쓴 첫째"])
        assert merged.llm_refined is True
        assert merged.action_steps == ["다시 쓴 첫째"]
        assert merged.evidence == ruled.evidence
        assert merged.choices == ruled.choices


class TestAudienceLookup:
    """연령대 조회는 실패해도 조용하다 — 대신 센다."""

    def test_백엔드가_설정되지_않으면_UNKNOWN(self, monkeypatch):
        monkeypatch.delenv("TRIAGE_BACKEND_URL", raising=False)
        assert g.lookup_audience("9111") is AudienceBand.UNKNOWN

    def test_조회가_실패하면_UNKNOWN_이고_실패를_센다(self, monkeypatch):
        monkeypatch.setenv("TRIAGE_BACKEND_URL", "http://localhost:1")
        before = g_failed_count()
        assert g.lookup_audience("9111") is AudienceBand.UNKNOWN
        assert g_failed_count() > before


def g_failed_count() -> float:
    from agent.metrics import fraud_guidance_audience_lookup_failed_total

    return fraud_guidance_audience_lookup_failed_total._value.get()
