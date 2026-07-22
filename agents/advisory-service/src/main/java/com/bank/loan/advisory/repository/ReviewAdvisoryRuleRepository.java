package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewAdvisoryRuleRepository extends JpaRepository<ReviewAdvisoryRule, Long> {

    Optional<ReviewAdvisoryRule> findByRuleCdAndDeletedAtIsNull(String ruleCd);

    List<ReviewAdvisoryRule> findByActiveYnAndDeletedAtIsNullOrderByRuleCdAsc(String activeYn);

    List<ReviewAdvisoryRule> findByAdvisoryTypeCdAndActiveYnAndDeletedAtIsNullOrderByRuleCdAsc(
            String advisoryTypeCd, String activeYn);
}
