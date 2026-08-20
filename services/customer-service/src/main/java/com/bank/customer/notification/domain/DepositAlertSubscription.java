package com.bank.customer.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 입금통보 신청.
 *
 * <p>이 표는 <b>발송된 알림</b>이 아니라 <b>받겠다는 의사</b>를 담는다. 알림함
 * ({@link CustomerNotification}) 과 헷갈리면 안 된다 — 저쪽은 결과, 이쪽은 설정이다.
 *
 * <p>연락처 규칙을 엔티티에 둔다. 연락처 없는 SMS 신청은 등록은 되지만 발송 시점에
 * 조용히 아무 데도 안 간다. 고객은 신청했다고 믿고 기다린다 — 가장 나쁜 종류의 실패다.
 */
@Getter
@Entity
@Table(name = "deposit_alert_subscription")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class DepositAlertSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_cd", nullable = false, length = 20)
    private AlertChannel channel;

    @Column(name = "contact", nullable = false, length = 255)
    private String contact;

    @Column(name = "min_amount", nullable = false)
    private long minAmount;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static DepositAlertSubscription open(
            Long customerId, String accountNumber, AlertChannel channel,
            String contact, long minAmount, OffsetDateTime at) {

        return DepositAlertSubscription.builder()
                .customerId(customerId)
                .accountNumber(requireAccount(accountNumber))
                .channel(channel)
                .contact(requireContact(channel, contact, customerId))
                .minAmount(requireMinAmount(minAmount))
                .active(true)
                .createdAt(at)
                .updatedAt(at)
                .build();
    }

    /** 해지. 행을 지우지 않는 이유는, 다시 신청할 때 같은 설정을 되살릴 수 있어서다. */
    public void close(OffsetDateTime at) {
        this.active = false;
        this.updatedAt = at;
    }

    /** 이미 있는 신청을 다시 켠다. 유니크 제약 때문에 새로 만들 수 없다. */
    public void reopen(String contact, long minAmount, OffsetDateTime at) {
        this.contact = requireContact(this.channel, contact, this.customerId);
        this.minAmount = requireMinAmount(minAmount);
        this.active = true;
        this.updatedAt = at;
    }

    /** 이 입금을 통보해야 하는가. */
    public boolean covers(long depositAmount) {
        return active && depositAmount >= minAmount;
    }

    private static String requireAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("어느 계좌인지 없다");
        }
        return accountNumber.strip();
    }

    private static String requireContact(AlertChannel channel, String contact, Long customerId) {
        if (!channel.requiresContact()) {
            // 앱 알림함은 고객 자신이 목적지다. 빈 값을 두면 NOT NULL 에 걸린다.
            return String.valueOf(customerId);
        }
        if (contact == null || contact.isBlank()) {
            throw new IllegalArgumentException(
                    channel + " 통보는 연락처가 있어야 한다 — 없으면 발송 때 아무 데도 안 간다");
        }
        return contact.strip();
    }

    private static long requireMinAmount(long minAmount) {
        if (minAmount < 0) {
            throw new IllegalArgumentException("통보 기준액은 음수일 수 없다 — 전부 받으려면 0 이다");
        }
        return minAmount;
    }
}
