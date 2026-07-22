package com.bank.docagent.submission.service;

import com.bank.docagent.infra.ocr.OcrClient;
import com.bank.docagent.infra.ocr.dto.OcrResponse;
import com.bank.docagent.infra.storage.ObjectStorageService;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.dto.OcrRegion;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * L3 OCR + Masking: inference-server 호출 → PII 마스킹 → 마스킹본 저장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrMaskingService {

    // 주민등록번호: 6자리-1자리+6자리
    private static final Pattern SSN = Pattern.compile("(\\d{6})-([1-4]\\d{6})");
    // 전화번호
    private static final Pattern PHONE = Pattern.compile("(01[0-9])-?(\\d{3,4})-?(\\d{4})");
    // 계좌번호 (10~16자리 숫자, 하이픈 포함)
    private static final Pattern ACCOUNT = Pattern.compile("\\d{3,6}-\\d{2,6}-\\d{4,8}");

    private final OcrClient ocrClient;
    private final ObjectStorageService storage;
    private final DocumentSubmissionRepository repository;

    @Transactional
    public OcrResult extractAndMask(DocumentSubmission submission, byte[] rawBytes,
                                    String contentType, String applicationId) {
        String submissionId = submission.getSubmissionId().toString();

        // inference-server 호출
        OcrResponse ocrResponse = ocrClient.extract(rawBytes, submissionId);
        List<OcrRegion> regions = ocrResponse.regions() != null ? ocrResponse.regions() : List.of();

        // 전체 텍스트 합치기
        String fullText = regions.stream()
            .filter(r -> r.confidence() >= 0.7)
            .map(OcrRegion::text)
            .reduce("", (a, b) -> a + "\n" + b);

        // SSN 원본 힌트 추출 (마스킹 전 — 체크섬 검증용, 외부 노출 금지)
        String rawSsnHint = extractFirstSsn(fullText);

        // PII 마스킹
        String maskedText = mask(fullText);

        // 마스킹 텍스트를 마스킹본으로 저장
        byte[] maskedBytes = maskedText.getBytes(StandardCharsets.UTF_8);
        String maskedKey = storage.uploadMasked(
            applicationId, submissionId, maskedBytes, "text/plain");

        submission.updateKeys(submission.getRawObjectKey(), maskedKey);
        log.info("L3 OCR+Masking 완료: submissionId={} regions={}", submissionId, regions.size());

        return new OcrResult(regions, fullText, maskedText, rawSsnHint);
    }

    /** SSN 패턴 첫 번째 매칭 반환 (체크섬 검증 전용 — 로그·저장 금지). */
    private String extractFirstSsn(String text) {
        var m = SSN.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    private String mask(String text) {
        // SSN: 앞 7자리 보존(생년월일 6자리 + 성별 1자리), 뒤 6자리 마스킹
        String result = SSN.matcher(text).replaceAll(m ->
            m.group(1) + "-" + m.group(2).charAt(0) + "******");
        result = PHONE.matcher(result).replaceAll(m ->
            m.group(1) + "-****-" + m.group(3));
        result = ACCOUNT.matcher(result).replaceAll(m ->
            m.group().replaceAll("\\d(?=\\d{4})", "*"));
        return result;
    }

    public record OcrResult(List<OcrRegion> regions, String rawText, String maskedText, String rawSsnHint) {}
}
