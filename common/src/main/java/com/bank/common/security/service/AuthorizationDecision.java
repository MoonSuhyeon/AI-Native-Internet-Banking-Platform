package com.bank.common.security.service;

/**
 * 인가 판정 결과.
 *
 * <p><b>왜 예외가 아니라 값인가.</b> 거절도 감사 대상이다
 * ({@code docs/decisions/transaction-initiator-auth-model.md} §3-2). 거절을 예외로
 * 던져 버리면 호출부가 잡아서 기록하도록 매번 기억해야 하는데, 그 기억은 언젠가
 * 빠진다. 판정을 값으로 돌려주면 "판정한 곳이 기록한다" 를 한 곳에서 지킬 수 있다.
 *
 * @param allowed    허용 여부
 * @param serviceId  신원. 자격증명이 틀려 신원을 못 세웠으면 {@code null}
 * @param operation  요청한 작업. 이름을 모르는 값이었으면 {@code null}
 * @param denyReason 거절 사유. 허용이면 {@code null}
 */
public record AuthorizationDecision(
        boolean allowed,
        String serviceId,
        ServiceOperation operation,
        DenyReason denyReason
) {

    public static AuthorizationDecision allow(String serviceId, ServiceOperation operation) {
        return new AuthorizationDecision(true, serviceId, operation, null);
    }

    public static AuthorizationDecision deny(String serviceId, ServiceOperation operation, DenyReason reason) {
        if (reason == null) {
            // 사유 없는 거절은 감사에서 쓸모가 없다. 만들 수 없게 막는다.
            throw new IllegalArgumentException("거절에는 사유가 필요하다");
        }
        return new AuthorizationDecision(false, serviceId, operation, reason);
    }

    /** 신원조차 세우지 못한 거절. 가장 먼저 봐야 할 기록이다. */
    public static AuthorizationDecision denyUnknownCaller() {
        return new AuthorizationDecision(false, null, null, DenyReason.UNKNOWN_CREDENTIAL);
    }
}
