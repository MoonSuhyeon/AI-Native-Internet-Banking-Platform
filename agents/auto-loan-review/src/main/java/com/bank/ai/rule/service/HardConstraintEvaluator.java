package com.bank.ai.rule.service;

import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.rule.config.RuleEngineProperties;
import com.bank.ai.rule.config.RuleEngineProperties.HardConstraints;
import com.bank.ai.rule.domain.HardFailReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoReviewRequest 의 hard constraint 위반 검출 — banking-review-llm §3 Step 4, §4.
 *
 * <p>발견된 모든 위반을 반환 (early-exit 안 함). 사유 다중인 경우 LLM 거절 통보에서 모두 인용 가능.
 *
 * <p>입력 필드가 {@code null} 이면 위반으로 판정하지 않음 — 누락 처리는 ML 모델의 missing 분기에
 * 위임. Hard fail 은 "명확히 기준 미달" 인 경우만 트리거.
 */
@Slf4j
@Service
public class HardConstraintEvaluator {

    private final HardConstraints limits;

    public HardConstraintEvaluator(RuleEngineProperties props) {
        this.limits = props.hardConstraints();
    }

    public List<HardFailReason> evaluate(AutoReviewRequest req) {
        List<HardFailReason> fails = new ArrayList<>();

        if (req.dsr() != null && req.dsr() > limits.dsrMax()) {
            fails.add(HardFailReason.DSR_EXCEEDED);
        }
        if (req.ltv() != null && req.ltv() > limits.ltvMax()) {
            fails.add(HardFailReason.LTV_EXCEEDED);
        }
        if (req.creditScoreProxy() != null && req.creditScoreProxy() < limits.creditScoreMin()) {
            fails.add(HardFailReason.CREDIT_SCORE_BELOW_MIN);
        }
        if (req.delinquencyHistory24m() != null
                && req.delinquencyHistory24m() > limits.delinquency24mMax()) {
            fails.add(HardFailReason.DELINQUENCY_24M_PRESENT);
        }
        if (req.age() != null && req.age() < limits.ageMin()) {
            fails.add(HardFailReason.AGE_BELOW_MIN);
        }

        if (!fails.isEmpty()) {
            log.info("hard fail detected: {} (dsr={}, ltv={}, score={}, delinq={}, age={})",
                    fails.stream().map(HardFailReason::code).toList(),
                    req.dsr(), req.ltv(), req.creditScoreProxy(),
                    req.delinquencyHistory24m(), req.age());
        }
        return fails;
    }
}
