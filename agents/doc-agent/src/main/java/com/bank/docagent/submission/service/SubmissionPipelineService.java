package com.bank.docagent.submission.service;

import com.bank.docagent.forgery.service.ForgeryAnalysisService;
import com.bank.docagent.forgery.service.ForgeryAnalysisService.ForgeryResult;
import com.bank.docagent.infra.ocr.TableOcrClient;
import com.bank.docagent.retention.RetentionService;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.VerifyStatus;
import com.bank.docagent.submission.dto.ExtractionResult;
import com.bank.docagent.submission.dto.extracted.StructuredData;
import com.bank.docagent.submission.dto.verification.VerificationBlock;
import com.bank.docagent.submission.service.DocumentClassifyService.DocType;
import com.bank.docagent.submission.service.OcrMaskingService.OcrResult;
import com.bank.docagent.verify.service.DocumentVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * L1 → L4b(Forgery) → L3 → L2 → [L3b: Table OCR] → L4 → L5 파이프라인 오케스트레이터.
 * Python 사이드카 호출: L4b(위변조), L3(OCR), L3b(PP-Structure), L4(LLM)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionPipelineService {

    private final DocumentIngestService    ingestService;
    private final DocumentClassifyService  classifyService;
    private final OcrMaskingService        ocrMaskingService;
    private final StructuredExtractService extractService;
    private final ForgeryAnalysisService   forgeryService;
    private final DocumentVerifyService    verifyService;
    private final RetentionService         retentionService;
    private final TableOcrClient           tableOcrClient;

    @Value("${doc-agent.default-product-id:P001}")
    private String defaultProductId;

    public ExtractionResult process(String applicationId, String docCode,
                                    MultipartFile file) throws IOException {
        return process(applicationId, docCode, defaultProductId, file);
    }

    public ExtractionResult process(String applicationId, String docCode,
                                    String productId, MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String submissionId;

        // L1: Ingest — 포맷 검증, MinIO 원본 저장
        DocumentSubmission submission = ingestService.ingest(applicationId, docCode, file);
        submissionId = submission.getSubmissionId().toString();

        // L4b: 위변조 시그널 분석 (사이드카, raw bytes 사용)
        ForgeryResult forgeryResult = forgeryService.analyze(
            submission.getSubmissionId(), docCode, bytes, file.getContentType());

        // L3: OCR + Masking (사이드카)
        OcrResult ocrResult = ocrMaskingService.extractAndMask(
            submission, bytes, file.getContentType(), applicationId);

        // L2: OCR 텍스트 기반 서류 유형 분류
        DocType docType = classifyService.classify(ocrResult.rawText());

        // L3b: 등기부등본 → PP-StructureV2 테이블 파싱 (갑구·을구 인식 강화)
        String maskedTextForLlm = ocrResult.maskedText();
        if (docType == DocType.REGISTRY_DEED) {
            String tableText = tableOcrClient.extractTable(bytes, submissionId);
            if (tableText != null && !tableText.isBlank()) {
                maskedTextForLlm = ocrResult.maskedText() + "\n\n[테이블 파싱 결과]\n" + tableText;
                log.info("등기부등본 테이블 텍스트 병합: submissionId={} tableLen={}",
                    submissionId, tableText.length());
            }
        }

        // L4: LLM 구조화 추출 (사이드카)
        StructuredData structuredData = extractService.extract(
            submissionId, docType, maskedTextForLlm);

        // L5: 룰 검증 + 진위확인 + 위변조 점수 합산
        VerificationBlock verification = verifyService.verify(
            submission, docType, structuredData, productId,
            ocrResult.rawSsnHint(),
            forgeryResult.aggregateScore(), forgeryResult.signals());

        VerifyStatus finalStatus = verification.status();
        submission.updateStatus(finalStatus);

        // HOLD 시 humanReviewStatus PENDING 세팅
        if (finalStatus == VerifyStatus.HOLD) {
            submission.markHoldPending();
        }

        // 보존 기간 계산 (HOLD는 심사원 결정 후 재계산, 여기서는 기본 5년)
        retentionService.applyRetention(submission, finalStatus);

        log.info("파이프라인 완료: submissionId={} docType={} forgeryScore={} status={}",
            submissionId, docType, forgeryResult.aggregateScore(), finalStatus);

        return ExtractionResult.of(
            submission.getSubmissionId(), applicationId, docCode,
            docType.name(), ocrResult.regions(), ocrResult.maskedText(),
            structuredData, verification, finalStatus
        );
    }
}
