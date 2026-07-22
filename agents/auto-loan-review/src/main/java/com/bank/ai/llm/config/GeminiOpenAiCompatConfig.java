package com.bank.ai.llm.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini OpenAI-compat provider 설정 — {@code ai.llm.provider=gemini-openai-compat} 일 때만 활성.
 *
 * <p>Google AI Studio 의 OpenAI 호환 endpoint 를 Spring AI {@link OpenAiChatModel} 로 래핑.
 * base-url · api-key 는 {@link LlmProperties} 에서 읽으며, 환경변수 {@code GEMINI_API_KEY} 로 주입.
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.llm", name = "provider", havingValue = "gemini-openai-compat")
public class GeminiOpenAiCompatConfig {

    @Bean
    OpenAiChatModel geminiChatModel(LlmProperties props) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.model())
                        .temperature(props.temperature())
                        .maxTokens(props.maxTokens())
                        .build())
                .build();
    }
}
