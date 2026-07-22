package com.bank.aigateway.tool;

import com.bank.aigateway.tool.config.AdvisoryToolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
@EnableConfigurationProperties(AdvisoryToolProperties.class)
public class AdvisoryHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT  = Duration.ofSeconds(10);

    private final AdvisoryToolProperties props;

    public AdvisoryHttpClient(AdvisoryToolProperties props) {
        this.props = props;
    }

    public Optional<String> get(String path) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + path))
                    .GET()
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return Optional.of(resp.body());
            }
            log.debug("advisory-service 비정상 응답 — path={} status={}", path, resp.statusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.debug("advisory-service 호출 실패 — path={}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
}
