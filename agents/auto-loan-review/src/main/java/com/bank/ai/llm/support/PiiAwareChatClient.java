package com.bank.ai.llm.support;

import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.privacy.PiiLeakageException;
import com.bank.ai.privacy.PiiMaskingFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PII-safe LLM 호출 래퍼 — plan/llm-pipeline.md §8.
 *
 * <p>두 가지 보호막:
 * <ol>
 *   <li><b>입력 마스킹</b>: {@code userContent} 의 PII 를 {@link PiiMaskingFilter#mask} 로
 *       토큰화한 뒤 LLM 에 전달. 원문 PII 는 provider 서버에 도달하지 않는다.
 *       {@code system} 은 신뢰된 내부 텍스트이므로 마스킹하지 않는다.</li>
 *   <li><b>출력 사후 검사</b>: LLM 응답을 JSON 직렬화 후 {@link PiiMaskingFilter#assertNoPii}
 *       통과. LLM 이 학습 데이터 등에서 PII 패턴을 재생성한 경우 {@link PiiLeakageException}
 *       을 던져 파이프라인을 중단한다.</li>
 * </ol>
 *
 * <p>사용처: {@link com.bank.ai.llm.purpose.PurposeAnalysisService} 등 자유 입력(purpose_text)
 * 을 LLM 에 전달하는 모든 서비스. 구조화 내부 데이터(PD score 등)만 사용하는 호출은
 * {@link LlmClient} 직접 사용 가능.
 *
 * <p>{@link LlmClient} 인터페이스를 구현하지 않는 이유: Spring 컨텍스트에 동일 인터페이스
 * 빈이 복수 등록되는 것을 피하기 위함. 서비스는 명시적으로 {@code PiiAwareChatClient} 또는
 * {@code LlmClient} 중 하나를 선택해 주입받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiAwareChatClient {

    private final LlmClient delegate;
    private final PiiMaskingFilter piiMaskingFilter;
    private final ObjectMapper objectMapper;

    /**
     * PII-safe LLM 호출.
     *
     * <ol>
     *   <li>{@code request.userContent()} PII 마스킹</li>
     *   <li>masked request 로 {@code delegate.call()} 호출</li>
     *   <li>응답 JSON 직렬화 후 PII 사후 검사</li>
     * </ol>
     *
     * @param request     원본 LLM 요청 (userContent 에 PII 포함 가능)
     * @param outputSchema 응답 매핑 record 클래스
     * @param <T>         응답 타입
     * @return LLM 응답 (PII 없음 보장)
     * @throws LlmCallException    LLM 호출 실패 또는 schema 위반
     * @throws PiiLeakageException 출력에 원시 PII 패턴 감지 시
     */
    public <T> T call(LlmRequest request, Class<T> outputSchema) {
        // ── 1. 입력 마스킹 ────────────────────────────────────────────────────
        var masked = piiMaskingFilter.mask(request.userContent());
        if (!masked.mapping().isEmpty()) {
            log.debug("PiiAwareChatClient: {}개 PII 토큰 마스킹 — promptId={}",
                    masked.mapping().size(), request.promptId());
        }

        var safeRequest = new LlmRequest(
                request.promptId(),
                request.promptVer(),
                request.system(),
                masked.maskedText(),
                request.maxTokens(),
                request.temperature()
        );

        // ── 2. LLM 호출 ───────────────────────────────────────────────────────
        T response = delegate.call(safeRequest, outputSchema);

        // ── 3. 출력 사후 검사 ──────────────────────────────────────────────────
        try {
            String responseJson = objectMapper.writeValueAsString(response);
            piiMaskingFilter.assertNoSensitivePii(responseJson, request.promptId());
        } catch (PiiLeakageException e) {
            log.error("PiiAwareChatClient: 출력 PII 감지 — promptId={} context={}",
                    request.promptId(), e.getContext());
            throw e;
        } catch (JsonProcessingException e) {
            // 직렬화 실패 시 PII 검사 스킵 — 구조화 응답에서 발생 가능성 낮음
            log.warn("PiiAwareChatClient: 출력 직렬화 실패 — PII 검사 생략 promptId={}",
                    request.promptId(), e);
        }

        return response;
    }
}
