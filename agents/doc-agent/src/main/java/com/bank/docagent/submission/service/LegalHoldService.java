package com.bank.docagent.submission.service;

import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 법적 보존(Legal Hold) 서비스.
 * legal_hold=true 시 retention_until=null (무기한 보존).
 * 해제 시 현재 verify_status 기준으로 보존 기간 복원.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalHoldService {

    private static final int NORMAL_YEARS  = 5;
    private static final int FORGERY_YEARS = 10;

    private final DocumentSubmissionRepository submissionRepository;

    @Transactional
    public DocumentSubmission enable(UUID submissionId) {
        DocumentSubmission submission = findOrThrow(submissionId);
        submission.enableLegalHold();
        log.info("Legal Hold 설정: submissionId={}", submissionId);
        return submission;
    }

    @Transactional
    public DocumentSubmission disable(UUID submissionId) {
        DocumentSubmission submission = findOrThrow(submissionId);
        LocalDate restored = switch (submission.getVerifyStatus()) {
            case LOCKED         -> LocalDate.now().plusYears(FORGERY_YEARS);
            default             -> LocalDate.now().plusYears(NORMAL_YEARS);
        };
        submission.disableLegalHold(restored);
        log.info("Legal Hold 해제: submissionId={} restoredRetentionUntil={}", submissionId, restored);
        return submission;
    }

    private DocumentSubmission findOrThrow(UUID submissionId) {
        return submissionRepository.findById(submissionId)
            .orElseThrow(() -> new IllegalArgumentException("제출 건 없음: " + submissionId));
    }
}
