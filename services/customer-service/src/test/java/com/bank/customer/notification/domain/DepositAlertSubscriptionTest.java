package com.bank.customer.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 입금통보 신청 규칙.
 *
 * <p><b>무엇을 지키는가.</b> "신청됐다" 와 "받을 수 있다" 가 어긋나지 않게 한다.
 * 연락처 없는 문자 통보는 등록은 되지만 발송 때 아무 데도 가지 않는다 — 고객은
 * 신청했다고 믿고 기다리므로, 실패했다는 사실조차 아무도 모른다.
 */
class DepositAlertSubscriptionTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();

    @Test
    @DisplayName("문자 통보는 연락처가 있어야 한다")
    void smsNeedsContact() {
        assertThatThrownBy(() -> DepositAlertSubscription.open(
                1L, "110-123-456", AlertChannel.SMS, "  ", 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("연락처가 있어야 한다");
    }

    @Test
    @DisplayName("앱 알림함은 연락처 없이도 된다")
    void pushNeedsNoContact() {
        // 고객 자신이 목적지다. 그래도 컬럼이 NOT NULL 이라 고객번호를 넣는다.
        DepositAlertSubscription s = DepositAlertSubscription.open(
                7L, "110-123-456", AlertChannel.PUSH, null, 0, NOW);

        assertThat(s.getContact()).isEqualTo("7");
    }

    @Test
    @DisplayName("통보 기준액은 음수일 수 없다")
    void minAmountNotNegative() {
        assertThatThrownBy(() -> DepositAlertSubscription.open(
                1L, "110-123-456", AlertChannel.PUSH, null, -1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기준액 미만 입금은 통보하지 않는다")
    void coversOnlyAboveThreshold() {
        DepositAlertSubscription s = DepositAlertSubscription.open(
                1L, "110-123-456", AlertChannel.PUSH, null, 100_000, NOW);

        assertThat(s.covers(99_999)).isFalse();
        assertThat(s.covers(100_000)).isTrue();
    }

    @Test
    @DisplayName("해지한 신청은 통보하지 않는다")
    void closedCoversNothing() {
        // 해지해도 행은 남는다 — 다시 켤 때 설정을 살리려고. 남아 있다고 발송되면 안 된다.
        DepositAlertSubscription s = DepositAlertSubscription.open(
                1L, "110-123-456", AlertChannel.PUSH, null, 0, NOW);
        s.close(NOW);

        assertThat(s.covers(1_000_000)).isFalse();
    }

    @Test
    @DisplayName("다시 켤 때도 연락처 규칙을 지킨다")
    void reopenRevalidates() {
        DepositAlertSubscription s = DepositAlertSubscription.open(
                1L, "110-123-456", AlertChannel.SMS, "010-1111-2222", 0, NOW);
        s.close(NOW);

        assertThatThrownBy(() -> s.reopen(null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(s.isActive()).isFalse();
    }
}
