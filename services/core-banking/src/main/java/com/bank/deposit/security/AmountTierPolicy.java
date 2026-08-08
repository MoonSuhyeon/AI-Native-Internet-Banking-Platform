package com.bank.deposit.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 금액을 구간으로 나눈다.
 *
 * <p>임계 금액은 운영 중 자주 바뀌는 값이라 설정으로 뺀다. 반면 구간의 <b>개수와 의미</b>는
 * 통제 설계라 코드에 둔다 — 설정으로 구간을 늘리고 줄이게 만들면, 어떤 금액이 어떤
 * 통제를 받는지 코드만 봐서는 알 수 없게 된다.
 *
 * <p>경계는 <b>이상(&ge;)</b> 기준이다. "500만원까지 일반" 이 아니라 "500만원부터 고액"
 * 이어야 헷갈리지 않는다.
 */
@Component
public class AmountTierPolicy {

    /** 이 금액 미만은 소액. 기본 10만원. */
    @Value("${deposit.transfer.tier.normal-from:100000}")
    private long normalFrom;

    /** 이 금액부터 고액. 기본 500만원. */
    @Value("${deposit.transfer.tier.high-from:5000000}")
    private long highFrom;

    /** 이 금액부터 초거액(비대면 불가). 기본 5천만원. */
    @Value("${deposit.transfer.tier.very-high-from:50000000}")
    private long veryHighFrom;

    /**
     * @param amount 이체 금액. null 이면 판단할 수 없으므로 가장 강한 구간으로 본다 —
     *               금액을 모르는 채로 소액 취급하면 통제를 우회하는 길이 된다.
     */
    public AmountTier of(Long amount) {
        if (amount == null) {
            return AmountTier.VERY_HIGH;
        }
        if (amount >= veryHighFrom) {
            return AmountTier.VERY_HIGH;
        }
        if (amount >= highFrom) {
            return AmountTier.HIGH;
        }
        if (amount >= normalFrom) {
            return AmountTier.NORMAL;
        }
        return AmountTier.SMALL;
    }
}
