package com.bank.loan.document.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.document.domain.LoanDocument;
import com.bank.loan.document.repository.LoanDocumentRepository;
import com.bank.loan.document.repository.LoanDocumentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * doc-agent 비동기 이벤트 처리.
 *
 * <p>서류 검증에는 두 경로가 있다.
 * <ul>
 *   <li>동기 — 업로드 시 {@code DocAgentClient.submit} 응답으로 즉시 판정한다.
 *       확정 판정(AUTO_PASS / NEEDS_RESUBMIT)은 여기서 끝난다.</li>
 *   <li>비동기 — 사람 검토가 필요해 HOLD 로 남은 건은 나중에 판정이 뒤집힌다.
 *       그 재판정이 이 클래스가 받는 이벤트다.</li>
 * </ul>
 *
 * <p>Kafka 는 at-least-once 이므로 같은 이벤트가 재전달될 수 있다.
 * 모든 처리는 목표 상태와 현재 상태를 비교해 이미 반영된 건은 조용히 건너뛴다(멱등).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocAgentEventService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_DOCUMENT    = "LOAN_DOCUMENT";
    private static final String TARGET_APPLICATION = "LOAN_APPLICATION";
    private static final String REASON_ROUTED = "DOC_AGENT_ROUTED";
    private static final String REASON_FRAUD  = "DOC_AGENT_FRAUD_CONFIRMED";

    private final LoanDocumentRepository documentRepository;
    private final LoanDocumentSubmissionRepository submissionRepository;
    private final LoanApplicationRepository applicationRepository;
    private final CurrentActorProvider currentActor;
    private final StatusHistoryPublisher statusHistoryPublisher;

    /**
     * {@code doc-agent.routed} — 제출 건의 검증 상태 재판정.
     *
     * <p>대상은 submissionId 로 찾는다. 업로드 시점에 저장해 둔 제출 이력이 docId 로 이어준다.
     */
    @Transactional
    public void handleRouted(String submissionId, String verifyStatus) {
        var submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            // 우리가 올린 적 없는 제출 건. 재처리해도 달라지지 않으므로 커밋하고 넘어간다.
            log.warn("doc-agent.routed — 알 수 없는 submissionId={} 무시", submissionId);
            return;
        }

        LoanDocument doc = documentRepository
                .findByDocIdAndDeletedAtIsNull(submission.getDocId()).orElse(null);
        if (doc == null || LoanDocument.STATUS_DELETED.equals(doc.getDocStatusCd())) {
            log.warn("doc-agent.routed — 삭제된 서류 docId={} 무시", submission.getDocId());
            return;
        }

        String before = doc.getDocStatusCd();
        doc.applyVerifyResult(verifyStatus, submissionId);
        submission.updateVerifyStatus(verifyStatus);

        if (before.equals(doc.getDocStatusCd())) {
            return; // 재전달이거나 HOLD 유지 — 이력을 중복으로 남기지 않는다.
        }

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_DOCUMENT, doc.getDocId(),
                before, doc.getDocStatusCd(),
                REASON_ROUTED, "submissionId=" + submissionId,
                currentActor.currentActorId()
        ));
    }

    /**
     * {@code doc-agent.fraud.audit} — 위변조 확정. 신청 자체를 거절한다.
     *
     * <p>서류 한 장의 문제가 아니라 신청인의 제출 행위가 부정으로 확정된 것이므로
     * 서류가 아닌 신청을 REJECTED 로 전이한다. 함께 오는 보존기한은 해당 신청의
     * 전체 서류에 박아 감사 대상으로 묶어 둔다.
     */
    @Transactional
    public void handleFraudConfirmed(String applNo, String retentionUntil) {
        LoanApplication application = applicationRepository
                .findByApplNoAndDeletedAtIsNull(applNo).orElse(null);
        if (application == null) {
            log.warn("doc-agent.fraud.audit — 알 수 없는 applNo={} 무시", applNo);
            return;
        }
        if (LoanApplication.STATUS_REJECTED.equals(application.currentStatus())) {
            return; // 이미 반영됨
        }

        String before = application.currentStatus();
        application.markRejected();

        String normalized = normalizeRetention(retentionUntil);
        if (normalized != null) {
            documentRepository
                    .findByApplIdAndDeletedAtIsNullOrderBySubmittedAtAsc(application.getApplId())
                    .forEach(doc -> doc.markRetained(normalized));
        }

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_APPLICATION, application.getApplId(),
                before, LoanApplication.STATUS_REJECTED,
                REASON_FRAUD, "retentionUntil=" + normalized,
                currentActor.currentActorId()
        ));
    }

    /**
     * 이벤트는 ISO 날짜(2060-05-28)로 보내지만 retention_until 컬럼은 8자리다.
     * 구분자를 걷어내 yyyyMMdd 로 맞춘다.
     */
    private String normalizeRetention(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() == 8 ? digits : null;
    }
}
