package com.bank.docagent.submission.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "loan_document_submission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class DocumentSubmission {

    @Id
    @UuidGenerator
    @Column(name = "submission_id", updatable = false, nullable = false)
    private UUID submissionId;

    @Column(name = "application_id", nullable = false)
    private String applicationId;

    @Column(name = "doc_code", nullable = false)
    private String docCode;

    @Column(name = "raw_object_key")
    private String rawObjectKey;

    @Column(name = "masked_object_key")
    private String maskedObjectKey;

    @Column(name = "forgery_score", precision = 3, scale = 2)
    private BigDecimal forgeryScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "verify_status", nullable = false)
    @Builder.Default
    private VerifyStatus verifyStatus = VerifyStatus.PENDING;

    @Column(name = "reviewer_id")
    private String reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "human_review_status")
    @Builder.Default
    private HumanReviewStatus humanReviewStatus = HumanReviewStatus.NOT_REQUIRED;

    @Column(name = "retention_until")
    private LocalDate retentionUntil;

    @Column(name = "legal_hold", nullable = false)
    @Builder.Default
    private boolean legalHold = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    public void updateKeys(String rawKey, String maskedKey) {
        this.rawObjectKey = rawKey;
        this.maskedObjectKey = maskedKey;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateStatus(VerifyStatus status) {
        this.verifyStatus = status;
        this.updatedAt = LocalDateTime.now();
    }

    public void markHoldPending() {
        this.humanReviewStatus = HumanReviewStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
    }

    public void applyHumanDecision(HumanReviewStatus decision, String reviewerId) {
        this.humanReviewStatus = decision;
        this.reviewerId = reviewerId;
        this.verifyStatus = (decision == HumanReviewStatus.CONFIRMED_FORGERY)
            ? VerifyStatus.LOCKED : VerifyStatus.CLEARED;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateRetentionUntil(LocalDate until) {
        this.retentionUntil = until;
        this.updatedAt = LocalDateTime.now();
    }

    public void enableLegalHold() {
        this.legalHold = true;
        this.retentionUntil = null;   // 무기한
        this.updatedAt = LocalDateTime.now();
    }

    public void disableLegalHold(LocalDate newRetentionUntil) {
        this.legalHold = false;
        this.retentionUntil = newRetentionUntil;
        this.updatedAt = LocalDateTime.now();
    }

    public enum VerifyStatus {
        PENDING, AUTO_PASS, NEEDS_RESUBMIT, HOLD, LOCKED, CLEARED
    }

    public enum HumanReviewStatus {
        NOT_REQUIRED, PENDING, CLEARED, CONFIRMED_FORGERY
    }
}
