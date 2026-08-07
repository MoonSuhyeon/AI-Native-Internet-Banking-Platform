package com.bank.loan.application.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.application.dto.LoanApplicationListResponse;
import com.bank.loan.application.dto.CancelLoanApplicationRequest;
import com.bank.loan.application.dto.CreateLoanApplicationRequest;
import com.bank.loan.application.dto.LoanApplicationResponse;
import com.bank.loan.application.service.LoanApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "대출신청", description = "LoanApplication - 대출 신청")
@RestController
@RequestMapping("/api/loan-applications")
@RequiredArgsConstructor
public class LoanApplicationController {

    private static final int DEVICE_MAX_LEN = 200;

    private final LoanApplicationService service;

    @Operation(summary = "대출 신청 단건 조회")
    @GetMapping("/{applId}")
    public ApiResponse<LoanApplicationResponse> get(@PathVariable Long applId, Authentication auth) {
        // 백오피스 직원(고객이 아닌 역할)은 소유권 검사 없이 조회 가능 — 본심사 승인 등 행위 권한과 조회를 일치시킨다.
        // 세부 접근 통제는 review 조회의 checkScope 가 담당한다.
        Long requestingCustomerId = isStaff(auth) ? null : extractPrincipal(auth);
        return ApiResponse.ok(service.get(applId, requestingCustomerId));
    }

    @Operation(summary = "고객 대출 신청 목록 조회", description = "customerId 기준 신청 이력을 최신순으로 반환.")
    @GetMapping
    public ApiResponse<LoanApplicationListResponse> list(
            @RequestParam(required = false) Long customerId,
            Authentication auth) {
        // 일반 고객: JWT principal 의 customerId 강제 사용 (요청 파라미터 무시)
        // ROLE_OPS(관리자): 파라미터로 넘긴 customerId 그대로 사용
        Long effectiveCustomerId = isOps(auth) ? customerId : extractPrincipal(auth);
        List<LoanApplicationResponse> items = service.list(effectiveCustomerId);
        return ApiResponse.ok(LoanApplicationListResponse.of(items));
    }

    @Operation(summary = "대출 신청",
            description = "본 신청 접수. Idempotency-Key 헤더로 중복 호출 방어. 상태 SUBMITTED.")
    @PostMapping
    public ResponseEntity<ApiResponse<LoanApplicationResponse>> create(
            @Valid @RequestBody CreateLoanApplicationRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest http,
            Authentication auth) {

        // 요청 바디의 customerId 를 무시하고 JWT principal 로 덮어쓴다
        Long requestingCustomerId = extractPrincipal(auth);
        LoanApplicationResponse saved = service.create(
                req, idempotencyKey, extractClientIp(http), extractDevice(http), requestingCustomerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(saved));
    }

    @Operation(summary = "대출 신청 취소(고객)",
            description = "심사 결과 도출 전 단계(SUBMITTED/PRESCREENED/REVIEWING)에서만 취소 가능. status_history 기록.")
    @PostMapping("/{applId}/cancel")
    public ApiResponse<LoanApplicationResponse> cancel(
            @PathVariable Long applId,
            @Valid @RequestBody CancelLoanApplicationRequest req,
            Authentication auth) {
        // ROLE_OPS(관리자)는 소유권 검사 없이 취소 가능
        Long requestingCustomerId = isOps(auth) ? null : extractPrincipal(auth);
        return ApiResponse.ok(service.cancel(applId, req, requestingCustomerId));
    }

    /** JWT principal 에서 customerId 를 추출한다. principal 이 없으면 null. */
    private Long extractPrincipal(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Long id) return id;
        return null;
    }

    /** 직원(고객이 아닌 역할 보유) 여부 — 백오피스 단건 조회는 소유권 검사를 건너뛴다. */
    private boolean isStaff(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> !"ROLE_CUSTOMER".equals(a.getAuthority()));
    }

    /** ROLE_OPS(운영/관리자) 여부. */
    private boolean isOps(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_OPS".equals(a.getAuthority()));
    }

    private String extractClientIp(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }

    private String extractDevice(HttpServletRequest http) {
        String ua = http.getHeader("User-Agent");
        if (ua == null) return null;
        return ua.length() > DEVICE_MAX_LEN ? ua.substring(0, DEVICE_MAX_LEN) : ua;
    }
}
