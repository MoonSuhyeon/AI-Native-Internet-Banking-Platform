package com.bank.docagent.forgery.infra.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ForgeryAnalyzeResponse(
    @JsonProperty("submission_id")   String submissionId,
    @JsonProperty("aggregate_score") double aggregateScore,
    @JsonProperty("signals")         List<SignalItem> signals
) {
    public record SignalItem(
        @JsonProperty("category") String category,
        @JsonProperty("type")     String type,
        @JsonProperty("score")    double score,
        @JsonProperty("evidence") String evidence
    ) {}
}
