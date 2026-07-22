package com.bank.docagent.infra.ocr.dto;

import com.bank.docagent.submission.dto.OcrRegion;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OcrResponse(
    @JsonProperty("submission_id") String submissionId,
    @JsonProperty("regions")       List<OcrRegion> regions,
    @JsonProperty("engine")        String engine
) {}
