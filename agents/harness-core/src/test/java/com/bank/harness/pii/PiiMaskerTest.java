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
}
