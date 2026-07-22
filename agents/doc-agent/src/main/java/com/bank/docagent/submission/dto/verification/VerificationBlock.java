package com.bank.docagent.submission.dto.verification;

import com.bank.docagent.submission.domain.DocumentSubmission.VerifyStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record VerificationBlock(
    @JsonProperty("status")              VerifyStatus status,
    @JsonProperty("overall_confidence")  double overallConfidence,
    @JsonProperty("missing_documents")   List<MissingDocument> missingDocuments,
    @JsonProperty("forgery")             ForgeryBlock forgery,
    @JsonProperty("consistency_check")   ConsistencyCheck consistencyCheck
) {
    public record ForgeryBlock(
        @JsonProperty("ai_signal_score")      double aiSignalScore,
        @JsonProperty("ai_signal_summary")    String aiSignalSummary,
        @JsonProperty("signals")              List<ForgerySignal> signals,
        @JsonProperty("human_review_status")  String humanReviewStatus
    ) {}
}
