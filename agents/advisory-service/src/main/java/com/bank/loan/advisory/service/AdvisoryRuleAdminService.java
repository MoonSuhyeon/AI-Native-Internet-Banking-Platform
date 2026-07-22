package com.bank.loan.advisory.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.dto.AdvisoryRuleResponse;
import com.bank.loan.advisory.dto.UpdateAdvisoryRuleRequest;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 어드바이저리 룰 마스터 관리(읽기 + admin 변경) 서비스.
 * 변경 시 `STATUS_HISTORY` 에 BEFORE/AFTER 스냅샷을 적재해 사후 감사 가능.
 */
@Service
@RequiredArgsConstructor
public class AdvisoryRuleAdminService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE = "REVIEW_ADVISORY_RULE";
    private static final String DEFAULT_REASON = "RULE_UPDATE";

    private final ReviewAdvisoryRuleRepository ruleRepo;
    private final StatusHistoryPublisher statusHistory;
    private final CurrentActorProvider currentActor;

    @Transactional(readOnly = true)
    public List<AdvisoryRuleResponse> listAll() {
        return ruleRepo.findAll().stream()
                .filter(r -> r.getDeletedAt() == null)
                .sorted(Comparator.comparing(ReviewAdvisoryRule::getRuleCd))
                .map(AdvisoryRuleResponse::of)
                .toList();
    }

    @Transactional
    public AdvisoryRuleResponse update(Long ruleId, UpdateAdvisoryRuleRequest req) {
        ReviewAdvisoryRule rule = ruleRepo.findById(ruleId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_190,
                        "rule not found ruleId=" + ruleId));

        String beforeActive = rule.getActiveYn();
        String beforeSnapshot = snapshot(rule);

        if (req.activeYn() != null && !req.activeYn().isBlank()) {
            if (ReviewAdvisoryRule.ACTIVE_Y.equals(req.activeYn())) rule.activate();
            else if (ReviewAdvisoryRule.ACTIVE_N.equals(req.activeYn())) rule.deactivate();
        }
        rule.updateMeta(
                req.ruleParams(),
                req.ruleVersion(),
                emptyToNull(req.effectiveStartDate()),
                emptyToNull(req.effectiveEndDate()),
                req.ruleDesc()
        );

        String afterSnapshot = snapshot(rule);
        String reason = (req.changeReasonCd() != null && !req.changeReasonCd().isBlank())
                ? req.changeReasonCd() : DEFAULT_REASON;
        String remark = "before={" + beforeSnapshot + "} after={" + afterSnapshot + "}"
                + (req.changeRemark() != null ? " | " + req.changeRemark() : "");

        statusHistory.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE, ruleId,
                beforeActive, rule.getActiveYn(),
                reason, remark,
                currentActor.currentActorId()));

        return AdvisoryRuleResponse.of(rule);
    }

    private static String snapshot(ReviewAdvisoryRule r) {
        return String.format("active=%s,version=%s,effective=%s~%s,paramsLen=%d",
                r.getActiveYn(), r.getRuleVersion(),
                r.getEffectiveStartDate() == null ? "-" : r.getEffectiveStartDate(),
                r.getEffectiveEndDate() == null ? "-" : r.getEffectiveEndDate(),
                r.getRuleParams() == null ? 0 : r.getRuleParams().length());
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
