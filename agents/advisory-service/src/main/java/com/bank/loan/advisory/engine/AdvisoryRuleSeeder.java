package com.bank.loan.advisory.engine;

import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 어드바이저리 룰 마스터(`REVIEW_ADVISORY_RULE`) 시드. 멱등 — 이미 활성 row 가 존재하면 skip.
 * 코드 빈으로 등록된 {@link AdvisoryRule} 구현체가 평가되려면 같은 rule_cd 의 활성 마스터 row 가
 * 있어야 한다(`AdvisoryEvaluator` 가 active_yn='Y' 인 마스터만 인정).
 */
@Slf4j
@Component
@Order(110)
@RequiredArgsConstructor
public class AdvisoryRuleSeeder implements ApplicationRunner {

    private final ReviewAdvisoryRuleRepository ruleRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int inserted = 0;
        for (RuleSeed seed : SEED) {
            if (ruleRepo.findByRuleCdAndDeletedAtIsNull(seed.ruleCd()).isPresent()) {
                continue;
            }
            ruleRepo.save(ReviewAdvisoryRule.builder()
                    .ruleCd(seed.ruleCd())
                    .ruleName(seed.ruleName())
                    .advisoryTypeCd(seed.advisoryTypeCd())
                    .ruleCategoryCd(seed.ruleCategoryCd())
                    .severityCd(seed.severityCd())
                    .ruleVersion(seed.ruleVersion())
                    .activeYn(ReviewAdvisoryRule.ACTIVE_Y)
                    .ruleDesc(seed.ruleDesc())
                    .build());
            inserted++;
        }
        if (inserted > 0) {
            log.info("어드바이저리 룰 마스터 시드 적재 완료 ({}건 추가)", inserted);
        }
    }

    private record RuleSeed(
            String ruleCd, String ruleName,
            String advisoryTypeCd, String ruleCategoryCd, String severityCd,
            String ruleVersion, String ruleDesc) {}

    private static final List<RuleSeed> SEED = List.of(
            new RuleSeed("DSR_THRESHOLD_OVERRIDE", "DSR 한도 초과 승인",
                    "REREVIEW_RECOMMEND", "THRESHOLD_VIOLATION", "CRITICAL",
                    "v1.0", "DSR_CALCULATION.dsr_status_cd=FAIL 인데 본심사가 승인된 경우"),
            new RuleSeed("LTV_THRESHOLD_OVERRIDE", "LTV 한도 초과 승인",
                    "REREVIEW_RECOMMEND", "THRESHOLD_VIOLATION", "CRITICAL",
                    "v1.0", "LTV_CALCULATION.ltv_status_cd=FAIL 인데 본심사가 승인된 경우"),
            new RuleSeed("BIAS_REJECT_RATE_DEVIATION", "심사관 거절율 편차",
                    "BIAS_DETECTION", "REVIEWER_DEVIATION", "WARN",
                    "v1.0", "코호트 거절율이 동료 평균 대비 +2σ 초과 (최소 표본 30)"),
            new RuleSeed("BIAS_APPROVAL_RATE_DEVIATION", "심사관 승인율 편차",
                    "BIAS_DETECTION", "REVIEWER_DEVIATION", "WARN",
                    "v1.0", "코호트 승인율이 동료 평균 대비 -2σ 미만 (최소 표본 30)"),
            new RuleSeed("PEER_DECISION_DIVERGENCE", "유사 신청자 결정 분기",
                    "REREVIEW_RECOMMEND", "PEER_DIVERGENCE", "WARN",
                    "v1.0", "유사 프로파일(신용±5점/DSR±500/LTV±500) 90일 그룹 70:30 분기에서 본 건이 소수 결정")
    );
}
