package com.bank.docagent.submission.controller;

import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.service.LegalHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "법적 보존", description = "Legal Hold 설정/해제 — 감사팀 전용")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class LegalHoldController {

    private final LegalHoldService legalHoldService;

    @Operation(summary = "Legal Hold 설정", description = "retention_until=null (무기한 보존)")
    @PatchMapping("/{submissionId}/legal-hold/enable")
    public ResponseEntity<Map<String, Object>> enable(@PathVariable UUID submissionId) {
        DocumentSubmission result = legalHoldService.enable(submissionId);
        return ResponseEntity.ok(buildResponse(result));
    }

    @Operation(summary = "Legal Hold 해제", description = "verify_status 기준으로 보존 기간 복원")
    @PatchMapping("/{submissionId}/legal-hold/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable UUID submissionId) {
        DocumentSubmission result = legalHoldService.disable(submissionId);
        return ResponseEntity.ok(buildResponse(result));
    }

    private Map<String, Object> buildResponse(DocumentSubmission s) {
        return Map.of(
            "submission_id",    s.getSubmissionId(),
            "legal_hold",       s.isLegalHold(),
            "retention_until",  s.getRetentionUntil() != null ? s.getRetentionUntil().toString() : "무기한",
            "verify_status",    s.getVerifyStatus()
        );
    }
}
