package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.SubscriptionPaymentRecognitionHistory;
import com.bank.deposit.domain.enums.RecognitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionPaymentRecognitionHistoryRepository extends JpaRepository<SubscriptionPaymentRecognitionHistory, Long> {
    List<SubscriptionPaymentRecognitionHistory> findByContractId(Long contractId);
    List<SubscriptionPaymentRecognitionHistory> findByContractIdAndRecognitionStatus(Long contractId, RecognitionStatus status);
}
