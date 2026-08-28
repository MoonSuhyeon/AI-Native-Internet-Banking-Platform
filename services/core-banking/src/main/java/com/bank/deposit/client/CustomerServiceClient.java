package com.bank.deposit.client;

import com.bank.deposit.client.dto.EmployeeAuthorizationRequest;
import com.bank.deposit.client.dto.EmployeeAuthorizationResponse;
import com.bank.deposit.client.dto.HolderInfoResponse;
import com.bank.deposit.client.dto.TransactionApprovalVerifyRequest;
import com.bank.deposit.client.dto.TransferLimitResponse;
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
     * 고객의 인터넷뱅킹 이체한도(1일/1회).
     *
     * <p>본인용 {@code /api/v1/customers/me/transfer-limit} 은 X-Customer-Id 기준이라
     * 여기서 쓸 수 없다. 이체를 처리하는 주체는 결제 서비스이지 고객이 아니다.
     *
     * <p>행이 없는 고객도 기본값이 돌아온다. 404 가 아니라는 점이 중요하다 —
     * "한도 없음" 으로 읽어 무제한 통과시키면, 한도를 한 번도 설정하지 않은 대다수
     * 고객이 오히려 제한 없이 이체하게 된다.
     */
    @GetMapping("/api/internal/customers/{customerId}/transfer-limit")
    TransferLimitResponse getTransferLimit(
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

    /**
     * 직원 인가 판단.
     *
     * <p><b>왜 core-banking 이 직접 묻는가.</b> 부르는 쪽(상담·조사)이 "나는 허가받았다"
     * 는 헤더를 실어 보내면, 그것은 클라이언트가 주장하는 값이다. 자원을 가진 쪽이
     * 직접 물어야 그 주장을 검증한 것이 된다.
     *
     * <p>직원 신원·역할의 정본은 customer-service 다. 다만 <b>여기서 ALLOW 가 와도
     * 자원이 열리는 것은 아니다</b> — core-banking 은 자기 자원 기준 검사를 따로 한다.
     * 중앙 인가가 잘못 열렸을 때 자원까지 같이 열리지 않게 하려는 것이다.
     */
    @PostMapping("/api/v1/internal/authorization/employee")
    EmployeeAuthorizationResponse authorizeEmployee(
            @RequestBody EmployeeAuthorizationRequest request,
            @RequestHeader("X-Caller-Service") String callerService
    );
}
