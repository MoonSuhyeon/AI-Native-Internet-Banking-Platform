package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PaymentScheduleRepository extends JpaRepository<PaymentSchedule, Long> {

    List<PaymentSchedule> findByContractIdOrderByPaymentRound(Long contractId);

    List<PaymentSchedule> findByContractIdAndStatus(Long contractId, PaymentStatus status);

    /** 자동이체: scheduled_date <= today이고 PENDING인 스케줄 */
    @Query("SELECT s FROM PaymentSchedule s WHERE s.isAutoTransfer = true AND s.status = :pending AND s.scheduledDate <= :today")
    List<PaymentSchedule> findAutoTransferDue(@Param("today") LocalDate today,
                                               @Param("pending") PaymentStatus pending);

    /** 수동 납입 지연: scheduled_date < today이고 PENDING인 스케줄 (당일은 유예) */
    @Query("SELECT s FROM PaymentSchedule s WHERE s.isAutoTransfer = false AND s.status = :pending AND s.scheduledDate < :today")
    List<PaymentSchedule> findManualOverdue(@Param("today") LocalDate today,
                                             @Param("pending") PaymentStatus pending);
}
