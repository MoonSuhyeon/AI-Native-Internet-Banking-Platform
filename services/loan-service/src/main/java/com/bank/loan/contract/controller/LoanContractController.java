package com.bank.loan.contract.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.contract.dto.LoanContractListResponse;
import com.bank.loan.contract.dto.CreateContractRequest;
import com.bank.loan.contract.dto.LoanContractResponse;
import com.bank.loan.contract.service.LoanContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "대출계약", description = "LoanContract - 약정한도 설정 및 조회")
@RestController
@RequestMapping("/api/loan-contracts")
@RequiredArgsConstructor
public class LoanContractController {

    private final LoanContractService service;

    @Operation(summary = "고객 대출 계약 목록 조회", description = "customerId 기준 계약 이력을 최신순으로 반환.")
    @GetMapping
    public ApiResponse<LoanContractListResponse> list(
            @RequestParam(required = false) Long customerId,
            Authentication auth) {
        // 일반 고객: JWT principal 의 customerId 강제 사용 (요청 파라미터 무시)
        // ROLE_OPS(관리자): 파라미터로 넘긴 customerId 그대로 사용
        Long effectiveCustomerId = isOps(auth) ? customerId : extractPrincipal(auth);
        List<LoanContractResponse> items = service.list(effectiveCustomerId);
        return ApiResponse.ok(LoanContractListResponse.of(items));
    }

    @Operation(summary = "약정한도 설정",
            description = "승인된 신청에 대해 최종 약정 한도·금리·기간을 확정한다. 초기 상태 SIGNED.")
    @PostMapping
    public ResponseEntity<ApiResponse<LoanContractResponse>> create(
            @Valid @RequestBody CreateContractRequest req) {
        LoanContractResponse saved = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(saved));
    }

    @Operation(summary = "대출 계약 단건 조회")
    @GetMapping("/{cntrId}")
    public ApiResponse<LoanContractResponse> get(@PathVariable Long cntrId) {
        return ApiResponse.ok(service.get(cntrId));
    }

    private Long extractPrincipal(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Long id) return id;
        return null;
    }

    private boolean isOps(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_OPS".equals(a.getAuthority()));
    }
}
