package com.bank.aigateway.tool;

import com.bank.aigateway.tool.config.AiServiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * ai-service REST 클라이언트 (시나리오 δ).
 * POST /rag/search 등 JSON body 전송을 지원한다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT  = Duration.ofSeconds(15);

    private final AiServiceProperties props;

    public AiServiceHttpClient(AiServiceProperties props) {
        this.props = props;
    }

    public Optional<String> post(String path, String jsonBody) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return Optional.of(resp.body());
            }
            log.debug("ai-service 비정상 응답 — path={} status={}", path, resp.statusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.debug("ai-service 호출 실패 — path={}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
}
