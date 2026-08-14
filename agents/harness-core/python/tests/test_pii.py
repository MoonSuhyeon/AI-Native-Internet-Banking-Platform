"""PII 규칙 계약 — 추적 저장소로 나가는 값에 무엇이 남는가.

이 테스트가 지키는 것은 "마스킹 함수가 동작한다" 가 아니다. **원본이 어디에도 남지
않는다** 이다. 둘은 다르다 — 최상위만 가리고 한 겹 아래를 놓치면 앞은 통과하고 뒤는
실패한다.
"""

from __future__ import annotations

import harness_core.pii as pii_mod
from harness_core.pii import mask_text, pseudonymize, scrub


class TestMaskText:
    def test_주민번호_계좌_전화_이메일_이름을_치환한다(self):
        text = (
            "홍길동님 주민 900101-1234567 계좌 110-234-567890 "
            "연락처 010-1234-5678 메일 hong@bank.co.kr"
        )
        out = mask_text(text)

        for leaked in ("900101-1234567", "110-234-567890", "010-1234-5678", "hong@bank.co.kr"):
            assert leaked not in out, f"{leaked} 가 그대로 남았다"
        assert "홍길동" not in out
        assert "[RRN]" in out and "[ACCT]" in out and "[PHONE]" in out and "[EMAIL]" in out

    def test_문자열이_아니면_그대로_돌려준다(self):
        # 호출부가 타입을 확인하지 않아도 되게 한다.
        assert mask_text(None) is None
        assert mask_text(1_000_000) == 1_000_000


class TestPseudonymize:
    def test_같은_입력은_같은_가명이_된다(self, monkeypatch):
        # 이게 없으면 "같은 고객의 조사 3건" 을 묶을 수 없다.
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        assert pseudonymize("9111", "cust") == pseudonymize("9111", "cust")

    def test_원본이_가명에_남지_않는다(self, monkeypatch):
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        assert "9111" not in pseudonymize("9111", "cust")

    def test_소금이_다르면_가명도_다르다(self, monkeypatch):
        monkeypatch.setenv("AGENT_PII_SALT", "salt-a")
        a = pseudonymize("9111", "cust")
        monkeypatch.setenv("AGENT_PII_SALT", "salt-b")
        assert pseudonymize("9111", "cust") != a

    def test_소금_미설정이면_고정값을_쓰지_않는다(self, monkeypatch):
        # 빈 소금으로 고정하면 무지개표에 뚫린다. 미설정은 임의 값으로 시작한다.
        monkeypatch.delenv("AGENT_PII_SALT", raising=False)
        assert pii_mod._salt() != ""
        assert len(pii_mod._salt()) >= 16


class TestScrub:
    def test_중첩된_dict_안쪽까지_가린다(self):
        # 도구 원응답은 dict 안에 dict 다. 최상위만 가리면 한 겹 아래가 그대로 나간다.
        raw = {
            "device": {"owner": "김철수님", "phone": "010-9876-5432"},
            "linked": [{"account": "333-11-999888"}],
        }
        out = scrub(raw)

        flat = repr(out)
        assert "010-9876-5432" not in flat
        assert "333-11-999888" not in flat
        assert "김철수" not in flat

    def test_숫자와_불린은_건드리지_않는다(self):
        # 금액·점수까지 가리면 추적이 쓸모없어진다.
        out = scrub({"amount": 8_500_000, "decisive": True, "score": 0.87})
        assert out == {"amount": 8_500_000, "decisive": True, "score": 0.87}

    def test_너무_깊으면_잘라낸다(self):
        deep: dict = {"v": "010-1111-2222"}
        for _ in range(10):
            deep = {"v": deep}
        assert "010-1111-2222" not in repr(scrub(deep))
