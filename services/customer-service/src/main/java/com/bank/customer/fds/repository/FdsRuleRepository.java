package com.bank.customer.fds.repository;

import com.bank.customer.fds.domain.FdsRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FdsRuleRepository extends JpaRepository<FdsRule, Long> {

    List<FdsRule> findByFdsRuleTargetEventCodeAndFdsRuleActiveYnAndDeletedAtIsNull(
            String targetEventCode, Boolean activeYn);

    boolean existsByFdsRuleCodeAndDeletedAtIsNull(String ruleCode);
}
