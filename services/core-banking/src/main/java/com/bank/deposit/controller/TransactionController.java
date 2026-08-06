package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.dto.request.*;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.security.TransferApprovalGate;
import com.bank.deposit.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final AuthenticatedCustomerValidator customerValidator;
    private final TransferApprovalGate transferApprovalGate;

    @GetMapping
    public Page<Transaction> list(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) OffsetDateTime startDate,
            @RequestParam(required = false) OffsetDateTime endDate,
            @PageableDefault(size = 200, sort = "transactionAt", direction = Sort.Direction.DESC) Pageable pageable) {
        if (customerId != null) {
            customerValidator.validate(authenticatedCustomerId, customerId);
            return transactionService.findByCustomer(customerId, pageable);
        }
        if (accountId == null) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        customerValidator.validateAccountOwner(authenticatedCustomerId, accountId);
        return transactionService.findByAccount(accountId, startDate, endDate, pageable);
    }

    @GetMapping("/{transactionId}")
    public Transaction get(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @PathVariable Long transactionId) {
        customerValidator.validateTransactionOwner(authenticatedCustomerId, transactionId);
        return transactionService.findById(transactionId);
    }

    @PostMapping("/deposit")
    public ResponseEntity<Transaction> deposit(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @Valid @RequestBody DepositRequest req) {
        customerValidator.validateAccountOwner(authenticatedCustomerId, req.accountId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.deposit(req.accountId(), req.amount(), req.channelType(),
                        req.transactionMemo(), req.depositorCustomerId(), req.depositorName()));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Transaction> withdraw(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @Valid @RequestBody WithdrawRequest req) {
        customerValidator.validateAccountOwner(authenticatedCustomerId, req.accountId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.withdraw(req.accountId(), req.amount(), req.channelType(), req.transactionMemo()));
    }

    /**
     * 이체는 고객만 호출한다(서비스 간 호출 없음). 그래서 신원을 필수로 요구한다 —
     * 헤더 없는 요청을 내부 호출로 봐주면 자금 유출 경로에 예외를 남기는 셈이다.
     */
    @PostMapping("/transfer")
    public ResponseEntity<Transaction> transfer(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @Valid @RequestBody TransferRequest req) {
        customerValidator.requireAccountOwner(authenticatedCustomerId, req.fromAccountId());
        // 소유권 다음에 승인 확인. 순서가 중요하다 — 남의 계좌 이체 시도는 승인 토큰을
        // 물어보기 전에 끊어야 계좌 존재 여부가 새지 않는다.
        transferApprovalGate.verify(req.approvalToken(), req.fromAccountId(), req.toAccountNo(), req.amount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.transfer(req.fromAccountId(), req.toAccountId(), req.toAccountNo(),
                        req.amount(), req.transferType(), req.counterpartyBankCode(), req.counterpartyBankName(),
                        req.counterpartyName(), req.channelType(), req.transactionMemo(), req.idempotencyKey()));
    }

    /** 적금 납입도 고객 전용 경로다. 이체와 같은 이유로 신원을 필수로 본다. */
    @PostMapping("/savings-payment")
    public ResponseEntity<Transaction> savingsPayment(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @Valid @RequestBody SavingsPaymentRequest req) {
        customerValidator.requireAccountOwner(authenticatedCustomerId, req.accountId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.savingsPayment(req.accountId(), req.contractId(),
                        req.amount(), req.paymentRound(), req.channelType()));
    }

    @PatchMapping("/{transactionId}/cancel")
    public Transaction cancel(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @PathVariable Long transactionId,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        customerValidator.validateTransactionOwner(authenticatedCustomerId, transactionId);
        return transactionService.reversal(transactionId, null);
    }
}
