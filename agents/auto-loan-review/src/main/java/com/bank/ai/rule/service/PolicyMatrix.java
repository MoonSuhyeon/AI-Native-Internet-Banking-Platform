package com.bank.ai.rule.service;

import com.bank.ai.rule.config.RuleEngineProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * (product, segment) → PD 임계치 lookup.
 *
 * <p>matrix 에 명시된 cell 우선, 없으면 {@code defaultPdThreshold} fallback.
 * lookup 결과는 RuleEngine 의 트랙 분기 + Track 1 safety margin 계산에 사용.
 *
 * <p>정책 변경은 application.yml 수정 + 재기동 (코드 변경 없음).
 * 신용정책위 분기 리뷰 일정과 매핑.
 */
@Slf4j
@Service
public class PolicyMatrix {

    private final RuleEngineProperties props;

    public PolicyMatrix(RuleEngineProperties props) {
        this.props = props;
    }

    /**
     * @param productCode 예: MORT_001, CRED_001 (LoanProduct.productCode)
     * @param segment     예: regular / precarious / senior / young / self_employed
     * @return 해당 cell 의 PD 임계치. 미정의 시 {@link RuleEngineProperties#defaultPdThreshold()}.
     */
    public double lookup(String productCode, String segment) {
        Map<String, Double> byProduct = props.pdThresholdMatrix().get(productCode);
        if (byProduct != null) {
            Double cell = byProduct.get(segment);
            if (cell != null) {
                return cell;
            }
        }
        log.debug("matrix miss for ({}, {}) — using default {}",
                productCode, segment, props.defaultPdThreshold());
        return props.defaultPdThreshold();
    }

    /**
     * Track 1 자동승인 진입을 위한 안전여유 임계치 = lookup × safetyMarginRatio.
     * pd ≤ 본 값 이면 Track 1, 그 외 pd ≤ lookup 이면 Track 3.
     */
    public double safetyMarginThreshold(String productCode, String segment) {
        return lookup(productCode, segment) * props.safetyMarginRatio();
    }

    /**
     * decision 모델 강한 승인 임계 (plan §5.3 결합 분기 — Track 1 진입 조건의 한 축).
     * 현재는 product/segment 무관 단일 값. 신용정책위 결정 시 매트릭스화 가능.
     */
    public double decisionStrongThreshold(String productCode, String segment) {
        return props.decisionStrongThreshold();
    }

    /**
     * decision 모델 약한 신뢰 임계 (plan §5.3 결합 분기 — Track 2 보조 사유).
     */
    public double decisionRejectThreshold(String productCode, String segment) {
        return props.decisionRejectThreshold();
    }
}
