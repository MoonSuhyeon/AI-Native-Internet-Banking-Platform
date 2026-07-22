package com.bank.ai.review.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * inference-server 응답 구조와 1:1 매핑.
 */
public record InferenceResponse(
        @JsonProperty("model_version") String modelVersion,
        List<Prediction> predictions
) {
    public record Prediction(
            String decision,
            double score,
            Map<String, Double> proba
    ) {
    }
}
