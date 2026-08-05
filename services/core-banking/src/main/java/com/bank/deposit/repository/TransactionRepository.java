package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.DirectionType;
import com.bank.deposit.domain.enums.TransactionStatus;
import com.bank.deposit.domain.enums.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findByAccountId(Long accountId, Pageable pageable);
    Page<Transaction> findByAccountIdAndTransactionAtBetween(Long accountId, OffsetDateTime start, OffsetDateTime end, Pageable pageable);
    List<Transaction> findByContractIdAndTransactionType(Long contractId, TransactionType type);
    Optional<Transaction> findByTransactionNumber(String transactionNumber);
    Optional<Transaction> findByIdempotencyKeyAndAccountId(String idempotencyKey, Long accountId);
    List<Transaction> findByAccountIdInAndTransactionAtBetweenAndStatus(
            List<Long> accountIds, OffsetDateTime start, OffsetDateTime end, TransactionStatus status);

    Page<Transaction> findByAccountIdIn(List<Long> accountIds, Pageable pageable);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.directionType = :directionType " +
           "AND t.transactionAt >= :start AND t.transactionAt < :end " +
           "AND t.status <> com.bank.deposit.domain.enums.TransactionStatus.CANCELED")
    BigDecimal sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(
            @Param("accountId") Long accountId,
            @Param("directionType") DirectionType directionType,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);

    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.directionType = :directionType " +
           "AND t.transactionAt >= :start AND t.transactionAt < :end " +
           "AND t.status <> com.bank.deposit.domain.enums.TransactionStatus.CANCELED")
    long countByAccountIdAndDirectionTypeAndTransactionAtBetween(
            @Param("accountId") Long accountId,
            @Param("directionType") DirectionType directionType,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);
}
