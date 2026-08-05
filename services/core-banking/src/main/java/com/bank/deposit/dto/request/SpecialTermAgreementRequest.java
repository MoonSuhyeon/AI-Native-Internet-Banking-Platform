package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotNull;

public record SpecialTermAgreementRequest(
        @NotNull Long specialTermId,
        @NotNull Boolean isAgreed,
        String agreedAt,
        String agreementIpAddress,
        String agreementDeviceInfo,
        Boolean isElectronicSigned
) {}
