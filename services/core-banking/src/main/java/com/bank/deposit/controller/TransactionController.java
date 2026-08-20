package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.dto.request.*;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.security.FdsPreCheckGate;
import com.bank.deposit.security.TransferApprovalGate;
import com.bank.deposit.service.AccountService;
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
    private final FdsPreCheckGate fdsPreCheckGate;
    private final AccountService accountService;

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
        //
        // 승인 토큰은 계좌 번호로 묶인다(결제계와 공유하는 기준). 여기서는 accountId 로
        // 받으므로 번호를 찾아 넘긴다.
        String fromAccountNo = accountService.findById(req.fromAccountId()).getAccountNumber();
        transferApprovalGate.verify(req.approvalToken(), fromAccountNo, req.toAccountNo(), req.amount());

        // 자행이체도 점검한다. 대포통장으로 흘러가는 자금은 자행/타행을 가리지 않는다.
        fdsPreCheckGate.evaluate(
                authenticatedCustomerId, fromAccountNo, req.counterpartyBankCode(),
                req.toAccountNo(), req.amount(), true,
                req.channelType() == null ? null : req.channelType().name(),
                req.approvalToken() != null && !req.approvalToken().isBlank());

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
    /**
     * 개인 메모 수정.
     *
     * <p>통장표시내용은 바꾸지 않는다 — 이체할 때 함께 보낸 문구라 사후에 고치면
     * 실제로 보낸 내용과 기록이 달라진다. 여기서 고치는 것은 고객 본인의 메모다.
     */
    @PatchMapping("/{transactionId}/memo")
    public Transaction updateMemo(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER,
                    required = false) String authenticatedCustomerId,
            @PathVariable Long transactionId,
            @Valid @RequestBody MemoUpdateRequest request) {
        customerValidator.validateTransactionOwner(authenticatedCustomerId, transactionId);
        return transactionService.updateCustomerMemo(transactionId, request.memo());
    }

}
