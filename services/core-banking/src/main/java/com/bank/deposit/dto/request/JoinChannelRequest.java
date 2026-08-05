package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.JoinChannel;
import jakarta.validation.constraints.NotNull;

public record JoinChannelRequest(
        @NotNull JoinChannel joinChannelCode
) {}
