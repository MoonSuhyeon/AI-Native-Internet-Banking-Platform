package com.bank.ai.review.dto;

import jakarta.validation.constraints.Min;

/**
 * 자동심사 신청 입력.
 *
 * 학습 모델의 피처와 동일한 키. 입력 누락 시 모델이 missing 분기로 라우팅.
 * BigDecimal 이 아닌 primitive 류를 쓰는 이유: 금액 비교가 아니라 ML 모델 피처라 정밀도 요구 없음.
 *
 * Layer 4 (PD 전용) 필드는 모두 nullable — Phase 1.10 호환. loan-service 측이 PD 모델
 * 입력 보강 후 채워 보내고, 누락 시엔 PD 모델이 NaN imputation 으로 처리한다.
 */
public record AutoReviewRequest(
        Long revId,                         // Phase 1.6: DB 적재된 revId (비동기 콜백용)
        // ---- Layer 1: persona ----
        String sex,
        @Min(0) Integer age,
        String maritalStatus,
        String militaryStatus,
        String familyType,
        String housingType,
        String educationLevel,
        String bachelorsField,
        String occupation,
        String district,
        String province,
        String applicantSegment,

        // ---- Layer 2: financial ----
        Integer incomeQuintile,
        Long annualIncomeKw,
        Long totalAssetKw,
        Long totalDebtKw,
        Long collateralDebtKw,
        Long creditDebtKw,
        Double dsr,
        Double ltv,
        Long monthlyCashflowMeanKw,
        Long monthlyCashflowStdKw,
        Integer delinquencyHistory24m,
        Integer creditScoreProxy,

        // ---- Layer 3: application ----
        String productCode,
        Long requestedAmountKw,
        Integer requestedPeriodMo,
        String purposeCd,
        Boolean purposeRedFlag,

        // ---- Layer 4: PD 모델 전용 (Phase 1.4-PD) ----
        String industryCd,
        Integer regionRiskBand,
        Integer nChildren,
        Integer employmentYears,
        Boolean bureauHasRecord,
        Integer bureauNActive,
        Integer bureauMaxStatus24m
) {
}
