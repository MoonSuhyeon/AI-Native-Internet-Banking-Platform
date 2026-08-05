package com.bank.payment.domain.mapper;

import com.bank.payment.domain.PaymentInstruction;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentInstructionMapper {

    void insert(PaymentInstruction paymentInstruction);

    PaymentInstruction selectById(@Param("paymentInstructionId") String paymentInstructionId);

    int updateStatus(@Param("paymentInstructionId") String paymentInstructionId,
                     @Param("status") String status,
                     @Param("completedAt") LocalDateTime completedAt,
                     @Param("failureCategory") String failureCategory,
                     @Param("version") Integer version);

    void updateReceiverHolderSnap(@Param("paymentInstructionId") String paymentInstructionId,
                                  @Param("receiverHolderNameSnap") String receiverHolderNameSnap,
                                  @Param("holderInquiryAt") LocalDateTime holderInquiryAt);

    void updateNextTimeoutAt(@Param("piId") String piId,
                             @Param("nextTimeoutAt") LocalDateTime nextTimeoutAt);

    List<PaymentInstruction> selectTimedOut();

    List<PaymentInstruction> selectDueScheduled();

    int claimScheduledForExecution(@Param("paymentInstructionId") String paymentInstructionId,
                                   @Param("version") Integer version);

    int cancelScheduledForUser(@Param("paymentInstructionId") String paymentInstructionId,
                               @Param("version") Integer version);

    int countIncomplete();

    int updateScheduled(@Param("paymentInstructionId") String paymentInstructionId,
                        @Param("scheduledExecutionAt") LocalDateTime scheduledExecutionAt,
                        @Param("version") Integer version);

    List<PaymentInstruction> selectByReceiverAccountNo(@Param("receiverAccountNo") String receiverAccountNo);
}
