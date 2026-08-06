package com.bank.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DevSecretGuard")
class DevSecretGuardTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "dev-secret-key-please-change-in-production-min-32-chars-long",
            "dev-rrn-crypto-key-change-in-production",
            "dev-ci-secret-change-in-production",
            "__CHANGE_ME__internet-banking-jwt-secret__",
    })
    @DisplayName("운영 프로파일에서 개발용 기본값이면 기동을 중단한다")
    void rejectsPlaceholdersInProduction(String value) {
        assertThatThrownBy(() -> DevSecretGuard.verify("jwt.secret", value, env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    @DisplayName("운영 프로파일에서 값이 비어 있으면 기동을 중단한다")
    void rejectsBlankInProduction() {
        assertThatThrownBy(() -> DevSecretGuard.verify("jwt.secret", "  ", env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("운영 프로파일이라도 실제 값이면 통과한다")
    void acceptsRealSecretInProduction() {
        assertThatCode(() -> DevSecretGuard.verify(
                "jwt.secret", "9f2c1b7a4e6d8f0a3c5e7b9d1f3a5c7e9b1d3f5a7c9e1b3d", env("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("로컬·테스트에서는 기본값을 막지 않는다")
    void allowsPlaceholderOutsideProduction() {
        // 개발 편의를 위해 둔 기본값이라 로컬까지 막으면 아무도 못 쓴다.
        assertThatCode(() -> DevSecretGuard.verify(
                "jwt.secret", "dev-secret-key-please-change-in-production", env("local")))
                .doesNotThrowAnyException();
        assertThatCode(() -> DevSecretGuard.verify(
                "jwt.secret", "dev-secret-key-please-change-in-production", env()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "staging", "PROD"})
    @DisplayName("운영으로 보는 프로파일 이름")
    void productionProfileNames(String profile) {
        assertThatThrownBy(() -> DevSecretGuard.verify("x", "dev-secret", env(profile)))
                .isInstanceOf(IllegalStateException.class);
    }
}
