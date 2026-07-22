package com.bank.docagent.submission.dto.verification;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MissingDocument(
    @JsonProperty("code")   String code,
    @JsonProperty("name")   String name,
    @JsonProperty("reason") String reason   // MISSING | EXPIRED | FORMAT_ERROR
) {}
