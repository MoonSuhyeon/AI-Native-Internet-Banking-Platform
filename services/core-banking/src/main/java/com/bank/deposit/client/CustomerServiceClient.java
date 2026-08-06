package com.bank.deposit.client;

import com.bank.deposit.client.dto.HolderInfoResponse;
import com.bank.deposit.client.dto.TransactionApprovalVerifyRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "customer-service", url = "${client.customer-service.url}")
public interface CustomerServiceClient {

    @GetMapping("/api/internal/customers/{customerId}/holder-info")
    HolderInfoResponse getHolderInfo(
            @PathVariable("customerId") Long customerId,
            @RequestHeader("X-Caller-Service") String callerService
    );

    /**
     * 자금이동 승인 토큰 검증. 성공하면 인증보안계에서 토큰이 소멸한다(1회용).
     *
     * <p>이 도메인이 PIN·인증서를 직접 다루지 않기 위한 경계다 — 인증수단의 비밀은
     * 인증보안계에 두고 여기서는 토큰 한 장만 확인한다.
     * 설계: docs/plan/transfer-step-up-auth.md
     */
    @PostMapping("/api/internal/transaction-approvals/verify")
    void verifyTransactionApproval(@RequestBody TransactionApprovalVerifyRequest request);
}
