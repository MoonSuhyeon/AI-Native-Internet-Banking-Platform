package com.bank.docagent.verify.service;

import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.VerifyStatus;
import com.bank.docagent.submission.dto.extracted.StructuredData;
import com.bank.docagent.submission.dto.verification.*;
import com.bank.docagent.submission.dto.verification.VerificationBlock.ForgeryBlock;
import com.bank.docagent.submission.service.DocumentClassifyService.DocType;
import com.bank.docagent.verify.domain.LoanProductDocumentRepository;
import com.bank.docagent.verify.port.IdentityVerificationPort;
import com.bank.docagent.verify.port.IdentityVerificationPort.VerifyResult;
import com.bank.docagent.verify.port.IdentityVerificationPort.VerifyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L5 Verify 오케스트레이터.
 * 1) 만료·누락 검사  → score 0 (상황 A)
 * 2) SSN 체크섬     → score += 0.5 (상황 B 후보)
 * 3) 진위확인 API   → score += 0.7 (상황 B 확실)
 * 4) 임계치 기반 라우팅 (0.3 / 0.7 잠정값, D-4 ROC로 확정)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentVerifyService {

    private static final double THRESHOLD_RESUBMIT = 0.3;
    private static final double THRESHOLD_HOLD      = 0.7;

    private final ExpiryCheckService              expiryCheck;
    private final ChecksumService                 checksumService;
    private final IdentityVerificationPort        identityPort;
    private final LoanProductDocumentRepository   productDocRepo;

    public VerificationBlock verify(DocumentSubmission submission,
                                    DocType docType,
                                    StructuredData structuredData,
                                    String productId) {
        return verify(submission, docType, structuredData, productId, null, 0.0, List.of());
    }

    /**
     * D-4: 위변조 사이드카 점수를 외부에서 주입받아 합산.
     * @param rawSsnHint L3 OCR 원본에서 추출한 SSN (null이면 체크섬 SKIP). 외부 노출 금지.
     */
    public VerificationBlock verify(DocumentSubmission submission,
                                    DocType docType,
                                    StructuredData structuredData,
                                    String productId,
                                    String rawSsnHint,
                                    double preComputedForgeryScore,
                                    List<ForgerySignal> preComputedSignals) {
        List<ForgerySignal> signals  = new ArrayList<>(preComputedSignals);
        List<MissingDocument> missing = new ArrayList<>();
        double forgeryScore = preComputedForgeryScore;

        // ── 1. 만료·누락 검사 ──────────────────────────────────────────────
        Map<String, String> issueDateMap = buildIssueDateMap(submission.getDocCode(), docType, structuredData);
        missing = expiryCheck.check(productId,
            Set.of(submission.getDocCode()), issueDateMap);

        // ── 2. SSN 체크섬 ─────────────────────────────────────────────────
        if (structuredData != null && structuredData.applicant() != null) {
            var applicant = structuredData.applicant();
            String maskedSsn = applicant.maskedSsn() != null
                ? (String) applicant.maskedSsn().value() : null;

            boolean ssnValid = checksumService.validateSsn(maskedSsn, rawSsnHint);
            if (!ssnValid) {
                forgeryScore += 0.5;
                signals.add(new ForgerySignal("SEMANTIC", "SSN_CHECKSUM_FAIL", 0.5, "주민번호 검증식 불일치"));
                log.warn("SSN 체크섬 실패: submissionId={}", submission.getSubmissionId());
            }
        }

        // ── 3. 진위확인 API ───────────────────────────────────────────────
        if (docType == DocType.ID_CARD && structuredData != null
                && structuredData.applicant() != null) {
            VerifyResult apiResult = callIdentityApi(structuredData);
            if (apiResult == VerifyResult.INVALID) {
                forgeryScore += 0.7;
                signals.add(new ForgerySignal("EXTERNAL", "IDENTITY_API_INVALID", 0.7,
                    "운전면허 진위확인 API: 불일치"));
            } else if (apiResult == VerifyResult.ERROR) {
                signals.add(new ForgerySignal("EXTERNAL", "IDENTITY_API_ERROR", 0.0,
                    "진위확인 API 호출 오류 — 수동 확인 권장"));
            }
        }

        // ── 4. 라우팅 결정 ─────────────────────────────────────────────────
        // auto_verify_enabled=false 서류(예: 매매계약서)는 점수 무관 심사원 HOLD 강제
        boolean forceHold = productDocRepo
            .findByProductIdAndReqDocCode(productId, submission.getDocCode())
            .map(d -> !d.isAutoVerifyEnabled())
            .orElse(false);

        VerifyStatus status = forceHold
            ? VerifyStatus.HOLD
            : routingDecision(forgeryScore, missing);
        String humanReviewStatus = (status == VerifyStatus.HOLD)
            ? "PENDING" : "NOT_REQUIRED";

        ForgeryBlock forgery = new ForgeryBlock(
            forgeryScore,
            signals.isEmpty() ? "시그널 없음" : signals.size() + "개 시그널 탐지",
            signals,
            humanReviewStatus
        );

        log.info("L5 Verify 완료: submissionId={} score={} status={}",
            submission.getSubmissionId(), forgeryScore, status);

        return new VerificationBlock(
            status,
            Math.max(0.0, 1.0 - forgeryScore),
            missing,
            forgery,
            ConsistencyCheck.ok()   // 단일 서류 검증 — 다중 서류 정합성은 D-5
        );
    }

    private VerifyStatus routingDecision(double score, List<MissingDocument> missing) {
        if (!missing.isEmpty()) return VerifyStatus.NEEDS_RESUBMIT;  // 상황 A
        if (score >= THRESHOLD_HOLD) return VerifyStatus.HOLD;        // 상황 B
        if (score >= THRESHOLD_RESUBMIT) return VerifyStatus.NEEDS_RESUBMIT;
        return VerifyStatus.AUTO_PASS;
    }

    private VerifyResult callIdentityApi(StructuredData data) {
        try {
            var app = data.applicant();
            String name = app.name() != null ? (String) app.name().value() : null;
            if (name == null) return VerifyResult.SKIPPED;
            return identityPort.verify(VerifyType.DRIVER_LICENSE, name, null, null);
        } catch (Exception e) {
            log.error("진위확인 호출 예외: {}", e.getMessage());
            return VerifyResult.ERROR;
        }
    }

    private Map<String, String> buildIssueDateMap(String docCode, DocType docType, StructuredData data) {
        if (data == null || data.applicant() == null) return Map.of();
        var issueField = data.applicant().issueDate();
        if (issueField == null || issueField.value() == null) return Map.of();
        return Map.of(docCode, (String) issueField.value());
    }
}
