package com.bank.docagent.submission.dto.verification;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ForgerySignal(
    @JsonProperty("category")   String category,    // META|VISUAL|SEMANTIC|EXTERNAL
    @JsonProperty("type")       String type,         // SSN_CHECKSUM_FAIL|IDENTITY_INVALID|...
    @JsonProperty("score")      double score,
    @JsonProperty("evidence")   String evidence
) {}
