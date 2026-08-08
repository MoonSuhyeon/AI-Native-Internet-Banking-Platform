package com.bank.payment.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이체 수수료 산정.
 *
 * <p>수수료는 돈이라 틀리면 고객이 손해를 보거나 은행이 손해를 본다. 그런데 어느
 * 쪽이든 예외가 나지 않아 조용하다 — 실제로 이 레포에서 수수료가 원장에만 기록되고
 * 잔액에서는 빠지지 않은 채로 있었다.
 *
 * <p>특히 <b>모르는 상태를 어느 쪽으로 기울이는가</b>를 고정한다. 자행여부를 모르면
 * 타행(유료)으로, 시각을 모르면 영업시간 안(할증 없음)으로 본다. 둘 다 "판정하지
 * 못한 것을 근거로 고객에게 불리하게 하지 않되, 수수료를 빠뜨리지도 않는다" 는 기준이다.
 */
class TransferFeePolicyTest {

    private TransferFeePolicy policy;

    /** KST 14:00 — 영업시간 안 */
    private static final OffsetDateTime BUSINESS_HOURS =
            OffsetDateTime.parse("2026-08-08T05:00:00Z");
    /** KST 22:00 — 영업시간 밖 */
    private static final OffsetDateTime AFTER_HOURS =
            OffsetDateTime.parse("2026-08-08T13:00:00Z");

    @BeforeEach
    void setUp() {
        policy = new TransferFeePolicy();
        ReflectionTestUtils.setField(policy, "intraBankFee", 0L);
        ReflectionTestUtils.setField(policy, "interBankFee", 500L);
        ReflectionTestUtils.setField(policy, "afterHoursSurcharge", 300L);
        ReflectionTestUtils.setField(policy, "businessHourFrom", 9);
        ReflectionTestUtils.setField(policy, "businessHourTo", 16);
    }

    @Test
    @DisplayName("자행이체는 무료 — 망 이용료가 없다")
    void intraBankIsFree() {
        assertThat(policy.feeFor(true, BUSINESS_HOURS)).isZero();
        assertThat(policy.feeFor(true, AFTER_HOURS))
                .as("자행은 시간대와 무관하다")
                .isZero();
    }

    @Test
    @DisplayName("타행이체는 기본 수수료를 받는다")
    void interBankChargesBaseFee() {
        assertThat(policy.feeFor(false, BUSINESS_HOURS)).isEqualTo(500L);
    }

    @Test
    @DisplayName("영업시간 밖 타행이체는 할증한다 — KST 기준이다")
    void afterHoursSurchargeUsesKst() {
        // 2026-08-08T13:00:00Z = KST 22:00 → 영업시간 밖
        assertThat(policy.feeFor(false, AFTER_HOURS)).isEqualTo(800L);
        // 2026-08-08T05:00:00Z = KST 14:00 → 영업시간 안
        assertThat(policy.feeFor(false, BUSINESS_HOURS)).isEqualTo(500L);
    }

    @Test
    @DisplayName("자행여부를 모르면 타행으로 본다 — 무료로 처리하면 수수료를 빠뜨린다")
    void unknownRoutingIsChargedAsInterBank() {
        assertThat(policy.feeFor(null, BUSINESS_HOURS)).isEqualTo(500L);
    }

    @Test
    @DisplayName("시각을 모르면 할증하지 않는다 — 판정 못 한 것으로 더 받지 않는다")
    void unknownTimeIsNotSurcharged() {
        assertThat(policy.feeFor(false, null)).isEqualTo(500L);
    }

    @Test
    @DisplayName("할증이 0이면 시간대와 무관하다 — 기본 설정")
    void zeroSurchargeIgnoresTime() {
        ReflectionTestUtils.setField(policy, "afterHoursSurcharge", 0L);

        assertThat(policy.feeFor(false, AFTER_HOURS)).isEqualTo(500L);
    }
}
