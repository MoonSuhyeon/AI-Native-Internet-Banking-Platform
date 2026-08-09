"""신뢰 경계 — 이 요청이 누구인가.

**왜 한 파일에 모았나.** 상담 서비스의 엔드포인트 24개 중 인증을 요구하는 것은
직원 기능 5종뿐이었다. 나머지는 요청 body 나 쿼리에 적힌 값을 그대로 믿었고,
그래서 다음이 가능했다.

- 남의 고객번호를 적어 상품·계약·현금흐름을 조회하고 서류를 제출한다.
- ``customer_no`` 를 빼고 ``/chat/history`` 를 부르면 전 고객 상담 이력이 나온다.
- ``PATCH /agents/{id}`` 로 아무나 상담원 비밀번호를 바꾼다.

각 엔드포인트가 저마다 헤더를 파싱하게 두면 어딘가는 반드시 빠진다. 실제로
직원 기능에도 "JWT 에서 추출해 사칭을 방지한다" 는 코드가 있었지만 세 군데가
끊겨 한 번도 발동하지 않았다. 그래서 **신원을 읽는 방법은 여기 한 곳에만 둔다.**

**게이트웨이가 유일한 신원 출처다.** api-gateway 의 JwtAuthenticationFilter 가
클라이언트발 ``X-Customer-Id`` · ``X-Employee-Id`` 를 제거한 뒤 검증된 JWT 클레임으로
덮어쓴다. 이 서비스는 게이트웨이를 거쳤다는 증거(``X-Gateway-Auth``)가 맞을 때만
그 헤더를 믿고, body 값으로는 **절대 되돌아가지 않는다**.

.. warning::
   공유 시크릿은 네트워크 격리의 대체재가 아니라 최소 방어선이다. 상담 서비스가
   8087 로 브라우저에 직접 열려 있으면 게이트웨이를 우회하면 그만이다.
   완결된 형태는 8087 이 브라우저에서 아예 닿지 않는 것이다.
"""

from __future__ import annotations

import hmac
import os

from fastapi import HTTPException, Request

#: 게이트웨이와 나눠 갖는 시크릿. 설정되지 않으면 신원 헤더를 전혀 믿지 않는다.
GATEWAY_SECRET_ENV = "CONSULTATION_GATEWAY_SHARED_SECRET"

_HEADER_GATEWAY_AUTH = "X-Gateway-Auth"
_HEADER_CUSTOMER_ID = "X-Customer-Id"
_HEADER_EMPLOYEE_ID = "X-Employee-Id"
_HEADER_USER_ROLE = "X-User-Role"


def gateway_verified(presented: str | None) -> bool:
    """이 요청이 게이트웨이를 거쳐 왔는지."""
    expected = os.getenv(GATEWAY_SECRET_ENV, "").strip()
    if not expected or not presented:
        return False
    # 타이밍 공격 회피 — 시크릿 비교에 == 를 쓰지 않는다.
    return hmac.compare_digest(expected, presented)


def _verified_header(request: Request, name: str) -> str | None:
    if not gateway_verified(request.headers.get(_HEADER_GATEWAY_AUTH)):
        return None
    # 게이트웨이는 해당 없는 쪽에도 헤더를 붙이되 빈 문자열을 넣는다(고객 토큰의
    # X-Employee-Id 등). 빈 값을 신원으로 넘기면 조회가 아무것도 못 찾아
    # "권한 없음" 이 아니라 "대상 없음" 으로 흐려진다.
    return (request.headers.get(name) or "").strip() or None


def customer_no(request: Request) -> str | None:
    """게이트웨이가 검증해 주입한 고객 식별자. 확인되지 않으면 None.

    상담 서비스의 ``customer_no`` 는 실제로는 숫자 customerId 이며(조회 쿼리가
    ``WHERE customer_id = :customer_no``), 게이트웨이가 ``X-Customer-Id`` 로 넣는
    값과 같다.

    None 은 "익명" 이라는 뜻이고, 그 상태로도 상품 검색·비교 같은 공개 기능은
    돌아야 한다. 그래서 여기서는 예외를 던지지 않는다 — 본인 확인이 필요한
    기능은 각자 ``_auth_required`` 로 거절한다(기존 동작).
    """
    return _verified_header(request, _HEADER_CUSTOMER_ID)


def employee_id(request: Request) -> str | None:
    """게이트웨이가 검증해 주입한 직원 식별자. 확인되지 않으면 None."""
    return _verified_header(request, _HEADER_EMPLOYEE_ID)


def require_employee(request: Request) -> str:
    """직원 전용 엔드포인트의 문지기.

    <p>고객 데이터 전체를 열거나 상담원 계정을 조작하는 경로에 쓴다. 이런 곳은
    "신원을 모르면 익명으로 취급" 이 성립하지 않는다 — 모르면 막아야 한다.

    :raises HTTPException: 403. 401 이 아닌 이유는 자격 증명을 다시 보내라는 뜻이
        아니기 때문이다. 게이트웨이를 거치지 않은 요청은 무엇을 붙여도 통과할 수 없다.
    """
    eid = employee_id(request)
    if eid is None:
        raise HTTPException(
            status_code=403,
            detail="직원 권한이 필요합니다. 게이트웨이를 통해 직원 계정으로 요청해주세요.",
        )
    return eid


def require_customer(request: Request) -> str:
    """본인 확인이 반드시 필요한 고객 엔드포인트의 문지기.

    서류 제출처럼 <b>남의 계정에 흔적을 남기는</b> 동작에 쓴다. 조회와 달리
    익명으로 물러설 자리가 없다.
    """
    cno = customer_no(request)
    if cno is None:
        raise HTTPException(
            status_code=403,
            detail="본인 확인이 필요합니다. 로그인 후 다시 시도해주세요.",
        )
    return cno


def roles(request: Request) -> list[str]:
    """게이트웨이가 주입한 역할 목록. 다른 서비스와 같은 규약(콤마 구분)."""
    raw = _verified_header(request, _HEADER_USER_ROLE)
    if not raw:
        return []
    return [r.strip() for r in raw.split(",") if r.strip()]
