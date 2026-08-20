package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.InterestHistory;
import com.bank.deposit.dto.request.InterestPayRequest;
import com.bank.deposit.dto.response.InterestIncomeStatementResponse;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.InterestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class InterestController {

    private final InterestService interestService;
    private final AuthenticatedCustomerValidator customerValidator;

    @PostMapping("/interests/calculate")
    public ResponseEntity<InterestHistory> calculate(@Valid @RequestBody InterestPayRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(interestService.payInterest(
                        req.contractId(), req.accountId(),
                        req.interestBeforeTax(), req.interestTaxAmount(), req.localIncomeTaxAmount(),
                        req.appliedInterestRate(), req.taxBenefitType(), req.appliedTaxRate(),
                        req.interestReason(), req.interestCalculationStartDate(), req.interestCalculationEndDate()));
    }

    /**
     * 이자소득 원천징수영수증 — 화면의 "연말정산증명서".
     *
     * <p>고객번호를 쿼리로 받지 않는다. 게이트웨이가 넣어 준 신원으로만 정한다 —
     * 받으면 값을 바꿔 남이 그 해에 이자를 얼마 받았는지 볼 수 있고, 그건 자산
     * 규모를 짐작하게 하는 값이다.
     */
    @GetMapping("/interests/income-statement")
    public InterestIncomeStatementResponse incomeStatement(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false)
            String authenticatedCustomerId,
            @RequestParam int taxYear) {
        String customerId = customerValidator.requireCustomer(authenticatedCustomerId);
        return interestService.interestIncomeStatement(customerId, taxYear);
    }

    @GetMapping("/interests")
    public List<InterestHistory> list(@RequestParam Long contractId) {
        return interestService.findByContract(contractId);
    }

    @GetMapping("/interests/{interestId}")
    public InterestHistory get(@PathVariable Long interestId) {
        return interestService.findById(interestId);
    }

    @GetMapping("/contracts/{contractId}/interests")
    public List<InterestHistory> listByContract(@PathVariable Long contractId) {
        return interestService.findByContract(contractId);
    }
}
