package com.bank.aigateway.audit;

import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.AuditAnalysisResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Audit Analysis", description = "감사/공정성 LLM 분석 (internal)")
@RestController
@RequestMapping("/internal/audit")
@RequiredArgsConstructor
public class AuditAnalysisController {

    private final AgenticAuditAnalysisService service;

    @Operation(summary = "감사 분석 실행", description = "BIAS_DETECTION 또는 COMPLIANCE_VERIFICATION 분석 수행")
    @PostMapping("/analyze")
    public ResponseEntity<AuditAnalysisResponse> analyze(@Valid @RequestBody AuditAnalysisRequest request) {
        return ResponseEntity.ok(service.analyze(request));
    }
}
