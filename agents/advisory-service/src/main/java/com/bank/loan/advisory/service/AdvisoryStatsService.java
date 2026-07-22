package com.bank.loan.advisory.service;

import com.bank.loan.advisory.domain.ReviewAdvisoryAck;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.dto.ReviewerAckStatsResponse;
import com.bank.loan.advisory.repository.ReviewAdvisoryAckRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 어드바이저리 운영 통계 서비스. (감사 / 운영자 도구)
 *   - 본 reviewer 대상 발행 리포트 총수 + 미해결(OPEN/VIEWED) 수
 *   - ack 응답코드별 카운트 (MAINTAIN/OVERTURN/ESCALATE/NEEDS_MORE_INFO)
 *   - 룰코드별 트리거 빈도
 */
@Service
@RequiredArgsConstructor
public class AdvisoryStatsService {

    private final ReviewAdvisoryReportRepository reportRepo;
    private final ReviewAdvisoryAckRepository ackRepo;
    private final ReviewAdvisoryRuleRepository ruleRepo;

    @Transactional(readOnly = true)
    public ReviewerAckStatsResponse statsForReviewer(Long reviewerId) {
        List<ReviewAdvisoryReport> reports = reportRepo
                .findByTargetReviewerIdAndDeletedAtIsNullOrderByGeneratedAtDesc(reviewerId);

        long total = reports.size();
        long unresolved = reports.stream()
                .filter(r -> ReviewAdvisoryReport.STATUS_OPEN.equals(r.getAdvrStatusCd())
                        || ReviewAdvisoryReport.STATUS_VIEWED.equals(r.getAdvrStatusCd()))
                .count();

        if (reports.isEmpty()) {
            return new ReviewerAckStatsResponse(reviewerId, 0L, 0L,
                    Collections.emptyMap(), Collections.emptyMap());
        }

        Set<Long> ruleIds = reports.stream().map(ReviewAdvisoryReport::getRuleId).collect(Collectors.toSet());
        Map<Long, String> ruleCdMap = ruleRepo.findAllById(ruleIds).stream()
                .collect(Collectors.toMap(ReviewAdvisoryRule::getRuleId, ReviewAdvisoryRule::getRuleCd));

        Map<String, Long> ruleTriggerCounts = reports.stream()
                .map(r -> ruleCdMap.getOrDefault(r.getRuleId(), "UNKNOWN"))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<Long> advrIds = reports.stream().map(ReviewAdvisoryReport::getAdvrId).toList();
        List<ReviewAdvisoryAck> acks = ackRepo.findByAdvrIdInOrderByAckedAtAsc(advrIds);
        Map<String, Long> ackResponseCounts = acks.stream()
                .collect(Collectors.groupingBy(ReviewAdvisoryAck::getAckResponseCd, Collectors.counting()));

        return new ReviewerAckStatsResponse(reviewerId, total, unresolved,
                ackResponseCounts, ruleTriggerCounts);
    }
}
