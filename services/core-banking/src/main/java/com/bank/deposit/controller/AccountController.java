package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.dto.request.AccountAliasUpdateRequest;
import com.bank.deposit.dto.request.AccountCreateRequest;
import com.bank.deposit.dto.request.AccountLimitUpdateRequest;
import com.bank.deposit.dto.request.AccountStatusUpdateRequest;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
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

    @GetMapping("/{accountId}")
    public Account get(@PathVariable Long accountId) {
        return accountService.findById(accountId);
    }

    @GetMapping("/by-number/{accountNo}")
    public Account getByNumber(@PathVariable String accountNo) {
        return accountService.findByAccountNumber(accountNo);
    }

    @PatchMapping("/{accountId}/status")
    public Account changeStatus(@PathVariable Long accountId,
                                @Valid @RequestBody AccountStatusUpdateRequest req) {
        return accountService.changeStatus(accountId, req.accountStatus());
    }

    @PatchMapping("/{accountId}/limits")
    public Account updateLimits(@PathVariable Long accountId,
                                @Valid @RequestBody AccountLimitUpdateRequest req) {
        return accountService.updateLimits(accountId, req.dailyWithdrawLimit(),
                req.dailyWithdrawCountLimit(), req.atmWithdrawLimit());
    }

    @PatchMapping("/{accountId}/alias")
    public Account updateAlias(@PathVariable Long accountId,
                               @Valid @RequestBody AccountAliasUpdateRequest req) {
        return accountService.updateAlias(accountId, req.accountAlias());
    }
}
