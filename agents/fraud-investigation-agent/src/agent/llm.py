"""LLM 경계 — 벤더 중립 인터페이스 (§16-4 Planner) + 실제 OpenAI/Anthropic.

LLM 역할은 두 가지로 한정한다(CLAUDE.md 원칙 1):
1. ``select_next_tool(state, matrix)`` — Tool Matrix 를 프롬프트에 넣어 다음 도구를
   고르고 **선택 이유**를 함께 반환한다(원칙 2: 매번 로그 → 설명가능성·감사·재현).
   실제 LLM에서도 이유 로그는 필수.
2. ``generate_recommendation(state)`` — 증거 사슬로 권고 근거 텍스트를 서술한다.

결정적 사실(사망·후견)·동작(지급정지·STR)은 LLM이 건드리지 않는다.

provider 선택: 환경변수 ``TRIAGE_LLM_PROVIDER`` = openai | anthropic | mock (기본 mock).
2티어 모델: 경량(분류/서술) + 상위(도구 선택 판단). ``TRIAGE_LLM_MODEL_LIGHT/HEAVY`` 로 덮어씀.
**API 키는 환경변수만** (OPENAI_API_KEY / ANTHROPIC_API_KEY) — 코드·깃에 두지 않는다.
벤더 SDK 는 메서드 첫 호출 때 **지연 import** 한다(패키지 import 안정성·테스트 목 유지).
"""

from __future__ import annotations

import json
import os
import re
from abc import ABC, abstractmethod

from .models import AgentState, AttackScenario
from .tool_matrix import render_matrix

# 2티어 기본 모델 (벤더별). 환경변수로 덮어쓸 수 있음.
DEFAULT_MODELS = {
    "openai": {"light": "gpt-4o-mini", "heavy": "gpt-4o"},
    "anthropic": {"light": "claude-haiku-4-5", "heavy": "claude-sonnet-4-6"},
}


def _models(provider: str) -> tuple[str, str]:
    d = DEFAULT_MODELS[provider]
    return (
        os.getenv("TRIAGE_LLM_MODEL_LIGHT", d["light"]),
        os.getenv("TRIAGE_LLM_MODEL_HEAVY", d["heavy"]),
    )


def _parse_json(text: str) -> dict:
    """LLM 응답에서 JSON 객체를 robust 하게 추출."""
    try:
        return json.loads(text)
    except Exception:
        m = re.search(r"\{.*\}", text, re.DOTALL)
        if m:
            try:
                return json.loads(m.group(0))
            except Exception:
                pass
    return {}


def _distribution_text(state: AgentState) -> str:
    return ", ".join(
        f"{s.value}={state.scenarios.get(s, 0):.2f}" for s in AttackScenario
    )


def _planner_prompt(state: AgentState, matrix: dict) -> tuple[str, str]:
    valid = list(matrix.keys())
    used = [e.tool for e in state.tool_log]
    evidence = "; ".join(e.signal for e in state.evidence) or "아직 없음"
    system = (
        "너는 은행 이상거래 조사 에이전트의 Planner다. 경쟁 가설을 가장 잘 가르는 "
        "조사 도구 하나를 고른다. 동작(지급정지·STR)은 절대 고르지 않는다 — 조회만. "
        '반드시 JSON 으로만 답한다: {"tool": "<도구명>", "reason": "<한 줄 이유>"}'
    )
    user = (
        f"현재 시나리오 분포: {_distribution_text(state)}\n"
        f"부가 태그: {{ {', '.join(f'{t.value}={v}' for t, v in state.tags.items())} }}\n"
        f"수집 증거: {evidence}\n"
        f"이미 호출한 도구: {used or '없음'}\n"
        f"남은 예산: {state.budget_left}\n\n"
        f"도구 분별력 사전(각 도구가 가르는 시나리오 쌍):\n{render_matrix(matrix)}\n\n"
        f"사용 가능 도구: {valid}\n"
        "지금 상위 경합 가설을 가장 잘 가르는 도구 하나를 고르고, 이유를 한 줄로."
    )
    return system, user


def _recommend_prompt(state: AgentState) -> tuple[str, str]:
    top = (
        max(state.scenarios, key=state.scenarios.get).value
        if state.scenarios
        else "미정"
    )
    chain = " | ".join(
        f"{log.tool}: {log.reason}" for log in state.tool_log
    )
    evidence = "; ".join(e.signal for e in state.evidence) or "없음"
    decisive = (
        f"결정적 사실={state.decisive_fact.kind.value}"
        if state.decisive_fact
        else "없음"
    )
    system = (
        "너는 은행 이상거래 분석 보조다. 조사 결과의 근거 사슬을 분석가가 읽도록 "
        "1~3문장 한국어로 간결히 서술한다. 단정 대신 근거를 들어라."
    )
    user = (
        f"우세 가설: {top}\n결정적 사실: {decisive}\n"
        f"도구 선택 사슬: {chain}\n증거: {evidence}\n"
        "위를 바탕으로 권고 근거를 서술."
    )
    return system, user


# --------------------------------------------------------------------------- #
class LLMClient(ABC):
    """벤더 중립 LLM 인터페이스. 구현체는 mock / openai / anthropic."""

    @abstractmethod
    def select_next_tool(self, state: AgentState, matrix: dict) -> dict:
        """다음 조사 도구를 고른다. 반환: ``{"tool": str, "reason": str}``."""

    @abstractmethod
    def generate_recommendation(self, state: AgentState) -> str:
        """증거·도구 로그로 권고 근거 텍스트를 만든다."""


# --------------------------------------------------------------------------- #
# MockLLMClient — 기본. 케이스별 스크립트 응답 (실제 호출 없음)
# --------------------------------------------------------------------------- #
class MockLLMClient(LLMClient):
    """케이스별로 미리 정한 (도구, 이유) 스크립트를 순서대로 돌려준다.

    호출 차수는 ``len(state.tool_log)`` 로 센다. 스크립트가 끝나면 matrix 에서
    아직 안 쓴 '가르는' 도구로 폴백한다(여전히 결정적 호출 없음).
    """

    SCRIPTS: dict[str, list[tuple[str, str]]] = {
        "ALT-H1": [
            (
                "get_device_fingerprint",
                "H1·H2가 상위 가설 — device_fingerprint가 H1/H2 구분에 가장 강함",
            ),
            (
                "get_related_accounts",
                "device=평소기기로 H2 약화 → H1 확정 위해 수취 네트워크(동일 디바이스·조직) 확인",
            ),
            (
                "get_str_history",
                "H1 보강 — 과거 STR로 머니뮬·피해 정황 확인",
            ),
        ],
        "ALT-H2": [
            (
                "get_device_fingerprint",
                "H1·H2가 상위 — device로 H1/H2 구분",
            ),
            (
                "get_auth_events",
                "낯선 기기 확인 → H2(계정탈취) 확정 위해 인증 실패·비번 변경 이력 확인",
            ),
        ],
        "ALT-H5": [
            (
                "get_device_fingerprint",
                "상위 가설 구분 위해 device 먼저 — H1/H2 구분",
            ),
            (
                "get_related_accounts",
                "정상 확정 위해 수취 네트워크 정상 여부 확인",
            ),
            (
                "get_aml_history",
                "정상 확정 보강 — AML 이력 정상 확인",
            ),
        ],
        "ALT-DEATH": [
            (
                "get_party",
                "권리자 적격성(사망·후견)은 결정적이라 먼저 확인 — 맞으면 즉시 fail-closed",
            ),
            (
                "get_related_accounts",
                "(여기 도달하면) 수취 네트워크 확인",
            ),
        ],
        "ALT-DECEASED": [
            (
                "get_party",
                "권리자 적격성(사망·후견)은 결정적 — 먼저 확인해 맞으면 즉시 fail-closed",
            ),
        ],
    }

    def select_next_tool(self, state: AgentState, matrix: dict) -> dict:
        # 반응형 분기: 직전 증거가 device_fingerprint 면 결과(평소/낯선)에 따라 다음 도구를
        # 바꾼다. "결과가 다음 행동을 바꾼다" — 같은 구조라도 데이터가 경로를 가른다.
        if state.evidence and state.evidence[-1].tool == "get_device_fingerprint":
            used = {e.tool for e in state.tool_log}
            known = bool(state.evidence[-1].raw.get("known_device", True))
            nxt = "get_related_accounts" if known else "get_auth_events"
            if nxt not in used:
                reason = (
                    "device=평소기기 → H2 약화, 수취 네트워크로 H1(보이스피싱) 확인"
                    if known
                    else "device=낯선기기 → H2 강화, 인증 이력으로 H2(계정탈취) 확인"
                )
                return {"tool": nxt, "reason": reason}

        script = self.SCRIPTS.get(state.alert.id, [])
        idx = len(state.tool_log)
        if idx < len(script):
            tool, reason = script[idx]
            return {"tool": tool, "reason": reason}
        return self._fallback(state, matrix)

    @staticmethod
    def _fallback(state: AgentState, matrix: dict) -> dict:
        used = {entry.tool for entry in state.tool_log}
        for tool, info in matrix.items():
            if tool not in used and info.get("separates"):
                return {
                    "tool": tool,
                    "reason": f"미사용 분별 도구 {tool} 로 경합 가설 추가 분리",
                }
        return {"tool": "get_customer", "reason": "추가 분별 도구 없음 — baseline 확인"}

    def generate_recommendation(self, state: AgentState) -> str:
        if state.decisive_fact:
            return (
                f"결정적 사실({state.decisive_fact.kind.value}) 확인 — "
                f"출처 {state.decisive_fact.source}. 즉시 차단 권고(fail-closed)."
            )
        top = (
            max(state.scenarios, key=state.scenarios.get).value
            if state.scenarios
            else "미정"
        )
        signals = "; ".join(e.signal for e in state.evidence) or "증거 없음"
        return f"우세 가설={top}. 근거: {signals}."


# --------------------------------------------------------------------------- #
# 실제 클라이언트 — 벤더 SDK 는 지연 import. 도구 선택 이유 로그 필수.
# --------------------------------------------------------------------------- #
class _RealClient(LLMClient):
    """OpenAI/Anthropic 공통 — 프롬프트 구성·검증·이유 보존은 동일, _complete 만 다름."""

    def __init__(self, provider: str):
        self.provider = provider
        self.light_model, self.heavy_model = _models(provider)
        self._sdk = None  # 지연 생성

    def _complete(self, model: str, system: str, user: str, json_mode: bool) -> str:
        raise NotImplementedError

    def select_next_tool(self, state: AgentState, matrix: dict) -> dict:
        system, user = _planner_prompt(state, matrix)
        raw = self._complete(self.heavy_model, system, user, json_mode=True)  # 판단=상위 모델
        data = _parse_json(raw)
        tool = data.get("tool")
        reason = data.get("reason", "")
        valid = list(matrix.keys())
        if tool not in valid:
            used = {e.tool for e in state.tool_log}
            tool = next((t for t in valid if t not in used), valid[0])
            reason = f"[보정] LLM 반환 무효 → {tool}. {reason}".strip()
        # 이유가 비면 설명가능성 위반 — 최소한의 근거를 채운다
        return {"tool": tool, "reason": reason or f"{tool} 선택(이유 미반환)"}

    def generate_recommendation(self, state: AgentState) -> str:
        system, user = _recommend_prompt(state)
        return self._complete(self.light_model, system, user, json_mode=False).strip()


class OpenAIClient(_RealClient):
    def __init__(self):
        super().__init__("openai")

    def _ensure(self):
        if self._sdk is None:
            from openai import OpenAI  # 지연 import — 키는 OPENAI_API_KEY env

            self._sdk = OpenAI()
        return self._sdk

    def _complete(self, model: str, system: str, user: str, json_mode: bool) -> str:
        kwargs = {"response_format": {"type": "json_object"}} if json_mode else {}
        resp = self._ensure().chat.completions.create(
            model=model,
            temperature=0,
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            **kwargs,
        )
        return resp.choices[0].message.content or ""


class AnthropicClient(_RealClient):
    def __init__(self):
        super().__init__("anthropic")

    def _ensure(self):
        if self._sdk is None:
            from anthropic import Anthropic  # 지연 import — 키는 ANTHROPIC_API_KEY env

            self._sdk = Anthropic()
        return self._sdk

    def _complete(self, model: str, system: str, user: str, json_mode: bool) -> str:
        if json_mode:
            user = user + '\n\nJSON 객체 하나만 출력. 예: {"tool": "...", "reason": "..."}'
        resp = self._ensure().messages.create(
            model=model,
            max_tokens=1024,
            system=system,
            messages=[{"role": "user", "content": user}],
        )
        return "".join(
            block.text for block in resp.content if getattr(block, "type", "") == "text"
        )


_PROVIDERS = {
    "mock": MockLLMClient,
    "openai": OpenAIClient,
    "anthropic": AnthropicClient,
}


def get_llm_client() -> LLMClient:
    """TRIAGE_LLM_PROVIDER 로 클라이언트 선택. 기본 mock (실제 호출은 설정 시에만)."""
    provider = os.getenv("TRIAGE_LLM_PROVIDER", "mock").lower()
    return _PROVIDERS.get(provider, MockLLMClient)()
