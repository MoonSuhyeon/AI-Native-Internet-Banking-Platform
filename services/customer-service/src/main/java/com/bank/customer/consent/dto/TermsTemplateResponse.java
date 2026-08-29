package com.bank.customer.consent.dto;

import com.bank.customer.consent.domain.TermsTemplate;

/** 화면이 "이 업무에서 받아야 할 약관" 을 그릴 때 쓴다. */
public record TermsTemplateResponse(
        Long termsTemplateId,
        String termsNo,
        String termsName,
        String termsCategoryCd,
        String termsVersion,
        boolean required,
        String description
) {
    public static TermsTemplateResponse of(TermsTemplate t) {
        return new TermsTemplateResponse(
                t.getTermsTemplateId(), t.getTermsNo(), t.getTermsName(),
                t.getTermsCategoryCd(), t.getTermsVersion(), t.isRequiredYn(),
                t.getDescription());
    }
}
