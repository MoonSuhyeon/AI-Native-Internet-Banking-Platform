package com.bank.deposit.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessAuditRepository extends JpaRepository<AccessAudit, Long> {

    List<AccessAudit> findByTargetCustomerIdOrderByAccessedAtDesc(String targetCustomerId);

    List<AccessAudit> findByResultCodeOrderByAccessedAtDesc(String resultCode);
}
