package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.TermApplicationManagement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TermApplicationManagementRepository extends JpaRepository<TermApplicationManagement, Long> {

    List<TermApplicationManagement> findByBusinessTypeCode(String businessTypeCode);

    List<TermApplicationManagement> findByCommonTermId(Long commonTermId);

    List<TermApplicationManagement> findByIsRequired(String isRequired);
}
