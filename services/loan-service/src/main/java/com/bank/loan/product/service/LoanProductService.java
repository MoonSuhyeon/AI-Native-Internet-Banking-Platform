package com.bank.loan.product.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.commonsync.outbox.CommonSyncOutboxAppender;
import com.bank.loan.product.domain.LoanProduct;
import com.bank.loan.product.dto.CreateLoanProductRequest;
import com.bank.loan.product.dto.DiscontinueLoanProductRequest;
import com.bank.loan.product.dto.LoanProductListItem;
import com.bank.loan.product.dto.LoanProductListResponse;
import com.bank.loan.product.dto.LoanProductResponse;
import com.bank.loan.product.dto.UpdateLoanProductRequest;
import com.bank.loan.product.repository.LoanProductRepository;
import com.bank.loan.product.repository.LoanProductSpecifications;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanProductService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "LOAN_PRODUCT";
    private static final boolean DEFAULT_NO = false;

    private final LoanProductRepository repository;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;
    private final CommonSyncOutboxAppender commonSyncOutboxAppender;

    @Transactional(readOnly = true)
    public LoanProductListResponse list(String loanTypeCd, String prodStatusCd, Pageable pageable) {
        Specification<LoanProduct> spec = Specification
                .where(LoanProductSpecifications.activeOnly())
                .and(LoanProductSpecifications.hasLoanType(loanTypeCd))
                .and(LoanProductSpecifications.hasStatus(prodStatusCd));
        return LoanProductListResponse.of(repository.findAll(spec, pageable).map(LoanProductListItem::of));
    }

    @Transactional(readOnly = true)
    public LoanProductResponse get(Long prodId) {
        LoanProduct product = repository.findByProdIdAndDeletedAtIsNull(prodId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_002));
        return LoanProductResponse.of(product);
    }

    @Transactional
    public LoanProductResponse create(CreateLoanProductRequest req) {
        validateRanges(req);

        Boolean guarantorYn = nvl(req.guarantorRequiredYn());
        int minGtr = req.minGuarantorCount() == null ? 0 : req.minGuarantorCount();
        validateGuarantorPolicy(guarantorYn, minGtr);
        validateApplicationValidityDays(req.applicationValidityDays());

        if (repository.existsByProdCdAndDeletedAtIsNull(req.prodCd())) {
            throw new BusinessException(LoanErrorCode.LOAN_001);
        }

        LoanProduct saved = repository.save(LoanProduct.builder()
                .productId(req.productId())
                .prodCd(req.prodCd())
                .prodName(req.prodName())
                .loanTypeCd(req.loanTypeCd())
                .targetCustomerCd(req.targetCustomerCd())
                .repaymentMethodCd(req.repaymentMethodCd())
                .rateTypeCd(req.rateTypeCd())
                .baseRateBps(req.baseRateBps())
                .minRateBps(req.minRateBps())
                .maxRateBps(req.maxRateBps())
                .minAmount(req.minAmount())
                .maxAmount(req.maxAmount())
                .minPeriodMo(req.minPeriodMo())
                .maxPeriodMo(req.maxPeriodMo())
                .collateralRequiredYn(nvl(req.collateralRequiredYn()))
                .guarantorRequiredYn(guarantorYn)
                .minGuarantorCount(minGtr)
                .applicationValidityDays(req.applicationValidityDays())
                .saleStartDate(req.saleStartDate())
                .saleEndDate(req.saleEndDate())
                .prodStatusCd(LoanProduct.STATUS_DRAFT)
                .prodTermsUrl(req.prodTermsUrl())
                .prodTermsHash(req.prodTermsHash())
                .build());

        // 상품 등록과 동일 트랜잭션에서 common_sync_outbox 적재 (outbox 패턴).
        // req.productId != null 이면 이미 외부에서 매핑된 상품 — 적재는 동일하게 진행,
        // 디스패처가 common_db 에서 product_cd 로 멱등 dedupe 처리.
        commonSyncOutboxAppender.enqueueProductInCurrentTx(saved);

        return LoanProductResponse.of(saved);
    }

    @Transactional
    public LoanProductResponse update(Long prodId, UpdateLoanProductRequest req) {
        LoanProduct product = repository.findByProdIdAndDeletedAtIsNull(prodId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_002));

        product.update(
                req.prodName(),
                req.loanTypeCd(), req.targetCustomerCd(),
                req.repaymentMethodCd(), req.rateTypeCd(),
                req.baseRateBps(), req.minRateBps(), req.maxRateBps(),
                req.minAmount(), req.maxAmount(),
                req.minPeriodMo(), req.maxPeriodMo(),
                req.collateralRequiredYn(), req.guarantorRequiredYn(),
                req.minGuarantorCount(),
                req.applicationValidityDays(),
                req.saleStartDate(), req.saleEndDate(),
                req.prodTermsUrl(), req.prodTermsHash(),
                req.prodStatusCd()
        );

        validateRanges(product);
        validateGuarantorPolicy(product.getGuarantorRequiredYn(), product.getMinGuarantorCount());
        validateApplicationValidityDays(product.getApplicationValidityDays());
        return LoanProductResponse.of(product);
    }

    @Transactional
    public LoanProductResponse discontinue(Long prodId, DiscontinueLoanProductRequest req) {
        LoanProduct product = repository.findByProdIdAndDeletedAtIsNull(prodId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_002));

        if (product.isDiscontinued()) {
            throw new BusinessException(LoanErrorCode.LOAN_004);
        }

        String before = product.currentStatus();
        product.discontinue(req.saleEndDate());

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, product.getProdId(),
                before, LoanProduct.STATUS_DISCONTINUED,
                req.reasonCd(), req.reasonRemark(),
                currentActor.currentActorId()
        ));

        return LoanProductResponse.of(product);
    }

    private void validateRanges(CreateLoanProductRequest req) {
        if (req.minAmount() > req.maxAmount()) {
            throw new BusinessException(LoanErrorCode.LOAN_003, "minAmount > maxAmount");
        }
        if (req.minPeriodMo() > req.maxPeriodMo()) {
            throw new BusinessException(LoanErrorCode.LOAN_003, "minPeriodMo > maxPeriodMo");
        }
        if (req.minRateBps() != null && req.maxRateBps() != null && req.minRateBps() > req.maxRateBps()) {
            throw new BusinessException(LoanErrorCode.LOAN_003, "minRateBps > maxRateBps");
        }
    }

    private void validateRanges(LoanProduct p) {
        if (p.getMinAmount() > p.getMaxAmount()) {
            throw new BusinessException(LoanErrorCode.LOAN_003, "minAmount > maxAmount");
        }
        if (p.getMinPeriodMo() > p.getMaxPeriodMo()) {
            throw new BusinessException(LoanErrorCode.LOAN_003, "minPeriodMo > maxPeriodMo");
        }
        if (p.getMinRateBps() != null && p.getMaxRateBps() != null && p.getMinRateBps() > p.getMaxRateBps()) {
            throw new BusinessException(LoanErrorCode.LOAN_003, "minRateBps > maxRateBps");
        }
    }

    /**
     * guarantorRequiredYn = true 이면 minGuarantorCount 가 1 이상이어야 한다.
     * 위반 시 LOAN_003 — 상품 범위/정책 오류와 동일 분류.
     */
    private void validateGuarantorPolicy(Boolean guarantorRequiredYn, int minGuarantorCount) {
        if (Boolean.TRUE.equals(guarantorRequiredYn) && minGuarantorCount < 1) {
            throw new BusinessException(LoanErrorCode.LOAN_003,
                    "guarantorRequiredYn=Y 이면 minGuarantorCount >= 1 이어야 합니다");
        }
    }

    /**
     * applicationValidityDays 는 null(=시스템 기본 14일) 이거나 1~90 범위여야 한다.
     * 위반 시 LOAN_003 — 상품 범위/정책 오류와 동일 분류.
     */
    private void validateApplicationValidityDays(Integer days) {
        if (days != null && (days < 1 || days > 90)) {
            throw new BusinessException(LoanErrorCode.LOAN_003,
                    "applicationValidityDays 는 1~90 사이여야 합니다");
        }
    }

    private Boolean nvl(Boolean yn) {
        return yn == null ? DEFAULT_NO : yn;
    }
}
