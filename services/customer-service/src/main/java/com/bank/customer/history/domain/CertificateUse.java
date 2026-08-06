package com.bank.customer.history.domain;

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
 * 인증서 사용 이력 (certificate_use 테이블).
 * INSERT-only 감사 테이블 — BaseEntity 미적용.
 * MVP: signedDataHash = IP+serial+timestamp 복합 해시, signatureValue = PIN 해시.
 */
@Entity
@Table(name = "certificate_use")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CertificateUse {

    public static final String RESULT_SUCCESS    = "SUCCESS";
    public static final String RESULT_FAIL_PIN   = "FAIL_PIN";
    public static final String RESULT_FAIL_LOCKED = "FAIL_LOCKED";
    public static final String RESULT_FAIL_EXPIRED = "FAIL_EXPIRED";
    public static final String RESULT_FAIL_REVOKED = "FAIL_REVOKED";

    public static final String PURPOSE_LOGIN = "LOGIN";
    /** 자금이동 승인(step-up). 로그인과 구별돼야 사후 조사에서 무엇을 위한 인증이었는지 알 수 있다. */
    public static final String PURPOSE_TRANSFER_APPROVAL = "TRANSFER_APPROVAL";
    public static final String CHANNEL_WEB   = "WEB";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "certificate_use_id")
    private Long certificateUseId;

    @Column(name = "certificate_id", nullable = false)
    private Long certificateId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "purpose_code", nullable = false, length = 30)
    private String purposeCode;

    @Column(name = "certificate_use_target_transaction_id", length = 50)
    private String targetTransactionId;

    @Column(name = "certificate_use_target_system_code", length = 20)
    private String targetSystemCode;

    @Column(name = "certificate_use_signed_data_hash", nullable = false, length = 255)
    private String signedDataHash;

    @Column(name = "certificate_use_signature_value", nullable = false, columnDefinition = "TEXT")
    private String signatureValue;

    @Column(name = "certificate_use_verification_result_code", nullable = false, length = 20)
    private String verificationResultCode;

    @Column(name = "certificate_use_failure_reason_code", length = 200)
    private String failureReasonCode;

    @Column(name = "certificate_use_request_ip", nullable = false, length = 45)
    private String requestIp;

    @Column(name = "certificate_use_request_channel_code", nullable = false, length = 20)
    private String requestChannelCode;

    @Column(name = "certificate_used_at", nullable = false)
    private OffsetDateTime usedAt;
}
