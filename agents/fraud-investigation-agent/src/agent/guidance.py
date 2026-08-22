"""소비자 안내 다듬기 — 탐지 근거를 그 사람이 읽을 수 있는 말로.

**무엇을 푸는가.** 이체가 이상거래로 멈추면 탐지기(fds-detector)가 근거와 행동요령을
규칙으로 확정해 보낸다. 그 문구는 *누가 읽어도 성립하는 기본형*이다. 여기서는 그것을
**읽는 사람에 맞춰** 다듬는다.

**왜 여기서 다듬는가.** 기본형을 만드는 자리는 돈이 움직이는 경로 안이라 수백 ms
예산에 묶여 있고, 생성 모델을 부를 자리가 없다. 화면은 기본형을 **먼저 그린 뒤**
이 결과가 도착하면 갈아끼운다. 늦게 와도, 안 와도 화면은 성립한다.

**왜 규칙이 먼저인가.** 이 안내는 은행 화면에 뜬다. 모델이 죽었거나 키가 없을 때
문구가 비면 차단만 되고 이유가 없는 화면이 되는데, 그건 고치려던 문제보다 나쁘다.
그래서 ``tailor`` 는 **모델 없이 완결**되고, LLM 은 그 위에 얹는 선택지다.

**모델이 못 하는 것.** 등급·근거·선택지는 손대지 않는다. 모델이 바꿀 수 있는 것은
*말투와 순서*뿐이다. 근거를 지어내거나 "안전하니 보내세요" 로 뒤집는 일이 없도록,
LLM 결과는 반드시 ``_safe_merge`` 를 거쳐 원래 근거·선택지를 되돌려 놓는다.
"""

from __future__ import annotations

import os
from enum import Enum

from pydantic import BaseModel, Field


class AudienceBand(str, Enum):
    """안내를 맞출 대상 구간. customer-service 의 같은 이름 열거형과 값이 같다."""

    MINOR = "MINOR"
    YOUNG_ADULT = "YOUNG_ADULT"
    GENERAL = "GENERAL"
    SENIOR = "SENIOR"
    UNKNOWN = "UNKNOWN"


class Guidance(BaseModel):
    """화면에 뜨는 안내 한 벌."""

    headline: str
    evidence: list[str] = Field(default_factory=list)
    action_steps: list[str] = Field(default_factory=list)
    choices: list[str] = Field(default_factory=list)
    #: 어느 구간에 맞췄는가. 화면은 안 쓰고 지표가 쓴다 — "맞춤 안내가 실제로
    #: 얼마나 적용됐나" 를 세려면 UNKNOWN 과 GENERAL 이 구별돼야 한다.
    audience: str = AudienceBand.UNKNOWN.value
    #: 모델이 다듬었는가. false 면 규칙만으로 만든 것이다.
    llm_refined: bool = False


# --------------------------------------------------------------------------- #
# 구간별 규칙 — 모델 없이 여기까지는 항상 된다
# --------------------------------------------------------------------------- #

#: 고령층 안내의 첫 줄. 보이스피싱은 이 한 통으로 대부분 끊기고, 목록을 끝까지
#: 읽지 않는 사람에게는 첫 줄이 사실상 안내 전부다.
_SENIOR_FIRST = "잠깐 멈추세요. 돈을 보내기 전에 받는 사람에게 직접 전화해 확인하세요."

_SENIOR_TAIL = (
    "은행 직원이나 검찰·경찰은 절대 돈을 보내라고 하지 않습니다. "
    "이상하면 끊고 1588-9999로 전화해 주세요."
)

#: 사회초년생은 취업·대출 사기의 표적이다. "기관을 사칭한다" 는 사실 자체를
#: 모르는 경우가 많아, 수법을 한 줄로 짚어 준다.
_YOUNG_ADULT_TAIL = (
    "채용·대출을 미끼로 계좌나 돈을 요구하는 것은 사기입니다. "
    "먼저 보내면 돌려받기 어렵습니다."
)

_MINOR_TAIL = "보내기 전에 보호자와 상의하세요."


def tailor(base: Guidance, band: AudienceBand) -> Guidance:
    """구간에 맞춰 안내를 다듬는다. 모델을 쓰지 않는다.

    근거(evidence)와 선택지(choices)는 건드리지 않는다 — 심사원이 보는 것과 고객이
    보는 것이 어긋나면 나중에 분쟁에서 설명이 갈린다.
    """
    steps = list(base.action_steps)

    if band is AudienceBand.SENIOR:
        # 첫 줄을 전화 확인으로 고정한다. 이미 비슷한 줄이 있어도 순서를 올린다.
        steps = [_SENIOR_FIRST] + [s for s in steps if not s.startswith("보내기 전에")]
        steps.append(_SENIOR_TAIL)
    elif band is AudienceBand.YOUNG_ADULT:
        steps.append(_YOUNG_ADULT_TAIL)
    elif band is AudienceBand.MINOR:
        steps.insert(0, _MINOR_TAIL)

    return Guidance(
        headline=base.headline,
        evidence=list(base.evidence),
        action_steps=steps,
        choices=list(base.choices),
        audience=band.value,
        llm_refined=False,
    )


# --------------------------------------------------------------------------- #
# 모델 다듬기 — 있으면 얹고, 없으면 위 결과가 그대로 답이다
# --------------------------------------------------------------------------- #

_SYSTEM = """당신은 은행의 이상거래 안내 문구를 다듬는 편집자입니다.

말투와 난이도만 바꿉니다. 판단은 이미 끝났고 당신이 뒤집을 수 없습니다.

규칙:
- 항목 수를 늘리거나 줄이지 마세요. 받은 개수 그대로 다시 쓰세요.
- 사실을 새로 만들지 마세요. 금액·횟수·날짜를 지어내면 안 됩니다.
- "안전합니다", "보내도 됩니다" 처럼 안심시키는 말은 절대 쓰지 마세요.
  이 화면은 이미 위험 신호가 잡힌 뒤에 뜹니다.
- 한 항목은 두 문장을 넘기지 마세요.
- JSON 만 출력하세요: {"action_steps": ["...", "..."]}
"""

_USER = """대상: {audience_desc}

행동요령:
{steps}
"""

_AUDIENCE_DESC = {
    AudienceBand.SENIOR: "고령층. 짧고 쉬운 문장. 어려운 금융 용어를 쓰지 마세요.",
    AudienceBand.YOUNG_ADULT: "사회초년생. 취업·대출 사기 수법을 모를 수 있습니다.",
    AudienceBand.MINOR: "미성년자. 쉬운 말로, 보호자와 상의하도록.",
    AudienceBand.GENERAL: "일반 성인.",
    AudienceBand.UNKNOWN: "일반 성인.",
}


def _llm_enabled() -> bool:
    """모델을 부를 것인가.

    기본은 끔이다. 이 경로는 고객 화면에 걸려 있어서, 켜는 것은 지연·비용·실패를
    같이 받아들이겠다는 결정이라 명시적이어야 한다.
    """
    return os.getenv("FRAUD_GUIDANCE_LLM", "").strip().lower() in {"1", "true", "on"}


def _safe_merge(base: Guidance, steps: list[str]) -> Guidance:
    """모델 결과를 원래 안내에 얹는다 — 바꿀 수 있는 것은 말투뿐.

    항목 수가 달라졌거나 비었으면 **통째로 버린다.** 모델이 항목을 합치거나 지우면
    그건 다듬기가 아니라 내용 변경이고, 그 결과가 맞는지 확인할 방법이 없다.
    """
    cleaned = [s.strip() for s in steps if isinstance(s, str) and s.strip()]
    if len(cleaned) != len(base.action_steps):
        return base
    return Guidance(
        headline=base.headline,
        evidence=list(base.evidence),      # 근거는 모델이 손대지 못한다
        action_steps=cleaned,
        choices=list(base.choices),        # 선택지도 마찬가지
        audience=base.audience,
        llm_refined=True,
    )


def refine(base: Guidance, band: AudienceBand) -> Guidance:
    """규칙으로 다듬고, 모델이 켜져 있으면 말투만 한 번 더 다듬는다."""
    ruled = tailor(base, band)
    if not _llm_enabled() or not ruled.action_steps:
        return ruled

    try:
        from .llm import _parse_json, get_llm_client

        raw = get_llm_client().refine_consumer_text(
            _SYSTEM,
            _USER.format(
                audience_desc=_AUDIENCE_DESC[band],
                steps="\n".join(f"- {s}" for s in ruled.action_steps),
            ),
        )
        return _safe_merge(ruled, _parse_json(raw).get("action_steps", []))
    except Exception:
        # 모델이 죽어도(키 없음·타임아웃·mock 미구현) 안내는 나가야 한다.
        # 규칙 결과가 이미 완결이라 여기서 조용히 되돌아가는 것이 맞다.
        return ruled


# --------------------------------------------------------------------------- #
# 연령대 조회 — 실패하면 조용히 UNKNOWN
# --------------------------------------------------------------------------- #
def lookup_audience(customer_id: str) -> AudienceBand:
    """customer-service 에서 이 고객의 안내 대상 구간을 가져온다.

    **생년월일을 받지 않는다.** 구간만 받는다 — customer-service 의 내부 API 가
    경계에서 줄여서 내보낸다. 여기로 날짜가 넘어오면 로그·트레이스·프롬프트에
    남는다.

    **실패는 조용하다.** 못 가져오면 ``UNKNOWN`` 이다. 맞춤이 안 되는 것과 안내가
    통째로 사라지는 것은 다르고, 후자가 훨씬 나쁘다. 대신 얼마나 실패하는지는
    지표로 남긴다 — 조용한 실패는 재야 보인다.
    """
    base = os.getenv("TRIAGE_BACKEND_URL", "").rstrip("/")
    if not base:
        return AudienceBand.UNKNOWN

    try:
        import httpx  # 지연 import — 이 경로에서만 필요

        from .tools import _internal_headers

        resp = httpx.get(
            f"{base}/api/v1/internal/audience/{customer_id}",
            headers=_internal_headers(),
            timeout=2.0,   # 화면이 기다리는 경로다. 길게 잡으면 안내가 늦게 뜬다.
        )
        resp.raise_for_status()
        band = (resp.json().get("data") or {}).get("band")
        return AudienceBand(band)
    except Exception:
        from .metrics import fraud_guidance_audience_lookup_failed_total

        fraud_guidance_audience_lookup_failed_total.inc()
        return AudienceBand.UNKNOWN
