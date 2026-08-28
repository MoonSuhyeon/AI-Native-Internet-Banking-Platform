package com.bank.deposit.dto.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상담이 상품 안내에 쓰는 통합 상품 항목.
 *
 * <p><b>왜 한 번에 묶어 주는가.</b> 상담은 상품 목록 · 대상 · 금리 · 예금 상세를 늘
 * 함께 쓴다. 개별 엔드포인트로 나눠 부르면 상품 수만큼 호출이 늘어난다(N+1).
 * 상품 카탈로그는 크지 않으므로 한 번에 주고, 걸러 내는 것은 부르는 쪽이 한다.
 *
 * <p>고객 데이터가 아니라 <b>공개 카탈로그</b>다. 그래서 행위자·사유·감사를 요구하지
 * 않는다 — 요구하면 통제가 아니라 형식만 늘어난다.
 */
public record InternalProductCatalogEntry(
        Long productId,
        String productName,
        String productType,
        String description,
        BigDecimal baseInterestRate,
        String preferentialRateCondition,
        Long minJoinAmount,
        Long maxJoinAmount,
        Integer minPeriodMonth,
        Integer maxPeriodMonth,
        Boolean isEarlyTerminationAllowed,
        Boolean isTaxBenefitAvailable,
        Boolean isAutoRenewalAvailable,
        Boolean isPassbookIssued,
        Boolean isCompoundInterest,
        /** DEMAND(입출금자유) · TERM(거치식) 등. 상담이 상품군을 가를 때 쓴다. */
        String depositType,
        List<TargetGroup> targetGroups,
        List<InterestRate> interestRates
) {
    public record TargetGroup(
            Long targetGroupId,
            String targetGroupName,
            String description,
            Integer minAge,
            Integer maxAge
    ) {}

    public record InterestRate(
            Long rateId,
            String rateType,
            Integer minimumContractPeriod,
            Integer maximumContractPeriod,
            BigDecimal rate,
            String conditionDescription
    ) {}
}
