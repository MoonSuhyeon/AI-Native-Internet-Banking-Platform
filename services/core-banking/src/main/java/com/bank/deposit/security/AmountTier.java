package com.bank.deposit.security;

/**
 * 이체 금액 구간.
 *
 * <p><b>왜 필요한가.</b> 실무에서 통제 수위는 금액에 따라 다르다. 소액까지 매번 본인
 * 확인을 요구하면 아무도 쓰지 않고, 초거액을 소액과 같이 처리하면 사고가 난다.
 * 그런데 이 구분이 없어서 {@code TransferApprovalGate} 는 {@code amount} 를 받고도
 * 쓰지 않았다 — 1만원과 10억이 같은 절차였다.
 *
 * <p><b>한 벌만 둔다.</b> 승인 강도와 FDS 장애 시 동작이 각각 다른 "거액" 기준을 쓰면
 * 같은 거래가 한쪽에선 고액이고 한쪽에선 아닌 상태가 된다. 두 판단이 이 구간을 공유한다.
 */
public enum AmountTier {

    /** 소액. 추가 인증 없이 통과시킨다. */
    SMALL,

    /** 일반. 본인 확인(승인 토큰)을 요구한다. */
    NORMAL,

    /** 고액. 승인 토큰을 요구하고, FDS 판정을 못 받으면 진행하지 않는다. */
    HIGH,

    /**
     * 초거액. 비대면 채널에서 처리하지 않는다.
     *
     * <p>실무에서도 일정 금액을 넘으면 영업점 확인으로 넘긴다. 사고 시 되돌릴 수 없는
     * 금액을 자동화된 경로에 두지 않는 것이 목적이다.
     */
    VERY_HIGH;

    /** 본인 확인(승인 토큰)이 필요한 구간인가. */
    public boolean requiresApprovalToken() {
        return this != SMALL;
    }

    /**
     * FDS 판정을 받지 못했을 때 거래를 멈춰야 하는 구간인가.
     *
     * <p>소액까지 멈추면 탐지기 장애가 곧 결제 장애가 된다 — 그게 더 큰 사고다.
     * 반대로 고액을 판정 없이 통과시키면 FDS 를 둔 의미가 없다.
     */
    public boolean failClosedOnDetectorOutage() {
        return this == HIGH || this == VERY_HIGH;
    }
}
