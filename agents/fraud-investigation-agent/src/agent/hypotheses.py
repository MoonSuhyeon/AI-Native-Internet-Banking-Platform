"""가설 관리 — 초기화·증거 반영 (§16-1 H/O 노드, §16-2).

축 1(시나리오)은 합 ≈ 1 로 정규화하며 경쟁시키고, 축 2(태그)는 독립 on/off.
``observe`` 는 가장 최근 증거 한 조각을 보고 시나리오 분포를 곱셈 갱신(likelihood) 후
정규화한다. 결정적 사실(사망·후견)은 여기서 다루지 않고 게이트(graph.py)가
``decisive_fact`` 로 가로챈다(fail-closed, 원칙 1).

갱신 계수는 §16-4 분별력 사전과 결을 맞춘 PoC 휴리스틱 — 엄밀한 기대정보이득은 v2.
"""

from __future__ import annotations

from .models import AgentState, AttackScenario, Tag

S = AttackScenario


def init_scenarios() -> dict[AttackScenario, float]:
    """균등 사전분포(각 0.2)로 5개 시나리오를 연다."""
    n = len(AttackScenario)
    return {s: 1.0 / n for s in AttackScenario}


def init_tags() -> dict[Tag, bool]:
    """부가 태그는 전부 off 로 시작."""
    return {t: False for t in Tag}


def normalize(scenarios: dict[AttackScenario, float]) -> dict[AttackScenario, float]:
    total = sum(scenarios.values())
    if total <= 0:
        return init_scenarios()
    return {s: v / total for s, v in scenarios.items()}


def observe(state: AgentState) -> tuple[dict[AttackScenario, float], dict[Tag, bool]]:
    """최근 증거로 시나리오 분포·태그를 갱신해 (scenarios, tags) 반환.

    각 도구 신호를 시나리오별 곱셈 계수로 반영한다. 계수는 §16-4 가 가르는
    시나리오 쌍과 일치(예: device 는 H1↔H2, auth 는 H2↔H5).
    """
    scenarios = dict(state.scenarios) or init_scenarios()
    tags = dict(state.tags) or init_tags()
    if not state.evidence:
        return normalize(scenarios), tags

    ev = state.evidence[-1]
    raw = ev.raw
    mult: dict[AttackScenario, float] = {s: 1.0 for s in AttackScenario}

    if ev.tool == "get_device_fingerprint":
        if raw.get("known_device", True):
            mult[S.H2_ACCOUNT_TAKEOVER] *= 0.25          # 평소 기기 → 탈취 약화
        else:
            mult[S.H2_ACCOUNT_TAKEOVER] *= 4.0           # 낯선 기기 → 탈취 강화
            mult[S.H1_VOICE_PHISHING] *= 0.4
            mult[S.H5_BENIGN] *= 0.4

    elif ev.tool == "get_auth_events":
        if raw.get("recent_cert_fail", 0) or raw.get("password_changed_recently"):
            mult[S.H2_ACCOUNT_TAKEOVER] *= 6.0           # 인증 실패·비번 변경 → 탈취
            mult[S.H5_BENIGN] *= 0.2
            mult[S.H1_VOICE_PHISHING] *= 0.5
        else:
            mult[S.H2_ACCOUNT_TAKEOVER] *= 0.5

    elif ev.tool == "get_related_accounts":
        if raw.get("organized_ring") or raw.get("same_device_customers", 1) > 1:
            mult[S.H1_VOICE_PHISHING] *= 10.0            # 머니뮬·조직 링 → 보이스피싱
            mult[S.H3_LAUNDERING] *= 1.2
            mult[S.H2_ACCOUNT_TAKEOVER] *= 0.5
            tags[Tag.T1_MULE] = True
            if raw.get("organized_ring"):
                tags[Tag.T2_ORGANIZED] = True
        else:
            mult[S.H5_BENIGN] *= 2.0                      # 정상 네트워크 → 오탐쪽
            mult[S.H1_VOICE_PHISHING] *= 0.6
        if raw.get("linked_str_reports", 0):
            mult[S.H1_VOICE_PHISHING] *= 2.0

    elif ev.tool == "get_str_history":
        if raw.get("past_str_reports", 0):
            mult[S.H1_VOICE_PHISHING] *= 1.5
            mult[S.H3_LAUNDERING] *= 1.3

    elif ev.tool == "get_aml_history":
        if raw.get("structuring") or raw.get("layering_hops", 0) > 1:
            mult[S.H3_LAUNDERING] *= 4.0                  # 구조화·다단 → 세탁
            mult[S.H4_INSIDER] *= 2.0
            mult[S.H5_BENIGN] *= 0.3
        else:
            mult[S.H5_BENIGN] *= 1.5

    elif ev.tool == "get_party":
        if not raw.get("name_match", True):
            tags[Tag.T1_MULE] = True                      # 명의 불일치 → 머니뮬

    # get_customer / get_fds_history 는 맥락 도구 — 분포 미변경

    updated = {s: scenarios[s] * mult[s] for s in AttackScenario}
    return normalize(updated), tags
