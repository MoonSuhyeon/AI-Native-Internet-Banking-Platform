package com.bank.customer.banking.dto;

import com.bank.customer.banking.domain.FavoriteTransfer;
import com.bank.customer.banking.domain.FavoriteType;

public record FavoriteTransferResponse(
        Long favoriteId,
        FavoriteType favoriteType,
        String alias,
        String bankCode,
        String bankName,
        String accountNumber,
        String accountHolderName,
        Long amount,
        int displayOrder
) {
    public static FavoriteTransferResponse of(FavoriteTransfer f) {
        return new FavoriteTransferResponse(
                f.getFavoriteId(), f.getFavoriteType(), f.getAlias(),
                f.getBankCode(), f.getBankName(), f.getAccountNumber(),
                f.getAccountHolderName(), f.getAmount(), f.getDisplayOrder());
    }
}
