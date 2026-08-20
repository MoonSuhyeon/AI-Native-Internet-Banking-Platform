package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.EarlyTerminationRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EarlyTerminationRateRepository extends JpaRepository<EarlyTerminationRate, Long> {

    /**
     * 해당 상품에 적용할 정책 구간들.
     *
     * <p>상품 전용 구간이 있으면 그것만, 없으면 기본값(product_type IS NULL)을 쓴다.
     * 둘을 섞으면 한 경과비율에 두 구간이 걸려 어떤 이율이 나올지 순서에 달리게 된다.
     */
    @Query("""
            SELECT r FROM EarlyTerminationRate r
            WHERE r.productType = :productType
            ORDER BY r.elapsedRatioFrom
            """)
    List<EarlyTerminationRate> findForProductType(@Param("productType") String productType);

    @Query("""
            SELECT r FROM EarlyTerminationRate r
            WHERE r.productType IS NULL
            ORDER BY r.elapsedRatioFrom
            """)
    List<EarlyTerminationRate> findDefaults();
}
