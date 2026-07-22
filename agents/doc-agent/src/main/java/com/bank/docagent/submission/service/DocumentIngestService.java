package com.bank.docagent.submission.service;

import com.bank.docagent.infra.storage.ObjectStorageService;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;

/**
 * L1 Ingest: 포맷 검증, MinIO 원본 저장, DB 레코드 생성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private static final Set<String> ALLOWED_TYPES =
        Set.of("application/pdf", "image/jpeg", "image/png");
    private static final long MAX_BYTES = 50L * 1024 * 1024;  // 50MB

    private final ObjectStorageService storage;
    private final DocumentSubmissionRepository repository;

    @Transactional
    public DocumentSubmission ingest(String applicationId, String docCode,
                                     MultipartFile file) throws IOException {
        validateFile(file);

        byte[] bytes = file.getBytes();
        DocumentSubmission submission = repository.save(
            DocumentSubmission.builder()
                .applicationId(applicationId)
                .docCode(docCode)
                .retentionUntil(LocalDate.now().plusDays(1825)) // 기본 5년, D-3에서 상품별 정책 적용
                .build()
        );

        String rawKey = storage.uploadRaw(
            applicationId,
            submission.getSubmissionId().toString(),
            bytes,
            file.getContentType()
        );
        submission.updateKeys(rawKey, null);
        log.info("L1 Ingest 완료: submissionId={} docCode={} size={}B",
            submission.getSubmissionId(), docCode, bytes.length);
        return submission;
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어 있습니다");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("파일 크기 초과 (최대 50MB)");
        }
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("허용되지 않는 파일 형식: " + file.getContentType());
        }
    }
}
