"""테스트 공통 — 게이트웨이를 거친 상태 만들기.

조사 에이전트의 엔드포인트는 직원 신원을 요구한다. 승인(``/approve``)만이 아니라
조사 큐(``/api/cases``)와 조사 실행(``/api/investigate``)도 마찬가지다.

- 큐 응답에는 고객 ID·계좌·금액·수취인이 그대로 담긴다.
- 조사 실행은 고객의 인증 이력·거래 내역을 끌어와 보고, 부를 때마다 LLM 호출이
  일어난다. 승인만 막고 여기를 열어 두면 동작은 못 해도 **열람은 자유롭고**,
  비용과 처리량도 아무나 소진시킬 수 있다.

그래서 흐름 테스트는 게이트웨이를 거친 요청을 흉내 낸다. 인가 자체가 제대로
막히는지는 ``test_endpoint_authorization.py`` 가 시크릿을 직접 다루며 따로 본다.
"""

from pathlib import Path

import pytest

from agent.tools import CASES_DIR

#: 게이트웨이와 나눠 갖는 시크릿(테스트 고정값).
GATEWAY_SECRET = "test-fraud-gateway-secret"

#: 게이트웨이가 직원 토큰을 검증해 통과시킨 상태.
GATEWAY_HEADERS = {
    "X-Gateway-Auth": GATEWAY_SECRET,
    "X-Employee-Id": "EMP001",
    "X-User-Role": "ROLE_COMPLIANCE",
}


@pytest.fixture(autouse=True)
def _gateway_secret(monkeypatch):
    """흐름 테스트가 게이트웨이 뒤에 있는 것처럼 만든다.

    autouse 인 이유: 인증을 붙이기 전에 쓰인 테스트가 여럿이라, 빠뜨리면 그 파일만
    조용히 403 으로 실패한다.
    """
    monkeypatch.setenv("FRAUD_GATEWAY_SHARED_SECRET", GATEWAY_SECRET)


@pytest.fixture(autouse=True)
def _cleanup_auto_case_files():
    """자동탐지 조사(``_persist_auto_case``)가 큐 파일로 남긴 테스트 산출물을 치운다.

    ``txn-`` 접두사만 지운다 — 커밋된 데모 픽스처(``case_*.json``)나 상담 접수
    (``consult-*.json``)는 이 경로가 안 만드므로 건드리지 않는다. 실제
    ``data/cases/`` 디렉터리를 쓰는 테스트라 정리를 안 하면 테스트 거래(TXN-1 등)가
    커밋되지 않은 채로 남아 다음 실행의 큐에 계속 나타난다.
    """
    before = {p.name for p in Path(CASES_DIR).glob("txn-*.json")}
    yield
    for p in Path(CASES_DIR).glob("txn-*.json"):
        if p.name not in before:
            p.unlink(missing_ok=True)
