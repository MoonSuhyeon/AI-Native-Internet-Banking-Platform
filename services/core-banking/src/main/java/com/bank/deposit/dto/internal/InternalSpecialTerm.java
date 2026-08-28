package com.bank.deposit.dto.internal;

import com.bank.deposit.domain.entity.SpecialTerm;

/** 약관 검색 결과 한 건. 상담이 약관 안내에 쓴다. */
public record InternalSpecialTerm(
        Long specialTermId,
        String specialTermName,
        String specialTermContent,
        String specialTermSummary,
        Boolean isRequired,
        String status
) {
    public static InternalSpecialTerm from(SpecialTerm t) {
        return new InternalSpecialTerm(
                t.getSpecialTermId(),
                t.getSpecialTermName(),
                t.getSpecialTermContent(),
                t.getSpecialTermSummary(),
                t.getIsRequired(),
                t.getStatus() != null ? t.getStatus().name() : null
        );
    }
}
