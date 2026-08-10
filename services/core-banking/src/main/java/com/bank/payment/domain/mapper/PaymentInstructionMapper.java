package com.bank.payment.domain.mapper;

import com.bank.payment.domain.PaymentInstruction;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface PaymentInstructionMapper {

    void insert(PaymentInstruction paymentInstruction);

    PaymentInstruction selectById(@Param("paymentInstructionId") String paymentInstructionId);

    int updateStatus(@Param("paymentInstructionId") String paymentInstructionId,
                     @Param("status") String status,
                     @Param("completedAt") OffsetDateTime completedAt,
                     @Param("failureCategory") String failureCategory,
                     @Param("version") Integer version);

    void updateReceiverHolderSnap(@Param("paymentInstructionId") String paymentInstructionId,
                                  @Param("receiverHolderNameSnap") String receiverHolderNameSnap,
                                  @Param("holderInquiryAt") OffsetDateTime holderInquiryAt);

    void updateNextTimeoutAt(@Param("piId") String piId,
                             @Param("nextTimeoutAt") OffsetDateTime nextTimeoutAt);

    List<PaymentInstruction> selectTimedOut();

    List<PaymentInstruction> selectDueScheduled();

    int claimScheduledForExecution(@Param("paymentInstructionId") String paymentInstructionId,
                                   @Param("version") Integer version);

    int cancelScheduledForUser(@Param("paymentInstructionId") String paymentInstructionId,
                               @Param("version") Integer version);

    int countIncomplete();

    /**
     * 고객의 당일 이체 누적액 — 인터넷뱅킹 이체한도 판정용.
     *
     * <p>계좌가 아니라 고객으로 묶는다. 한도가 고객당이므로 계좌로 집계하면 계좌를
     * 두 개 가진 사람이 한도를 두 배로 쓴다.
     */
    long sumDailyTransferAmount(@Param("senderUserId") String senderUserId,
                                @Param("businessDate") String businessDate,
                                @Param("excludePiId") String excludePiId);

    int updateScheduled(@Param("paymentInstructionId") String paymentInstructionId,
                        @Param("scheduledExecutionAt") OffsetDateTime scheduledExecutionAt,
                        @Param("version") Integer version);

    List<PaymentInstruction> selectByReceiverAccountNo(@Param("receiverAccountNo") String receiverAccountNo);
}
