package com.bank.docagent.infra.ocr.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TableOcrResponse(
    @JsonProperty("submission_id") String submissionId,
    @JsonProperty("table_text")    String tableText,
    @JsonProperty("table_count")   int    tableCount
) {}
