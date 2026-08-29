package com.bank.deposit.dto.internal;

import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.Product;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 상담·만기 안내에 필요한 만큼만 담은 계약 요약. */
public record InternalContractSummary(
        Long contractId,
        String contractNumber,
        Long joinAmount,
        BigDecimal contractInterestRate,
        LocalDate startedAt,
        LocalDate maturityAt,
        String contractStatus,
        Long productId,
        /** 상품명·상품유형은 공개 카탈로그 값이다. 계약과 늘 같이 쓰이므로 여기 담는다. */
        String productName,
        String productType
) {
    public static InternalContractSummary from(Contract c, Product product) {
        return new InternalContractSummary(
                c.getContractId(),
                c.getContractNumber(),
                c.getJoinAmount(),
                c.getContractInterestRate(),
                c.getStartedAt(),
                c.getMaturityAt(),
                c.getContractStatus() != null ? c.getContractStatus().name() : null,
                c.getProductId(),
                product != null ? product.getProductName() : null,
                product != null && product.getProductType() != null ? product.getProductType().name() : null
        );
    }
}
