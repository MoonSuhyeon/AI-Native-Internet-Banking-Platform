package com.bank.docagent.infra.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExtractRequest(
    @JsonProperty("submission_id") String submissionId,
    @JsonProperty("doc_type")      String docType,
    @JsonProperty("masked_text")   String maskedText
) {}
