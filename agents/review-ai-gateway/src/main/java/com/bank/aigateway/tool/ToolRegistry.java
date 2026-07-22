package com.bank.aigateway.tool;

import com.bank.aigateway.llm.agentic.ToolCall;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor> executors;

    public ToolRegistry(List<ToolExecutor> executors) {
        this.executors = executors.stream()
                .collect(Collectors.toMap(ToolExecutor::toolName, e -> e));
        log.info("ToolRegistry 초기화 — 등록 tool: {}", this.executors.keySet());
    }

    public List<ToolDefinition> allDefinitions() {
        return executors.values().stream()
                .map(ToolExecutor::definition)
                .toList();
    }

    public Function<ToolCall, String> asExecutorFunction() {
        return call -> {
            ToolExecutor executor = executors.get(call.name());
            if (executor == null) {
                log.warn("등록되지 않은 tool 호출 — name={}", call.name());
                return "";
            }
            return executor.execute(call.input());
        };
    }
}
