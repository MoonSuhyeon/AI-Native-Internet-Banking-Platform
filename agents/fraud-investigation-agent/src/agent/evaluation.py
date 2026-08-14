"""조사 채점 — LLM 심판 없이, Tool Matrix 를 정답지로 쓴다.

**왜 LLM 심판을 안 쓰나.** 표준 방식은 gpt-4o 같은 모델에게 "이 선택이 타당했나" 를
묻는 것이다. 여기서는 두 가지가 걸린다. 첫째, 심판에 넘기는 것은 조사 기록이고
거기엔 고객 거래·인증 이력이 들어 있다 — 외부로 나가는 경로가 하나 더 생긴다.
둘째, 같은 입력에 같은 점수가 나오지 않으면 감사에서 설명할 수 없다.

**그럴 필요가 없다.** ``tool_matrix.py`` 가 도구마다 *어떤 시나리오 쌍을 가르는지*
를 이미 결정적으로 적어 두었다. 그러면 "그 시점에 무엇이 경합 중이었나" 와 대조하는
것만으로 채점이 된다. 채점기가 결정적이면 회귀 비교도 의미를 갖는다.

**무엇을 채점하나.** 셋이고, 셋 다 서로 다른 실패를 잡는다.

- :func:`score_tool_choice` — 예산을 판별에 썼나, 아니면 흘렸나
- :func:`fail_closed_respected` — 결정적 사실이 나온 뒤에 더 뒤졌나 (원칙 1 위반)
- :func:`score_investigation` — 위 둘을 조사 1건으로 묶은 값

**한계.** 매트릭스가 정답지이므로 매트릭스가 틀리면 채점도 틀린다. 이 채점은
"플래너가 매트릭스에 적힌 분별력을 실제로 활용했나" 를 볼 뿐, 매트릭스 자체가 옳은지는
말하지 않는다. 그건 도메인 검토의 몫이다.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field

from .models import AgentState, AttackScenario, ToolLogEntry
from .tool_matrix import TOOL_MATRIX


class ToolChoiceVerdict(str, Enum):
    """도구 선택 1회에 대한 판정.

    앞의 셋은 예산을 쓸 값어치가 있었다는 뜻이고, 뒤의 셋은 각각 다른 이유로
    흘렸다는 뜻이다. 뭉뚱그리면 원인별로 고칠 수 없어 나눠 둔다.
    """

    DECISIVE_PROBE = "DECISIVE_PROBE"  # 결정적 사실을 낼 수 있는 도구 — 언제 불러도 정당
    INFORMATIVE = "INFORMATIVE"        # 경합 중인 시나리오 쌍을 가른다
    TAG_REVEALING = "TAG_REVEALING"    # 시나리오는 못 가르나 아직 안 켜진 태그를 켤 수 있다
    SETTLED = "SETTLED"                # 가르긴 하는데 그 쌍이 이미 닫혔다 — 플래너 오판
    CONTEXT_ONLY = "CONTEXT_ONLY"      # 애초에 가르는 게 없는 baseline 도구
    REDUNDANT = "REDUNDANT"            # 이미 부른 도구 — 순수 낭비

    @property
    def productive(self) -> bool:
        return self in _PRODUCTIVE


_PRODUCTIVE = frozenset(
    {
        ToolChoiceVerdict.DECISIVE_PROBE,
        ToolChoiceVerdict.INFORMATIVE,
        ToolChoiceVerdict.TAG_REVEALING,
    }
)


class InvestigationScore(BaseModel):
    """조사 1건의 채점 결과."""

    verdicts: list[ToolChoiceVerdict] = Field(default_factory=list)
    budget_used: int = 0
    fail_closed_respected: bool = True

    @property
    def productive_ratio(self) -> float:
        """예산 중 판별에 쓰인 비율. 도구를 하나도 안 불렀으면 1.0 (흘린 게 없다)."""
        if not self.verdicts:
            return 1.0
        return sum(1 for v in self.verdicts if v.productive) / len(self.verdicts)

    def count(self, verdict: ToolChoiceVerdict) -> int:
        return sum(1 for v in self.verdicts if v == verdict)

    @property
    def clean(self) -> bool:
        """흘린 호출이 없고 fail-closed 도 지켰나."""
        return self.productive_ratio == 1.0 and self.fail_closed_respected


def score_tool_choice(
    entry: ToolLogEntry,
    already_used: set[str] | None = None,
    matrix: dict | None = None,
) -> ToolChoiceVerdict:
    """도구 선택 1회를 판정한다.

    판정 순서에 뜻이 있다. 이미 부른 도구는 무엇을 가르든 낭비이므로 먼저 걸러내고,
    결정적 도구는 경합 상태와 무관하게 정당하다 — 권리자 적격성(사망·후견)은 책임
    등급 최상위라 늦게 확인할수록 손해가 커진다.
    """
    matrix = matrix or TOOL_MATRIX
    used = already_used or set()

    if entry.tool in used:
        return ToolChoiceVerdict.REDUNDANT

    spec = matrix.get(entry.tool)
    if spec is None:  # 매트릭스에 없는 도구 — 분별력을 주장할 근거가 없다
        return ToolChoiceVerdict.CONTEXT_ONLY

    if spec.get("decisive"):
        return ToolChoiceVerdict.DECISIVE_PROBE

    competing = set(entry.competing)
    separates = spec.get("separates") or []
    if any(a in competing and b in competing for a, b in separates):
        return ToolChoiceVerdict.INFORMATIVE

    reveals = spec.get("reveals") or []
    if any(tag not in set(entry.tags_on) for tag in reveals):
        return ToolChoiceVerdict.TAG_REVEALING

    if separates:  # 가를 줄은 아는데 그 쌍이 이미 끝났다
        return ToolChoiceVerdict.SETTLED
    return ToolChoiceVerdict.CONTEXT_ONLY


def fail_closed_respected(state: AgentState) -> bool:
    """결정적 사실이 나온 뒤에 도구를 더 불렀는지 본다 (원칙 1).

    사망·후견이 확인되면 게이트는 예산·신뢰도를 보지 않고 종료해야 한다. 그 뒤에도
    조사가 이어졌다면 fail-closed 가 뚫린 것이고, 그건 성능이 아니라 통제 문제다.

    결정적 사실이 없으면 해당 없음이라 참을 돌려준다.
    """
    fact = state.decisive_fact
    if fact is None:
        return True
    log = state.tool_log or []
    for i, entry in enumerate(log):
        if entry.tool == fact.source:
            return i == len(log) - 1  # 그 호출이 마지막이어야 한다
    return True  # 로그에 없으면 판단 근거가 없다 — 여기서 실패로 몰지 않는다


def score_investigation(state: AgentState, matrix: dict | None = None) -> InvestigationScore:
    """조사 1건을 채점한다. 도구를 부른 순서대로 훑는다."""
    verdicts: list[ToolChoiceVerdict] = []
    used: set[str] = set()
    for entry in state.tool_log or []:
        verdicts.append(score_tool_choice(entry, used, matrix))
        used.add(entry.tool)
    return InvestigationScore(
        verdicts=verdicts,
        budget_used=len(verdicts),
        fail_closed_respected=fail_closed_respected(state),
    )


__all__ = [
    "InvestigationScore",
    "ToolChoiceVerdict",
    "fail_closed_respected",
    "score_investigation",
    "score_tool_choice",
]
