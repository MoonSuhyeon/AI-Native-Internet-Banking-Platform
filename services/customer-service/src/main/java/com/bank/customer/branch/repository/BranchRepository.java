package com.bank.customer.branch.repository;

import com.bank.customer.branch.domain.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    /**
     * 지점명·지역으로 찾는다.
     *
     * <p>둘을 한 조건으로 묶는 이유는, 고객이 "강남" 이라고 쳤을 때 그것이 지점명인지
     * 지역인지 구분해 입력하지 않기 때문이다.
     */
    @Query("""
            SELECT b FROM Branch b
            WHERE b.active = true
              AND (:keyword IS NULL
                   OR LOWER(b.branchName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.region)     LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY b.region, b.branchName
            """)
    List<Branch> search(@Param("keyword") String keyword);
}
