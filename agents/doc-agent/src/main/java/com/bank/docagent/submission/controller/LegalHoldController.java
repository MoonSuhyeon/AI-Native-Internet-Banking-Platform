package com.bank.docagent.submission.controller;

import com.bank.docagent.config.GatewayIdentity;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.service.LegalHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 법적 보존 — 삭제를 막는 규제 통제.
 *
 * <p>"감사팀 전용" 이라고 적혀 있었지만 막혀 있지 않았다. 게이트웨이에 doc-agent
 * 라우트가 없어 8087 을 그대로 부를 수 있었고, 아무나 보존을 해제할 수 있었다.
 */
@Tag(name = "법적 보존", description = "Legal Hold 설정/해제 — 감사팀 전용")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class LegalHoldController {

    private final LegalHoldService legalHoldService;
    private final GatewayIdentity gatewayIdentity;

    @Operation(summary = "Legal Hold 설정", description = "retention_until=null (무기한 보존)")
    @PatchMapping("/{submissionId}/legal-hold/enable")
    public ResponseEntity<Map<String, Object>> enable(@PathVariable UUID submissionId,
                                                     HttpServletRequest request) {
        gatewayIdentity.requireEmployee(request);
        DocumentSubmission result = legalHoldService.enable(submissionId);
        return ResponseEntity.ok(buildResponse(result));
    }

    @Operation(summary = "Legal Hold 해제", description = "verify_status 기준으로 보존 기간 복원")
    @PatchMapping("/{submissionId}/legal-hold/disable")
    // 설정보다 해제가 더 위험하다. 보존을 켜는 것은 지나쳐도 데이터가 남을 뿐이지만,
    // 끄는 것은 보관 기간이 복원되어 **증거가 삭제 대상이 된다**.
    public ResponseEntity<Map<String, Object>> disable(@PathVariable UUID submissionId,
                                                      HttpServletRequest request) {
        gatewayIdentity.requireEmployee(request);
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
