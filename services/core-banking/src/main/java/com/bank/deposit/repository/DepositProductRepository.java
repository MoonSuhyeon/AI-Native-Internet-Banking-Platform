package com.bank.deposit.repository;

import java.util.List;

import com.bank.deposit.domain.entity.DepositProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepositProductRepository extends JpaRepository<DepositProduct, Long> {
    Optional<DepositProduct> findByProductId(Long productId);
    boolean existsByProductId(Long productId);

    /** 상품 여러 건의 예금 상세를 한 번에. */
    List<DepositProduct> findByProductIdIn(List<Long> productIds);
}
