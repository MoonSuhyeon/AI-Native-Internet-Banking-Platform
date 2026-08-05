package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.dto.request.*;
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

    @GetMapping
    public Page<Transaction> list(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) OffsetDateTime startDate,
            @RequestParam(required = false) OffsetDateTime endDate,
            @PageableDefault(size = 200, sort = "transactionAt", direction = Sort.Direction.DESC) Pageable pageable) {
        if (customerId != null) {
            return transactionService.findByCustomer(customerId, pageable);
        }
        if (accountId == null) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        return transactionService.findByAccount(accountId, startDate, endDate, pageable);
    }

    @GetMapping("/{transactionId}")
    public Transaction get(@PathVariable Long transactionId) {
        return transactionService.findById(transactionId);
    }

    @PostMapping("/deposit")
    public ResponseEntity<Transaction> deposit(@Valid @RequestBody DepositRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.deposit(req.accountId(), req.amount(), req.channelType(),
                        req.transactionMemo(), req.depositorCustomerId(), req.depositorName()));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Transaction> withdraw(@Valid @RequestBody WithdrawRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.withdraw(req.accountId(), req.amount(), req.channelType(), req.transactionMemo()));
    }

    @PostMapping("/transfer")
    public ResponseEntity<Transaction> transfer(@Valid @RequestBody TransferRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.transfer(req.fromAccountId(), req.toAccountId(), req.toAccountNo(),
                        req.amount(), req.transferType(), req.counterpartyBankCode(), req.counterpartyBankName(),
                        req.counterpartyName(), req.channelType(), req.transactionMemo(), req.idempotencyKey()));
    }

    @PostMapping("/savings-payment")
    public ResponseEntity<Transaction> savingsPayment(@Valid @RequestBody SavingsPaymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.savingsPayment(req.accountId(), req.contractId(),
                        req.amount(), req.paymentRound(), req.channelType()));
    }

    @PatchMapping("/{transactionId}/cancel")
    public Transaction cancel(@PathVariable Long transactionId,
                              @RequestBody(required = false) java.util.Map<String, String> body) {
        return transactionService.reversal(transactionId, null);
    }
}
