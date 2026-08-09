package com.bank.loan.advisory.dto;

import java.util.Map;

/**
 * 심사관별 ack 응답 분포 통계.
 *   - totalReports: 본 심사관 대상 발행된 리포트 총 건수
 *   - ackResponseCounts: ack 응답코드별 카운트 (MAINTAIN/OVERTURN/ESCALATE/NEEDS_MORE_INFO)
 *   - unresolvedCount: OPEN/VIEWED 상태(미해결) 리포트 수
 *   - ruleTriggerCounts: 룰코드별 트리거 빈도
 */
public record ReviewerAckStatsResponse(
        Long reviewerId,
        long totalReports,
        long unresolvedCount,
        Map<String, Long> ackResponseCounts,
        Map<String, Long> ruleTriggerCounts
) {}
