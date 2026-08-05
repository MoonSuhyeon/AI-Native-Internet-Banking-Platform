package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "banking_deposit_product_special_terms")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductSpecialTerm extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "deposit_product_special_term_id")
    private Long productSpecialTermId;

    @Column(name = "banking_product_id", nullable = false)
    private Long productId;

    @Column(name = "special_term_id", nullable = false)
    private Long specialTermId;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private Boolean isRequired = false;
}
