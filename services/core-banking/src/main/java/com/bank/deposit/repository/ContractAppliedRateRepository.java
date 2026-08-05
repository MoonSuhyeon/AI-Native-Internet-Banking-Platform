package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.ContractAppliedRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContractAppliedRateRepository extends JpaRepository<ContractAppliedRate, Long> {
    List<ContractAppliedRate> findByContractId(Long contractId);
}
