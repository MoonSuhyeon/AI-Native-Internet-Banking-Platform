package com.bank.customer.cert.domain;

import com.bank.common.time.BusinessDate;
import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Entity
@Table(name = "certificate")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Certificate extends BaseEntity {

    public static final String STATUS_ACTIVE    = "ACTIVE";
    public static final String STATUS_EXPIRED   = "EXPIRED";
    public static final String STATUS_REVOKED   = "REVOKED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "certificate_id")
    private Long certificateId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "auth_method_id", nullable = false)
    private Long authMethodId;

    @Column(name = "certificate_type_code", nullable = false, length = 20)
    private String certificateTypeCode;

    @Column(name = "certificate_serial_number", nullable = false, length = 100, unique = true)
    private String certificateSerialNumber;

    @Column(name = "certificate_issuer_name", nullable = false, length = 50)
    private String certificateIssuerName;

    @Column(name = "certificate_subject_dn", nullable = false, columnDefinition = "TEXT")
    private String certificateSubjectDn;

    @Column(name = "certificate_issuer_dn", nullable = false, columnDefinition = "TEXT")
    private String certificateIssuerDn;

    @Column(name = "certificate_public_key", nullable = false, columnDefinition = "TEXT")
    private String certificatePublicKey;

    @Column(name = "certificate_purpose_code", nullable = false, length = 50)
    private String certificatePurposeCode;

    @Column(name = "certificate_issued_date", nullable = false, length = 8)
    private String certificateIssuedDate;

    @Column(name = "certificate_expiry_date", nullable = false, length = 8)
    private String certificateExpiryDate;

    @Column(name = "certificate_renewal_scheduled_date", length = 8)
    private String certificateRenewalScheduledDate;

    @Column(name = "certificate_status_code", nullable = false, length = 20)
    private String certificateStatusCode;

    @Column(name = "certificate_revoke_reason_code", length = 200)
    private String certificateRevokeReasonCode;

    @Column(name = "certificate_revoked_at")
    private OffsetDateTime certificateRevokedAt;

    @Column(name = "cert_pin_hash", length = 255)
    private String certPinHash;

    @Column(name = "cert_login_failure_count")
    private Integer certLoginFailureCount;

    @Column(name = "max_cert_login_failure_count")
    private Integer maxCertLoginFailureCount;

    @Column(name = "last_cert_login_failure_at")
    private OffsetDateTime lastCertLoginFailureAt;

    @Column(name = "cert_login_locked_at")
    private OffsetDateTime certLoginLockedAt;

    @Column(name = "cert_login_unlocked_at")
    private OffsetDateTime certLoginUnlockedAt;

    // -------------------------------------------------------------------------

    public boolean isActive() {
        return STATUS_ACTIVE.equals(certificateStatusCode);
    }

    public boolean isExpired() {
        return BusinessDate.todayDate().isAfter(LocalDate.parse(
                certificateExpiryDate.substring(0, 4) + "-"
                        + certificateExpiryDate.substring(4, 6) + "-"
                        + certificateExpiryDate.substring(6, 8)));
    }

    public boolean isLocked() {
        return certLoginLockedAt != null && certLoginUnlockedAt == null;
    }

    public void recordLoginFailure() {
        if (this.certLoginFailureCount == null) this.certLoginFailureCount = 0;
        this.certLoginFailureCount++;
        this.lastCertLoginFailureAt = OffsetDateTime.now();
        if (this.maxCertLoginFailureCount != null
                && this.certLoginFailureCount >= this.maxCertLoginFailureCount) {
            this.certLoginLockedAt = OffsetDateTime.now();
        }
    }

    public void updatePinHash(String pinHash) {
        this.certPinHash = pinHash;
    }

    public void recordLoginSuccess() {
        this.certLoginFailureCount = 0;
        this.lastCertLoginFailureAt = null;
    }

    public void revoke(String reason) {
        this.certificateStatusCode = STATUS_REVOKED;
        this.certificateRevokeReasonCode = reason;
        this.certificateRevokedAt = OffsetDateTime.now();
    }
}
