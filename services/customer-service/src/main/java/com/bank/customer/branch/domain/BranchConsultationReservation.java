package com.bank.customer.branch.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 지점 상담 예약.
 *
 * <p>예전에는 "상담 예약" 버튼이 alert 만 띄우고 아무것도 저장하지 않았다. 고객은
 * 예약됐다고 믿고 지점에 갔을 것이다 — 화면이 문자로 안내하겠다고 약속까지 했다.
 */
@Getter
@Entity
@Table(name = "branch_consultation_reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BranchConsultationReservation {

    public static final String STATUS_RESERVED = "RESERVED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "reserved_at", nullable = false)
    private OffsetDateTime reservedAt;

    @Column(name = "topic_cd", nullable = false, length = 30)
    private String topicCd;

    @Column(name = "memo", length = 500)
    private String memo;

    /** 안내를 보낼 번호. 화면이 문자로 안내하겠다고 하므로 비워 둘 수 없다. */
    @Column(name = "contact_phone", nullable = false, length = 30)
    private String contactPhone;

    @Column(name = "status_cd", nullable = false, length = 20)
    private String statusCd;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static BranchConsultationReservation reserve(
            Long customerId, Branch branch, OffsetDateTime reservedAt,
            String topicCd, String memo, String contactPhone, OffsetDateTime now) {

        if (reservedAt.isBefore(now)) {
            throw new IllegalArgumentException("지난 시각으로는 예약할 수 없다");
        }
        if (!branch.isOpenAt(reservedAt.toLocalTime())) {
            throw new IllegalArgumentException(
                    "영업시간(" + branch.getOpenTime() + "~" + branch.getCloseTime() + ") 밖이다");
        }
        if (contactPhone == null || contactPhone.isBlank()) {
            throw new IllegalArgumentException("연락처가 없다 — 안내를 보낼 곳이 없다");
        }
        if (topicCd == null || topicCd.isBlank()) {
            throw new IllegalArgumentException("상담 내용을 선택해야 한다");
        }

        return BranchConsultationReservation.builder()
                .customerId(customerId)
                .branchId(branch.getBranchId())
                .reservedAt(reservedAt)
                .topicCd(topicCd)
                .memo(memo)
                .contactPhone(contactPhone.strip())
                .statusCd(STATUS_RESERVED)
                .createdAt(now)
                .build();
    }

    public void cancel(OffsetDateTime at) {
        if (!STATUS_RESERVED.equals(statusCd)) {
            throw new IllegalStateException("예약 상태가 아니다: " + statusCd);
        }
        this.statusCd = STATUS_CANCELLED;
        this.cancelledAt = at;
    }
}
