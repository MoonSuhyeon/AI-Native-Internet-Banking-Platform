package com.bank.ai.llm.report;

import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.llm.config.LlmProperties;
import com.bank.ai.llm.prompt.PromptRegistry;
import com.bank.ai.llm.support.LlmCostExceededException;
import com.bank.ai.llm.support.LlmCostMeter;
import com.bank.ai.rule.domain.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 트랙별 심사 리포트 생성 — plan/llm-pipeline.md §5 (P3).
 *
 * <p>비동기 후처리 Step 8 의 핵심. RuleEngine·PD·PurposeAnalysis 결과를 종합해
 * 트랙별 톤 다른 한국어 리포트를 LLM 으로 생성. 모든 실패 경로는 {@link TemplateFallback}.
 *
 * <p>파이프라인:
 * <ol>
 *   <li>kill switch ({@code ai.llm.enabled=false}) → fallback</li>
 *   <li>{@link PromptRegistry#get} 로 트랙별 system prompt 로드 (YAML 버저닝)</li>
 *   <li>{@link LlmClient}.call(LlmRequest, ReviewReport.class) — provider 추상</li>
 *   <li>{@link GroundingValidator} 검증 — citation 환각 차단</li>
 *   <li>응답의 {@code track} 이 입력과 일치하는지 검증 (LLM 이 자체 분기 시도 차단)</li>
 *   <li>실패 시 → {@link TemplateFallback}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewReportService {

    private static final int PROMPT_VERSION = 1;

    private final LlmClient llmClient;
    private final GroundingValidator groundingValidator;
    private final TemplateFallback templateFallback;
    private final LlmProperties props;
    private final PromptRegistry promptRegistry;
    private final LlmCostMeter costMeter;

    public ReviewReport generate(ReviewReportInput input) {
        if (!props.enabled()) {
            return templateFallback.generate(input, "LLM 비활성화");
        }

        var promptId = trackPromptId(input.track());
        var prompt = promptRegistry.get(promptId, PROMPT_VERSION);
        var request = new LlmRequest(
                promptId,
                PROMPT_VERSION,
                prompt.system(),
                buildUserContent(input),
                prompt.maxTokens(),
                prompt.temperature()
        );

        // cap 체크 — 초과 시 fallback (plan §9)
        try {
            costMeter.checkCapOrThrow(request);
        } catch (LlmCostExceededException e) {
            log.warn("review_report: 일일 cap 초과 — fallback: {}", e.getMessage());
            costMeter.record(request, props.model(), 0, 0, 0, LlmCostMeter.STATUS_FALLBACK);
            return templateFallback.generate(input, "토큰 cap 초과");
        }

        ReviewReport raw;
        long start = System.currentTimeMillis();
        try {
            raw = llmClient.call(request, ReviewReport.class);
            costMeter.record(request, props.model(), 0, 0,
                    System.currentTimeMillis() - start, LlmCostMeter.STATUS_SUCCESS);
        } catch (LlmCallException e) {
            costMeter.record(request, props.model(), 0, 0,
                    System.currentTimeMillis() - start, LlmCostMeter.STATUS_ERROR);
            log.warn("LLM review_report 실패 — fallback: {}", e.getMessage());
            return templateFallback.generate(input, "LLM 호출 실패: " + e.getMessage());
        }

        // 응답 track 이 요청 track 과 다르면 LLM 의 자체 분기 시도 — reject
        if (raw.track() != input.track()) {
            log.warn("LLM 응답 track 불일치 요청={} 응답={}", input.track(), raw.track());
            return templateFallback.generate(input, "응답 track 불일치");
        }

        var validation = groundingValidator.validate(raw);
        if (!validation.passed()) {
            return templateFallback.generate(input,
                    "grounding 실패: " + String.join("; ", validation.issues()));
        }
        return raw;
    }

    /**
     * 트랙별 prompt id — plan/llm-pipeline.md §5.3.
     * Track.name() = "TRACK_1" → toLowerCase = "track_1" → replace("_","") = "track1".
     * 결과: "review_report_track1" / "review_report_track2" / "review_report_track3"
     */
    static String trackPromptId(Track track) {
        return "review_report_" + track.name().toLowerCase().replace("_", "");
    }

    private String buildUserContent(ReviewReportInput in) {
        var hardFailLine = in.hardFails().isEmpty()
                ? "hardFails: 없음"
                : "hardFails: " + in.hardFails().stream()
                        .map(h -> "%s(%s)".formatted(h.code(), h.message()))
                        .reduce((a, b) -> a + ", " + b).orElse("");
        var purposeLine = in.purposeAnalysis() != null
                ? "purposeAnalysis: plausibility=%.2f, specificity=%.2f, redFlags=%s"
                        .formatted(in.purposeAnalysis().plausibility(),
                                   in.purposeAnalysis().specificity(),
                                   in.purposeAnalysis().redFlags())
                : "purposeAnalysis: (미가용)";
        var decisionLine = in.decisionScore() != null
                ? "decisionScore: %.4f".formatted(in.decisionScore())
                : "decisionScore: (PD-only 폴백)";
        var ragLine = buildRagChunksSection(in.ragContext());

        return """
                <user_content>
                  track: %s
                  personaSummary: %s
                  productCode: %s
                  pdScore: %.4f (threshold %.4f, safetyTau %.4f)
                  %s
                  %s
                  %s
                  %s
                </user_content>
                """.formatted(
                in.track().name(),
                in.personaSummary(),
                in.productCode(),
                in.pdScore(), in.pdThreshold(), in.safetyMarginThreshold(),
                decisionLine,
                hardFailLine,
                purposeLine,
                ragLine
        );
    }

    private static String buildRagChunksSection(List<Chunk> chunks) {
        if (chunks.isEmpty()) return "rag_policy_context: (없음)";
        var sb = new StringBuilder("rag_policy_context:\n");
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            sb.append("  [%d] id=%s — %s%n".formatted(i + 1, c.sourceId(), c.promptText()));
        }
        return sb.toString().stripTrailing();
    }
}
