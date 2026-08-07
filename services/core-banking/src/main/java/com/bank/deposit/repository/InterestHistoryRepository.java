package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.InterestHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InterestHistoryRepository extends JpaRepository<InterestHistory, Long> {
    List<InterestHistory> findByContractIdOrderByInterestPaidAtDesc(Long contractId);
    List<InterestHistory> findByAccountIdOrderByInterestPaidAtDesc(Long accountId);

    @Query("SELECT COALESCE(SUM(h.interestAfterTax), 0) FROM InterestHistory h WHERE h.contractId = :contractId")
    Long sumInterestAfterTaxByContractId(Long contractId);
}
