package com.bank.loan.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoanLedgerReconciliationRepository extends JpaRepository<LoanLedgerReconciliation, Long> {

    Optional<LoanLedgerReconciliation> findByBaseDate(String baseDate);
}
