package com.bank.docagent.infra.ocr;

import com.bank.docagent.infra.ocr.dto.OcrRequest;
import com.bank.docagent.infra.ocr.dto.OcrResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class InferenceServerOcrClient implements OcrClient {

    private final RestClient restClient;

    @Value("${doc-agent.inference.base-url}")
    private String baseUrl;

    @Override
    @CircuitBreaker(name = "ocr", fallbackMethod = "fallback")
    public OcrResponse extract(byte[] imageBytes, String submissionId) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        return restClient.post()
            .uri(baseUrl + "/ocr/extract")
            .body(new OcrRequest(b64, submissionId))
            .retrieve()
            .body(OcrResponse.class);
    }

    OcrResponse fallback(byte[] imageBytes, String submissionId, Throwable t) {
        log.warn("OCR sidecar 장애, fallback 반환. submissionId={} cause={}", submissionId, t.getMessage());
        return new OcrResponse(submissionId, java.util.List.of(), "fallback");
    }
}
