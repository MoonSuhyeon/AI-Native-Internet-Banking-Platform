package com.bank.deposit.controller.v1;

import com.bank.deposit.dto.interservice.AccountInfoResponse;
import com.bank.deposit.dto.interservice.ApiResponse;
import com.bank.deposit.dto.interservice.HolderInfoResponse;
import com.bank.deposit.service.DepositV1Service;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * payment-service ↔ deposit-service 서비스간 계좌 조회 API (v1).
 * 명세: docs/deposit-payment-api-spec.md  A-1, A-2
 */
@RestController
@RequestMapping("/v1/accounts")
@RequiredArgsConstructor
public class AccountV1Controller {

    private final DepositV1Service depositV1Service;

    /** A-1. 계좌 상태·사기 플래그 조회 */
    @GetMapping("/{accountNo}")
    public ApiResponse<AccountInfoResponse> getAccount(@PathVariable String accountNo) {
        return ApiResponse.ok(depositV1Service.getAccountInfo(accountNo));
    }

    /** A-2. 예금주 조회 */
    @GetMapping("/{accountNo}/holder")
    public ApiResponse<HolderInfoResponse> getHolder(@PathVariable String accountNo) {
        return ApiResponse.ok(depositV1Service.getHolderInfo(accountNo));
    }
}
