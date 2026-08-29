package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.InterestHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InterestHistoryRepository extends JpaRepository<InterestHistory, Long> {
    List<InterestHistory> findByContractIdOrderByInterestPaidAtDesc(Long contractId);
    List<InterestHistory> findByAccountIdOrderByInterestPaidAtDesc(Long accountId);

    /** 고객의 계좌 여러 건에 대한 이자 내역을 한 번에. 계좌마다 부르면 질의가 계좌 수만큼 는다. */
    List<InterestHistory> findByAccountIdInOrderByInterestIdDesc(List<Long> accountIds);

    @Query("SELECT COALESCE(SUM(h.interestAfterTax), 0) FROM InterestHistory h WHERE h.contractId = :contractId")
    Long sumInterestAfterTaxByContractId(Long contractId);
}
