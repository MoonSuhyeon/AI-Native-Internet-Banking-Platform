package com.bank.loan.guarantor.service;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.guarantor.repository.GuarantorAgreementRepository;
import com.bank.loan.product.domain.LoanProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 보증 필수 상품의 SIGNED 보증인 충족 여부를 검증하는 헬퍼.
 *
 * 사용처:
 *   LoanReviewService  — 본심사 사전조건 (미충족 시 LOAN_038)
 *   LoanContractService — 약정 체결 사전조건 (미충족 시 LOAN_175)
 *   LoanExecutionService — drawdown 사전조건 (미충족 시 LOAN_176)
 *
 * 각 호출지점이 satisfies() = false 일 때 도메인별 에러코드로 BusinessException 을 던진다.
 */
@Component
@RequiredArgsConstructor
public class GuarantorPolicyValidator {

    private final GuarantorAgreementRepository guarantorAgreementRepository;

    /**
     * 상품 보증 정책을 신청 건이 충족하는지 확인한다.
     *
     * @return guarantorRequiredYn = false 이면 항상 true.
     *         'Y' 이면 SIGNED 약정 수 >= minGuarantorCount 일 때 true.
     */
    public boolean satisfies(LoanApplication appl, LoanProduct product) {
        if (!product.isGuarantorRequired()) {
            return true;
        }
        long signedCount = guarantorAgreementRepository.countActiveSignedByApplId(appl.getApplId());
        return signedCount >= product.getMinGuarantorCount();
    }
}
