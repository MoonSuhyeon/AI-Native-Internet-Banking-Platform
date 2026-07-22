package com.bank.ai.review.client;

import com.bank.ai.bias.dto.BiasReportCallbackRequest;
import com.bank.ai.review.dto.ReviewReportUpdateRequest;
import com.bank.common.web.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * loan-service 로의 콜백 클라이언트. LLM 리포트 생성 결과를 전송한다.
 */
@Slf4j
@Component
public class LoanServiceClient {

    private final RestClient restClient;
    private final String internalToken;

    public LoanServiceClient(
            @Value("${ai.loan-service.base-url:http://localhost:8083}") String baseUrl,
            @Value("${ai.loan-service.internal-token:local-internal-token}") String internalToken) {
        this.internalToken = internalToken;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public void updateReport(Long revId, ReviewReportUpdateRequest req) {
        log.info("Sending report update callback to loan-service: revId={}", revId);
        restClient.patch()
                .uri("/api/loan-applications/reviews/{revId}/report", revId)
                .body(req)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 편향 분석 결과를 loan-service 에 콜백.
     * POST /api/internal/loan-reviews/{revId}/bias-report
     */
    public void reportBias(Long revId, BiasReportCallbackRequest req) {
        log.info("Sending bias-report callback to loan-service: revId={} severity={}",
                revId, req.severityCd());
        restClient.post()
                .uri("/api/internal/loan-reviews/{revId}/bias-report", revId)
                .header("X-Internal-Token", internalToken)
                .body(req)
                .retrieve()
                .toBodilessEntity();
    }
}
