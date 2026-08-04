package com.bank.loan.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * doc-agent API 호출 이력. loan_document_submission 매핑.
 */
@Getter
@Entity
@Table(name = "loan_document_submission")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class LoanDocumentSubmission {

    @Id
    @Column(name = "submission_id", length = 36)
    private String submissionId;

    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "application_id", nullable = false, length = 30)
    private String applicationId;

    @Column(name = "doc_code", nullable = false, length = 50)
    private String docCode;

    @Column(name = "verify_status", length = 50)
    private String verifyStatus;

    @Column(name = "confidence_score", precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** doc-agent.routed 로 재판정된 검증 상태를 반영한다. */
    public void updateVerifyStatus(String verifyStatus) {
        this.verifyStatus = verifyStatus;
    }
}
