package com.bank.payment.outbound.feign;

import com.bank.payment.outbound.feign.dto.BalanceInquiryData;
import com.bank.payment.outbound.feign.dto.BalanceTxData;
import com.bank.payment.outbound.feign.dto.DepositRequest;
import com.bank.payment.outbound.feign.dto.DepositResponse;
import com.bank.payment.outbound.feign.dto.LimitInquiryData;
import com.bank.payment.outbound.feign.dto.WithdrawCancelData;
import com.bank.payment.outbound.feign.dto.WithdrawRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
    name = "deposit-balance",
    url = "${deposit.balance.url:http://localhost:8082}",
    configuration = DepositFeignConfig.class,
    primary = false
)
public interface DepositBalanceClient {

    // B-1 잔액조회 — D-REQ-3: deposit에 /balances 전용 endpoint 없음. 해결 전 mock 의존.
    @GetMapping("/api/v1/balances/{accountNo}")
    DepositResponse<BalanceInquiryData> getBalance(@PathVariable("accountNo") String accountNo);

    // B-2 한도조회 — D-REQ-4: deposit에 /limits endpoint 없음. 해결 전 mock 의존.
    @GetMapping("/api/v1/limits/{accountNo}")
    DepositResponse<LimitInquiryData> getLimit(
            @PathVariable("accountNo") String accountNo,
            @RequestParam(value = "date", required = false) String date);

    // B-3 출금 (멱등키 @RequestHeader)
    // deposit 실제 경로: POST /api/transactions/withdraw (TransactionController.java:46)
    // 실패(4xx/5xx) 시 DepositFeignErrorDecoder → DepositInboundFailureException
    @PostMapping("/api/transactions/withdraw")
    BalanceTxData withdraw(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody WithdrawRequest request);

    // B-4 입금 (멱등키 @RequestHeader)
    // deposit 실제 경로: POST /api/transactions/deposit (TransactionController.java:39)
    // 실패(4xx/5xx) 시 DepositFeignErrorDecoder → DepositInboundFailureException
    @PostMapping("/api/transactions/deposit")
    BalanceTxData deposit(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody DepositRequest request);

    // B-5 출금취소 — deposit 실제 엔드포인트: PATCH /api/transactions/{transactionId}/cancel
    // bare Transaction 직렬화(200 OK) → WithdrawCancelData(@JsonIgnoreProperties lean 수신).
    // X-Idempotency-Key: deposit이 무시하지만 무해하므로 유지.
    @PatchMapping("/api/transactions/{transactionId}/cancel")
    WithdrawCancelData withdrawCancel(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @PathVariable("transactionId") Long transactionId);
}
