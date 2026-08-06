package com.bank.common.security;

import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Set;

/**
 * 개발용 기본 시크릿으로 운영이 기동되는 것을 막는다.
 *
 * <p><b>왜 필요한가.</b> 시크릿 설정이 {@code ${JWT_SECRET:dev-secret-key-...}} 형태라
 * 환경변수 주입이 빠져도 앱이 <b>정상 기동</b>한다. 그 기본값은 레포에 그대로 적혀 있으므로,
 * 그 상태의 운영은 다음을 뜻한다.
 *
 * <ul>
 *   <li>JWT 시크릿 — 누구나 임의의 {@code customerId}·역할로 토큰을 만들어 서명할 수 있다.
 *       로그인 없이 아무나 그 사람이 된다.</li>
 *   <li>주민번호 암호화 키 — DB 가 유출됐을 때 암호화가 아무 역할을 하지 못한다.</li>
 * </ul>
 *
 * <p>가장 나쁜 형태는 "안전하지 않은데 조용히 뜨는 것"이다. 아무도 모르는 채로 몇 달이 간다.
 * 은행 시스템의 기본은 <b>안전하지 않으면 뜨지 않는다</b> 이므로, 운영 프로파일에서는 기동을 거부한다.
 *
 * <p>로컬·테스트에서는 아무것도 하지 않는다. 개발 편의를 위해 기본값을 둔 것이므로
 * 그 용도까지 막으면 아무도 못 쓴다.
 */
public final class DevSecretGuard {

    /** 이 프로파일이 하나라도 활성이면 운영으로 본다. */
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "stage", "staging");

    /** 값에 이 조각이 들어 있으면 "아직 안 바꾼 기본값"으로 본다. */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "dev-secret", "dev-rrn", "dev-ci", "change-in-production", "CHANGE_ME", "changeme"
    );

    private DevSecretGuard() {
    }

    /**
     * 운영 프로파일에서 기본값·빈 값이면 예외를 던져 기동을 중단시킨다.
     *
     * @param propertyName 진단 메시지에 쓸 프로퍼티 이름 (예: {@code jwt.secret})
     * @param value        실제 주입된 값
     * @param environment  활성 프로파일 판별용
     */
    public static void verify(String propertyName, String value, Environment environment) {
        if (!isProduction(environment)) {
            return;
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(failureMessage(propertyName, "값이 비어 있습니다"));
        }
        String lowered = value.toLowerCase();
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lowered.contains(marker.toLowerCase())) {
                throw new IllegalStateException(
                        failureMessage(propertyName, "개발용 기본값이 그대로입니다"));
            }
        }
    }

    public static boolean isProduction(Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if (PRODUCTION_PROFILES.contains(profile.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String failureMessage(String propertyName, String reason) {
        return """
                운영 프로파일에서 %s 가 안전하지 않아 기동을 중단합니다 — %s.
                환경변수로 실제 값을 주입하세요(.env.prod 참조).
                이 값이 기본값인 채로 뜨면 토큰 위조·암호문 복호화가 가능해집니다."""
                .formatted(propertyName, reason);
    }
}
