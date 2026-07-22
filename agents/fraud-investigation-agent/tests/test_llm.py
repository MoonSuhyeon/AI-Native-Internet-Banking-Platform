"""Stage llm 테스트 — 벤더 중립 인터페이스 · 혼합형 도구 선택 · 선택 이유 로깅."""

import pytest

from agent.llm import (
    AnthropicClient,
    MockLLMClient,
    OpenAIClient,
    get_llm_client,
)
from agent.models import AgentState
from agent.planner import plan_next_tool
from agent.tool_matrix import TOOL_MATRIX, render_matrix
from agent.tools import load_case


def _initial_state(case_name: str) -> AgentState:
    return AgentState(alert=load_case(case_name).alert)


def test_default_provider_is_mock(monkeypatch):
    monkeypatch.delenv("TRIAGE_LLM_PROVIDER", raising=False)
    assert isinstance(get_llm_client(), MockLLMClient)


def test_real_clients_construct_without_sdk_and_have_two_tier_models():
    # 생성 시 벤더 SDK 를 import 하지 않는다(지연 import) → SDK 없이도 객체 생성 OK
    oai = OpenAIClient()
    ant = AnthropicClient()
    assert oai.light_model and oai.heavy_model      # 2티어 설정값
    assert ant.light_model and ant.heavy_model
    assert oai.light_model != oai.heavy_model


def test_provider_factory_maps_real_clients(monkeypatch):
    monkeypatch.setenv("TRIAGE_LLM_PROVIDER", "openai")
    assert isinstance(get_llm_client(), OpenAIClient)
    monkeypatch.setenv("TRIAGE_LLM_PROVIDER", "anthropic")
    assert isinstance(get_llm_client(), AnthropicClient)


def test_model_overrides_from_env(monkeypatch):
    monkeypatch.setenv("TRIAGE_LLM_MODEL_LIGHT", "x-light")
    monkeypatch.setenv("TRIAGE_LLM_MODEL_HEAVY", "x-heavy")
    c = OpenAIClient()
    assert (c.light_model, c.heavy_model) == ("x-light", "x-heavy")


def test_h1_initial_selects_device_with_h1h2_reason():
    state = _initial_state("case_h1")
    decision = MockLLMClient().select_next_tool(state, TOOL_MATRIX)
    # 상위 가설을 가장 잘 가르는 device 계열을 고르고
    assert decision["tool"] == "get_device_fingerprint"
    # 이유에 H1/H2 구분 근거가 들어간다 (설명가능성)
    assert "H1/H2 구분" in decision["reason"]


def test_reason_is_logged_to_tool_log():
    state = _initial_state("case_h1")
    assert state.tool_log == []

    tool = plan_next_tool(state, MockLLMClient(), TOOL_MATRIX)

    assert tool == "get_device_fingerprint"
    assert len(state.tool_log) == 1
    entry = state.tool_log[-1]
    assert entry.tool == "get_device_fingerprint"
    assert "H1/H2 구분" in entry.reason  # 선택 이유가 그대로 남는다


def test_planner_advances_through_script():
    state = _initial_state("case_h1")
    first = plan_next_tool(state, MockLLMClient(), TOOL_MATRIX)
    second = plan_next_tool(state, MockLLMClient(), TOOL_MATRIX)
    assert first == "get_device_fingerprint"
    assert second == "get_related_accounts"  # 2차: 수취 네트워크
    assert [e.tool for e in state.tool_log] == [first, second]


def test_matrix_renders_for_prompt_context():
    text = render_matrix(TOOL_MATRIX)
    assert "get_device_fingerprint" in text
    assert "H1_VOICE_PHISHING↔H2_ACCOUNT_TAKEOVER" in text
