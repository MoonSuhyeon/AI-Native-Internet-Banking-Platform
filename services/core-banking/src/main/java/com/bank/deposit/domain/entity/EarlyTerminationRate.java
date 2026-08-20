package com.bank.deposit.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 중도해지 이율 정책 한 구간.
 *
 * <p>경과 비율(계약기간 대비 실제 보유기간)에 따라 약정이율의 몇 %를 주는지 정한다.
 * 이 값은 AXful Bank 의 정책이며 은행마다 다르다 — 그래서 코드가 아니라 표에 둔다.
 */
@Getter
@Entity
@Table(name = "early_termination_rate")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class EarlyTerminationRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rate_id")
    private Long rateId;

    /** NULL 이면 모든 상품에 적용되는 기본값. */
    @Column(name = "product_type", length = 30)
    private String productType;

    @Column(name = "elapsed_ratio_from", nullable = false, precision = 4, scale = 3)
    private BigDecimal elapsedRatioFrom;

    @Column(name = "elapsed_ratio_to", nullable = false, precision = 4, scale = 3)
    private BigDecimal elapsedRatioTo;

    @Column(name = "rate_multiplier", nullable = false, precision = 4, scale = 3)
    private BigDecimal rateMultiplier;

    /** 최저보장이율(연 %). 약정이율에 배수를 곱한 값이 이보다 낮으면 이 값을 준다. */
    @Column(name = "min_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal minRate;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    /** 구간 [from, to) 에 드는가. 끝값을 열어 두어 구간이 겹치지 않게 한다. */
    public boolean covers(BigDecimal elapsedRatio) {
        return elapsedRatio.compareTo(elapsedRatioFrom) >= 0
                && elapsedRatio.compareTo(elapsedRatioTo) < 0;
    }
}
