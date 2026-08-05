package com.bank.deposit.dto.interservice;

import com.bank.deposit.domain.entity.Account;

public record HolderInfoResponse(
        String accountNo,
        String holderName,
        String holderType,
        String customerId,
        boolean deceasedFlag,
        long version
) {
    public static HolderInfoResponse from(Account a) {
        // holderName: customer-service 연동 전까지 accountAlias를 임시 사용.
        // TODO: customer-service FeignClient로 실제 예금주명 조회
        String holderName = a.getAccountAlias() != null ? a.getAccountAlias() : a.getCustomerId();
        return new HolderInfoResponse(
                a.getAccountNumber(),
                holderName,
                "INDIVIDUAL",
                a.getCustomerId(),
                false,  // deceasedFlag: customer-service 연동 전까지 false
                a.getVersion() != null ? a.getVersion() : 0L
        );
    }
}
