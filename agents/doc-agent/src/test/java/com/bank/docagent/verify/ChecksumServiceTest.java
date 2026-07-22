package com.bank.docagent.verify;

import com.bank.docagent.verify.service.ChecksumService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumServiceTest {

    private final ChecksumService sut = new ChecksumService();

    // ── SSN ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SSN 원본 없으면 SKIP(true) 반환")
    void ssn_null_returns_true() {
        assertThat(sut.validateSsn("900101-1******", null)).isTrue();
    }

    @Test
    @DisplayName("SSN 공백 원본도 SKIP(true) 반환")
    void ssn_blank_returns_true() {
        assertThat(sut.validateSsn("900101-1******", "   ")).isTrue();
    }

    @Test
    @DisplayName("자릿수 불일치 시 false")
    void ssn_wrong_length() {
        assertThat(sut.validateSsn(null, "12345")).isFalse();
    }

    @Test
    @DisplayName("SSN 체크섬 유효 케이스 — rawSsnHint 있을 때 실제 검증 수행")
    void ssn_valid_with_hint() {
        // 900101-1234568
        // weights={2,3,4,5,6,7,8,9,2,3,4,5}
        // sum = 9*2+0*3+0*4+1*5+0*6+1*7+1*8+2*9+3*2+4*3+5*4+6*5 = 124
        // check = (11 - 124%11)%10 = (11-3)%10 = 8 → 마지막 자리 8
        assertThat(sut.validateSsn("900101-1******", "900101-1234568")).isTrue();
    }

    @Test
    @DisplayName("SSN 체크섬 무효 케이스 — 마지막 자리 불일치")
    void ssn_invalid_with_hint() {
        // 마지막 자리를 9로 변조 (정상은 8)
        assertThat(sut.validateSsn("900101-1******", "900101-1234569")).isFalse();
    }

    // ── 사업자번호 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("사업자번호 유효 케이스 — d[8]=0 (기존 테스트)")
    void business_number_valid_d8_zero() {
        // 220-81-10004: d[8]=0, 가중합 26, 체크=4
        assertThat(sut.validateBusinessNumber("2208110004")).isTrue();
    }

    @Test
    @DisplayName("사업자번호 유효 케이스 — d[8]≠0 (삼성전자 실존 번호)")
    void business_number_valid_d8_nonzero() {
        // 124-81-00998: d[8]=9
        // w={1,3,7,1,3,7,1,3,5}, sum(d[0..8]) = 1+6+28+8+3+0+0+27+45 = 118
        // floor(9*5/10) = 4  →  total = 122
        // check = (10 - 122%10)%10 = 8 → 마지막 자리 8
        assertThat(sut.validateBusinessNumber("1248100998")).isTrue();
    }

    @Test
    @DisplayName("사업자번호 무효 케이스")
    void business_number_invalid() {
        assertThat(sut.validateBusinessNumber("1234567890")).isFalse();
    }

    @Test
    @DisplayName("사업자번호 null이면 true(SKIP) 반환")
    void business_number_null_returns_true() {
        assertThat(sut.validateBusinessNumber(null)).isTrue();
    }

    @Test
    @DisplayName("사업자번호 자릿수 불일치 시 false")
    void business_number_wrong_length() {
        assertThat(sut.validateBusinessNumber("12345")).isFalse();
    }
}
