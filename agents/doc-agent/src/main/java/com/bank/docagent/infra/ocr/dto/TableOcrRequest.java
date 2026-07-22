package com.bank.docagent.infra.ocr.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TableOcrRequest(
    @JsonProperty("image_b64")     String imageB64,
    @JsonProperty("submission_id") String submissionId
) {}
