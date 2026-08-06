package com.bank.customer.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService("unit-test-key", new org.springframework.mock.env.MockEnvironment());

    @Test
    @DisplayName("암호화→복호화 라운드트립 — 원문 복원, 암호문은 평문과 다름")
    void roundTrip() {
        String plain = "9001011234567";
        String enc = crypto.encrypt(plain);

        assertThat(enc).isNotEqualTo(plain);
        assertThat(crypto.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    @DisplayName("같은 평문도 매번 다른 암호문(랜덤 IV) — 둘 다 복호화는 동일")
    void randomIvPerEncryption() {
        String plain = "hello-pii";
        String a = crypto.encrypt(plain);
        String b = crypto.encrypt(plain);

        assertThat(a).isNotEqualTo(b);
        assertThat(crypto.decrypt(a)).isEqualTo(plain);
        assertThat(crypto.decrypt(b)).isEqualTo(plain);
    }

    @Test
    @DisplayName("null·빈문자는 그대로 통과")
    void nullAndBlankPassthrough() {
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.encrypt("")).isEmpty();
        assertThat(crypto.decrypt(null)).isNull();
    }
}
