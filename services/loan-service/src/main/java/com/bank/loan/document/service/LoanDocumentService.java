package com.bank.loan.document.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.security.Sha256;
import com.bank.common.web.BusinessException;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bank.loan.document.domain.LoanDocument;
import com.bank.loan.document.docagent.DocAgentClient;
import com.bank.loan.document.docagent.DocAgentException;
import com.bank.loan.document.docagent.SubmissionResult;
import com.bank.loan.document.domain.LoanDocumentSubmission;
import com.bank.loan.document.dto.LoanDocumentDownload;
import com.bank.loan.document.dto.LoanDocumentListResponse;
import com.bank.loan.document.dto.LoanDocumentResponse;
import com.bank.loan.document.repository.LoanDocumentRepository;
import com.bank.loan.document.repository.LoanDocumentSubmissionRepository;
import com.bank.loan.document.storage.LoanDocumentStorage;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LoanDocumentService {

    private static final Logger log = LoggerFactory.getLogger(LoanDocumentService.class);

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "LOAN_DOCUMENT";
    private static final String REASON_DELETED         = "DOCUMENT_DELETED";
    private static final String REASON_VERIFIED        = "DOCUMENT_VERIFIED";
    private static final String REASON_NEEDS_RESUBMIT  = "DOCUMENT_NEEDS_RESUBMIT";
    private static final String REASON_HOLD            = "DOCUMENT_HOLD";
    private static final String REASON_VERIFY_DEFERRED = "DOCUMENT_VERIFY_DEFERRED";

    private final LoanDocumentRepository repository;
    private final LoanDocumentSubmissionRepository submissionRepository;
    private final LoanApplicationRepository applicationRepository;
    private final DocAgentClient docAgentClient;
    private final LoanDocumentStorage storage;
    private final CurrentActorProvider currentActor;
    private final StatusHistoryPublisher statusHistoryPublisher;

    @Transactional
    public LoanDocumentResponse delete(Long docId) {
        LoanDocument doc = repository.findByDocIdAndDeletedAtIsNull(docId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_041));
        String before = doc.getDocStatusCd();
        doc.markDeleted();
        doc.softDelete(currentActor.currentActorId());

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, doc.getDocId(),
                before, LoanDocument.STATUS_DELETED,
                REASON_DELETED, null,
                currentActor.currentActorId()
        ));

        return LoanDocumentResponse.of(doc);
    }

    @Transactional(readOnly = true)
    public LoanDocumentListResponse list(Long applId) {
        applicationRepository.findByApplIdAndDeletedAtIsNull(applId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_012));

        List<LoanDocumentResponse> items = repository
                .findByApplIdAndDeletedAtIsNullOrderBySubmittedAtAsc(applId)
                .stream().map(LoanDocumentResponse::of).toList();
        return LoanDocumentListResponse.of(items);
    }

    /**
     * 서류 원본 다운로드.
     *
     * <p>삭제된 서류는 조회 단계에서 걸러지므로 별도 분기가 없다.
     */
    @Transactional(readOnly = true)
    public LoanDocumentDownload download(Long docId) {
        LoanDocument doc = repository.findByDocIdAndDeletedAtIsNull(docId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_041));
        return new LoanDocumentDownload(
                doc.getDocName(),
                doc.getMimeType(),
                storage.load(doc.getDocId()));
    }

    @Transactional
    public LoanDocumentResponse upload(Long applId, String docTypeCd, String docSourceCd,
                                       MultipartFile file) {
        // 빈 파일은 서류로 성립하지 않는다. 해시·크기만 남고 내용이 없는 row 가 생기면
        // 검증도 다운로드도 불가능한 유령 서류가 되므로 입구에서 막는다.
        if (file == null || file.isEmpty()) {
            throw new BusinessException(LoanErrorCode.LOAN_040);
        }

        LoanApplication application = applicationRepository.findByApplIdAndDeletedAtIsNull(applId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_012));

        LoanDocument saved = repository.save(LoanDocument.builder()
                .applId(application.getApplId())
                .docTypeCd(docTypeCd)
                .docStatusCd(LoanDocument.STATUS_UPLOADED)
                .docSourceCd(docSourceCd == null ? LoanDocument.SOURCE_MOBILE : docSourceCd)
                .docName(file.getOriginalFilename())
                .docHash(hashOf(file))
                .mimeType(file.getContentType())
                .fileSizeBytes(file.getSize())
                .submittedAt(OffsetDateTime.now())
                .build());

        // 원본 보관은 doc-agent 호출보다 먼저 한다. 검증이 실패해도 올린 서류는 남아야
        // 재검증·감사 대상이 될 수 있다.
        storage.store(saved.getDocId(), file);

        SubmissionResult result;
        try {
            result = docAgentClient.submit(
                    application.getApplNo(),
                    docTypeCd,
                    String.valueOf(application.getProdId()),
                    file);
        } catch (DocAgentException e) {
            // doc-agent 미연결/일시장애 — 업로드 자체는 성공시키고 검증만 보류(PENDING)한다.
            // 트랜잭션을 롤백하지 않으므로 서류 row 는 보존되며, 추후 재검증 대상이 된다.
            return deferVerification(saved, e);
        }

        saved.applyVerifyResult(result.verifyStatus(), result.submissionId());

        Double rawScore = result.documentVerification() != null
                ? result.documentVerification().confidenceScore() : null;
        submissionRepository.save(LoanDocumentSubmission.builder()
                .submissionId(result.submissionId())
                .docId(saved.getDocId())
                .applicationId(application.getApplNo())
                .docCode(docTypeCd)
                .verifyStatus(result.verifyStatus())
                .confidenceScore(rawScore != null
                        ? java.math.BigDecimal.valueOf(rawScore) : null)
                .occurredAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build());

        String reason = switch (result.verifyStatus()) {
            case SubmissionResult.VERIFY_AUTO_PASS      -> REASON_VERIFIED;
            case SubmissionResult.VERIFY_NEEDS_RESUBMIT -> REASON_NEEDS_RESUBMIT;
            default                                     -> REASON_HOLD;
        };

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, saved.getDocId(),
                LoanDocument.STATUS_UPLOADED, saved.getDocStatusCd(),
                reason, "submissionId=" + result.submissionId(),
                currentActor.currentActorId()
        ));

        return LoanDocumentResponse.of(saved);
    }

    /**
     * doc-agent 호출 실패 시 검증을 보류(PENDING)하고 업로드를 성공으로 마무리한다.
     * 서류는 UPLOADED 상태로 남아 추후 재검증 대상이 된다.
     */
    private LoanDocumentResponse deferVerification(LoanDocument saved, DocAgentException e) {
        log.warn("doc-agent 연동 실패 — 서류 docId={} 검증 보류(PENDING) 처리: {}",
                saved.getDocId(), e.toString());
        saved.markVerificationDeferred();

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, saved.getDocId(),
                LoanDocument.STATUS_UPLOADED, saved.getDocStatusCd(),
                REASON_VERIFY_DEFERRED, "docAgentError=" + e.getMessage(),
                currentActor.currentActorId()
        ));

        return LoanDocumentResponse.of(saved);
    }


    /**
     * 업로드 파일의 SHA-256 무결성 해시.
     *
     * <p>loan_document.doc_hash 컬럼과 응답 DTO 는 있었으나 값을 채우는 코드가 없어
     * 항상 null 이었다. 서류 위변조 판별의 근거이므로 업로드 시점에 계산해 박제한다.
     */
    private String hashOf(MultipartFile file) {
        try {
            return Sha256.hex(file.getBytes());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "업로드 파일 해시 계산 실패: " + file.getOriginalFilename(), e);
        }
    }
}
