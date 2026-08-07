package com.bank.loan.collateral.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCollateralRequest(

        @NotBlank @Size(max = 50) String colTypeCd,

        @Size(max = 200) String colName,
        @Size(max = 500) String colAddress,
        @Size(max = 100) String colRegistryNo,

        @Min(0) Long declaredValue,

        @Size(max = 10) String currencyCd,
        @Size(max = 50) String ownershipTypeCd,

        Boolean seniorLienYn,
        @Min(0) Long seniorLienAmount
) {
}
