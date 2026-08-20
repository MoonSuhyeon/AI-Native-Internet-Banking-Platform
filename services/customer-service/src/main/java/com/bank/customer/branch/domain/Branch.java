package com.bank.customer.branch.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 영업점.
 *
 * <p>코어뱅킹의 {@code department} 와 다른 개념이다 — 그쪽은 내부 조직 단위이고
 * 이쪽은 고객이 찾아가는 곳이라 주소·전화·영업시간을 갖는다.
 */
@Getter
@Entity
@Table(name = "branch")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    @Column(name = "branch_name", nullable = false, length = 100)
    private String branchName;

    @Column(name = "branch_type", nullable = false, length = 20)
    private String branchType;

    @Column(name = "region", nullable = false, length = 50)
    private String region;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "open_time", nullable = false, length = 5)
    private String openTime;

    @Column(name = "close_time", nullable = false, length = 5)
    private String closeTime;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * 이 시각에 상담을 받을 수 있는가.
     *
     * <p>영업시간 밖 예약을 받으면 고객이 헛걸음한다. 마감 시각 자체도 받지 않는다 —
     * 16:00 마감인데 16:00 상담은 앉을 시간이 없다.
     */
    public boolean isOpenAt(LocalTime time) {
        return !time.isBefore(LocalTime.parse(openTime)) && time.isBefore(LocalTime.parse(closeTime));
    }
}
