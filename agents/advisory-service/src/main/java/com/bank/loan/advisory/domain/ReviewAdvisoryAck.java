package com.bank.loan.advisory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

/**
 * 어드바이저리 리포트 ack 응답. ERD REVIEW_ADVISORY_ACK 매핑. append-only 이력.
 * 한 리포트에 ack 가 여러 번 적재될 수 있다 (예: 보류 → 추가 검토 → 확정).
 * ack 시점의 결정 전/후 코드를 함께 캡처해 사후 통계 가능.
 */
@Getter
@Entity
@Table(name = "review_advisory_ack")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReviewAdvisoryAck {

    public static final String RESPONSE_MAINTAIN         = "MAINTAIN";
    public static final String RESPONSE_OVERTURN         = "OVERTURN";
    public static final String RESPONSE_ESCALATE         = "ESCALATE";
    public static final String RESPONSE_NEEDS_MORE_INFO  = "NEEDS_MORE_INFO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "advk_id")
    private Long advkId;

    @Column(name = "advr_id", nullable = false)
    private Long advrId;

    @Column(name = "ack_reviewer_id", nullable = false)
    private Long ackReviewerId;

    @Column(name = "ack_response_cd", nullable = false, length = 50)
    private String ackResponseCd;

    @Column(name = "decision_change_yn", nullable = false, length = 1)
    private String decisionChangeYn;

    @Column(name = "ack_reason_cd", length = 50)
    private String ackReasonCd;

    @Column(name = "ack_remark", length = 500)
    private String ackRemark;

    @Column(name = "before_decision_cd", length = 50)
    private String beforeDecisionCd;

    @Column(name = "after_decision_cd", length = 50)
    private String afterDecisionCd;

    @Column(name = "acked_at", nullable = false)
    private OffsetDateTime ackedAt;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "device", length = 200)
    private String device;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;
}
