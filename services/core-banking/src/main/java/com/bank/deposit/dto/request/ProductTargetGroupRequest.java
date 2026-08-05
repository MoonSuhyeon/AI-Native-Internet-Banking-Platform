package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotNull;

public record ProductTargetGroupRequest(
        @NotNull Long targetGroupId
) {}
