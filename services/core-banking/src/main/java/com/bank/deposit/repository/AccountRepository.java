package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.enums.AccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCustomerId(String customerId);
    List<Account> findByCustomerIdAndAccountStatus(String customerId, AccountStatus status);
    Optional<Account> findByAccountNumber(String accountNumber);
    Optional<Account> findByContractId(Long contractId);
    boolean existsByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountId = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") Long accountId);

    @Query(value = "select nextval('deposit_account_number_seq')", nativeQuery = true)
    Long nextAccountNumberSequenceValue();
}
