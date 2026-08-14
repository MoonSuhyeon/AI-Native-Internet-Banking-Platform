"""추적·로그로 나가는 값에서 개인정보를 걷어낸다.

**왜 하네스에 있나.** 관측 도구(Phoenix·Langfuse)는 프롬프트와 도구 응답을 그대로
적재한다. 계측을 켜는 순간 추적 저장소가 **PII 사본**이 된다. 에이전트마다 각자
가리면 한 곳이 빠지고, 빠진 곳이 유출 경로로 남는다. 그래서 규칙을 한 곳에 둔다.

**두 가지가 필요하다.** 성격이 달라서 한 함수로는 안 된다.

- :func:`mask_text` — 자유 텍스트에 섞여 든 것. 주민번호·계좌·전화·이름.
  Java ``PiiMaskingUtil`` 과 **같은 규칙**이다 (두 런타임이 같은 하네스를 구현한다).
- :func:`pseudonymize` — 필드 자체가 식별자인 것. 고객번호·계좌번호처럼
  ``customer_id="9111"`` 로 통째 들어오는 값은 정규식이 잡을 수 없다.

가명처리를 쓰는 이유는 지우면 못 쓰기 때문이다. 고객번호를 ``[MASKED]`` 로 지우면
"같은 고객의 조사 3건" 을 묶을 수 없다. 같은 입력에 같은 가명이 나오면 묶을 수는
있고, 원본으로 되돌릴 수는 없다. 신용정보법이 말하는 가명처리가 이것이다.

소금(``AGENT_PII_SALT``)이 없으면 가명이 프로세스마다 달라진다 — 재기동하면 같은
고객이 다른 가명이 된다. 운영에서는 반드시 설정해야 하고, 미설정은 그 사실이
드러나도록 임의 값으로 시작한다(빈 소금으로 고정하면 무지개표에 뚫린다).
"""

from __future__ import annotations

import hashlib
import hmac
import os
import re
import secrets

# ── 자유 텍스트 규칙 (Java PiiMaskingUtil 과 동일) ──────────────────────────
_RRN = re.compile(r"\d{6}-\d{7}")
_ACCT = re.compile(r"\d{3,6}-\d{2,6}-\d{6,}")
_PHONE = re.compile(r"01[016789]-?\d{3,4}-?\d{4}")
_NAME = re.compile(r"[가-힣]{2,4}(?=님|씨)")
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")

_RULES = (
    (_RRN, "[RRN]"),
    (_ACCT, "[ACCT]"),
    (_PHONE, "[PHONE]"),
    (_EMAIL, "[EMAIL]"),
    (_NAME, "[NAME]"),
)

_FALLBACK_SALT = secrets.token_hex(16)


def mask_text(value):
    """자유 텍스트에서 주민번호·계좌·전화·이메일·이름을 치환한다.

    문자열이 아니면 그대로 돌려준다 — 호출부가 타입을 확인하지 않아도 되게.
    """
    if not isinstance(value, str) or not value:
        return value
    for pattern, token in _RULES:
        value = pattern.sub(token, value)
    return value


def _salt() -> str:
    return os.getenv("AGENT_PII_SALT") or _FALLBACK_SALT


def pseudonymize(value, prefix: str = "id") -> str | None:
    """식별자를 되돌릴 수 없는 안정된 가명으로 바꾼다.

    같은 입력 → 같은 출력이므로 추적을 묶을 수 있고, 소금 없이는 되돌릴 수 없다.
    """
    if value is None:
        return None
    digest = hmac.new(
        _salt().encode("utf-8"), str(value).encode("utf-8"), hashlib.sha256
    ).hexdigest()
    return f"{prefix}_{digest[:12]}"


def scrub(value, _depth: int = 0):
    """중첩 구조를 훑으며 문자열마다 :func:`mask_text` 를 적용한다.

    도구 원응답은 dict 안에 dict 다. 최상위만 가리면 한 겹 아래가 그대로 나간다.
    """
    if _depth > 6:  # 순환·과도한 깊이 방어. 그 아래는 통째로 버린다.
        return "[TRUNCATED]"
    if isinstance(value, str):
        return mask_text(value)
    if isinstance(value, dict):
        return {k: scrub(v, _depth + 1) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [scrub(v, _depth + 1) for v in value]
    return value
