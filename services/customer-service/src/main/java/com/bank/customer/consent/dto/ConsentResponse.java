package com.bank.customer.consent.dto;

import com.bank.customer.consent.domain.TermsConsent;
import com.bank.customer.consent.domain.TermsTemplate;

import java.time.OffsetDateTime;

/**
 * 동의 한 건.
 *
 * <p>약관 이름·성격을 함께 담는다. 동의 이력을 보는 이유가 "무엇에 동의했나" 이므로
 * 번호만 돌려주면 그 질문에 답하지 못한다.
 *
 * <p>{@code clientIp} 는 담지 않는다. 고객이 자기 이력을 볼 때 필요한 값이 아니고,
 * 분쟁 조사에서 필요해지면 감사 로그가 붙은 별도 경로로 연다.
 */
public record ConsentResponse(
        Long consentId,
        String termsNo,
        String termsName,
        String termsCategoryCd,
        String termsVersion,
        boolean required,
        String bizDivCd,
        Long consentTargetId,
        boolean agreed,
        OffsetDateTime agreedAt,
        String consentMethodCd,
        boolean withdrawn,
        OffsetDateTime withdrawnAt,
        String withdrawnReason,
        /** 지금 이 동의가 효력이 있는가. 동의했고 철회하지 않았을 때만 참이다. */
        boolean effective
) {
    public static ConsentResponse of(TermsConsent c, TermsTemplate t) {
        return new ConsentResponse(
                c.getConsentId(),
                t != null ? t.getTermsNo() : null,
                t != null ? t.getTermsName() : null,
                t != null ? t.getTermsCategoryCd() : null,
                t != null ? t.getTermsVersion() : null,
                t != null && t.isRequiredYn(),
                c.getBizDivCd(),
                c.getConsentTargetId(),
                c.isAgreedYn(),
                c.getAgreedAt(),
                c.getConsentMethodCd(),
                c.isWithdrawnYn(),
                c.getWithdrawnAt(),
                c.getWithdrawnReason(),
                c.isAgreedYn() && !c.isWithdrawnYn());
    }
}
