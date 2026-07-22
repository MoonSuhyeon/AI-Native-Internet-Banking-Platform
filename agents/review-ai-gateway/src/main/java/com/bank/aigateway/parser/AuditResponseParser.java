package com.bank.aigateway.parser;

import com.bank.aigateway.llm.LlmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 응답 텍스트에서 JSON 블록을 추출해 구조화된 필드로 파싱.
 * LLM이 JSON 외에 부가 텍스트를 포함할 경우에도 정규식으로 JSON 블록만 추출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditResponseParser {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*?}", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public ParsedAuditResult parse(String llmContent) {
        String json = extractJson(llmContent);
        try {
            JsonNode node = objectMapper.readTree(json);
            List<Long> chunkIds = new ArrayList<>();
            JsonNode chunkNode = node.path("citedChunkIds");
            if (chunkNode.isArray()) {
                for (JsonNode id : chunkNode) {
                    if (id.isNumber()) chunkIds.add(id.asLong());
                }
            }
            return new ParsedAuditResult(
                    node.path("conclusion").asText("INSUFFICIENT_DATA"),
                    node.path("reasoningSummary").asText(""),
                    node.path("confidenceScore").asDouble(0.0),
                    chunkIds
            );
        } catch (Exception e) {
            log.warn("LLM 응답 파싱 실패 — 원본: {}", llmContent, e);
            throw new LlmException("LLM 응답 파싱 실패", e);
        }
    }

    private String extractJson(String text) {
        Matcher matcher = JSON_BLOCK.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return text;
    }

    public record ParsedAuditResult(
            String conclusion,
            String reasoningSummary,
            double confidenceScore,
            List<Long> citedChunkIds
    ) {}
}
