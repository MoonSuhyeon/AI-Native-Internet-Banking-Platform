package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.DepositProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepositProductRepository extends JpaRepository<DepositProduct, Long> {
    Optional<DepositProduct> findByProductId(Long productId);
    boolean existsByProductId(Long productId);
}
