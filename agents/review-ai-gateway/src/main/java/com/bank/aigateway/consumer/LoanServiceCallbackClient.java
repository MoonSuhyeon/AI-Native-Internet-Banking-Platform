package com.bank.aigateway.consumer;

import com.bank.aigateway.audit.dto.AuditAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * loan-service 편향 검증 결과 콜백 클라이언트.
 * POST /api/loans/reviews/{revId}/bias-result
 */
@Slf4j
@Component
public class LoanServiceCallbackClient {

    private static final String CALLBACK_PATH = "/api/loans/reviews/{revId}/bias-result";

    private static final String STATUS_PASS    = "PASS";
    private static final String STATUS_FLAGGED = "FLAGGED";
    private static final String STATUS_FAILED  = "FAILED";

    private static final String CONCLUSION_BIAS_SUSPECTED   = "BIAS_SUSPECTED";
    private static final String CONCLUSION_NO_BIAS_DETECTED = "NO_BIAS_DETECTED";

    private final RestClient restClient;

    public LoanServiceCallbackClient(@Value("${loan-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public void reportResult(Long revId, AuditAnalysisResponse result) {
        String status = toStatus(result.conclusion());
        boolean biasDetected = STATUS_FLAGGED.equals(status);
        var body = new BiasResultCallback(
                status,
                result.analysisType(),
                result.reasoningSummary(),
                biasDetected
        );
        doPost(revId, body);
    }

    public void reportFailure(Long revId, String reason) {
        var body = new BiasResultCallback(STATUS_FAILED, "BIAS_DETECTION", reason, false);
        doPost(revId, body);
    }

    private void doPost(Long revId, BiasResultCallback body) {
        try {
            restClient.post()
                    .uri(CALLBACK_PATH, revId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("bias-check 콜백 완료 revId={} status={}", revId, body.status());
        } catch (Exception e) {
            log.error("bias-check 콜백 실패 revId={}: {}", revId, e.getMessage());
        }
    }

    private static String toStatus(String conclusion) {
        return switch (conclusion) {
            case CONCLUSION_BIAS_SUSPECTED   -> STATUS_FLAGGED;
            case CONCLUSION_NO_BIAS_DETECTED -> STATUS_PASS;
            default                          -> STATUS_FAILED;
        };
    }

    record BiasResultCallback(
            String status,
            String analysisType,
            String findingSummary,
            boolean biasDetected
    ) {}
}
