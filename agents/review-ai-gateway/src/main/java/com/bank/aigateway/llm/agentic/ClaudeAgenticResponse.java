package com.bank.aigateway.llm.agentic;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

public record ClaudeAgenticResponse(
        String stopReason,
        ArrayNode rawContentNodes,
        String textContent,
        List<ToolCall> toolCalls,
        int inputTokens,
        int outputTokens
) {
    public boolean isEndTurn() { return "end_turn".equals(stopReason); }
    public boolean isToolUse() { return "tool_use".equals(stopReason); }
}
