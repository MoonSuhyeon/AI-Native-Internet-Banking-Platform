package com.bank.ai.llm.purpose;

/**
 * PurposeAnalysisService 입력 — PII 마스킹 호출 측에서 처리한 가공 데이터.
 *
 * <p>persona 는 segment + occupation + income_quintile + age_band 등 derived 만 — raw 이름·전화 X.
 *
 * @param personaSummary    "regular / 전문가 / Q4 / 35-44" 같은 요약 문자열
 * @param purposeText       사용자 자유 입력 (호출 측에서 마스킹·trim 권장)
 * @param productCode       상품 코드 (MORT_001 / CRED_001 등)
 * @param requestedAmountKw 신청 금액 (만원)
 * @param requestedPeriodMo 기간 (월)
 */
public record PurposeAnalysisInput(
        String personaSummary,
        String purposeText,
        String productCode,
        Long requestedAmountKw,
        Integer requestedPeriodMo
) {
}
