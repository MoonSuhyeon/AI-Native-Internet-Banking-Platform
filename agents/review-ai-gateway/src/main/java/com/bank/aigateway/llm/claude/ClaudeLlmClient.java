package com.bank.aigateway.llm.claude;

import com.bank.aigateway.llm.LlmClient;
import com.bank.aigateway.llm.LlmException;
import com.bank.aigateway.llm.LlmRequest;
import com.bank.aigateway.llm.LlmResponse;
import com.bank.aigateway.llm.ToolAwareLlmClient;
import com.bank.aigateway.llm.agentic.ClaudeAgenticResponse;
import com.bank.aigateway.llm.agentic.ToolCall;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.observability.LangfuseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "claude")
@EnableConfigurationProperties(ClaudeProperties.class)
@RequiredArgsConstructor
public class ClaudeLlmClient implements LlmClient, ToolAwareLlmClient {

    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ClaudeProperties props;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private LangfuseService langfuse;

    @Override
    public LlmResponse complete(LlmRequest request) {
        String body = buildBody(request);
        HttpClient client = buildHttpClient();

        for (int attempt = 1; attempt <= props.getMaxAttempts(); attempt++) {
            try {
                return doRequest(client, body);
            } catch (LlmException e) {
                if (attempt == props.getMaxAttempts()) throw e;
                log.warn("Claude 호출 실패 — attempt={}/{}: {}", attempt, props.getMaxAttempts(), e.getMessage());
                sleep(props.getRetryBackoffMs() * attempt);
            }
        }
        throw new LlmException("Claude 호출 최대 재시도 초과");
    }

    private LlmResponse doRequest(HttpClient client, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + MESSAGES_PATH))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", props.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new LlmException("Claude API 오류 — status=" + resp.statusCode() + " body=" + resp.body());
            }
            return parseResponse(resp.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Claude API 통신 오류", e);
        }
    }

    private String buildBody(LlmRequest request) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", props.getModel());
            root.put("max_tokens", request.maxTokens());

            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                root.put("system", request.systemPrompt());
            }

            ArrayNode messages = root.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");
            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", request.userPrompt());

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new LlmException("Claude 요청 직렬화 실패", e);
        }
    }

    private LlmResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String text = root.path("content").get(0).path("text").asText();
            int inputTokens = root.path("usage").path("input_tokens").asInt();
            int outputTokens = root.path("usage").path("output_tokens").asInt();
            return new LlmResponse(text, inputTokens, outputTokens);
        } catch (Exception e) {
            throw new LlmException("Claude 응답 파싱 실패", e);
        }
    }

    private HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    @Override
    public ClaudeAgenticResponse completeWithTools(String systemPrompt, ArrayNode messages, List<ToolDefinition> tools) {
        String body = buildBodyWithTools(systemPrompt, messages, tools);
        HttpClient client = buildHttpClient();

        String traceId = langfuse != null ? langfuse.newTraceId() : null;
        if (langfuse != null) {
            langfuse.trace(traceId, "audit-analysis",
                    java.util.Map.<String, Object>of("model", props.getModel()),
                    java.util.List.of("review-ai-gateway"));
        }

        for (int attempt = 1; attempt <= props.getMaxAttempts(); attempt++) {
            try {
                Instant start = Instant.now();
                ClaudeAgenticResponse response = doRequestWithTools(client, body);
                if (langfuse != null) {
                    langfuse.generation(traceId, "completeWithTools", props.getModel(),
                            messages.toString(), response.textContent(),
                            response.inputTokens(), response.outputTokens(),
                            start, Instant.now());
                }
                return response;
            } catch (LlmException e) {
                if (attempt == props.getMaxAttempts()) throw e;
                log.warn("Claude tool-use 호출 실패 — attempt={}/{}: {}", attempt, props.getMaxAttempts(), e.getMessage());
                sleep(props.getRetryBackoffMs() * attempt);
            }
        }
        throw new LlmException("Claude tool-use 호출 최대 재시도 초과");
    }

    private String buildBodyWithTools(String systemPrompt, ArrayNode messages, List<ToolDefinition> tools) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", props.getModel());
            root.put("max_tokens", props.getMaxTokens());

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                root.put("system", systemPrompt);
            }

            root.set("messages", messages);

            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsNode = root.putArray("tools");
                for (ToolDefinition tool : tools) {
                    ObjectNode toolNode = toolsNode.addObject();
                    toolNode.put("name", tool.name());
                    toolNode.put("description", tool.description());
                    toolNode.set("input_schema", tool.inputSchema());
                }
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new LlmException("Claude tool-use 요청 직렬화 실패", e);
        }
    }

    private ClaudeAgenticResponse doRequestWithTools(HttpClient client, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + MESSAGES_PATH))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", props.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new LlmException("Claude API 오류 — status=" + resp.statusCode() + " body=" + resp.body());
            }
            return parseAgenticResponse(resp.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Claude API 통신 오류", e);
        }
    }

    private ClaudeAgenticResponse parseAgenticResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String stopReason = root.path("stop_reason").asText("end_turn");
            int inputTokens  = root.path("usage").path("input_tokens").asInt();
            int outputTokens = root.path("usage").path("output_tokens").asInt();

            ArrayNode rawContent = (ArrayNode) root.path("content");
            String textContent = null;
            List<ToolCall> toolCalls = new ArrayList<>();

            for (JsonNode block : rawContent) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    textContent = block.path("text").asText();
                } else if ("tool_use".equals(type)) {
                    toolCalls.add(new ToolCall(
                            block.path("id").asText(),
                            block.path("name").asText(),
                            block.path("input")
                    ));
                }
            }

            return new ClaudeAgenticResponse(stopReason, rawContent, textContent, toolCalls, inputTokens, outputTokens);
        } catch (Exception e) {
            throw new LlmException("Claude agentic 응답 파싱 실패", e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
