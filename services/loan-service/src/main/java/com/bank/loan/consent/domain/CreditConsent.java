package com.bank.loan.consent.domain;

import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 신용정보 동의. ERD STAGE 2 CREDIT_CONSENT 매핑.
 *
 * 동의 행위(consentYn=Y)는 신규 INSERT 한다. 철회(withdraw)는 별도 메서드로 행 갱신.
 */
@Getter
@Entity
@Table(name = "credit_consent")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CreditConsent extends BaseEntity {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "csnt_id")
    private Long csntId;

    @Column(name = "appl_id", nullable = false)
    private Long applId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "consent_type_cd", nullable = false, length = 50)
    private String consentTypeCd;

    @Column(name = "consent_scope_cd", nullable = false, length = 50)
    private String consentScopeCd;

    @Column(name = "consent_target_cd", nullable = false, length = 50)
    private String consentTargetCd;

    @Column(name = "consent_yn", nullable = false)
    private Boolean consentYn;

    @Column(name = "consented_at", nullable = false)
    private OffsetDateTime consentedAt;

    @Column(name = "consent_method_cd", length = 50)
    private String consentMethodCd;

    @Column(name = "consent_token", length = 100)
    private String consentToken;

    @Column(name = "signed_doc_url", length = 500)
    private String signedDocUrl;

    @Column(name = "signed_doc_hash", length = 128)
    private String signedDocHash;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "device", length = 200)
    private String device;

    @Column(name = "retention_until", length = 8)
    private String retentionUntil;

    @Column(name = "withdrawn_yn", nullable = false)
    private Boolean withdrawnYn;

    @Column(name = "withdrawn_at")
    private OffsetDateTime withdrawnAt;

    public boolean isWithdrawn() {
        return Boolean.TRUE.equals(withdrawnYn);
    }

    public void withdraw(OffsetDateTime at) {
        this.withdrawnYn = true;
        this.withdrawnAt = at;
    }
}
