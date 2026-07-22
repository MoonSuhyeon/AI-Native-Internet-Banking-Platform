package com.bank.docagent.forgery.infra.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ForgeryAnalyzeRequest(
    @JsonProperty("submission_id") String submissionId,
    @JsonProperty("doc_type")      String docType,
    @JsonProperty("file_b64")      String fileB64,
    @JsonProperty("content_type")  String contentType
) {}
