package com.bank.loan.product.preferential.repository;

import com.bank.loan.product.preferential.domain.PreferentialRatePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreferentialRatePolicyRepository extends JpaRepository<PreferentialRatePolicy, Long> {

    boolean existsByProdIdAndConditionCdAndActiveYnAndDeletedAtIsNull(
            Long prodId, String conditionCd, Boolean activeYn);

    List<PreferentialRatePolicy> findAllByProdIdAndDeletedAtIsNullOrderByPolicyIdAsc(Long prodId);
}
