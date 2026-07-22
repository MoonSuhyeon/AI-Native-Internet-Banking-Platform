package com.bank.aigateway.tool.executor;

import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.tool.AdvisoryHttpClient;
import com.bank.aigateway.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewerHistoryToolExecutor implements ToolExecutor {

    public static final String TOOL_NAME = "get_reviewer_history";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "reviewer_id": {
                  "type": "integer",
                  "description": "이력을 조회할 심사관 ID"
                },
                "days": {
                  "type": "integer",
                  "description": "조회 기간(일). 기본값 90",
                  "default": 90
                }
              },
              "required": ["reviewer_id"]
            }
            """;

    private final AdvisoryHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String toolName() { return TOOL_NAME; }

    @Override
    @SneakyThrows
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME,
                "심사관의 최근 N일간 심사 결정 이력(승인·거절 건수, 코호트 분포)을 조회합니다. 패턴 분석 및 편향 근거 확보에 활용하십시오.",
                objectMapper.readTree(SCHEMA));
    }

    @Override
    public String execute(JsonNode input) {
        long reviewerId = input.path("reviewer_id").asLong(0);
        if (reviewerId == 0) return "";
        int days = input.path("days").asInt(90);
        return httpClient.get("/api/internal/advisory/reviewer-history?reviewerId=" + reviewerId + "&days=" + days)
                .orElse("");
    }
}
