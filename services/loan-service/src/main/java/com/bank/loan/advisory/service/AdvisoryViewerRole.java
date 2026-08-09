package com.bank.loan.advisory.service;

import java.util.Collection;

/**
 * 어드바이저리 조회 권한 역할.
 * <ul>
 *   <li>REVIEWER : 본인 대상 리포트만 조회 가능 (자신이 targetReviewerId 인 건)</li>
 *   <li>AUDITOR  : 전체 리포트 조회 (감사 목적), 변경 불가</li>
 *   <li>ADMIN    : 전체 조회 + 룰 변경 가능</li>
 * </ul>
 *
 * <p><b>역할을 어디서 읽는지가 바뀌었다.</b> 예전에는 {@code X-Actor-Role} 헤더를
 * 컨트롤러가 직접 받았다. 그 이름은 이 코드에서만 쓰던 것이라, 게이트웨이가
 * 클라이언트발 신원 헤더를 지울 때 대상 목록에 들어 있지 않았다 — 게이트웨이를
 * 거치더라도 <b>클라이언트가 붙인 값이 그대로 살아남았다</b>. 권한 검사가 22곳에
 * 있었지만 전부 호출자가 스스로 적은 값을 읽고 있었다.
 *
 * <p>지금은 {@code GatewayHeaderAuthFilter} 가 검증된 {@code X-User-Role} 로 세운
 * SecurityContext 의 권한을 쓴다. 게이트웨이는 클라이언트발 {@code X-User-Role} 을
 * 지우고 JWT 클레임으로 덮어쓰므로, 이 값은 위조할 수 없다.
 *
 * <p>어휘도 은행 공통({@code BankRole})을 따른다. 예전의 REVIEWER/AUDITOR/ADMIN 은
 * 이 서비스만의 단어였다.
 */
public enum AdvisoryViewerRole {
    REVIEWER, AUDITOR, ADMIN;

    /**
     * 스프링 권한 목록에서 이 서비스의 열람 등급을 정한다.
     *
     * <p>가장 높은 것을 고른다. 한 사람이 여러 역할을 가질 수 있는데 첫 번째만 보면
     * 목록 순서에 따라 권한이 달라진다.
     *
     * <p>모르는 역할은 REVIEWER 로 떨어진다 — 권한을 올려 주는 쪽으로 실수하지 않는다.
     * 예외를 던지지 않는 이유는, 게이트웨이가 넣는 목록에 이 서비스와 무관한
     * 역할(ROLE_CUSTOMER 등)이 섞여 오기 때문이다. 거기서 예외를 던지면 정상
     * 사용자가 500 을 받는다.
     */
    public static AdvisoryViewerRole from(Collection<String> authorities) {
        if (authorities == null || authorities.isEmpty()) {
            return REVIEWER;
        }
        return authorities.stream()
                .map(AdvisoryViewerRole::mapOne)
                .max(Enum::compareTo)      // 선언 순서 = 권한 크기
                .orElse(REVIEWER);
    }

    private static AdvisoryViewerRole mapOne(String authority) {
        if (authority == null) {
            return REVIEWER;
        }
        return switch (authority.trim().toUpperCase()) {
            // 룰 변경까지 가능한 최고 권한
            case "ROLE_ADMIN" -> ADMIN;

            // 전체 조회는 되지만 변경은 안 되는 감사 권한.
            // 컴플라이언스·본사 리스크관리부가 심사 품질을 들여다보는 자리다.
            case "ROLE_COMPLIANCE", "ROLE_HQ_RISK" -> AUDITOR;

            // 그 외는 본인 건만. 모르는 역할도 여기로 떨어진다.
            default -> REVIEWER;
        };
    }

    public boolean canSeeAll() {
        return this == AUDITOR || this == ADMIN;
    }
}
