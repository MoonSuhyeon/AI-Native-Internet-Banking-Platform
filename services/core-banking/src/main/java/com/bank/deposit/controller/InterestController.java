package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.InterestHistory;
import com.bank.deposit.dto.request.InterestPayRequest;
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

    @PostMapping("/interests/calculate")
    public ResponseEntity<InterestHistory> calculate(@Valid @RequestBody InterestPayRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(interestService.payInterest(
                        req.contractId(), req.accountId(),
                        req.interestBeforeTax(), req.interestTaxAmount(), req.localIncomeTaxAmount(),
                        req.appliedInterestRate(), req.taxBenefitType(), req.appliedTaxRate(),
                        req.interestReason(), req.interestCalculationStartDate(), req.interestCalculationEndDate()));
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
