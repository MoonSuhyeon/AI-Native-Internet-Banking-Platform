package com.bank.docagent.submission.dto.extracted;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 필드 단위 추출값 + 메타데이터.
 * confidence는 OCR 영역의 평균 신뢰도를 상속, null이면 LLM 추론값.
 */
public record ExtractedField<T>(
    @JsonProperty("value")      T value,
    @JsonProperty("confidence") Double confidence,
    @JsonProperty("source_doc") String sourceDoc
) {
    public static <T> ExtractedField<T> of(T value, String sourceDoc) {
        return new ExtractedField<>(value, null, sourceDoc);
    }
}
