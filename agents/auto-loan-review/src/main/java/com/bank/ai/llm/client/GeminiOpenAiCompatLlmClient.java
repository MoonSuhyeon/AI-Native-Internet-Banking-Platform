package com.bank.ai.llm.client;

import com.bank.ai.langfuse.LangfuseService;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Google AI Studio Gemini OpenAI-compat 엔드포인트 LLM 클라이언트.
 *
 * <p>{@code ai.llm.provider=gemini-openai-compat} 일 때 활성. Spring AI {@link OpenAiChatModel} 을
 * Gemini base-url 로 구성해 기존 {@link LlmClient} 계약을 구현한다.
 *
 * <p>구조화 출력: {@link BeanOutputConverter} 가 outputSchema 로부터 JSON Schema 지시문을
 * system prompt 에 append 해 Gemini 가 스키마 준수 응답을 내도록 유도.
 *
 * <p>호출 전 {@link LlmRequestRateMeter#tryAcquire()} 로 RPD/RPM 한도 체크.
 * 한도 초과 시 {@link LlmCallException} (message = "LLM_RATE_LIMITED") — 호출 측에서
 * {@code TemplateFallback} 으로 분기.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.llm", name = "provider", havingValue = "gemini-openai-compat")
@RequiredArgsConstructor
public class GeminiOpenAiCompatLlmClient implements LlmClient {

    private final OpenAiChatModel chatModel;
    private final LlmRequestRateMeter rateMeter;

    @Autowired(required = false)
    private LangfuseService langfuse;

    @Override
    public <T> T call(LlmRequest request, Class<T> outputSchema) {
        if (!rateMeter.tryAcquire()) {
            throw new LlmCallException("LLM_RATE_LIMITED");
        }

        var converter = new BeanOutputConverter<>(outputSchema);
        String systemPrompt = (request.system() != null ? request.system() : "")
                + "\n\n" + converter.getFormat();

        var prompt = new Prompt(
                List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(request.userContent())
                ),
                OpenAiChatOptions.builder()
                        .temperature(request.temperature())
                        .maxTokens(request.maxTokens())
                        .build()
        );

        Instant llmStart = Instant.now();
        ChatResponse response = chatModel.call(prompt);
        Instant llmEnd = Instant.now();
        String content = response.getResult().getOutput().getText();
        log.debug("GeminiOpenAiCompatLlmClient: promptId={} chars={}", request.promptId(),
                content != null ? content.length() : 0);

        if (langfuse != null) {
            String traceId = langfuse.newTraceId();
            langfuse.trace(traceId, "auto-loan-review", null, List.of("auto-loan-review"));
            var usage = response.getMetadata().getUsage();
            langfuse.generation(traceId, request.promptId(), "gemini",
                    request.userContent(), content,
                    usage != null ? (int) usage.getPromptTokens() : null,
                    usage != null ? (int) usage.getCompletionTokens() : null,
                    llmStart, llmEnd);
        }

        try {
            return converter.convert(content);
        } catch (Exception e) {
            throw new LlmCallException(
                    "Gemini 응답 JSON 파싱 실패 promptId=" + request.promptId() + ": " + e.getMessage(), e);
        }
    }
}
