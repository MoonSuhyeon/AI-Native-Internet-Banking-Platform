package com.bank.customer.consent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 고객 약관 동의 이력. 고객 도메인이 정본이다.
 *
 * <p><b>왜 {@code BaseEntity} 를 쓰지 않는가.</b> 그 상위 클래스는 소프트 삭제
 * ({@code deletedAt}) 를 전제한다. 동의 이력에 삭제는 성립하지 않는다 — 동의를 거두는
 * 것은 <b>철회</b>이고, 철회는 동의했던 사실을 지우는 것이 아니라 그 뒤에 덧붙이는
 * 사실이다. 지울 수 있게 두면 뒤에 분쟁이 났을 때 근거가 되지 못한다.
 * DB 트리거도 같은 것을 강제한다(V42).
 *
 * <p><b>거절도 기록한다.</b> {@code agreedYn = false} 로 남긴다. "안 받았다" 와
 * "거절했다" 는 다른 사실이고, 분쟁이 되는 것은 대개 그 차이다.
 */
@Getter
@Entity
@Table(name = "terms_consent")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@lombok.AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TermsConsent {

    /** 어떻게 받았는가. 분쟁 시 경로가 쟁점이 된다. */
    public static final String METHOD_WEB    = "WEB";
    public static final String METHOD_MOBILE = "MOBILE";
    public static final String METHOD_BRANCH = "BRANCH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consent_id")
    private Long consentId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "terms_template_id", nullable = false, updatable = false)
    private Long termsTemplateId;

    @Column(name = "biz_div_cd", nullable = false, updatable = false, length = 50)
    private String bizDivCd;

    /** 어느 건에 대한 동의인가(인증서 발급 id·대출 신청 id 등). 도메인이 달라 값 참조다. */
    @Column(name = "consent_target_id", updatable = false)
    private Long consentTargetId;

    @Column(name = "agreed_yn", nullable = false, updatable = false)
    private boolean agreedYn;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private OffsetDateTime agreedAt;

    @Column(name = "consent_method_cd", nullable = false, updatable = false, length = 20)
    private String consentMethodCd;

    @Column(name = "consent_tool", updatable = false, length = 500)
    private String consentTool;

    @Column(name = "signed_doc_url", updatable = false, length = 500)
    private String signedDocUrl;

    @Column(name = "signed_doc_hash", updatable = false, length = 64)
    private String signedDocHash;

    /** IPv6 최대 표기까지 담는다. 서브넷 연산을 쓰지 않으므로 문자열로 둔다. */
    @Column(name = "client_ip", updatable = false, length = 45)
    private String clientIp;

    @Column(name = "withdrawn_yn", nullable = false)
    private boolean withdrawnYn;

    @Column(name = "withdrawn_at")
    private OffsetDateTime withdrawnAt;

    @Column(name = "withdrawn_reason", length = 500)
    private String withdrawnReason;

    @Column(name = "retention_until", length = 8)
    private String retentionUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    /**
     * 철회한다.
     *
     * <p>되돌리는 길은 두지 않는다. 다시 동의를 받으려면 새 이력으로 남긴다 —
     * 철회를 켰다 껐다 할 수 있으면 이력이 사실을 말하지 못한다. DB 트리거도 같은
     * 것을 막는다.
     */
    public void withdraw(String reason, OffsetDateTime when, Long actor) {
        if (this.withdrawnYn) {
            throw new IllegalStateException("이미 철회된 동의다: consentId=" + consentId);
        }
        if (!this.agreedYn) {
            throw new IllegalStateException("거절한 항목은 철회할 것이 없다: consentId=" + consentId);
        }
        this.withdrawnYn = true;
        this.withdrawnAt = when;
        this.withdrawnReason = reason;
        this.updatedAt = when;
        this.updatedBy = actor;
    }
}
