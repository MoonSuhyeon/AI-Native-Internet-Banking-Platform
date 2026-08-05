package com.bank.payment.outbound.feign;

import com.bank.payment.outbound.feign.dto.AccountInquiryData;
import com.bank.payment.outbound.feign.dto.HolderInquiryData;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
    name = "deposit-account",
    url = "${deposit.account.url:http://localhost:8082}",
    configuration = DepositFeignConfig.class,
    primary = false
)
public interface DepositAccountClient {

    // A-1 계좌번호 기반 계좌조회 — D-REQ-1 해결. deposit AccountController.java:49-52
    // 응답: Account 엔티티 직접 반환(wrapper 없음). accountId(Long PK) 포함.
    @GetMapping("/api/accounts/by-number/{accountNo}")
    AccountInquiryData getAccountByNo(@PathVariable("accountNo") String accountNo);

    // A-1 계좌조회 (accountId Long PK 기반) — deposit 실제 경로: GET /api/accounts/{accountId}
    // 직접 PK 조회가 필요한 경우에만 사용. accountNo로 조회 시 getAccountByNo() 사용.
    @GetMapping("/api/accounts/{accountId}")
    AccountInquiryData getAccount(@PathVariable("accountId") String accountId);

    // A-2 예금주조회 — D-REQ-5: deposit에 holderName 없음(customerId만 저장). 해결 전 mock 의존.
    @GetMapping("/api/v1/accounts/{accountNo}/holder")
    HolderInquiryData getHolder(@PathVariable("accountNo") String accountNo);
}
