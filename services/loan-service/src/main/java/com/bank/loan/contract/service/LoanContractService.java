package com.bank.loan.contract.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.dto.CreateContractRequest;
import com.bank.loan.contract.dto.LoanContractResponse;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.guarantor.domain.GuarantorAgreement;
import com.bank.loan.guarantor.repository.GuarantorAgreementRepository;
import com.bank.loan.guarantor.service.GuarantorPolicyValidator;
import com.bank.loan.maturity.service.MaturityService;
import com.bank.loan.product.domain.LoanProduct;
import com.bank.loan.product.repository.LoanProductRepository;
import com.bank.loan.notification.event.ContractSignedEvent;
import com.bank.loan.advisory.AdvisoryClient;
import com.bank.loan.advisory.AdvisoryReportSummary;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 약정한도 설정 서비스.
 *
 * 흐름:
 *   1) 신청이 APPROVED 인지 검증 (LOAN_060)
 *   2) 신청에 미서명(REGISTERED) 보증 약정이 잔존하면 차단 (LOAN_175)
 *      flows §1.1 — 약정 체결 전제조건: GUARANTOR_AGREEMENT.gagr_status_cd=SIGNED
 *   3) 약정금액·기간이 신청 범위 이내인지 검증 (LOAN_061)
 *      - 본심사(loan_review) 미구현이므로 application.requested_* 를 상한으로 사용
 *      - 본심사 도입 시 loan_review.approved_* 로 교체
 *   4) loan_contract INSERT (status=SIGNED)
 *   5) application status APPROVED → CONTRACTED
 *   6) status_history 양쪽 모두 기록 (BEFORE_COMMIT 동일 트랜잭션)
 */
@Service
@RequiredArgsConstructor
public class LoanContractService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_CONTRACT = "LOAN_CONTRACT";
    private static final String TARGET_APPLICATION = "LOAN_APPLICATION";
    private static final String DEFAULT_CURRENCY = "KRW";
    private static final String REASON_CONTRACT_SIGNED = "CONTRACT_SIGNED";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LoanContractRepository repository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanProductRepository productRepository;
    private final GuarantorAgreementRepository guarantorAgreementRepository;
    private final GuarantorPolicyValidator guarantorPolicyValidator;
    private final ContractNumberGenerator cntrNoGenerator;
    private final MaturityService maturityService;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;
    private final ApplicationEventPublisher eventPublisher;
    private final LoanReviewRepository reviewRepository;
    private final AdvisoryClient advisoryClient;

    /**
     * CRITICAL 어드바이저리가 미확인 상태면 약정 체결을 막는다.
     *
     * <p>같은 통제가 4-eye 승인 경로(LoanReviewApproverService)에는 있었으나
     * 약정 체결 경로에는 없어, 편향 경고가 미해결인 채로 약정이 체결될 수 있었다.
     * AdvisoryFlowIntegrationTest 가 이 동작을 검증하고 있었지만 구현이 없어
     * 실패하던 것을 통제 추가로 해소한다.
     *
     * <p>본심사가 없으면(어드바이저리 대상 자체가 없으므로) 통과시킨다.
     */
    private void assertNoCriticalUnackedAdvisory(Long applId) {
        reviewRepository.findByApplIdAndDeletedAtIsNull(applId).ifPresent(review -> {
            List<AdvisoryReportSummary> reports = advisoryClient.getReports(review.getRevId());
            boolean hasUnackedCritical = reports.stream()
                    .filter(r -> "CRITICAL".equals(r.severityCd()))
                    .anyMatch(r -> !"ACKED".equals(r.advrStatusCd())
                            && !"RESOLVED".equals(r.advrStatusCd()));
            if (hasUnackedCritical) {
                throw new BusinessException(LoanErrorCode.LOAN_201);
            }
        });
    }

    @Transactional
    public LoanContractResponse create(CreateContractRequest req) {
        LoanApplication application = applicationRepository.findByApplIdAndDeletedAtIsNull(req.applId())
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_012));

        if (!application.isApproved()) {
            throw new BusinessException(LoanErrorCode.LOAN_060);
        }

        assertNoCriticalUnackedAdvisory(application.getApplId());

        validateGuarantorsSigned(application.getApplId());

        // 보증 필수 상품이면 SIGNED 보증인 충족 여부 재확인 — 미서명 잔존 외에도 수 부족 방어
        LoanProduct product = productRepository
                .findByProdIdAndDeletedAtIsNull(application.getProdId()).orElse(null);
        if (product != null && !guarantorPolicyValidator.satisfies(application, product)) {
            throw new BusinessException(LoanErrorCode.LOAN_175,
                    "guarantorRequired: signedCount < minGuarantorCount=" + product.getMinGuarantorCount());
        }

        validateRanges(req, application);

        int spread = req.spreadBps() == null ? 0 : req.spreadBps();
        int preferential = req.preferentialRateBps() == null ? 0 : req.preferentialRateBps();
        int totalRate = req.totalRateBps() == null
                ? Math.max(0, req.baseRateBps() + spread - preferential)
                : req.totalRateBps();

        OffsetDateTime now = OffsetDateTime.now();
        LocalDate startDate = req.cntrStartDate() != null
                ? LocalDate.parse(req.cntrStartDate(), DATE)
                : BusinessDate.dateOf(now);
        LocalDate endDate = req.cntrEndDate() != null
                ? LocalDate.parse(req.cntrEndDate(), DATE)
                : startDate.plusMonths(req.contractedPeriodMo());

        LoanContract saved = repository.save(LoanContract.builder()
                .cntrNo(cntrNoGenerator.generate(now))
                .applId(application.getApplId())
                .customerId(application.getCustomerId())
                .prodId(application.getProdId())
                .contractedAmount(req.contractedAmount())
                .currencyCd(req.currencyCd() == null ? DEFAULT_CURRENCY : req.currencyCd())
                .contractedPeriodMo(req.contractedPeriodMo())
                .totalRateBps(totalRate)
                .baseRateBps(req.baseRateBps())
                .spreadBps(spread)
                .preferentialRateBps(preferential)
                .rateTypeCd(req.rateTypeCd())
                .repaymentMethodCd(req.repaymentMethodCd())
                .cntrStatusCd(LoanContract.STATUS_SIGNED)
                .cntrStartDate(startDate.format(DATE))
                .cntrEndDate(endDate.format(DATE))
                .cntrDocUrl(req.cntrDocUrl())
                .cntrDocHash(req.cntrDocHash())
                .signedAt(now)
                .build());

        // 신청 상태 전이
        String applBefore = application.currentStatus();
        application.markContracted();

        Long actor = currentActor.currentActorId();

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_CONTRACT, saved.getCntrId(),
                null, LoanContract.STATUS_SIGNED,
                REASON_CONTRACT_SIGNED, null, actor
        ));
        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_APPLICATION, application.getApplId(),
                applBefore, LoanApplication.STATUS_CONTRACTED,
                REASON_CONTRACT_SIGNED, null, actor
        ));

        // 만기 정보 자동 생성 (original = current = cntr_end_date)
        maturityService.createOnContract(saved);

        // 약정서·안내서 발송 트리거 (fire-and-forget) — AFTER_COMMIT 시점에 별도 스레드에서 실행.
        // ContractNotificationListener 가 PDF 생성/SMS/알림톡/이메일 stub 수행.
        eventPublisher.publishEvent(new ContractSignedEvent(
                saved.getCntrId(), saved.getCntrNo(),
                application.getApplId(), application.getCustomerId()
        ));

        return LoanContractResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public Page<LoanContractResponse> listForAdmin(
            String cntrStatusCd, String dateFrom, String dateTo, int page, int size) {
        return repository
                .findForAdmin(cntrStatusCd, dateFrom, dateTo, PageRequest.of(page, size))
                .map(LoanContractResponse::of);
    }

    @Transactional(readOnly = true)
    public List<LoanContractResponse> list(Long customerId) {
        return repository.findByCustomerIdAndDeletedAtIsNullOrderByCntrIdDesc(customerId)
                .stream()
                .map(LoanContractResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public LoanContractResponse get(Long cntrId) {
        return repository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .map(LoanContractResponse::of)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));
    }

    /**
     * 신청에 등록된 보증 약정 중 REGISTERED(미서명) 상태가 잔존하면 약정 체결 차단.
     * CANCELED 는 검증 대상에서 제외. 보증 약정이 한 건도 없는 신용대출은 자동 통과.
     */
    private void validateGuarantorsSigned(Long applId) {
        if (guarantorAgreementRepository.existsByApplIdAndGagrStatusCdAndDeletedAtIsNull(
                applId, GuarantorAgreement.STATUS_REGISTERED)) {
            throw new BusinessException(LoanErrorCode.LOAN_175, "applId=" + applId);
        }
    }

    private void validateRanges(CreateContractRequest req, LoanApplication application) {
        if (req.contractedAmount() > application.getRequestedAmount()) {
            throw new BusinessException(LoanErrorCode.LOAN_061,
                    "contractedAmount > requestedAmount");
        }
        if (req.contractedPeriodMo() > application.getRequestedPeriodMo()) {
            throw new BusinessException(LoanErrorCode.LOAN_061,
                    "contractedPeriodMo > requestedPeriodMo");
        }
    }
}
