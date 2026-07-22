package com.bank.docagent.infra.ocr;

import com.bank.docagent.infra.ocr.dto.TableOcrRequest;
import com.bank.docagent.infra.ocr.dto.TableOcrResponse;
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
public class InferenceServerTableOcrClient implements TableOcrClient {

    private final RestClient restClient;

    @Value("${doc-agent.inference.base-url}")
    private String baseUrl;

    @Override
    @CircuitBreaker(name = "ocr", fallbackMethod = "fallback")
    public String extractTable(byte[] imageBytes, String submissionId) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        TableOcrResponse resp = restClient.post()
            .uri(baseUrl + "/ocr/extract-table")
            .body(new TableOcrRequest(b64, submissionId))
            .retrieve()
            .body(TableOcrResponse.class);
        if (resp == null || resp.tableText() == null) return "";
        log.debug("PP-Structure 테이블 파싱 완료: submissionId={} tables={}", submissionId, resp.tableCount());
        return resp.tableText();
    }

    String fallback(byte[] imageBytes, String submissionId, Throwable t) {
        log.warn("Table OCR sidecar 장애, 빈 텍스트 반환. submissionId={} cause={}", submissionId, t.getMessage());
        return "";
    }
}
