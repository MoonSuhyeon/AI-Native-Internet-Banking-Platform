package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.dto.request.AccountAliasUpdateRequest;
import com.bank.deposit.dto.request.AccountCreateRequest;
import com.bank.deposit.dto.request.AccountLimitUpdateRequest;
import com.bank.deposit.dto.request.AccountStatusUpdateRequest;
import com.bank.deposit.dto.response.EarlyTerminationEstimateResponse;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.EarlyTerminationService;
import com.bank.deposit.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final EarlyTerminationService earlyTerminationService;
    private final AuthenticatedCustomerValidator customerValidator;

    @PostMapping
    public ResponseEntity<Account> create(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @Valid @RequestBody AccountCreateRequest req) {
        customerValidator.validate(authenticatedCustomerId, req.customerId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.create(req.customerId(), req.contractId(), req.accountType(),
                        req.savingType(), req.accountAlias(), req.accountPassword()));
    }

    @GetMapping
    public List<Account> list(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @RequestParam String customerId) {
        customerValidator.validate(authenticatedCustomerId, customerId);
        return accountService.findByCustomer(customerId);
    }

    /**
     * 해지 예상 명세 — "해지상세조회".
     *
     * <p>아무것도 바꾸지 않는다. 조회가 상태를 건드리면 "확인만 해 봤는데 해지됐다"
     * 가 가능해진다.
     *
     * <p>신원을 필수로 요구한다. 이건 고객 화면 전용 경로이고, 남의 계좌의 잔액·이자를
     * 계산해 볼 수 있게 두면 조회만으로 자산 규모가 새어 나간다.
     */
    @GetMapping("/{accountId}/termination-estimate")
    public EarlyTerminationEstimateResponse terminationEstimate(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @PathVariable Long accountId) {
        customerValidator.requireAccountOwner(authenticatedCustomerId, accountId);
        return earlyTerminationService.estimate(accountId, java.time.LocalDate.now());
    }

    @GetMapping("/{accountId}")
    public Account get(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @PathVariable Long accountId) {
        customerValidator.validateAccountOwner(authenticatedCustomerId, accountId);
        return accountService.findById(accountId);
    }

    /**
     * 계좌번호로 조회. payment 오케스트레이터가 서비스 간 호출로 쓰므로(A-1 수신계좌 검증)
     * 신원 없는 호출은 통과시키고, 고객 요청이면 본인 계좌인지 본다.
     */
    @GetMapping("/by-number/{accountNo}")
    public Account getByNumber(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @PathVariable String accountNo) {
        Account account = accountService.findByAccountNumber(accountNo);
        customerValidator.validateAccountOwner(authenticatedCustomerId, account.getAccountId());
        return account;
    }

    @PatchMapping("/{accountId}/status")
    public Account changeStatus(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @PathVariable Long accountId,
            @Valid @RequestBody AccountStatusUpdateRequest req) {
        customerValidator.validateAccountOwner(authenticatedCustomerId, accountId);
        return accountService.changeStatus(accountId, req.accountStatus());
    }

    @PatchMapping("/{accountId}/limits")
    public Account updateLimits(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @PathVariable Long accountId,
            @Valid @RequestBody AccountLimitUpdateRequest req) {
        customerValidator.validateAccountOwner(authenticatedCustomerId, accountId);
        return accountService.updateLimits(accountId, req.dailyWithdrawLimit(),
                req.dailyWithdrawCountLimit(), req.atmWithdrawLimit());
    }

    @PatchMapping("/{accountId}/alias")
    public Account updateAlias(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @PathVariable Long accountId,
            @Valid @RequestBody AccountAliasUpdateRequest req) {
        customerValidator.validateAccountOwner(authenticatedCustomerId, accountId);
        return accountService.updateAlias(accountId, req.accountAlias());
    }
}
