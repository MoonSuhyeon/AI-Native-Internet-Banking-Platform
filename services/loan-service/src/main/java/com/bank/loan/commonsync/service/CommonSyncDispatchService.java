package com.bank.loan.commonsync.service;

import com.bank.commonaccount.contract.domain.CommonContract;
import com.bank.commonaccount.contract.repository.CommonContractRepository;
import com.bank.commonaccount.product.domain.CommonProduct;
import com.bank.commonaccount.product.repository.CommonProductRepository;
import com.bank.commonaccount.transaction.domain.CommonTransaction;
import com.bank.commonaccount.transaction.repository.CommonTransactionRepository;
import com.bank.loan.commonsync.dto.CommonSyncDispatchSummary;
import com.bank.loan.commonsync.dto.ContractSyncPayload;
import com.bank.loan.commonsync.dto.ProductSyncPayload;
import com.bank.loan.commonsync.dto.TransactionSyncPayload;
import com.bank.loan.commonsync.outbox.CommonSyncOutbox;
import com.bank.loan.commonsync.outbox.CommonSyncOutboxRepository;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.execution.repository.LoanExecutionRepository;
import com.bank.loan.product.domain.LoanProduct;
import com.bank.loan.product.repository.LoanProductRepository;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * common_sync_outbox 디스패치 배치.
 *
 * 트랜잭션 모델 (이중 datasource):
 *   1) 후보 픽업          — primaryTxManager readonly 트랜잭션
 *   2) 부모 common_id 조회 — loan_db 읽기 (트랜잭션 불필요, auto-commit)
 *   3) common_db upsert   — commonTxManager REQUIRES_NEW 트랜잭션 (perRowCommonWriter)
 *   4) 브리지 백필 + DONE  — primaryTxManager REQUIRES_NEW 트랜잭션 (perRowLoanWriter)
 *      (backfill 실패 시 FAILED — primaryTxManager REQUIRES_NEW)
 *
 * 한 row 처리 실패가 전체 배치를 깨면 안 된다 — try/catch 로 row 단위 격리.
 *
 * 순서 의존성:
 *   CONTRACT 는 연결된 PRODUCT 가 먼저 동기화돼야 한다 (LoanProduct.productId ≠ null 조건).
 *   TRANSACTION 은 CONTRACT 가 먼저 동기화돼야 한다 (LoanContract.contractId ≠ null 조건).
 *   미충족 시 FAILED 로 전이 → 백오프 재시도.
 */
@Slf4j
@Service
public class CommonSyncDispatchService {

    public static final int DEFAULT_PAGE_SIZE = 200;

    private final CommonSyncOutboxRepository outboxRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanContractRepository loanContractRepository;
    private final LoanExecutionRepository loanExecutionRepository;
    private final RepaymentTransactionRepository repaymentTransactionRepository;
    private final CommonProductRepository commonProductRepository;
    private final CommonContractRepository commonContractRepository;
    private final CommonTransactionRepository commonTransactionRepository;
    private final PlatformTransactionManager transactionManager;
    private final PlatformTransactionManager commonTransactionManager;
    private final ObjectMapper objectMapper;

    @Autowired
    public CommonSyncDispatchService(
            CommonSyncOutboxRepository outboxRepository,
            LoanProductRepository loanProductRepository,
            LoanContractRepository loanContractRepository,
            LoanExecutionRepository loanExecutionRepository,
            RepaymentTransactionRepository repaymentTransactionRepository,
            CommonProductRepository commonProductRepository,
            CommonContractRepository commonContractRepository,
            CommonTransactionRepository commonTransactionRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("commonTransactionManager") PlatformTransactionManager commonTransactionManager,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanContractRepository = loanContractRepository;
        this.loanExecutionRepository = loanExecutionRepository;
        this.repaymentTransactionRepository = repaymentTransactionRepository;
        this.commonProductRepository = commonProductRepository;
        this.commonContractRepository = commonContractRepository;
        this.commonTransactionRepository = commonTransactionRepository;
        this.transactionManager = transactionManager;
        this.commonTransactionManager = commonTransactionManager;
        this.objectMapper = objectMapper;
    }

    private TransactionTemplate perRowLoanWriter;
    private TransactionTemplate perRowCommonWriter;

    @PostConstruct
    void init() {
        perRowLoanWriter = new TransactionTemplate(transactionManager);
        perRowLoanWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        perRowCommonWriter = new TransactionTemplate(commonTransactionManager);
        perRowCommonWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CommonSyncDispatchSummary dispatch() {
        return dispatch(DEFAULT_PAGE_SIZE);
    }

    public CommonSyncDispatchSummary dispatch(int pageSize) {
        OffsetDateTime now = OffsetDateTime.now();
        List<CommonSyncOutbox> candidates = pickCandidates(now, pageSize);
        int done = 0, failed = 0, dead = 0;
        for (CommonSyncOutbox candidate : candidates) {
            try {
                Outcome o = processOne(candidate.getOutboxId());
                switch (o) {
                    case DONE   -> done++;
                    case FAILED -> failed++;
                    case DEAD   -> dead++;
                    case SKIP   -> { /* 이미 처리됨 */ }
                }
            } catch (RuntimeException e) {
                log.warn("[common-sync-dispatch] outboxId={} skipped: {}",
                        candidate.getOutboxId(), e.getMessage());
            }
        }
        return CommonSyncDispatchSummary.of(candidates.size(), done, failed, dead);
    }

    private List<CommonSyncOutbox> pickCandidates(OffsetDateTime now, int pageSize) {
        return outboxRepository
                .findByStatusInAndNextAttemptAtLessThanEqualAndDeletedAtIsNullOrderByNextAttemptAtAsc(
                        List.of(CommonSyncOutbox.STATUS_PENDING, CommonSyncOutbox.STATUS_FAILED),
                        now,
                        PageRequest.of(0, pageSize));
    }

    /**
     * 한 outbox row 처리.
     *
     * 1) loan_db 에서 row 리로드 (no tx)
     * 2) 부모 common_id 조회 — loan_db 읽기 (CONTRACT/TRANSACTION 타입)
     * 3) common_db upsert (REQUIRES_NEW on commonTxManager)
     * 4) 브리지 백필 + DONE (REQUIRES_NEW on primaryTxManager)
     */
    private Outcome processOne(Long outboxId) {
        CommonSyncOutbox snapshot = outboxRepository
                .findByOutboxIdAndDeletedAtIsNull(outboxId).orElse(null);
        if (snapshot == null) return Outcome.SKIP;
        if (!CommonSyncOutbox.STATUS_PENDING.equals(snapshot.getStatus())
                && !CommonSyncOutbox.STATUS_FAILED.equals(snapshot.getStatus())) {
            return Outcome.SKIP;
        }

        // 부모 common_id 조회 (common_db 트랜잭션 진입 전 — loan_db read)
        Long parentCommonId;
        try {
            parentCommonId = resolveParentCommonId(snapshot);
        } catch (RuntimeException e) {
            String err = safeMsg(e);
            log.warn("[common-sync-dispatch] parent not ready outboxId={} target={}: {}",
                    outboxId, snapshot.getTargetTypeCd(), err);
            String errFinal = err;
            perRowLoanWriter.execute(s -> {
                CommonSyncOutbox row = outboxRepository
                        .findByOutboxIdAndDeletedAtIsNull(outboxId).orElseThrow();
                row.markFailed(errFinal, OffsetDateTime.now());
                return null;
            });
            return applyFailedOutcome(outboxId);
        }

        // common_db upsert
        final Long parentId = parentCommonId;
        Long commonId;
        try {
            commonId = perRowCommonWriter.execute(s -> upsertToCommonDb(snapshot, parentId));
        } catch (RuntimeException e) {
            String err = safeMsg(e);
            log.warn("[common-sync-dispatch] common_db upsert failed outboxId={} target={}: {}",
                    outboxId, snapshot.getTargetTypeCd(), err);
            String errFinal = err;
            perRowLoanWriter.execute(s -> {
                CommonSyncOutbox row = outboxRepository
                        .findByOutboxIdAndDeletedAtIsNull(outboxId).orElseThrow();
                row.markFailed(errFinal, OffsetDateTime.now());
                return null;
            });
            return applyFailedOutcome(outboxId);
        }

        // 브리지 백필 + outbox DONE
        final Long finalCommonId = commonId;
        final CommonSyncOutbox payloadRef = snapshot; // payload 문자열 읽기 전용 참조
        perRowLoanWriter.execute(s -> {
            backfillBridge(payloadRef, finalCommonId);
            CommonSyncOutbox row = outboxRepository
                    .findByOutboxIdAndDeletedAtIsNull(outboxId).orElseThrow();
            row.markDone(finalCommonId, OffsetDateTime.now());
            return null;
        });
        return Outcome.DONE;
    }

    // -------------------------------------------------------------------------
    // 부모 common_id 조회
    // -------------------------------------------------------------------------

    /**
     * CONTRACT: LoanProduct.productId (CommonProduct PK) 반환.
     * TRANSACTION: LoanContract.contractId (CommonContract PK) 반환.
     * PRODUCT: null 반환.
     */
    private Long resolveParentCommonId(CommonSyncOutbox outbox) {
        return switch (outbox.getTargetTypeCd()) {
            case CommonSyncOutbox.TARGET_PRODUCT -> null;
            case CommonSyncOutbox.TARGET_CONTRACT -> {
                ContractSyncPayload p = parse(outbox.getPayload(), ContractSyncPayload.class);
                LoanProduct lp = loanProductRepository.findByProdIdAndDeletedAtIsNull(p.prodId())
                        .orElseThrow(() -> new IllegalStateException(
                                "LoanProduct not found: prodId=" + p.prodId()));
                if (lp.getProductId() == null) {
                    throw new IllegalStateException(
                            "LoanProduct not yet synced to common_db: prodId=" + p.prodId());
                }
                yield lp.getProductId();
            }
            case CommonSyncOutbox.TARGET_TRANSACTION -> {
                TransactionSyncPayload p = parse(outbox.getPayload(), TransactionSyncPayload.class);
                LoanContract lc = loanContractRepository.findByCntrIdAndDeletedAtIsNull(p.cntrId())
                        .orElseThrow(() -> new IllegalStateException(
                                "LoanContract not found: cntrId=" + p.cntrId()));
                if (lc.getContractId() == null) {
                    throw new IllegalStateException(
                            "LoanContract not yet synced to common_db: cntrId=" + p.cntrId());
                }
                yield lc.getContractId();
            }
            default -> throw new IllegalArgumentException(
                    "Unknown targetTypeCd: " + outbox.getTargetTypeCd());
        };
    }

    // -------------------------------------------------------------------------
    // common_db upsert (perRowCommonWriter 내부에서 호출)
    // -------------------------------------------------------------------------

    private Long upsertToCommonDb(CommonSyncOutbox outbox, Long parentCommonId) {
        return switch (outbox.getTargetTypeCd()) {
            case CommonSyncOutbox.TARGET_PRODUCT     -> upsertProduct(outbox);
            case CommonSyncOutbox.TARGET_CONTRACT    -> upsertContract(outbox, parentCommonId);
            case CommonSyncOutbox.TARGET_TRANSACTION -> upsertTransaction(outbox, parentCommonId);
            default -> throw new IllegalArgumentException(
                    "Unknown targetTypeCd: " + outbox.getTargetTypeCd());
        };
    }

    private Long upsertProduct(CommonSyncOutbox outbox) {
        ProductSyncPayload p = parse(outbox.getPayload(), ProductSyncPayload.class);
        return commonProductRepository.findByProductCd(outbox.getSourceNo())
                .map(CommonProduct::getProductId)
                .orElseGet(() -> commonProductRepository.save(
                        CommonProduct.builder()
                                .productCd(p.prodCd())
                                .bizDivCd(p.bizDivCd())
                                .productName(p.prodName())
                                .productTypeCd(p.loanTypeCd())
                                .targetTypeCd(p.targetCustomerCd())
                                .minAmount(p.minAmount())
                                .maxAmount(p.maxAmount())
                                .minPeriodMo(p.minPeriodMo())
                                .maxPeriodMo(p.maxPeriodMo())
                                .saleStartDate(p.saleStartDate())
                                .saleEndDate(p.saleEndDate())
                                .productStatus(p.prodStatus())
                                .saleYn(true)
                                .policyProductYn(false)
                                .build())
                        .getProductId());
    }

    private Long upsertContract(CommonSyncOutbox outbox, Long commonProductId) {
        ContractSyncPayload p = parse(outbox.getPayload(), ContractSyncPayload.class);
        return commonContractRepository.findByContractNo(outbox.getSourceNo())
                .map(CommonContract::getContractId)
                .orElseGet(() -> commonContractRepository.save(
                        CommonContract.builder()
                                .contractNo(p.cntrNo())
                                .customerId(p.customerId())
                                .customerNo(p.customerNo())
                                .productId(commonProductId)
                                .bizDivCd(p.bizDivCd())
                                .contractAmount(p.contractAmount())
                                .rateTypeCd(p.rateTypeCd())
                                .baseRateBps(p.baseRateBps())
                                .spreadBps(p.spreadBps())
                                .preferentialBps(p.preferentialBps())
                                .totalRateBps(p.totalRateBps())
                                .contractStartDate(p.contractStartDate())
                                .contractEndDate(p.contractEndDate())
                                .autoTransferYn(p.autoTransferYn())
                                .autoTransferDay(p.autoTransferDay())
                                .signedAt(p.signedAt())
                                .contractChannelCd(p.cntrChannelCd())
                                .spotId(p.spotId())
                                .spotName(p.spotName())
                                .managerId(p.managerId())
                                .managerName(p.managerName())
                                .proxyYn(p.proxyYn())
                                .contractStatus(p.cntrStatusCd())
                                .build())
                        .getContractId());
    }

    private Long upsertTransaction(CommonSyncOutbox outbox, Long commonContractId) {
        TransactionSyncPayload p = parse(outbox.getPayload(), TransactionSyncPayload.class);
        return commonTransactionRepository.findByTransactionNo(outbox.getSourceNo())
                .map(CommonTransaction::getTransactionId)
                .orElseGet(() -> commonTransactionRepository.save(
                        CommonTransaction.builder()
                                .transactionNo(p.transactionNo())
                                .contractId(commonContractId)
                                .transactionTypeCd(p.transactionTypeCd())
                                .debitCreditType(p.debitCreditType())
                                .transactionAmount(p.transactionAmount())
                                .balanceBefore(p.balanceBefore())
                                .balanceAfter(p.balanceAfter())
                                .feeAmount(p.feeAmount())
                                .channelCd(p.channelCd())
                                .counterpartyBankCd(p.counterpartyBankCd())
                                .counterpartyAccountNo(p.counterpartyAccountNo())
                                .counterpartyName(p.counterpartyName())
                                .transactionMemo(p.transactionMemo())
                                .transactionStatus(p.transactionStatus())
                                .transactedAt(p.transactedAt())
                                .currencyCd(p.currencyCd())
                                .availableBalance(p.availableBalance())
                                .transactionSummary(p.transactionSummary())
                                .transferTypeCd(p.transferTypeCd())
                                .failureTypeCd(p.failureTypeCd())
                                .failureReasonCd(p.failureReasonCd())
                                .failedAt(p.failedAt())
                                .approvalNo(p.approvalNo())
                                .ledgerPostedAt(p.ledgerPostedAt())
                                .build())
                        .getTransactionId());
    }

    // -------------------------------------------------------------------------
    // 브리지 백필 (perRowLoanWriter 내부에서 호출)
    // -------------------------------------------------------------------------

    private void backfillBridge(CommonSyncOutbox outbox, Long commonId) {
        switch (outbox.getTargetTypeCd()) {
            case CommonSyncOutbox.TARGET_PRODUCT ->
                    loanProductRepository.updateProductId(outbox.getSourceId(), commonId);
            case CommonSyncOutbox.TARGET_CONTRACT ->
                    loanContractRepository.updateContractId(outbox.getSourceId(), commonId);
            case CommonSyncOutbox.TARGET_TRANSACTION -> {
                TransactionSyncPayload p = parse(outbox.getPayload(), TransactionSyncPayload.class);
                if (TransactionSyncPayload.SOURCE_EXECUTION.equals(p.sourceTable())) {
                    loanExecutionRepository.updateTransactionId(outbox.getSourceId(), commonId);
                } else {
                    repaymentTransactionRepository.updateTransactionId(outbox.getSourceId(), commonId);
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unknown targetTypeCd: " + outbox.getTargetTypeCd());
        }
    }

    // -------------------------------------------------------------------------
    // 실패 결과 판정
    // -------------------------------------------------------------------------

    private Outcome applyFailedOutcome(Long outboxId) {
        CommonSyncOutbox row = outboxRepository
                .findByOutboxIdAndDeletedAtIsNull(outboxId).orElseThrow();
        return CommonSyncOutbox.STATUS_DEAD.equals(row.getStatus())
                ? Outcome.DEAD : Outcome.FAILED;
    }

    // -------------------------------------------------------------------------
    // 유틸
    // -------------------------------------------------------------------------

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("payload 역직렬화 실패: " + type.getSimpleName(), e);
        }
    }

    private static String safeMsg(Throwable t) {
        return t.getClass().getSimpleName() + ": "
                + (t.getMessage() == null ? "" : t.getMessage());
    }

    private enum Outcome { DONE, FAILED, DEAD, SKIP }
}
