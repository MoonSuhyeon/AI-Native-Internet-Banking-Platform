package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.SpecialTerm;
import com.bank.deposit.domain.enums.SpecialTermStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpecialTermRepository extends JpaRepository<SpecialTerm, Long> {
    List<SpecialTerm> findByStatus(SpecialTermStatus status);
}
