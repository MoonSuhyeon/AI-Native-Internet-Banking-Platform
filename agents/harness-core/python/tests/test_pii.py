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


class TestScrubIdentifierFields:
    """정규식이 잡을 수 없는 것 — 필드 자체가 식별자인 경우.

    ``customer_id="9111"`` 은 어떤 정규식에도 걸리지 않는다. 숫자 네 자리일 뿐이다.
    그래서 값이 아니라 **필드 이름**으로 판단한다. 이 판정이 빠지면 추적 저장소에
    고객번호가 그대로 쌓이고, 그것이 관측을 켠 대가가 된다.
    """

    def test_고객번호가_원문으로_남지_않는다(self, monkeypatch):
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        out = scrub({"customer_id": "9111", "message": "적금 추천해줘"})

        assert "9111" not in repr(out), "고객번호가 그대로 추적에 실렸다"
        assert out["customer_id"].startswith("cust_")
        assert out["message"] == "적금 추천해줘", "질문까지 가리면 추적이 쓸모없어진다"

    def test_같은_고객은_같은_가명이_된다(self, monkeypatch):
        # 묶을 수 없으면 가명으로 바꿀 이유가 없다. 지우는 것과 같아진다.
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        a = scrub({"customer_id": "9111"})["customer_id"]
        b = scrub({"customerNo": "9111"})["customerNo"]
        assert a == b, "표기가 달라도 같은 고객이면 같은 가명이어야 한다"

    def test_표기가_달라도_잡는다(self, monkeypatch):
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        out = scrub({
            "customerId": "9111", "customer_no": "9111",
            "accountNumber": "1002", "account_id": 1002,
            "staff_id": "E01", "employeeId": "E01",
        })
        for key, value in out.items():
            assert not str(value).endswith(("9111", "1002", "E01")), f"{key} 가 그대로다"

    def test_속성_이름_앞에_붙은_이름은_떼고_본다(self, monkeypatch):
        # 추적 속성은 점으로 이어 붙인다. 마지막 마디를 안 보면 하나도 안 걸린다.
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        out = scrub("9111", key="harness.customer_id")
        assert out.startswith("cust_") and "9111" not in out

    def test_목록_안의_식별자도_바꾼다(self, monkeypatch):
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        out = scrub({"account_ids": [1001, 1002]})
        assert "1001" not in repr(out) and "1002" not in repr(out)

    def test_계약번호_상품번호는_남긴다(self):
        # 일부러 넣지 않은 것이다. 그 자체로 사람을 가리키지 않고, 만기·재예치 추적을
        # 되짚을 때 유일한 손잡이다. 다 가리면 추적을 켠 이유가 없어진다.
        out = scrub({"contract_id": 77, "product_id": 12, "amount": 8_500_000})
        assert out == {"contract_id": 77, "product_id": 12, "amount": 8_500_000}

    def test_이름_필드는_가명으로_바꾼다(self, monkeypatch):
        # 정규식은 "김철수님" 처럼 호칭이 붙어야 잡는다. 필드에 이름만 들어오면 놓친다.
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        out = scrub({"customer_name": "김철수"})
        assert "김철수" not in repr(out)


class TestSaltMisconfiguration:
    """소금 미설정은 증상이 없는 고장이다 — 그래서 소리를 내야 한다.

    추적은 잘 쌓이고 값도 그럴듯하다. 나중에 "같은 고객의 실행 3건" 을 묶어 보려 할
    때야 안 된다는 걸 안다. 그때는 이미 쌓인 것을 되살릴 수 없다.
    """

    def test_소금이_없으면_경고한다(self, monkeypatch, caplog):
        monkeypatch.delenv("AGENT_PII_SALT", raising=False)
        pii_mod.reset_salt_warning_for_test()

        with caplog.at_level("WARNING"):
            pseudonymize("9111", "cust")

        assert any("AGENT_PII_SALT" in r.message for r in caplog.records), (
            "미설정이 조용히 지나가면 가명이 이어지지 않는 것을 아무도 모른다"
        )

    def test_경고는_한_번만_한다(self, monkeypatch, caplog):
        # 값마다 경고하면 로그가 묻히고, 묻히면 없는 것과 같다.
        monkeypatch.delenv("AGENT_PII_SALT", raising=False)
        pii_mod.reset_salt_warning_for_test()

        with caplog.at_level("WARNING"):
            for _ in range(5):
                pseudonymize("9111", "cust")

        warnings = [r for r in caplog.records if "AGENT_PII_SALT" in r.message]
        assert len(warnings) == 1, f"경고가 {len(warnings)}번 나왔다"

    def test_소금이_있으면_경고하지_않는다(self, monkeypatch, caplog):
        monkeypatch.setenv("AGENT_PII_SALT", "test-salt")
        pii_mod.reset_salt_warning_for_test()

        with caplog.at_level("WARNING"):
            pseudonymize("9111", "cust")

        assert not [r for r in caplog.records if "AGENT_PII_SALT" in r.message]

    def test_경고해도_가명처리는_계속된다(self, monkeypatch):
        # 관측 설정 때문에 본 작업을 멈추는 것이 더 나쁘다.
        monkeypatch.delenv("AGENT_PII_SALT", raising=False)
        pii_mod.reset_salt_warning_for_test()

        out = scrub({"customer_id": "9111"})
        assert "9111" not in repr(out), "경고와 무관하게 원본은 나가면 안 된다"
