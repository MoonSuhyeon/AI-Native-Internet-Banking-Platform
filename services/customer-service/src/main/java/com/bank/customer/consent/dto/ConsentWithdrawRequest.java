package com.bank.customer.consent.dto;

import jakarta.validation.constraints.NotBlank;

/** 철회 사유는 받아 둔다 — 왜 거뒀는지가 뒤에 쟁점이 된다. */
public record ConsentWithdrawRequest(@NotBlank String reason) {}
