package com.bank.customer.cert;

import com.bank.common.web.ApiResponse;
import com.bank.customer.cert.dto.TransactionApprovalIssueRequest;
import com.bank.customer.cert.dto.TransactionApprovalIssueResponse;
import com.bank.customer.cert.dto.TransactionApprovalVerifyRequest;
import com.bank.customer.cert.service.TransactionApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자금이동 step-up 인증 — 거래 승인 토큰 발급·검증.
 *
 * <p><b>경로가 둘로 갈린 이유.</b>
 * <ul>
 *   <li>발급은 고객이 부른다 → {@code /api/v1/customers/me/**}.
 *       게이트웨이의 CUSTOMER_ONLY_PATHS 에 이미 들어 있어 직원·관리자 세션 토큰으로는
 *       호출되지 않는다.</li>
 *   <li>검증은 계정계(core-banking)가 부른다 → {@code /api/internal/**}.
 *       서비스 간 호출 규약이라 고객 토큰이 없다. {@code /api/v1/internal/**} 로 두면
 *       SecurityConfig 의 직원 역할 게이트에 걸려 계정계가 못 부른다.</li>
 * </ul>
 *
 * <p>설계 배경: docs/plan/transfer-step-up-auth.md
 */
@RestController
@RequiredArgsConstructor
public class TransactionApprovalController {

    private final TransactionApprovalService transactionApprovalService;

    /** 인증서 PIN 확인 → 이 거래에 한정된 승인 토큰 발급. */
    @PostMapping("/api/v1/customers/me/transaction-approvals")
    public ResponseEntity<ApiResponse<TransactionApprovalIssueResponse>> issue(
            @RequestHeader("X-Customer-Id") Long customerId,
            @Valid @RequestBody TransactionApprovalIssueRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                transactionApprovalService.issue(customerId, request, extractIp(httpRequest))));
    }

    /** 계정계가 이체 직전에 부른다. 성공하면 토큰은 즉시 소멸한다(1회용). */
    @PostMapping("/api/internal/transaction-approvals/verify")
    public ResponseEntity<ApiResponse<Void>> verify(
            @Valid @RequestBody TransactionApprovalVerifyRequest request) {
        transactionApprovalService.verify(request);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
