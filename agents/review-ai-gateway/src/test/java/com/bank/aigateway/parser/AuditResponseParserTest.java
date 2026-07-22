package com.bank.aigateway.parser;

import com.bank.aigateway.llm.LlmException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditResponseParserTest {

    AuditResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new AuditResponseParser(new ObjectMapper());
    }

    @Test
    void 정상_JSON_응답_파싱() {
        String content = """
                {
                  "conclusion": "BIAS_SUSPECTED",
                  "reasoningSummary": "편향 의심 신호가 탐지되었습니다.",
                  "confidenceScore": 0.87
                }
                """;

        AuditResponseParser.ParsedAuditResult result = parser.parse(content);

        assertThat(result.conclusion()).isEqualTo("BIAS_SUSPECTED");
        assertThat(result.reasoningSummary()).isEqualTo("편향 의심 신호가 탐지되었습니다.");
        assertThat(result.confidenceScore()).isEqualTo(0.87);
    }

    @Test
    void LLM이_JSON_앞뒤에_텍스트_포함해도_JSON_블록_추출() {
        String content = """
                분석 결과를 아래에 제시합니다:
                {
                  "conclusion": "NO_BIAS_DETECTED",
                  "reasoningSummary": "편향 없음.",
                  "confidenceScore": 0.92
                }
                이상입니다.
                """;

        AuditResponseParser.ParsedAuditResult result = parser.parse(content);

        assertThat(result.conclusion()).isEqualTo("NO_BIAS_DETECTED");
        assertThat(result.confidenceScore()).isEqualTo(0.92);
    }

    @Test
    void conclusion_누락_시_INSUFFICIENT_DATA_기본값() {
        String content = """
                {
                  "reasoningSummary": "데이터 부족",
                  "confidenceScore": 0.3
                }
                """;

        AuditResponseParser.ParsedAuditResult result = parser.parse(content);

        assertThat(result.conclusion()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void confidenceScore_누락_시_0점_기본값() {
        String content = """
                {
                  "conclusion": "COMPLIANT",
                  "reasoningSummary": "규정 준수"
                }
                """;

        AuditResponseParser.ParsedAuditResult result = parser.parse(content);

        assertThat(result.confidenceScore()).isEqualTo(0.0);
        assertThat(result.conclusion()).isEqualTo("COMPLIANT");
    }

    @Test
    void 완전히_파싱_불가한_응답은_LlmException_발생() {
        String content = "이것은 JSON이 아닙니다 — 완전 자유 텍스트";

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("파싱 실패");
    }

    @Test
    void 규정준수_VIOLATION_SUSPECTED_파싱() {
        String content = """
                {"conclusion":"VIOLATION_SUSPECTED","reasoningSummary":"규정 위반 의심","confidenceScore":0.78}
                """;

        AuditResponseParser.ParsedAuditResult result = parser.parse(content);

        assertThat(result.conclusion()).isEqualTo("VIOLATION_SUSPECTED");
        assertThat(result.confidenceScore()).isEqualTo(0.78);
    }
}
