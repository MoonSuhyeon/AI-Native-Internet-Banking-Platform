package com.bank.customer.identity.port;

import com.bank.common.web.BusinessException;
import com.bank.customer.identity.port.IdentityVerificationPort.VerifiedIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockIdentityVerificationProviderTest {

    private final IdentityVerificationPort port = new MockIdentityVerificationProvider("unit-test-secret", new org.springframework.mock.env.MockEnvironment());

    @Test
    @DisplayName("주민번호 7번째 자리 1 → 1900년대 남성 내국인")
    void parses1900sMale() {
        VerifiedIdentity vi = port.resolve("홍길동", "9001011234567", "01012345678");

        assertThat(vi.birthDate()).isEqualTo("19900101");
        assertThat(vi.genderCode()).isEqualTo("M");
        assertThat(vi.nationalityTypeCode()).isEqualTo("DOMESTIC");
    }

    @Test
    @DisplayName("주민번호 7번째 자리 4 → 2000년대 여성 내국인")
    void parses2000sFemale() {
        VerifiedIdentity vi = port.resolve("김영희", "0502034000000", "01012345678");

        assertThat(vi.birthDate()).isEqualTo("20050203");
        assertThat(vi.genderCode()).isEqualTo("F");
        assertThat(vi.nationalityTypeCode()).isEqualTo("DOMESTIC");
    }

    @Test
    @DisplayName("주민번호 7번째 자리 5/6 → 외국인")
    void parsesForeigner() {
        assertThat(port.resolve("외국인", "9001015234567", "01012345678").nationalityTypeCode())
                .isEqualTo("FOREIGN");
    }

    @Test
    @DisplayName("CI 는 같은 주민번호면 동일, 다른 주민번호면 다름 (결정적)")
    void ciIsDeterministic() {
        String ci1 = port.resolve("홍길동", "9001011234567", "01012345678").ci();
        String ci2 = port.resolve("홍길동", "9001011234567", "01099999999").ci();
        String ci3 = port.resolve("김영희", "0502034000000", "01012345678").ci();

        assertThat(ci1).isEqualTo(ci2);   // 전화번호와 무관
        assertThat(ci1).isNotEqualTo(ci3);
    }

    @Test
    @DisplayName("형식 오류(13자리 아님·잘못된 월) → BusinessException")
    void rejectsInvalidRrn() {
        assertThatThrownBy(() -> port.resolve("홍길동", "12345", "01012345678"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> port.resolve("홍길동", "9013011234567", "01012345678"))
                .isInstanceOf(BusinessException.class);   // 13월
    }
}
