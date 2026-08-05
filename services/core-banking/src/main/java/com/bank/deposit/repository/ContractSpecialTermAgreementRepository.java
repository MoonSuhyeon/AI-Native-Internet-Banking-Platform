package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.ContractSpecialTermAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractSpecialTermAgreementRepository extends JpaRepository<ContractSpecialTermAgreement, Long> {
    List<ContractSpecialTermAgreement> findByContractId(Long contractId);
    Optional<ContractSpecialTermAgreement> findByContractIdAndSpecialTermId(Long contractId, Long specialTermId);
}
