package com.bank.customer.session.domain;

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
 * 로그인 세션 (login_session 테이블).
 * INSERT-only 감사 — Redis JWT와 병행하여 DB 이력을 남긴다.
 * token_id FK는 DEFERRABLE → 세션 생성 후 api_token 생성, 이후 UPDATE로 채운다.
 */
@Entity
@Table(name = "login_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class LoginSession {

    public static final String STATUS_ACTIVE     = "ACTIVE";
    public static final String STATUS_EXPIRED    = "EXPIRED";
    public static final String STATUS_LOGGED_OUT = "LOGGED_OUT";
    public static final String STATUS_FORCED_OUT = "FORCED_OUT";

    public static final String CHANNEL_WEB = "WEB";

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "login_attempt_id", nullable = false)
    private Long loginAttemptId;

    @Column(name = "device_id")
    private Long deviceId;

    /** DEFERRABLE FK — api_token 생성 후 채워진다 */
    @Column(name = "token_id", nullable = false)
    private Long tokenId;

    @Column(name = "session_issued_ip", nullable = false, length = 45)
    private String sessionIssuedIp;

    @Column(name = "session_channel_code", nullable = false, length = 20)
    private String sessionChannelCode;

    @Column(name = "session_status_code", nullable = false, length = 20)
    private String sessionStatusCode;

    @Column(name = "session_mfa_completed_yn", nullable = false, length = 1)
    private Boolean sessionMfaCompletedYn;

    @Column(name = "session_expiry_at", nullable = false)
    private OffsetDateTime sessionExpiryAt;

    @Column(name = "session_ended_at")
    private OffsetDateTime sessionEndedAt;

    @Column(name = "session_end_reason_code", length = 20)
    private String sessionEndReasonCode;

    public void linkToken(Long tokenId) { this.tokenId = tokenId; }

    public void end(String reason) {
        this.sessionStatusCode   = STATUS_LOGGED_OUT;
        this.sessionEndedAt      = OffsetDateTime.now();
        this.sessionEndReasonCode = reason;
    }

    public void expire() {
        this.sessionStatusCode = STATUS_EXPIRED;
        this.sessionEndedAt    = OffsetDateTime.now();
    }
}
