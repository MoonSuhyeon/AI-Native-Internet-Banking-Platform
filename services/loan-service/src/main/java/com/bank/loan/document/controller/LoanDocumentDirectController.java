package com.bank.loan.document.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.document.dto.LoanDocumentDownload;
import com.bank.loan.document.dto.LoanDocumentResponse;
import com.bank.loan.document.service.LoanDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@Tag(name = "신청서류", description = "LoanDocument - 직접 접근")
@RestController
@RequestMapping("/api/loan-documents")
@RequiredArgsConstructor
public class LoanDocumentDirectController {

    private final LoanDocumentService service;

    @Operation(summary = "서류 다운로드",
            description = "업로드 시 보관한 원본 바이트를 첨부파일로 내려준다.")
    @GetMapping("/{docId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long docId) {
        LoanDocumentDownload doc = service.download(docId);

        // 파일명에 한글·공백이 섞여도 깨지지 않도록 RFC 5987 인코딩으로 내린다.
        String disposition = ContentDisposition.attachment()
                .filename(doc.fileName() == null ? "document" : doc.fileName(),
                        StandardCharsets.UTF_8)
                .build().toString();

        MediaType contentType = doc.mimeType() == null || doc.mimeType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(doc.mimeType());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(contentType)
                .body(doc.content());
    }

    @Operation(summary = "서류 삭제",
            description = "soft delete + doc_status_cd 를 DELETED 로 전이.")
    @DeleteMapping("/{docId}")
    public ApiResponse<LoanDocumentResponse> delete(@PathVariable Long docId) {
        return ApiResponse.ok(service.delete(docId));
    }
}
