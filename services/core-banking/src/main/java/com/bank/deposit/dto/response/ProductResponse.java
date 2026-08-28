package com.bank.deposit.dto.response;

import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.domain.enums.ProductStatus;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.domain.enums.SavingType;

import java.math.BigDecimal;
import java.util.List;

public record ProductResponse(
        Long productId,
        ProductType productType,
        SavingType savingType,
        String productName,
        String description,
        Long departmentId,
        BigDecimal baseInterestRate,
        BigDecimal bestRate,
        /** 우대금리 조건 원문. 상담이 비교 안내에 쓴다. */
        String preferentialRateCondition,
        Long minJoinAmount,
        Long maxJoinAmount,
        Integer minPeriodMonth,
        Integer maxPeriodMonth,
        Boolean isEarlyTerminationAllowed,
        Boolean isTaxBenefitAvailable,
        Boolean isAutoRenewalAvailable,
        Boolean isPassbookIssued,
        String releasedAt,
        String endedAt,
        ProductStatus productStatus,
        List<TargetGroupInfo> targetGroups
) {
    public record TargetGroupInfo(
            Long targetGroupId,
            String targetGroupName,
            Integer minAge,
            Integer maxAge
    ) {
        public static TargetGroupInfo from(TargetGroup tg) {
            return new TargetGroupInfo(tg.getTargetGroupId(), tg.getTargetGroupName(), tg.getMinAge(), tg.getMaxAge());
        }
    }

    public static ProductResponse from(Product product) {
        return from(product, null, null, List.of());
    }

    public static ProductResponse from(Product product, BigDecimal bestRate) {
        return from(product, bestRate, null, List.of());
    }

    public static ProductResponse from(Product product, BigDecimal bestRate, List<TargetGroupInfo> targetGroups) {
        return from(product, bestRate, null, targetGroups);
    }

    public static ProductResponse from(Product product, BigDecimal bestRate, SavingType savingType, List<TargetGroupInfo> targetGroups) {
        return new ProductResponse(
                product.getProductId(),
                product.getProductType(),
                savingType,
                product.getProductName(),
                product.getDescription(),
                product.getDepartmentId(),
                product.getBaseInterestRate(),
                bestRate,
                product.getPreferentialRateCondition(),
                product.getMinJoinAmount(),
                product.getMaxJoinAmount(),
                product.getMinPeriodMonth(),
                product.getMaxPeriodMonth(),
                product.getIsEarlyTerminationAllowed(),
                product.getIsTaxBenefitAvailable(),
                product.getIsAutoRenewalAvailable(),
                product.getIsPassbookIssued(),
                product.getReleasedAt(),
                product.getEndedAt(),
                product.getProductStatus(),
                targetGroups
        );
    }
}
