package com.bank.aigateway.tool.executor;

import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.tool.AdvisoryHttpClient;
import com.bank.aigateway.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 정책 인용 검색 tool executor (Path C — advisory-service 직접 호출).
 * advisory GET /api/internal/advisory/policy-citations?query=... 를 호출한다.
 */
@Component
@RequiredArgsConstructor
public class PolicyCitationToolExecutor implements ToolExecutor {

    public static final String TOOL_NAME = "get_policy_citation";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "검색할 정책·규정 키워드 (예: 'DSR 한도 초과 예외처리')"
                }
              },
              "required": ["query"]
            }
            """;

    private final AdvisoryHttpClient advisoryHttpClient;
    private final ObjectMapper       objectMapper;

    @Override
    public String toolName() { return TOOL_NAME; }

    @Override
    @SneakyThrows
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME,
                "관련 여신 정책·규정 조항을 벡터 검색으로 조회합니다. 편향 판단 기준이나 규정 우회 여부 확인 시 활용하십시오.",
                objectMapper.readTree(SCHEMA));
    }

    @Override
    public String execute(JsonNode input) {
        String query = input.path("query").asText("");
        if (query.isBlank()) return "";
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return advisoryHttpClient.get("/api/internal/advisory/policy-citations?query=" + encoded)
                .orElse("");
    }
}
