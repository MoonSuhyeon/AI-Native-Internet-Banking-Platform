package com.bank.loan.advisory.service;

import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.ReviewAdvisoryAck;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.observability.AdvisoryMetrics;
import com.bank.loan.advisory.repository.ReviewAdvisoryAckRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 어드바이저리 리포트 ack 등록 서비스. 한 리포트에 ack 가 여러 번 적재될 수 있으며,
 * 상태가 RESOLVED 인 리포트에는 새 ack 를 허용하지 않는다.
 *
 * 동작:
 *   1) 리포트 조회 (deleted_at IS NULL, RESOLVED 면 LOAN_191)
 *   2) ack row append (응답코드 + 사유 + 리마크 + before/after 결정 + clientIp/device + actor)
 *   3) 리포트 상태 전이: OPEN/VIEWED → ACKED
 */
@Service
@RequiredArgsConstructor
public class AdvisoryAckService {

    private final ReviewAdvisoryReportRepository reportRepo;
    private final ReviewAdvisoryAckRepository ackRepo;
    private final CurrentActorProvider currentActor;
    private final AdvisoryMetrics metrics;

    @Transactional
    public ReviewAdvisoryAck acknowledge(Long advrId, AdvisoryAckCommand cmd) {
        ReviewAdvisoryReport report = reportRepo.findById(advrId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_190));

        if (ReviewAdvisoryReport.STATUS_RESOLVED.equals(report.getAdvrStatusCd())) {
            throw new BusinessException(LoanErrorCode.LOAN_191,
                    "advrStatusCd=" + report.getAdvrStatusCd());
        }

        OffsetDateTime ackedAt = OffsetDateTime.now();
        Long ackerId = cmd.ackReviewerId() != null
                ? cmd.ackReviewerId()
                : currentActor.currentActorId();

        ReviewAdvisoryAck ack = ackRepo.save(ReviewAdvisoryAck.builder()
                .advrId(report.getAdvrId())
                .ackReviewerId(ackerId)
                .ackResponseCd(cmd.ackResponseCd())
                .decisionChangeYn(cmd.decisionChangeYn() != null ? cmd.decisionChangeYn() : "N")
                .ackReasonCd(cmd.ackReasonCd())
                .ackRemark(cmd.ackRemark())
                .beforeDecisionCd(cmd.beforeDecisionCd())
                .afterDecisionCd(cmd.afterDecisionCd())
                .ackedAt(ackedAt)
                .clientIp(cmd.clientIp())
                .device(cmd.device())
                .build());

        report.markAcked();
        metrics.incrementAckResponse(cmd.ackResponseCd());
        return ack;
    }

    @Builder
    public record AdvisoryAckCommand(
            String ackResponseCd,
            String decisionChangeYn,
            String ackReasonCd,
            String ackRemark,
            String beforeDecisionCd,
            String afterDecisionCd,
            Long ackReviewerId,
            String clientIp,
            String device
    ) {}
}
