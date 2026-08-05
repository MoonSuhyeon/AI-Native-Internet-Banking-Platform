package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TermsTargetMapRequest(
        @NotNull Long termsTemplateId,
        @NotNull Long targetId,
        @NotBlank String bizDivCd,
        @Pattern(regexp = "Y|N") String requiredYn,
        Long createdBy
) {}
