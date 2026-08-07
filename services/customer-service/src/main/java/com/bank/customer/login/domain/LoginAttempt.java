package com.bank.customer.login.domain;

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
 * 로그인 시도 이력 (login_attempt 테이블).
 * 성공/실패 모두 기록. customer_id 는 loginId 미존재 시 null 허용.
 * INSERT-only 감사 테이블 — BaseEntity 미적용 (created_by 강제 NOT NULL 회피).
 * created_at / updated_at / version 은 DB DEFAULT(CURRENT_TIMESTAMP / 0) 로 처리.
 */
@Entity
@Table(name = "login_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "login_attempt_id")
    private Long loginAttemptId;

    /** 로그인 성공 시 실제 고객 ID, 미존재 ID 시도 시 null */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "attempted_login_id", nullable = false, length = 50)
    private String attemptedLoginId;

    @Column(name = "login_attempt_channel_code", nullable = false, length = 20)
    private String loginAttemptChannelCode;

    @Column(name = "login_attempt_ip", nullable = false, length = 45)
    private String loginAttemptIp;

    @Column(name = "login_attempt_user_agent", columnDefinition = "TEXT")
    private String loginAttemptUserAgent;

    /** 'T' = 성공, 'F' = 실패 */
    @Column(name = "login_attempt_success_yn", nullable = false, length = 1)
    private Boolean loginAttemptSuccessYn;

    /** 실패 사유 코드 (예: CUST_010, CUST_011). 성공 시 null */
    @Column(name = "login_attempt_failure_reason_code", length = 20)
    private String loginAttemptFailureReasonCode;

    @Column(name = "login_attempted_at", nullable = false)
    private OffsetDateTime loginAttemptedAt;
}
