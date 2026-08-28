package com.bank.payment.security;

/**
 * 한 서비스에 부여된 작업 권한과 그에 붙은 정책.
 *
 * @param permissionId 권한 식별자. 허용 계좌 목록을 이 값으로 찾는다
 * @param serviceId    서비스 식별자
 * @param operation    작업 이름 (DB 저장 값)
 * @param maxAmount    건당 한도. {@code null} 이면 무제한 — 권장하지 않는다
 * @param status       {@code ACTIVE} / {@code REVOKED}
 */
public record ServicePermission(
        Long permissionId,
        String serviceId,
        String operation,
        Long maxAmount,
        String status
) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /**
     * 금액이 한도 안인가.
     *
     * <p>금액이 없는 요청({@code null})은 한도 검사 대상이 아니다 — 자금이 움직이지 않는
     * 작업에도 이 구조를 쓸 수 있어야 한다. 금액이 있으면 한도와 견준다.
     */
    public boolean withinAmountLimit(Long amount) {
        if (amount == null || maxAmount == null) {
            return true;
        }
        return amount <= maxAmount;
    }
}
