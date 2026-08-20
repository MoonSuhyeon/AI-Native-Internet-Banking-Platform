package com.bank.customer.branch.dto;

import com.bank.customer.branch.domain.Branch;

public record BranchResponse(
        Long branchId,
        String branchCode,
        String branchName,
        String branchType,
        String region,
        String address,
        String phone,
        String openTime,
        String closeTime
) {
    public static BranchResponse of(Branch b) {
        return new BranchResponse(
                b.getBranchId(), b.getBranchCode(), b.getBranchName(), b.getBranchType(),
                b.getRegion(), b.getAddress(), b.getPhone(), b.getOpenTime(), b.getCloseTime());
    }
}
