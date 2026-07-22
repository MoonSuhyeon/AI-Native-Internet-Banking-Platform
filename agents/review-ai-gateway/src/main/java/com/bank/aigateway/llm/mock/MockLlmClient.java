package com.bank.aigateway.llm.mock;

import com.bank.aigateway.llm.LlmClient;
import com.bank.aigateway.llm.LlmRequest;
import com.bank.aigateway.llm.LlmResponse;
import com.bank.aigateway.llm.ToolAwareLlmClient;
import com.bank.aigateway.llm.agentic.ClaudeAgenticResponse;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient, ToolAwareLlmClient {

    private final ObjectMapper objectMapper;

    @Override
    public LlmResponse complete(LlmRequest request) {
        log.info("[MockLlmClient] LLM 호출 (mock) — prompt 길이={}", request.userPrompt().length());
        String content = """
                {
                  "conclusion": "BIAS_SUSPECTED",
                  "reasoningSummary": "[Mock] 편향 의심 신호가 탐지되었습니다. 실제 LLM 연동 전 Mock 응답입니다.",
                  "confidenceScore": 0.5
                }
                """;
        return new LlmResponse(content, request.userPrompt().length() / 4, 80);
    }

    @Override
    public ClaudeAgenticResponse completeWithTools(String systemPrompt, ArrayNode messages, List<ToolDefinition> tools) {
        log.info("[MockLlmClient] tool-use 호출 (mock) — tools={}", tools.stream().map(ToolDefinition::name).toList());
        String text = """
                {
                  "conclusion": "BIAS_SUSPECTED",
                  "reasoningSummary": "[Mock] tool-use agentic 응답입니다.",
                  "confidenceScore": 0.5
                }
                """;
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject().put("type", "text").put("text", text);
        return new ClaudeAgenticResponse("end_turn", content, text, List.of(), 100, 80);
    }
}
