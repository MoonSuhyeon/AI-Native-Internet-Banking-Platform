package com.bank.loan.collateral.dto;

import com.bank.loan.collateral.domain.Collateral;

public record CollateralResponse(
        Long colId,
        Long applId,
        String colTypeCd,
        String colStatusCd,
        String colNo,
        String colName,
        String colAddress,
        String colRegistryNo,
        Long declaredValue,
        String currencyCd,
        String ownershipTypeCd,
        Boolean seniorLienYn,
        Long seniorLienAmount
) {
    public static CollateralResponse of(Collateral c) {
        return new CollateralResponse(
                c.getColId(), c.getApplId(), c.getColTypeCd(), c.getColStatusCd(),
                c.getColNo(), c.getColName(), c.getColAddress(), c.getColRegistryNo(),
                c.getDeclaredValue(), c.getCurrencyCd(),
                c.getOwnershipTypeCd(), c.getSeniorLienYn(), c.getSeniorLienAmount()
        );
    }
}
