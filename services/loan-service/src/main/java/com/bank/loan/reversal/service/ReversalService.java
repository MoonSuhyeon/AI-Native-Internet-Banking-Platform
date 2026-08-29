package com.bank.loan.reversal.service;

import com.bank.common.security.service.ServiceOperation;
import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.security.crypto.CryptoService;
import com.bank.common.web.BusinessException;
import com.bank.loan.payment.SystemAccountProvider;
import com.bank.loan.payment.client.PaymentServiceClient;
import com.bank.loan.payment.client.dto.PaymentRequest;
import com.bank.loan.payment.client.dto.PaymentResponse;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.bank.loan.repaymentaccount.domain.RepaymentAccount;
import com.bank.loan.repaymentaccount.repository.RepaymentAccountRepository;
import com.bank.loan.reversal.dto.ReverseRepaymentRequest;
import com.bank.loan.reversal.dto.ReversalResponse;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 상환 거래 역분개(Reversal) 서비스 — TYPE_REVERSAL.
 *
 * 공통 절차:
 *   1) 멱등성 키 검사 — 동일 키 재호출 시 기존 reversal row 반환
 *   2) 대상 tx 조회 (cntrId 일치 + SUCCESS + reversalYn=N)
 *   3) 중복 reversal 차단 (LOAN_097)
 *   4) 새 RepaymentTransaction row (TYPE_REVERSAL, reversal_yn=Y, reversal_target_rtx_id=원본)
 *      금액은 원본과 동일 양수 — 회계 반대분개(common_transaction) 는 본 단계 외.
 *
 * 타입별 분기:
 *
 *   [SCHEDULED]
 *     대응 RepaymentSchedule (PAID) 를 DUE 로 되돌리고 status_history append.
 *
 *   [EARLY]
 *     스케줄 V 되돌리기 — V_new (현재 max) 의 회차들을 SUPERSEDED, V_prev 회차들을 DUE 부활.
 *     제약 (LOAN_099 — 위반 시 차단):
 *       - 대상 EARLY 이후 같은 계약에 또 다른 EARLY 가 없어야 함 (V 추론 깨짐 방지)
 *       - V_new 회차 전체가 DUE/OVERDUE 상태여야 함 (PAID/PARTIAL_PAID 가 있으면 V_prev 부활 시 충돌)
 *       - V_prev 가 존재해야 함 (즉 V_new = V2 이상)
 *     단순화 가정:
 *       - 금리변경(RateChange) 으로 EARLY 이후 또 다른 V 가 만들어진 경우도 위 제약(V_new 회차 모두 활성)
 *         으로 사실상 차단된다 — 금리변경은 SUPERSEDED 가 아닌 새 V_new+1 을 만들어 V_new 회차가 SUPERSEDED 되기 때문.
 *
 *   [PARTIAL / REVERSAL]
 *     본 단계 미지원 (LOAN_096).
 *
 * 정정의 정정(역분개의 역분개) 는 미지원.
 */
@Service
@RequiredArgsConstructor
public class ReversalService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "REPAYMENT_SCHEDULE";
    private static final String REASON_REVERSED = "REPAYMENT_REVERSED";
    private static final String REASON_PREPAY_REVERSED = "PREPAY_REVERSED";
    private static final String DEFAULT_CHANNEL = "MANUAL";
    private static final String CHANNEL_OPEN_BANKING = "OPEN_BANKING";

    private final RepaymentTransactionRepository txRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final RepaymentAccountRepository repaymentAccountRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final SystemAccountProvider systemAccountProvider;
    private final CryptoService cryptoService;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;

    @Transactional
    public ReversalResponse reverse(Long cntrId, Long rtxId, ReverseRepaymentRequest req,
                                    String idempotencyKey) {
        // 1) 멱등성
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = txRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                RepaymentTransaction tx = existing.get();
                return ReversalResponse.of(tx, tx.getRschId());
            }
        }

        // 2) 대상 tx 검증
        RepaymentTransaction target = txRepository.findByRtxIdAndDeletedAtIsNull(rtxId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_095));
        if (!target.getCntrId().equals(cntrId)) {
            throw new BusinessException(LoanErrorCode.LOAN_095,
                    "cntrId mismatch: tx.cntrId=" + target.getCntrId() + ", path=" + cntrId);
        }
        if (!RepaymentTransaction.STATUS_SUCCESS.equals(target.getRtxStatusCd())) {
            throw new BusinessException(LoanErrorCode.LOAN_096,
                    "status=" + target.getRtxStatusCd());
        }
        if (Boolean.TRUE.equals(target.getReversalYn())) {
            throw new BusinessException(LoanErrorCode.LOAN_096,
                    "target is itself a reversal row");
        }

        // 3) 중복 reversal 차단 (빠른 사전 검사 — 실제 동시성 차단은 reversal row INSERT 시
        //    부분 UNIQUE 인덱스(ux_rtx_active_reversal_target)가 담당한다)
        if (txRepository.existsActiveReversal(target.getRtxId())) {
            throw new BusinessException(LoanErrorCode.LOAN_097);
        }

        // 4) 타입 분기 — 각 분기는 reversal row 를 INSERT+flush 한 뒤에만 환급 이체를 실행한다.
        //    (환급보다 row 를 먼저 확정해야 UNIQUE 제약이 동시/중복 요청의 이중 환급을 막는다)
        return switch (target.getRtxTypeCd()) {
            case RepaymentTransaction.TYPE_SCHEDULED -> reverseScheduled(target, req, idempotencyKey);
            case RepaymentTransaction.TYPE_EARLY     -> reverseEarly(target, req, idempotencyKey);
            default -> throw new BusinessException(LoanErrorCode.LOAN_096,
                    "type=" + target.getRtxTypeCd() + " (only SCHEDULED / EARLY supported)");
        };
    }

    private ReversalResponse reverseScheduled(RepaymentTransaction target, ReverseRepaymentRequest req,
                                              String idempotencyKey) {
        RepaymentTransaction reversal = saveReversalRow(target, idempotencyKey);
        refundIfNeeded(target);

        Long restoredRschId = null;
        if (target.getRschId() != null) {
            RepaymentSchedule schedule = scheduleRepository.findById(target.getRschId())
                    .filter(s -> s.getDeletedAt() == null)
                    .orElse(null);
            if (schedule != null && schedule.isPaid()) {
                String before = schedule.currentStatus();
                schedule.markDue();
                statusHistoryPublisher.publish(StatusChangeEvent.of(
                        DOMAIN_CD, TARGET_TABLE_CD, schedule.getRschId(),
                        before, RepaymentSchedule.STATUS_DUE,
                        REASON_REVERSED,
                        "reversalRtxId=" + reversal.getRtxId()
                                + (req.reversalReasonCd() == null ? "" : ", reason=" + req.reversalReasonCd())
                                + (req.reversalRemark()   == null ? "" : ", remark=" + req.reversalRemark()),
                        currentActor.currentActorId()
                ));
                restoredRschId = schedule.getRschId();
            }
        }
        return ReversalResponse.of(reversal, restoredRschId);
    }

    private ReversalResponse reverseEarly(RepaymentTransaction target, ReverseRepaymentRequest req,
                                          String idempotencyKey) {
        Long cntrId = target.getCntrId();

        // [EARLY-1] 대상 이후 또 다른 EARLY 가 없어야 함
        if (txRepository.existsLaterEarly(cntrId, target.getRtxId(), target.getPaidAt())) {
            throw new BusinessException(LoanErrorCode.LOAN_099,
                    "later EARLY exists after rtxId=" + target.getRtxId());
        }

        // [EARLY-2] V_new(=max) / V_prev(=max-1) 식별
        String vNew = scheduleRepository.findMaxVersion(cntrId);
        if (vNew == null || vNew.isBlank()) {
            throw new BusinessException(LoanErrorCode.LOAN_099, "no schedule version");
        }
        String vPrev = decrementVersion(vNew);
        if (vPrev == null) {
            throw new BusinessException(LoanErrorCode.LOAN_099,
                    "V_prev not derivable from V_new=" + vNew);
        }

        List<RepaymentSchedule> newRows = scheduleRepository
                .findByCntrIdAndRschVersionCdAndDeletedAtIsNullOrderByInstallmentNoAsc(cntrId, vNew);
        if (newRows.isEmpty()) {
            throw new BusinessException(LoanErrorCode.LOAN_099, "V_new=" + vNew + " has no rows");
        }

        // [EARLY-3] V_new 회차 전부 DUE/OVERDUE 여야 함
        for (RepaymentSchedule s : newRows) {
            String st = s.currentStatus();
            if (!RepaymentSchedule.STATUS_DUE.equals(st) && !RepaymentSchedule.STATUS_OVERDUE.equals(st)) {
                throw new BusinessException(LoanErrorCode.LOAN_099,
                        "V_new=" + vNew + " has non-active schedule: rschId="
                                + s.getRschId() + ", status=" + st);
            }
        }

        // [EARLY-4] V_prev 회차들 — SUPERSEDED 상태여야 부활 의미가 있음
        List<RepaymentSchedule> prevRows = scheduleRepository
                .findByCntrIdAndRschVersionCdAndDeletedAtIsNullOrderByInstallmentNoAsc(cntrId, vPrev);
        if (prevRows.isEmpty()) {
            throw new BusinessException(LoanErrorCode.LOAN_099, "V_prev=" + vPrev + " has no rows");
        }
        List<RepaymentSchedule> toRestore = prevRows.stream()
                .filter(s -> RepaymentSchedule.STATUS_SUPERSEDED.equals(s.currentStatus()))
                .toList();

        // 5) 새 reversal row 저장 (+flush) 후 환급 — 환급보다 row 를 먼저 확정한다
        RepaymentTransaction reversal = saveReversalRow(target, idempotencyKey);
        refundIfNeeded(target);

        // 6) V_new 회차들 → SUPERSEDED (publish per row)
        int supersededCount = 0;
        for (RepaymentSchedule s : newRows) {
            String before = s.currentStatus();
            s.markSuperseded();
            statusHistoryPublisher.publish(StatusChangeEvent.of(
                    DOMAIN_CD, TARGET_TABLE_CD, s.getRschId(),
                    before, RepaymentSchedule.STATUS_SUPERSEDED,
                    REASON_PREPAY_REVERSED,
                    "reversalRtxId=" + reversal.getRtxId() + ", supersededVersion=" + vNew,
                    currentActor.currentActorId()
            ));
            supersededCount++;
        }

        // 7) V_prev SUPERSEDED 회차들 → DUE 부활 (publish per row)
        int restoredCount = 0;
        for (RepaymentSchedule s : toRestore) {
            String before = s.currentStatus();
            s.markDue();
            statusHistoryPublisher.publish(StatusChangeEvent.of(
                    DOMAIN_CD, TARGET_TABLE_CD, s.getRschId(),
                    before, RepaymentSchedule.STATUS_DUE,
                    REASON_PREPAY_REVERSED,
                    "reversalRtxId=" + reversal.getRtxId() + ", restoredVersion=" + vPrev,
                    currentActor.currentActorId()
            ));
            restoredCount++;
        }

        return ReversalResponse.ofEarly(reversal, vNew, supersededCount, vPrev, restoredCount);
    }

    /**
     * reversal row 를 INSERT 하고 즉시 flush 한다.
     * 부분 UNIQUE 인덱스(ux_rtx_active_reversal_target) 위반 시 — 동시/중복 요청이 이미 활성
     * 역분개를 만든 경우 — LOAN_097 로 변환해 이중 환급을 DB 레벨에서 차단한다.
     */
    private RepaymentTransaction saveReversalRow(RepaymentTransaction target, String idempotencyKey) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            return txRepository.saveAndFlush(RepaymentTransaction.builder()
                    .cntrId(target.getCntrId())
                    .rschId(target.getRschId())
                    .rtxTypeCd(RepaymentTransaction.TYPE_REVERSAL)
                    .totalAmount(target.getTotalAmount())
                    .principalAmount(target.getPrincipalAmount())
                    .interestAmount(target.getInterestAmount())
                    .overdueInterestAmount(target.getOverdueInterestAmount())
                    .feeAmount(target.getFeeAmount())
                    .currencyCd(target.getCurrencyCd())
                    .channelCd(DEFAULT_CHANNEL)
                    .rtxStatusCd(RepaymentTransaction.STATUS_SUCCESS)
                    .paidAt(now)
                    .valueDate(null)
                    .balanceAfter(null)
                    .idempotencyKey(idempotencyKey)
                    .reversalYn(true)
                    .reversalTargetRtxId(target.getRtxId())
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(LoanErrorCode.LOAN_097);
        }
    }

    /**
     * 원 거래에 pi_id 가 있으면 payment-service 를 통해 자동 환급한다.
     * pi_id 가 없는 거래(창구 수납 등)는 환급이 수동 처리이므로 호출을 생략한다.
     * 반드시 reversal row INSERT+flush 가 끝난 뒤에 호출해야 한다.
     */
    private void refundIfNeeded(RepaymentTransaction target) {
        if (target.getPiId() != null) {
            requestRefund(target);
        }
    }

    /**
     * payment-service 환급 이체 호출.
     * senderAccountId = 은행 수납계좌, receiverAccountNo = 고객 상환계좌.
     * COMPLETED 이외 응답이면 LOAN_186 예외 (역분개 전체 트랜잭션 롤백).
     */
    private void requestRefund(RepaymentTransaction target) {
        RepaymentAccount account = repaymentAccountRepository
                .findByCntrIdAndDeletedAtIsNull(target.getCntrId())
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_080));

        String receiverAccountNo = cryptoService.decrypt(account.getAccountNoEnc());
        String receiverHolderName = account.getHolderNameEnc() != null
                ? cryptoService.decrypt(account.getHolderNameEnc())
                : account.getHolderNameMasked();

        // 호출자 멱등키를 섞지 않고 rtxId 로만 결정적으로 구성한다.
        // 키가 호출마다 달라지면 payment-service 멱등도 깨져 이중 환급을 막지 못한다.
        String refundIdemKey = "REV-" + target.getCntrId() + "-" + target.getRtxId();

        PaymentRequest req = new PaymentRequest(
                ServiceOperation.LOAN_REVERSE.code(),
                systemAccountProvider.collectionAccount().getAccountNo(),
                account.getBankCd(),
                receiverAccountNo,
                receiverHolderName,
                BigDecimal.valueOf(target.getTotalAmount()),
                "대출상환 역분개 환급",
                "대출환급",
                CHANNEL_OPEN_BANKING,
                String.valueOf(target.getCntrId())
        );

        PaymentResponse resp = paymentServiceClient.pay(refundIdemKey, req);
        if (resp == null || !PaymentResponse.STATUS_COMPLETED.equals(resp.status())) {
            String detail = resp != null
                    ? "status=" + resp.status() + ", failureCategory=" + resp.failureCategory()
                    : "payment-service 응답 없음";
            throw new BusinessException(LoanErrorCode.LOAN_186, detail);
        }
    }

    /** "V1" → null (V0 없음), "V2" → "V1", "V12" → "V11". 파싱 실패 또는 V1 이하면 null. */
    private String decrementVersion(String current) {
        if (current == null || current.length() < 2 || current.charAt(0) != 'V') return null;
        try {
            int n = Integer.parseInt(current.substring(1));
            return n <= 1 ? null : "V" + (n - 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
