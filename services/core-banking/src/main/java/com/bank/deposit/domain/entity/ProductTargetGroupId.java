package com.bank.deposit.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class ProductTargetGroupId implements Serializable {

    @Column(name = "banking_product_id")
    private Long productId;

    @Column(name = "target_group_id")
    private Long targetGroupId;
}
