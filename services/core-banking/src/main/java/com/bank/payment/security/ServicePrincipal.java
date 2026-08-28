package com.bank.payment.security;

/**
 * 호출한 서비스의 신원.
 *
 * <p><b>자격증명은 담지 않는다.</b> 신원을 세운 뒤에는 해시조차 들고 다닐 이유가 없고,
 * 들고 다니면 로그·예외 메시지에 섞여 나갈 자리가 늘어난다.
 *
 * @param serviceId   서비스 식별자 (예: {@code LOAN_SERVICE})
 * @param displayName 사람이 읽을 이름
 * @param status      {@code ACTIVE} / {@code SUSPENDED}
 */
public record ServicePrincipal(
        String serviceId,
        String displayName,
        String status
) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
