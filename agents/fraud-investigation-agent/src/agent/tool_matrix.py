"""Tool Matrix — 도구 분별력 사전 (§16-4).

각 도구가 *어떤 시나리오 쌍을 가르는지* 를 결정적 데이터로 적어 둔 표.
Planner(planner.py)가 이 표를 LLM 프롬프트 컨텍스트로 넘기고, **LLM이 도구를 고른다**.
순수 매트릭스만 쓰면 전문가시스템이 되고, 순수 LLM만 쓰면 설명 불가 — 그 사이(혼합형).

표 자체는 결정적이라 감사 가능한 뼈대를 제공할 뿐, 선택은 LLM이 하고 그 이유를
tool_log 에 남긴다.
"""

from __future__ import annotations

from .models import AttackScenario as S
from .models import Tag

# 도구 → 분별력 정보
#   separates : 이 도구가 가르는 시나리오 쌍 목록
#   reveals   : 이 도구가 켤 수 있는 부가 태그
#   decisive  : 결정적 사실(fail-closed) 유발 가능 여부
#   note      : 한 줄 설명
TOOL_MATRIX: dict[str, dict] = {
    "get_party": {
        "separates": [],
        "reveals": [Tag.T1_MULE],
        "decisive": True,  # 사망·후견 → 즉시 fail-closed
        "note": "사망·후견(결정적) · 명의자 불일치",
    },
    "get_device_fingerprint": {
        "separates": [
            (S.H1_VOICE_PHISHING, S.H2_ACCOUNT_TAKEOVER),
            (S.H2_ACCOUNT_TAKEOVER, S.H5_BENIGN),
        ],
        "reveals": [],
        "decisive": False,
        "note": "평소/낯선 기기 — H1↔H2 의 1차 분기",
    },
    "get_auth_events": {
        "separates": [(S.H2_ACCOUNT_TAKEOVER, S.H5_BENIGN)],
        "reveals": [],
        "decisive": False,
        "note": "인증 실패·비번 변경 — H2 확정/기각",
    },
    "get_related_accounts": {
        "separates": [(S.H1_VOICE_PHISHING, S.H3_LAUNDERING)],
        "reveals": [Tag.T1_MULE, Tag.T2_ORGANIZED, Tag.T3_NEW_ACCOUNT],
        "decisive": False,
        "note": "동일 디바이스 다고객·조직 링 — 수취 네트워크",
    },
    "get_str_history": {
        "separates": [(S.H1_VOICE_PHISHING, S.H4_INSIDER)],
        "reveals": [],
        "decisive": False,
        "note": "과거 STR — H3 강화",
    },
    "get_aml_history": {
        "separates": [(S.H3_LAUNDERING, S.H5_BENIGN)],
        "reveals": [],
        "decisive": False,
        "note": "구조화·다단 경유 — H4 강화",
    },
    # baseline·맥락 도구 (직접 가르지는 않음)
    "get_customer": {
        "separates": [],
        "reveals": [],
        "decisive": False,
        "note": "평소 거래·기기 baseline(맥락)",
    },
    "get_fds_history": {
        "separates": [],
        "reveals": [],
        "decisive": False,
        "note": "과거 탐지 패턴(맥락)",
    },
}


def render_matrix(matrix: dict[str, dict] | None = None) -> str:
    """매트릭스를 LLM 프롬프트에 넣을 텍스트로 렌더 (설명가능성 근거)."""
    matrix = matrix or TOOL_MATRIX
    lines = []
    for tool, info in matrix.items():
        pairs = ", ".join(
            f"{a.value}↔{b.value}" for a, b in info.get("separates", [])
        )
        tags = ", ".join(t.value for t in info.get("reveals", []))
        tag = "decisive" if info.get("decisive") else (pairs or "—")
        extra = f" (+태그 {tags})" if tags else ""
        lines.append(f"- {tool}: {tag}{extra} · {info.get('note', '')}")
    return "\n".join(lines)
