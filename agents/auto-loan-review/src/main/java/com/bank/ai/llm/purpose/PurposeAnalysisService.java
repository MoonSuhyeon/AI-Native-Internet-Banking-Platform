package com.bank.ai.llm.purpose;

import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.config.LlmProperties;
import com.bank.ai.llm.prompt.PromptInjectionDefense;
import com.bank.ai.llm.prompt.PromptRegistry;
import com.bank.ai.llm.support.LlmCostExceededException;
import com.bank.ai.llm.support.LlmCostMeter;
import com.bank.ai.privacy.PiiMaskingFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 신청 사유 plausibility · specificity · red flag 분석 — plan/llm-pipeline.md §4 (P1).
 *
 * <p>파이프라인:
 * <ol>
 *   <li>kill switch ({@code ai.llm.enabled=false}) → 결정론 fallback ({@link #fallback}) 즉시 반환</li>
 *   <li>{@link PiiMaskingFilter} 로 purpose_text 사전 마스킹 (자유 입력에 PII 출현 대비)</li>
 *   <li>{@link PromptInjectionDefense} delimiter wrap + 의심 패턴 검출 → INSTRUCTION_INJECTION_SUSPECT
 *       red flag 후속 합류</li>
 *   <li>{@link LlmClient}.call(LlmRequest, PurposeAnalysis.class) 호출 (provider 추상)</li>
 *   <li>output 의 reasoning 에 PII 토큰 출현 시 fallback</li>
 *   <li>injection 감지된 경우 red flag 보강해 반환</li>
 * </ol>
 *
 * <p>LLM 실패는 모두 결정론적 fallback 으로 우회 — 호출 측 (RuleEngine·LOAN_REVIEW)이 항상
 * 유효한 PurposeAnalysis 를 받도록.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurposeAnalysisService {

    static final String PROMPT_ID = "purpose_analysis";
    static final int PROMPT_VERSION = 1;

    private final LlmClient llmClient;
    private final PiiMaskingFilter piiMaskingFilter;
    private final PromptInjectionDefense injectionDefense;
    private final LlmProperties props;
    private final PromptRegistry promptRegistry;
    private final LlmCostMeter costMeter;

    public PurposeAnalysis analyze(PurposeAnalysisInput input) {
        if (!props.enabled()) {
            log.debug("ai.llm.enabled=false — fallback");
            return fallback(input, List.of(), "LLM 비활성화 — 결정론 fallback");
        }

        var purposeText = input.purposeText() != null ? input.purposeText() : "";
        var masked = piiMaskingFilter.mask(purposeText);
        var defense = injectionDefense.defend(masked.maskedText());

        // user content 본문: persona·상품 메타 + 사유 (delimiter 안에 사유만)
        String userContent = """
                페르소나: %s
                상품: %s · 금액: %s만원 · 기간: %s개월

                %s
                """.formatted(
                input.personaSummary(),
                input.productCode(),
                input.requestedAmountKw(),
                input.requestedPeriodMo(),
                defense.wrappedContent()
        );

        var prompt = promptRegistry.get(PROMPT_ID, PROMPT_VERSION);
        var request = new LlmRequest(
                PROMPT_ID, PROMPT_VERSION,
                prompt.system(), userContent,
                prompt.maxTokens(), prompt.temperature()
        );

        // cap 체크 — 초과 시 fallback (plan §9)
        try {
            costMeter.checkCapOrThrow(request);
        } catch (LlmCostExceededException e) {
            log.warn("purpose_analysis: 일일 cap 초과 — fallback: {}", e.getMessage());
            costMeter.record(request, props.model(), 0, 0, 0, LlmCostMeter.STATUS_FALLBACK);
            return fallback(input, defense.suspectedPatterns(), "토큰 cap 초과");
        }

        PurposeAnalysis result;
        long start = System.currentTimeMillis();
        try {
            result = llmClient.call(request, PurposeAnalysis.class);
            costMeter.record(request, props.model(), 0, 0,
                    System.currentTimeMillis() - start, LlmCostMeter.STATUS_SUCCESS);
        } catch (LlmCallException e) {
            costMeter.record(request, props.model(), 0, 0,
                    System.currentTimeMillis() - start, LlmCostMeter.STATUS_ERROR);
            log.warn("LLM purpose_analysis 실패 — fallback: {}", e.getMessage());
            return fallback(input, defense.suspectedPatterns(), "LLM 호출 실패: " + e.getMessage());
        }

        // 출력 사후 검사 — reasoning 에 마스킹 토큰 회수가 안 됐는지 (LLM 이 우연히 PII 패턴 생성)
        if (containsPiiToken(result.reasoning(), masked.mapping())) {
            log.warn("LLM 응답에 마스킹 토큰 출현 — fallback");
            return fallback(input, defense.suspectedPatterns(), "출력 사후 검사 실패");
        }

        // injection 감지된 경우 red flag 보강
        if (defense.injectionSuspected()) {
            List<PurposeAnalysis.RedFlag> merged = new ArrayList<>(result.redFlags());
            if (!merged.contains(PurposeAnalysis.RedFlag.INSTRUCTION_INJECTION_SUSPECT)) {
                merged.add(PurposeAnalysis.RedFlag.INSTRUCTION_INJECTION_SUSPECT);
            }
            return new PurposeAnalysis(
                    result.plausibility(),
                    result.specificity(),
                    merged,
                    result.reasoning()
            );
        }
        return result;
    }

    /** 결정론적 fallback — LLM 미가용·실패 시. 신중하게 중간 점수 부여. */
    private PurposeAnalysis fallback(
            PurposeAnalysisInput input,
            List<String> suspectedPatterns,
            String reasoning
    ) {
        List<PurposeAnalysis.RedFlag> flags = new ArrayList<>();
        if (!suspectedPatterns.isEmpty()) {
            flags.add(PurposeAnalysis.RedFlag.INSTRUCTION_INJECTION_SUSPECT);
        }
        var txt = input.purposeText() != null ? input.purposeText() : "";
        if (txt.length() < 20) {
            flags.add(PurposeAnalysis.RedFlag.VAGUE_PURPOSE);
        }
        return new PurposeAnalysis(0.50, 0.50, flags, "[fallback] " + reasoning);
    }

    private boolean containsPiiToken(String response, java.util.Map<String, String> tokens) {
        if (response == null || tokens.isEmpty()) return false;
        for (String token : tokens.keySet()) {
            if (response.contains(token)) return true;
        }
        return false;
    }
}
