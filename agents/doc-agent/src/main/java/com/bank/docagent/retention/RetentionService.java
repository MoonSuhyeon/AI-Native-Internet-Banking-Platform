package com.bank.docagent.retention;

import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.VerifyStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 문서 보존 기간 정책.
 * <ul>
 *   <li>AUTO_PASS / CLEARED / NEEDS_RESUBMIT → 5년 (정상·재제출)</li>
 *   <li>LOCKED (CONFIRMED_FORGERY) → 10년 (의심 건)</li>
 *   <li>HOLD → 심사원 결정 후 재계산</li>
 *   <li>legal_hold = true → 무기한 (retention_until = null)</li>
 * </ul>
 */
@Slf4j
@Service
public class RetentionService {

    private static final int NORMAL_YEARS  = 5;
    private static final int FORGERY_YEARS = 10;

    public void applyRetention(DocumentSubmission submission, VerifyStatus status) {
        if (submission.isLegalHold()) {
            // legal_hold 중에는 보존 기간 변경하지 않음 (무기한)
            return;
        }

        LocalDate until = switch (status) {
            case LOCKED                         -> LocalDate.now().plusYears(FORGERY_YEARS);
            case AUTO_PASS, CLEARED,
                 NEEDS_RESUBMIT, PENDING, HOLD  -> LocalDate.now().plusYears(NORMAL_YEARS);
        };

        submission.updateRetentionUntil(until);
        log.info("보존 기간 설정: submissionId={} status={} retentionUntil={}",
            submission.getSubmissionId(), status, until);
    }
}
