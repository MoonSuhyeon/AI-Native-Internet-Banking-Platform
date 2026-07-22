package com.bank.aigateway.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Langfuse 인제스천 API로 트레이스/제너레이션을 전송하는 서비스.
 * langfuse.enabled=true 일 때만 활성화된다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "langfuse.enabled", havingValue = "true")
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseService {

    private final RestClient restClient;

    public LangfuseService(LangfuseProperties props) {
        String credentials = props.publicKey() + ":" + props.secretKey();
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
        this.restClient = RestClient.builder()
                .baseUrl(props.host())
                .defaultHeader("Authorization", authHeader)
                .build();
    }

    public String newTraceId() {
        return UUID.randomUUID().toString();
    }

    public String newSpanId() {
        return UUID.randomUUID().toString();
    }

    /** 트레이스 생성 */
    public void trace(String traceId, String name, Map<String, Object> metadata, List<String> tags) {
        var body = new java.util.HashMap<String, Object>();
        body.put("id", traceId);
        body.put("name", name);
        body.put("metadata", metadata != null ? metadata : Map.of());
        if (!tags.isEmpty()) {
            body.put("tags", tags);
        }
        send(Map.of(
                "id", UUID.randomUUID().toString(),
                "type", "trace-create",
                "timestamp", Instant.now().toString(),
                "body", body
        ));
    }

    /** LLM 제너레이션 기록 */
    public void generation(String traceId, String name, String model,
                           Object input, Object output,
                           int inputTokens, int outputTokens,
                           Instant startTime, Instant endTime) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("id", newSpanId());
        body.put("traceId", traceId);
        body.put("name", name);
        body.put("model", model);
        body.put("input", input);
        body.put("output", output != null ? output : "");
        body.put("startTime", startTime.toString());
        body.put("endTime", endTime.toString());
        body.put("usage", Map.of("input", inputTokens, "output", outputTokens));

        send(Map.of(
                "id", UUID.randomUUID().toString(),
                "type", "generation-create",
                "timestamp", Instant.now().toString(),
                "body", body
        ));
    }

    private void send(Map<String, Object> event) {
        try {
            restClient.post()
                    .uri("/api/public/ingestion")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("batch", List.of(event)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Langfuse 전송 실패: {}", e.getMessage());
        }
    }
}
