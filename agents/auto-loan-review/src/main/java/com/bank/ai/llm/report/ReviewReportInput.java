package com.bank.ai.llm.report;

import com.bank.ai.llm.purpose.PurposeAnalysis;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;

import java.util.List;

/**
 * ReviewReportService 입력 — RuleEngine·PD/decision 추론·PurposeAnalysis·RAG 컨텍스트 종합.
 *
 * <p>personaSummary 는 segment + occupation + income_quintile + age_band 등 derived 만.
 * Raw PII 는 호출 측에서 절대 포함 X (PII 마스킹 적용 후 호출).
 *
 * @param track               RuleEngine 분기 결과 — 응답 schema 검증에도 사용
 * @param pdScore             PD 모델 출력 (calibrated). 폴백 시 P(REJECT)
 * @param decisionScore       HMDA decision 모델의 P(APPROVE). null 가능 (PD-only 폴백 시)
 * @param pdThreshold         적용된 정책 매트릭스 임계
 * @param safetyMarginThreshold Track 1 진입 임계
 * @param hardFails           Track 2 일 때 위반 사유
 * @param personaSummary      "regular / 전문가 / Q4 / 35-44" 류 derived only
 * @param productCode         상품
 * @param purposeAnalysis     §4 PurposeAnalysis 결과 (null 가능 — LLM 미가용 fallback 시)
 * @param ragContext          RAG 정책 코퍼스 청크 (D2-4). 빈 리스트면 주입 없음.
 */
public record ReviewReportInput(
        Track track,
        double pdScore,
        Double decisionScore,
        double pdThreshold,
        double safetyMarginThreshold,
        List<HardFailReason> hardFails,
        String personaSummary,
        String productCode,
        PurposeAnalysis purposeAnalysis,
        List<Chunk> ragContext
) {
    public ReviewReportInput {
        ragContext = ragContext != null ? List.copyOf(ragContext) : List.of();
    }
}
