package com.bank.harness.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("외부로 나가는 프롬프트에서 개인식별정보를 가린다")
class PiiMaskerTest {

    @Test
    @DisplayName("주민번호·계좌·전화·이메일·이름을 가린다")
    void masks_known_identifiers() {
        String raw = "홍길동님 주민번호 900101-1234567, 계좌 110-223-456789, "
                + "연락처 010-1234-5678, 메일 hong@example.com";

        String masked = PiiMasker.mask(raw);

        assertThat(masked).contains("[RRN]", "[ACCT]", "[PHONE]", "[EMAIL]", "[NAME]");
        assertThat(masked)
                .as("원문 조각이 하나라도 남으면 가린 의미가 없다")
                .doesNotContain("900101-1234567", "110-223-456789", "010-1234-5678",
                        "hong@example.com", "홍길동");
    }

    @Test
    @DisplayName("가릴 것이 없는 문장은 그대로 둔다")
    void leaves_clean_text_alone() {
        String raw = "신용등급 3등급, 연소득 4800만원, 기존 대출 2건";
        assertThat(PiiMasker.mask(raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("null 과 빈 문자열에서 터지지 않는다")
    void tolerates_empty_input() {
        assertThat(PiiMasker.mask(null)).isNull();
        assertThat(PiiMasker.mask("")).isEmpty();
    }

    @Test
    @DisplayName("주민번호를 계좌번호로 잘못 잡지 않는다")
    void does_not_confuse_rrn_with_account() {
        assertThat(PiiMasker.mask("900101-1234567"))
                .as("계좌 패턴을 먼저 적용하면 주민번호가 [ACCT] 로 잡힌다")
                .isEqualTo("[RRN]");
    }

    // ── 이름으로 판단하는 것 (정규식이 잡을 수 없는 것) ──────────────────────

    @Test
    @DisplayName("고객번호는 정규식에 안 걸리므로 필드 이름으로 가린다")
    void pseudonymizes_identifier_fields() {
        // "9111" 은 숫자 네 자리일 뿐이다. 값만 보면 금액인지 고객번호인지 알 수 없다.
        String out = PiiMasker.scrubAttribute("harness.customer_id", "9111");

        assertThat(out).startsWith("cust_");
        assertThat(out)
                .as("이 판정이 빠지면 추적 저장소에 고객번호가 그대로 쌓인다")
                .doesNotContain("9111");
    }

    @Test
    @DisplayName("같은 고객은 표기가 달라도 같은 가명이 된다")
    void same_customer_yields_same_pseudonym() {
        // 묶을 수 없으면 가명으로 바꿀 이유가 없다. 지우는 것과 같아진다.
        assertThat(PiiMasker.scrubAttribute("customerId", "9111"))
                .isEqualTo(PiiMasker.scrubAttribute("customer_no", "9111"));
    }

    @Test
    @DisplayName("금액·계약번호는 그대로 둔다")
    void keeps_business_values() {
        // 다 가리면 추적을 켠 이유가 없어진다. 무엇을 남길지가 설계다.
        assertThat(PiiMasker.scrubAttribute("harness.amount", 8_500_000)).isEqualTo("8500000");
        assertThat(PiiMasker.scrubAttribute("harness.contract_id", 77)).isEqualTo("77");
    }

    @Test
    @DisplayName("식별자가 아닌 값은 마스킹 규칙을 그대로 탄다")
    void non_identifier_still_masked() {
        assertThat(PiiMasker.scrubAttribute("harness.summary", "홍길동님 010-1234-5678"))
                .contains("[NAME]", "[PHONE]")
                .doesNotContain("홍길동", "010-1234-5678");
    }

    @Test
    @DisplayName("파이썬 하네스와 같은 가명을 낸다")
    void matches_python_harness() {
        // 두 런타임이 같은 하네스를 구현한다 — 공유되는 것은 코드가 아니라 계약이다.
        // 어긋나면 같은 고객이 런타임마다 다른 가명이 되어, 자바 에이전트와 파이썬
        // 에이전트의 실행이 이어지지 않는다. 그런데 그 고장은 증상이 없다. 양쪽 다
        // 잘 돌고 값도 그럴듯하며, 묶어 보려 할 때야 안 된다는 걸 안다.
        //
        // 기대값은 같은 소금으로 파이썬 쪽을 돌려 얻은 것이다:
        //   AGENT_PII_SALT=harness-cross-runtime-test
        //   harness_core.pii.pseudonymize("9111", "cust")
        // 한쪽 알고리즘(HMAC·해시·자리수·형식)을 바꾸면 여기서 걸린다.
        assertThat(PiiMasker.pseudonymize("9111", "cust", CROSS_RUNTIME_SALT))
                .isEqualTo("cust_3bf9f4426ebd");
    }

    private static final String CROSS_RUNTIME_SALT = "harness-cross-runtime-test";
}
