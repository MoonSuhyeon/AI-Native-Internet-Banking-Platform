package com.bank.docagent.infra.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record ExtractResponse(
    @JsonProperty("submission_id") String submissionId,
    @JsonProperty("doc_type")      String docType,
    @JsonProperty("fields")        Map<String, Object> fields,
    @JsonProperty("model")         String model
) {}
