package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.enums.ProductStatus;
import com.bank.deposit.domain.enums.ProductType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByProductType(ProductType productType);
    List<Product> findByProductStatus(ProductStatus productStatus);
    List<Product> findByProductTypeAndProductStatus(ProductType productType, ProductStatus productStatus);

    /**
     * 가입 금액 조건 필터링을 DB에서 처리 — 전체 메모리 로딩 방지.
     * min/max 가 null 인 상품(제한 없음)도 포함한다.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.productStatus = 'SELLING'
              AND (p.minJoinAmount IS NULL OR p.minJoinAmount <= :amount)
              AND (p.maxJoinAmount IS NULL OR p.maxJoinAmount >= :amount)
            ORDER BY p.baseInterestRate DESC
            """)
    List<Product> findSellingProductsByJoinAmount(@Param("amount") BigDecimal amount);
}
