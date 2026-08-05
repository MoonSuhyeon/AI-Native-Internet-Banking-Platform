package com.bank.deposit.controller.v1;

import com.bank.deposit.dto.interservice.*;
import com.bank.deposit.service.DepositV1Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * payment-service ↔ deposit-service 서비스간 잔액·출금·입금·한도 API (v1).
 * 명세: docs/deposit-payment-api-spec.md  B-1 ~ B-5
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class BalanceV1Controller {

    private final DepositV1Service depositV1Service;

    /** B-1. 잔액 조회 */
    @GetMapping("/balances/{accountNo}")
    public ApiResponse<BalanceResponse> getBalance(@PathVariable String accountNo) {
        return ApiResponse.ok(depositV1Service.getBalance(accountNo));
    }

    /** B-2. 한도 조회 */
    @GetMapping("/limits/{accountNo}")
    public ApiResponse<LimitResponse> getLimit(
            @PathVariable String accountNo,
            @RequestParam(required = false) String date) {
        return ApiResponse.ok(depositV1Service.getLimit(accountNo, date));
    }

    /** B-3. 출금 */
    @PostMapping("/balances/withdraw")
    public ApiResponse<TransactionResponse> withdraw(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WithdrawRequest req) {
        return ApiResponse.ok(depositV1Service.withdraw(req, idempotencyKey));
    }

    /** B-4. 입금 (자행 이체 수신) */
    @PostMapping("/balances/deposit")
    public ApiResponse<TransactionResponse> deposit(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest req) {
        return ApiResponse.ok(depositV1Service.deposit(req, idempotencyKey));
    }

    /** B-5. 출금 취소 (Saga 보상) */
    @PostMapping("/balances/withdraw/cancel")
    public ApiResponse<CancelResponse> cancelWithdraw(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WithdrawCancelRequest req) {
        return ApiResponse.ok(depositV1Service.cancelWithdraw(req, idempotencyKey));
    }
}
