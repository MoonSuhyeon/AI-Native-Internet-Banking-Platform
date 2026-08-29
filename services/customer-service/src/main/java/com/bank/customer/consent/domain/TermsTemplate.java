package com.bank.customer.consent.domain;

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
 * 약관 템플릿 — 동의가 가리키는 대상.
 *
 * <p><b>개정하면 고치는 것이 아니라 새 행을 넣는다.</b> 같은 {@code termsNo} 의 내용을
 * 바꾸면 그 이전에 동의한 사람들이 무엇에 동의했는지 사라진다. 동의 이력이 가리키는
 * 곳의 내용이 바뀌면 이력은 더 이상 증거가 아니다.
 */
@Getter
@Entity
@Table(name = "terms_template")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TermsTemplate {

    /** 약관 성격. 무엇에 대한 동의인지 가른다. */
    public static final String CATEGORY_PRIVACY     = "PRIVACY";
    public static final String CATEGORY_THIRD_PARTY = "THIRD_PARTY";
    public static final String CATEGORY_MARKETING   = "MARKETING";
    public static final String CATEGORY_AGREEMENT   = "AGREEMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "terms_template_id")
    private Long termsTemplateId;

    /** 화면이 이 값으로 템플릿을 찾는다. 바꾸면 그 화면의 동의가 끊긴다. */
    @Column(name = "terms_no", nullable = false, length = 50)
    private String termsNo;

    @Column(name = "terms_name", nullable = false, length = 200)
    private String termsName;

    @Column(name = "terms_category_cd", nullable = false, length = 20)
    private String termsCategoryCd;

    @Column(name = "terms_version", nullable = false, length = 20)
    private String termsVersion;

    @Column(name = "description")
    private String description;

    /** 필수 약관은 거절하면 그 업무를 진행할 수 없다. */
    @Column(name = "required_yn", nullable = false)
    private boolean requiredYn;

    @Column(name = "biz_div_cd", nullable = false, length = 50)
    private String bizDivCd;

    @Column(name = "active_yn", nullable = false)
    private boolean activeYn;

    @Column(name = "effective_from", length = 8)
    private String effectiveFrom;

    @Column(name = "effective_to", length = 8)
    private String effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
