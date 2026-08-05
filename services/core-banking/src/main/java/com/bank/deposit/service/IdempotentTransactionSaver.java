package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotentTransactionSaver {

    private final TransactionRepository transactionRepository;

    // REQUIRES_NEW: 외부 트랜잭션과 분리하여 save 실패 시 외부 트랜잭션 오염 방지
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction saveOrFetch(Transaction tx, String idempotencyKey, Long accountId) {
        try {
            return transactionRepository.save(tx);
        } catch (DataIntegrityViolationException e) {
            return transactionRepository
                    .findByIdempotencyKeyAndAccountId(idempotencyKey, accountId)
                    .orElseThrow(() -> e);
        }
    }
}
