package com.bank.docagent.infra.llm;

import com.bank.docagent.infra.llm.dto.ExtractRequest;
import com.bank.docagent.infra.llm.dto.ExtractResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmExtractClient {

    private final RestClient restClient;

    @Value("${doc-agent.inference.base-url}")
    private String baseUrl;

    @CircuitBreaker(name = "llm", fallbackMethod = "fallback")
    public ExtractResponse extract(String submissionId, String docType, String maskedText) {
        return restClient.post()
            .uri(baseUrl + "/extract/structured")
            .body(new ExtractRequest(submissionId, docType, maskedText))
            .retrieve()
            .body(ExtractResponse.class);
    }

    ExtractResponse fallback(String submissionId, String docType, String maskedText, Throwable t) {
        log.warn("LLM sidecar 장애, fallback. submissionId={} cause={}", submissionId, t.getMessage());
        return new ExtractResponse(submissionId, docType, Map.of(), "fallback");
    }
}
