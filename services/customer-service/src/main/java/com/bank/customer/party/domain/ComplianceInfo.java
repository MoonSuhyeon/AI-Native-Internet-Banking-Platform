package com.bank.customer.party.domain;

import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 컴플라이언스정보 (compliance_info 테이블).
 * party 1:1. AML/KYC/FATCA/CRS 정보를 통합 관리.
 * is_sanctioned_yn 은 DB GENERATED STORED 컬럼 — insertable=false, updatable=false.
 */
@Entity
@Table(name = "compliance_info")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ComplianceInfo extends BaseEntity {

    public static final String AML_LOW    = "LOW";
    public static final String AML_MEDIUM = "MEDIUM";
    public static final String AML_HIGH   = "HIGH";

    public static final String KYC_PENDING   = "PENDING";
    public static final String KYC_COMPLETED = "COMPLETED";
    public static final String KYC_EXPIRED   = "EXPIRED";

    public static final String CDD_SIMPLIFIED = "SIMPLIFIED";
    public static final String CDD_STANDARD   = "STANDARD";
    public static final String CDD_ENHANCED   = "ENHANCED";

    public static final String FATCA_EXEMPT     = "EXEMPT";
    public static final String FATCA_REPORTABLE = "REPORTABLE";
    public static final String FATCA_PENDING     = "PENDING";

    public static final String CRS_EXEMPT     = "EXEMPT";
    public static final String CRS_REPORTABLE = "REPORTABLE";
    public static final String CRS_PENDING    = "PENDING";

    @Id
    @Column(name = "party_id")
    private Long partyId;

    // ── AML ──────────────────────────────────────────────────────────────────
    @Column(name = "aml_risk_level_code", nullable = false, length = 20)
    private String amlRiskLevelCode;

    @Column(name = "aml_last_assessed_at")
    private OffsetDateTime amlLastAssessedAt;

    @Column(name = "aml_next_review_date", length = 8)
    private String amlNextReviewDate;

    @Column(name = "aml_last_assessed_by_employee_id")
    private Long amlLastAssessedByEmployeeId;

    // ── 제재 ─────────────────────────────────────────────────────────────────
    @Column(name = "is_ofac_sanctioned_yn", nullable = false, length = 1)
    private Boolean isOfacSanctionedYn;

    @Column(name = "is_un_sanctioned_yn", nullable = false, length = 1)
    private Boolean isUnSanctionedYn;

    @Column(name = "is_eu_sanctioned_yn", nullable = false, length = 1)
    private Boolean isEuSanctionedYn;

    @Column(name = "is_kr_sanctioned_yn", nullable = false, length = 1)
    private Boolean isKrSanctionedYn;

    /** DB GENERATED STORED — 읽기 전용 */
    @Column(name = "is_sanctioned_yn", insertable = false, updatable = false, length = 1)
    private Boolean isSanctionedYn;

    @Column(name = "sanction_last_screened_at")
    private OffsetDateTime sanctionLastScreenedAt;

    @Column(name = "sanction_next_screen_date", length = 8)
    private String sanctionNextScreenDate;

    // ── KYC ──────────────────────────────────────────────────────────────────
    @Column(name = "kyc_status_code", nullable = false, length = 20)
    private String kycStatusCode;

    @Column(name = "kyc_completed_at")
    private OffsetDateTime kycCompletedAt;

    @Column(name = "kyc_expiry_date", length = 8)
    private String kycExpiryDate;

    @Column(name = "kyc_next_review_date", length = 8)
    private String kycNextReviewDate;

    @Column(name = "kyc_completed_by_employee_id")
    private Long kycCompletedByEmployeeId;

    @Column(name = "identity_verification_method_code", length = 10)
    private String identityVerificationMethodCode;

    // ── CDD ──────────────────────────────────────────────────────────────────
    @Column(name = "cdd_level_code", nullable = false, length = 20)
    private String cddLevelCode;

    @Column(name = "cdd_last_reviewed_at")
    private OffsetDateTime cddLastReviewedAt;

    @Column(name = "cdd_next_review_date", length = 8)
    private String cddNextReviewDate;

    @Column(name = "edd_required_yn", nullable = false, length = 1)
    private Boolean eddRequiredYn;

    @Column(name = "edd_last_reviewed_at")
    private OffsetDateTime eddLastReviewedAt;

    @Column(name = "edd_next_review_date", length = 8)
    private String eddNextReviewDate;

    // ── FATCA ─────────────────────────────────────────────────────────────────
    @Column(name = "fatca_status_code", nullable = false, length = 20)
    private String fatcaStatusCode;

    @Column(name = "fatca_last_reviewed_at")
    private OffsetDateTime fatcaLastReviewedAt;

    @Column(name = "fatca_next_review_date", length = 8)
    private String fatcaNextReviewDate;

    @Column(name = "fatca_reportable_yn", nullable = false, length = 1)
    private Boolean fatcaReportableYn;

    // ── CRS ───────────────────────────────────────────────────────────────────
    @Column(name = "crs_status_code", nullable = false, length = 20)
    private String crsStatusCode;

    @Column(name = "crs_last_reviewed_at")
    private OffsetDateTime crsLastReviewedAt;

    @Column(name = "crs_next_review_date", length = 8)
    private String crsNextReviewDate;

    @Column(name = "crs_reportable_yn", nullable = false, length = 1)
    private Boolean crsReportableYn;

    // ── 비즈니스 메서드 ────────────────────────────────────────────────────────

    public boolean isSanctioned() { return Boolean.TRUE.equals(isSanctionedYn); }
    public boolean isEddRequired() { return Boolean.TRUE.equals(eddRequiredYn); }

    public void updateAmlRisk(String riskLevel, Long assessedByEmployeeId) {
        this.amlRiskLevelCode            = riskLevel;
        this.amlLastAssessedAt           = OffsetDateTime.now();
        this.amlLastAssessedByEmployeeId = assessedByEmployeeId;
    }

    public void completeKyc(String expiryDate, String methodCode, Long completedByEmployeeId) {
        this.kycStatusCode                    = KYC_COMPLETED;
        this.kycCompletedAt                   = OffsetDateTime.now();
        this.kycExpiryDate                    = expiryDate;
        this.identityVerificationMethodCode   = methodCode;
        this.kycCompletedByEmployeeId         = completedByEmployeeId;
    }

    public void updateSanctionScreen(String screenDate) {
        this.sanctionLastScreenedAt  = OffsetDateTime.now();
        this.sanctionNextScreenDate  = screenDate;
    }

    public void requireEdd() {
        this.eddRequiredYn    = true;
        this.cddLevelCode     = CDD_ENHANCED;
    }
}
