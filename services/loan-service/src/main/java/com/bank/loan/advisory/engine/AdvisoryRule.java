package com.bank.loan.advisory.engine;

import java.util.List;

/**
 * 어드바이저리 트리거 룰 인터페이스. 구현체는 `@Component` 빈으로 등록되어
 * `AdvisoryEvaluator` 가 mode 분기에 따라 호출한다.
 *
 * 구현 가이드:
 *   - {@link #ruleCd()} 는 `REVIEW_ADVISORY_RULE.rule_cd` 와 일치해야 한다. 평가 시점에
 *     활성 마스터가 존재하지 않으면 evaluator 가 평가를 건너뛴다.
 *   - {@link #supports(EvaluationMode)} 로 SYNC/BATCH 중 지원 모드를 선언한다.
 *   - {@link #evaluate(RuleContext)} 는 부수효과 없는 *조회·계산* 만 수행하고, 영속화는
 *     evaluator 가 담당한다. 예외 발생 시 evaluator 가 해당 룰만 격리해 무시한다.
 */
public interface AdvisoryRule {

    String ruleCd();

    boolean supports(EvaluationMode mode);

    List<RuleResult> evaluate(RuleContext context);
}
