package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.ProductSpecialTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductSpecialTermRepository extends JpaRepository<ProductSpecialTerm, Long> {
    List<ProductSpecialTerm> findByProductId(Long productId);
    boolean existsByProductIdAndSpecialTermId(Long productId, Long specialTermId);
    void deleteByProductIdAndSpecialTermId(Long productId, Long specialTermId);
}
