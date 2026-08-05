package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.SubscriptionProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionProductRepository extends JpaRepository<SubscriptionProduct, Long> {
}
