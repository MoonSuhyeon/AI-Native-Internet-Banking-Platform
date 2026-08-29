"""추적·로그로 나가는 값에서 개인정보를 걷어낸다.

**왜 하네스에 있나.** 관측 도구(Phoenix·Langfuse)는 프롬프트와 도구 응답을 그대로
적재한다. 계측을 켜는 순간 추적 저장소가 **PII 사본**이 된다. 에이전트마다 각자
가리면 한 곳이 빠지고, 빠진 곳이 유출 경로로 남는다. 그래서 규칙을 한 곳에 둔다.

**두 가지가 필요하다.** 성격이 달라서 한 함수로는 안 된다.

- :func:`mask_text` — 자유 텍스트에 섞여 든 것. 주민번호·계좌·전화·이름.
  Java ``PiiMaskingUtil`` 과 **같은 규칙**이다 (두 런타임이 같은 하네스를 구현한다).
- :func:`pseudonymize` — 필드 자체가 식별자인 것. 고객번호·계좌번호처럼
  ``customer_id="9111"`` 로 통째 들어오는 값은 정규식이 잡을 수 없다.

:func:`scrub` 이 둘을 함께 건다. 문자열은 :func:`mask_text` 로, 이름이 식별자를 뜻하는
필드(:data:`_ID_KEYS`)는 :func:`pseudonymize` 로. 호출부가 무엇을 가려야 하는지 알 필요가
없게 하려는 것이다 — 알아야 하면 언젠가 한 곳이 빠진다.

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
import logging
import os
import re
import secrets
import threading

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


# ── 식별자 필드 (정규식이 잡을 수 없는 것) ──────────────────────────────────
#
# ``customer_id="9111"`` 은 어떤 정규식에도 걸리지 않는다. 숫자 네 자리일 뿐이다.
# 그래서 **값이 아니라 필드 이름**으로 판단한다. 이름이 식별자를 뜻하면 가명으로 바꾼다.
#
# 왜 호출부가 아니라 여기서 하나. 에이전트가 늘 때마다 "여기도 가명처리해야지" 를
# 다시 떠올려야 하면 언젠가 한 곳이 빠지고, 빠진 곳이 유출 경로로 남는다. 이름만
# 맞으면 새 필드도 자동으로 이 경로를 지난다.
#
# **일부러 넣지 않은 것** — 계약번호·상품번호. 그 자체로 사람을 가리키지 않고,
# 만기·재예치 추적을 되짚을 때 유일한 손잡이다. 다 가리면 추적을 켠 이유가 없어진다.
# 무엇을 남길지는 이 표가 정하므로, 늘리거나 줄일 때 그 판단도 여기 적는다.
_ID_KEYS = {
    "customerid":  "cust",  "customerno":   "cust",
    "custid":      "cust",  "custno":       "cust",
    "accountid":   "acct",  "accountno":    "acct",
    "accountnumber": "acct", "acctno":      "acct",
    "employeeid":  "emp",   "staffid":      "emp",
    "loginid":     "user",  "userid":       "user",
    "customername":  "name", "holdername":  "name",
    "employeename":  "name", "counterpartyname": "name",
    "username":      "name", "recipientname":    "name",
    "phonenumber": "phone", "phoneno":      "phone",
    "mobileno":    "phone",
    "emailaddress": "email",
    "rrn":         "rrn",   "residentregistrationnumber": "rrn",
    "birthdate":   "birth", "dateofbirth":  "birth",
}


def _id_prefix(key) -> str | None:
    """필드 이름이 식별자를 뜻하면 가명 접두사를 돌려준다.

    ``harness.customer_id`` 처럼 앞에 붙는 이름은 떼고 마지막 마디만 본다 —
    추적 속성은 점으로 이어 붙이므로 그렇게 하지 않으면 하나도 걸리지 않는다.

    복수형(``account_ids``)도 같이 본다. 목록으로 넘기는 쪽이 흔한데, 단수만 보면
    그쪽이 통째로 빠진다.
    """
    if not isinstance(key, str):
        return None
    tail = key.rsplit(".", 1)[-1].replace("_", "").replace("-", "").lower()
    prefix = _ID_KEYS.get(tail)
    if prefix is None and tail.endswith("s"):
        prefix = _ID_KEYS.get(tail[:-1])
    return prefix


def mask_text(value):
    """자유 텍스트에서 주민번호·계좌·전화·이메일·이름을 치환한다.

    문자열이 아니면 그대로 돌려준다 — 호출부가 타입을 확인하지 않아도 되게.
    """
    if not isinstance(value, str) or not value:
        return value
    for pattern, token in _RULES:
        value = pattern.sub(token, value)
    return value


_logger = logging.getLogger(__name__)
_fallback_warned = False
_warn_lock = threading.Lock()


def _salt() -> str:
    """소금을 읽는다. 없으면 그 사실을 <b>한 번</b> 알린다.

    미설정이 위험한 이유는 가명이 뚫려서가 아니다. 임의 소금이라 오히려 되돌리기는
    더 어렵다. 문제는 **묶이지 않는다**는 것이다 — 재기동하면 같은 고객이 다른
    가명이 되고, 상담과 조사가 서로 다른 소금을 쓰면 같은 사람인 줄 모른다.
    가명처리를 쓰는 이유가 "지우면 못 쓰니까" 였는데 그 이유가 사라진다.

    그런데 이 고장은 아무 증상이 없다. 추적은 잘 쌓이고 값도 그럴듯하다. 나중에
    묶어 보려 할 때야 안 된다는 걸 안다. 그래서 시작할 때 소리를 낸다.

    막지는 않는다. 관측 설정 때문에 본 작업을 멈추는 것이 더 나쁘다.
    """
    configured = os.getenv("AGENT_PII_SALT")
    if configured:
        return configured

    global _fallback_warned
    if not _fallback_warned:
        with _warn_lock:
            if not _fallback_warned:
                _fallback_warned = True
                _logger.warning(
                    "AGENT_PII_SALT 가 없어 임의 소금으로 시작한다. 가명이 프로세스마다 "
                    "달라져 같은 고객의 실행을 묶을 수 없다 — 재기동 전후도, 에이전트 "
                    "사이도 이어지지 않는다. 운영에서는 반드시 설정할 것."
                )
    return _FALLBACK_SALT


def reset_salt_warning_for_test() -> None:
    """경고 1회 플래그를 되돌린다. 운영 경로에서는 쓰지 않는다."""
    global _fallback_warned
    with _warn_lock:
        _fallback_warned = False


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


def scrub(value, _depth: int = 0, key=None):
    """중첩 구조를 훑으며 문자열은 가리고 식별자 필드는 가명으로 바꾼다.

    도구 원응답은 dict 안에 dict 다. 최상위만 가리면 한 겹 아래가 그대로 나간다.

    ``key`` 는 이 값이 담겨 있던 필드 이름이다. 값만 보면 ``"9111"`` 이 고객번호인지
    금액인지 알 수 없으므로, 이름으로 판단한다. 부르는 쪽이 최상위 이름을 아는
    경우(추적 속성처럼) 넘겨 주면 그 층도 함께 걸린다.
    """
    if _depth > 6:  # 순환·과도한 깊이 방어. 그 아래는 통째로 버린다.
        return "[TRUNCATED]"

    prefix = _id_prefix(key)
    if prefix is not None and not isinstance(value, (dict, list, tuple)):
        # 식별자는 지우지 않고 바꾼다. 지우면 "같은 고객의 실행 3건" 을 묶을 수 없다.
        return pseudonymize(value, prefix)

    if isinstance(value, str):
        return mask_text(value)
    if isinstance(value, dict):
        return {k: scrub(v, _depth + 1, key=k) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [scrub(v, _depth + 1, key=key) for v in value]
    return value
