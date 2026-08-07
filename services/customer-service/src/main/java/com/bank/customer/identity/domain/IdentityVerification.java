package com.bank.customer.identity.domain;

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
 * 본인확인 이력 (identity_verification 테이블).
 * INSERT-only — mobile_auth 검증 완료 시 함께 기록.
 */
@Entity
@Table(name = "identity_verification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class IdentityVerification {

    public static final String AGENCY_NICE = "NICE";
    public static final String AGENCY_KCB  = "KCB";
    public static final String AGENCY_SCI  = "SCI";
    public static final String AGENCY_PASS = "PASS";

    public static final String PURPOSE_SIGNUP          = "SIGNUP";
    public static final String PURPOSE_PASSWORD_RESET  = "PASSWORD_RESET";
    public static final String PURPOSE_IDENTITY_VERIFY = "IDENTITY_VERIFY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "identity_verification_id")
    private Long identityVerificationId;

    /** 가입 전 본인확인 시 null 허용 */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "mobile_auth_id", nullable = false)
    private Long mobileAuthId;

    @Column(name = "identity_verification_agency_code", nullable = false, length = 30)
    private String identityVerificationAgencyCode;

    @Column(name = "identity_verification_purpose_code", nullable = false, length = 30)
    private String identityVerificationPurposeCode;

    /** 연계정보(CI) — 고유 식별자 */
    @Column(name = "identity_verification_ci_value", nullable = false, length = 88)
    private String identityVerificationCiValue;

    @Column(name = "identity_verification_name", nullable = false, length = 50)
    private String identityVerificationName;

    @Column(name = "identity_verification_birth_date", nullable = false, length = 8)
    private String identityVerificationBirthDate;

    @Column(name = "identity_verification_gender_code", nullable = false, length = 1)
    private String identityVerificationGenderCode;

    @Column(name = "identity_verification_nationality_type_code", nullable = false, length = 20)
    private String identityVerificationNationalityTypeCode;

    @Column(name = "identity_verification_telecom_carrier_code", length = 20)
    private String identityVerificationTelecomCarrierCode;

    @Column(name = "identity_verification_phone_number", length = 20)
    private String identityVerificationPhoneNumber;

    @Column(name = "identity_verified_at", nullable = false)
    private OffsetDateTime identityVerifiedAt;

    /** 주민등록번호 AES-256 암호문 — 가입 시 party_person.rrn_encrypted 로 이전. 평문 미보관. */
    @Column(name = "rrn_encrypted", length = 255)
    private String rrnEncrypted;

    /** 가입에 1회 소비됐는지 — 검증 1건은 가입 1건에만 쓰인다. */
    @Column(name = "consumed_yn", nullable = false, length = 1)
    @Builder.Default
    private Boolean consumedYn = false;

    @Column(name = "consumed_customer_id")
    private Long consumedCustomerId;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    public boolean isConsumed() { return Boolean.TRUE.equals(consumedYn); }

    /** 가입 완료 시 소비 처리 — 생성된 고객과 연결한다. */
    public void consume(Long customerId) {
        this.consumedYn          = true;
        this.consumedCustomerId  = customerId;
        this.consumedAt          = OffsetDateTime.now();
    }
}
