package com.bank.aigateway.llm;

import com.bank.aigateway.llm.agentic.ClaudeAgenticResponse;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

public interface ToolAwareLlmClient {
    ClaudeAgenticResponse completeWithTools(String systemPrompt, ArrayNode messages, List<ToolDefinition> tools);
}
