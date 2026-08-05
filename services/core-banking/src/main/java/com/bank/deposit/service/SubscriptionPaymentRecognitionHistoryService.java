package com.bank.deposit.service;

import com.bank.deposit.domain.entity.SubscriptionPaymentRecognitionHistory;
import com.bank.deposit.domain.enums.RecognitionStatus;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.SubscriptionPaymentRecognitionHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionPaymentRecognitionHistoryService {

    private final SubscriptionPaymentRecognitionHistoryRepository repository;

    public List<SubscriptionPaymentRecognitionHistory> findByContractId(Long contractId) {
        return repository.findByContractId(contractId);
    }

    public List<SubscriptionPaymentRecognitionHistory> findByContractIdAndStatus(
            Long contractId, RecognitionStatus status) {
        return repository.findByContractIdAndRecognitionStatus(contractId, status);
    }

    public SubscriptionPaymentRecognitionHistory findById(Long recognitionId) {
        return repository.findById(recognitionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "청약 납입 인정 이력을 찾을 수 없습니다. id=" + recognitionId));
    }

    @Transactional
    public SubscriptionPaymentRecognitionHistory save(SubscriptionPaymentRecognitionHistory history) {
        return repository.save(history);
    }
}
