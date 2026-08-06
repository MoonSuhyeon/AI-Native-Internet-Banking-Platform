package com.bank.customer.cert.domain;

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
 * 거래 승인 토큰. V7 마이그레이션이 만들어 둔 {@code auth_token} 테이블에 매핑한다.
 *
 * <p>테이블은 "인증토큰 — 결제계 연동" 목적으로 이미 있었는데 엔티티가 없어 아무도 쓰지
 * 않았다. 용도가 정확히 이것이라 새로 만들지 않고 그대로 쓴다.
 *
 * <p><b>토큰 원문을 저장하지 않는다.</b> {@code authTokenHash} 에는 토큰과 <b>거래 내용</b>을
 * 함께 해시한 값이 들어간다. 이렇게 하면 두 가지가 동시에 성립한다.
 * <ul>
 *   <li>DB 가 유출돼도 토큰을 복원할 수 없다.</li>
 *   <li>토큰이 그 거래에 묶인다 — 1,000원 이체로 받은 토큰으로 1,000만원을 보낼 수 없다.
 *       검증할 때 같은 거래 내용으로 해시를 다시 만들어 대조하므로, 금액이나 계좌가 다르면
 *       애초에 조회되지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "auth_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AuthToken {

    /** 자금 이동 승인 용도. */
    public static final String PURPOSE_TRANSFER = "TRANSFER";

    public static final String METHOD_CERT = "CERT";

    public static final String STATUS_ACTIVE  = "ACTIVE";
    public static final String STATUS_USED    = "USED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auth_token_id")
    private Long authTokenId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** sha256(토큰 || 거래 내용). 원문도, 거래와 무관한 해시도 아니다. */
    @Column(name = "auth_token_hash", nullable = false)
    private String authTokenHash;

    @Column(name = "auth_method_type_code", nullable = false)
    private String authMethodTypeCode;

    @Column(name = "auth_token_purpose_code", nullable = false)
    private String authTokenPurposeCode;

    @Column(name = "auth_token_status_code", nullable = false)
    @Builder.Default
    private String authTokenStatusCode = STATUS_ACTIVE;

    @Column(name = "auth_token_issued_at", nullable = false)
    @Builder.Default
    private OffsetDateTime authTokenIssuedAt = OffsetDateTime.now();

    @Column(name = "auth_token_expiry_at", nullable = false)
    private OffsetDateTime authTokenExpiryAt;

    @Column(name = "auth_token_used_at")
    private OffsetDateTime authTokenUsedAt;

    public boolean isExpired(OffsetDateTime now) {
        return authTokenExpiryAt.isBefore(now);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(authTokenStatusCode);
    }

    /** 사용 처리. 1회용이므로 되돌리지 않는다. */
    public void markUsed(OffsetDateTime now) {
        this.authTokenStatusCode = STATUS_USED;
        this.authTokenUsedAt = now;
    }

    public void markExpired() {
        this.authTokenStatusCode = STATUS_EXPIRED;
    }
}
