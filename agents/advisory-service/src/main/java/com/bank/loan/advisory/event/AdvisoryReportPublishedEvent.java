package com.bank.loan.advisory.event;

/**
 * 어드바이저리 리포트 발행 이벤트. 배치 평가 후 발행되며,
 * 알림(메일/푸시) 핸들러가 대상 심사관에게 통지하는 데 사용된다.
 *
 * 핸들러는 본 패키지 외부(notification 모듈 등)에서 등록.
 */
public record AdvisoryReportPublishedEvent(
        Long advrId,
        Long targetReviewerId,
        String severityCd,
        String baseDate
) {}
