package com.bank.loan.collateral.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.collateral.domain.Collateral;
import com.bank.loan.collateral.dto.CollateralListResponse;
import com.bank.loan.collateral.dto.CollateralResponse;
import com.bank.loan.collateral.dto.CreateCollateralRequest;
import com.bank.loan.collateral.dto.ReleaseCollateralRequest;
import com.bank.loan.collateral.dto.UpdateCollateralRequest;
import com.bank.loan.collateral.repository.CollateralRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CollateralService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "COLLATERAL";
    private static final String DEFAULT_CURRENCY = "KRW";
    private static final boolean DEFAULT_NO = false;

    private final CollateralRepository repository;
    private final LoanApplicationRepository applicationRepository;
    private final CollateralNumberGenerator colNoGenerator;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;

    @Transactional
    public CollateralResponse release(Long colId, ReleaseCollateralRequest req) {
        Collateral collateral = repository.findByColIdAndDeletedAtIsNull(colId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_050));

        if (collateral.isReleased()) {
            throw new BusinessException(LoanErrorCode.LOAN_051);
        }

        String before = collateral.currentStatus();
        collateral.release();

        String remark = req.releaseRemark();
        if (req.releaseDate() != null) {
            remark = (remark == null ? "" : remark + " / ") + "releaseDate=" + req.releaseDate();
        }
        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, collateral.getColId(),
                before, Collateral.STATUS_RELEASED,
                req.releaseReasonCd(), remark,
                currentActor.currentActorId()
        ));

        return CollateralResponse.of(collateral);
    }

    @Transactional
    public CollateralResponse update(Long colId, UpdateCollateralRequest req) {
        Collateral collateral = repository.findByColIdAndDeletedAtIsNull(colId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_050));

        if (collateral.isReleased()) {
            throw new BusinessException(LoanErrorCode.LOAN_051);
        }

        collateral.update(
                req.colTypeCd(),
                req.colName(), req.colAddress(), req.colRegistryNo(),
                req.declaredValue(),
                req.currencyCd(), req.ownershipTypeCd(),
                req.seniorLienYn(), req.seniorLienAmount()
        );
        return CollateralResponse.of(collateral);
    }

    @Transactional(readOnly = true)
    public CollateralListResponse list(Long applId) {
        applicationRepository.findByApplIdAndDeletedAtIsNull(applId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_012));

        List<CollateralResponse> items = repository
                .findByApplIdAndDeletedAtIsNullOrderByCreatedAtAsc(applId)
                .stream().map(CollateralResponse::of).toList();
        return CollateralListResponse.of(items);
    }

    @Transactional
    public CollateralResponse register(Long applId, CreateCollateralRequest req) {
        LoanApplication application = applicationRepository.findByApplIdAndDeletedAtIsNull(applId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_012));

        Collateral saved = repository.save(Collateral.builder()
                .applId(application.getApplId())
                .colTypeCd(req.colTypeCd())
                .colStatusCd(Collateral.STATUS_REGISTERED)
                .colNo(colNoGenerator.generate(OffsetDateTime.now()))
                .colName(req.colName())
                .colAddress(req.colAddress())
                .colRegistryNo(req.colRegistryNo())
                .declaredValue(req.declaredValue())
                .currencyCd(req.currencyCd() == null ? DEFAULT_CURRENCY : req.currencyCd())
                .ownershipTypeCd(req.ownershipTypeCd())
                .seniorLienYn(req.seniorLienYn() == null ? DEFAULT_NO : req.seniorLienYn())
                .seniorLienAmount(req.seniorLienAmount())
                .build());

        return CollateralResponse.of(saved);
    }
}
