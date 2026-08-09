package com.bank.loan.advisory.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * review-ai-gateway HTTP 클라이언트. POST /internal/audit/analyze 호출.
 * 실패 시 RuntimeException — 호출측에서 best-effort 처리 (감사 의견 없이 룰 리포트만 유지).
 */
@Slf4j
@Component
@EnableConfigurationProperties(AiGatewayProperties.class)
@RequiredArgsConstructor
public class AiGatewayClient {

    private static final String ANALYZE_PATH = "/internal/audit/analyze";

    private final AiGatewayProperties props;
    private final ObjectMapper objectMapper;

    public GatewayAnalysisResponse analyze(GatewayAnalysisRequest request) {
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + ANALYZE_PATH))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("AI Gateway 오류 — status=" + resp.statusCode());
            }
            return objectMapper.readValue(resp.body(), GatewayAnalysisResponse.class);
        } catch (Exception e) {
            log.warn("AI Gateway 호출 실패 — type={} revId={}: {}", request.analysisType(), request.revId(), e.getMessage());
            throw new RuntimeException("AI Gateway 호출 실패", e);
        }
    }
}
