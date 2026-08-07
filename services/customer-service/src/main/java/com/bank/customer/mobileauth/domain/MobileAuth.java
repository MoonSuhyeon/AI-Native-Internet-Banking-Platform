package com.bank.customer.mobileauth.domain;

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
 * 휴대폰 인증 요청 이력 (mobile_auth 테이블).
 * INSERT-only — 검증 완료 시 verified_yn, verified_at, attempt_count 업데이트.
 */
@Entity
@Table(name = "mobile_auth")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MobileAuth {

    public static final String METHOD_SMS  = "SMS";
    public static final String METHOD_PASS = "PASS";

    public static final String PURPOSE_SIGNUP         = "SIGNUP";
    public static final String PURPOSE_PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PURPOSE_IDENTITY_VERIFY = "IDENTITY_VERIFY";

    public static final String CHANNEL_WEB = "WEB";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mobile_auth_id")
    private Long mobileAuthId;

    /** 가입 전 본인확인 허용 → nullable */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "mobile_auth_method_type_code", nullable = false, length = 20)
    private String mobileAuthMethodTypeCode;

    @Column(name = "mobile_auth_telecom_carrier_code", nullable = false, length = 20)
    private String mobileAuthTelecomCarrierCode;

    @Column(name = "mobile_auth_recipient_phone_number", nullable = false, length = 20)
    private String mobileAuthRecipientPhoneNumber;

    /** SHA-256 해시 */
    @Column(name = "mobile_auth_code_hash", nullable = false, length = 255)
    private String mobileAuthCodeHash;

    @Column(name = "mobile_auth_purpose_code", nullable = false, length = 30)
    private String mobileAuthPurposeCode;

    @Column(name = "mobile_auth_request_ip", nullable = false, length = 45)
    private String mobileAuthRequestIp;

    @Column(name = "mobile_auth_request_channel_code", nullable = false, length = 20)
    private String mobileAuthRequestChannelCode;

    @Column(name = "mobile_auth_sent_at", nullable = false)
    private OffsetDateTime mobileAuthSentAt;

    @Column(name = "mobile_auth_expiry_at", nullable = false)
    private OffsetDateTime mobileAuthExpiryAt;

    @Column(name = "mobile_auth_verified_at")
    private OffsetDateTime mobileAuthVerifiedAt;

    @Column(name = "mobile_auth_verified_yn", nullable = false, length = 1)
    private Boolean mobileAuthVerifiedYn;

    @Column(name = "mobile_auth_attempt_count", nullable = false)
    private int mobileAuthAttemptCount;

    @Column(name = "mobile_auth_failure_reason_code", length = 200)
    private String mobileAuthFailureReasonCode;

    public boolean isVerified() { return Boolean.TRUE.equals(mobileAuthVerifiedYn); }
    public boolean isExpired()  { return OffsetDateTime.now().isAfter(mobileAuthExpiryAt); }

    public void recordAttempt() { this.mobileAuthAttemptCount++; }

    public void verify() {
        this.mobileAuthVerifiedYn = true;
        this.mobileAuthVerifiedAt = OffsetDateTime.now();
    }

    public void fail(String reason) {
        this.mobileAuthFailureReasonCode = reason;
    }
}
