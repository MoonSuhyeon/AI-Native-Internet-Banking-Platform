package com.bank.loan.product.preferential.service;

import com.bank.common.web.BusinessException;
import com.bank.loan.product.domain.LoanProduct;
import com.bank.loan.product.preferential.domain.PreferentialRatePolicy;
import com.bank.loan.product.preferential.dto.CreatePreferentialRatePolicyRequest;
import com.bank.loan.product.preferential.dto.PreferentialRatePolicyResponse;
import com.bank.loan.product.preferential.repository.PreferentialRatePolicyRepository;

import java.util.List;
import com.bank.loan.product.repository.LoanProductRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PreferentialRatePolicyService {

    private static final boolean DEFAULT_ACTIVE = true;

    private final PreferentialRatePolicyRepository policyRepository;
    private final LoanProductRepository productRepository;

    @Transactional
    public PreferentialRatePolicyResponse create(Long prodId, CreatePreferentialRatePolicyRequest req) {
        // 상품 존재 검증 (soft-deleted 제외)
        LoanProduct product = productRepository.findByProdIdAndDeletedAtIsNull(prodId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_002));

        // 동일 상품 × 동일 조건의 활성 정책 중복 차단
        if (policyRepository.existsByProdIdAndConditionCdAndActiveYnAndDeletedAtIsNull(
                product.getProdId(), req.conditionCd(), DEFAULT_ACTIVE)) {
            throw new BusinessException(LoanErrorCode.LOAN_005);
        }

        PreferentialRatePolicy saved = policyRepository.save(PreferentialRatePolicy.builder()
                .prodId(product.getProdId())
                .policyName(req.policyName())
                .conditionCd(req.conditionCd())
                .preferentialRateBps(req.preferentialRateBps())
                .maxStackBps(req.maxStackBps())
                .activeYn(DEFAULT_ACTIVE)
                .effectiveStartDate(req.effectiveStartDate())
                .effectiveEndDate(req.effectiveEndDate())
                .policyRemark(req.policyRemark())
                .build());

        return PreferentialRatePolicyResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<PreferentialRatePolicyResponse> list(Long prodId) {
        return policyRepository.findAllByProdIdAndDeletedAtIsNullOrderByPolicyIdAsc(prodId)
                .stream()
                .map(PreferentialRatePolicyResponse::of)
                .toList();
    }
}
