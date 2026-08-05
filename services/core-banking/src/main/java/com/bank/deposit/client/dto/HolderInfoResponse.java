package com.bank.deposit.client.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class HolderInfoResponse {
    private String customerId;
    private String holderName;
    private String holderType;
    private boolean deceasedFlag;
}
