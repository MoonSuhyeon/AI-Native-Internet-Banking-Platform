package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.enums.ContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractRepository extends JpaRepository<Contract, Long> {
    List<Contract> findByCustomerId(String customerId);
    List<Contract> findByCustomerIdAndContractStatus(String customerId, ContractStatus status);
    List<Contract> findByContractStatus(ContractStatus status);
    Optional<Contract> findByContractNumber(String contractNumber);
    boolean existsByContractNumber(String contractNumber);
}
