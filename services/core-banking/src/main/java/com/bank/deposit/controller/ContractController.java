package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.*;
import com.bank.deposit.domain.enums.ContractStatus;
import com.bank.deposit.dto.request.*;
import com.bank.deposit.dto.request.PreferentialRateRequest;
import org.springframework.web.bind.annotation.RequestBody;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.ContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;
    private final AuthenticatedCustomerValidator customerValidator;

    // ── 계약 ───────────────────────────────────────────────────────────────────

    @PostMapping("/contracts")
    public ResponseEntity<Contract> create(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @Valid @RequestBody ContractCreateRequest req) {
        customerValidator.validate(authenticatedCustomerId, req.customerId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contractService.createContract(
                        req.customerId(), req.productId(), req.joinAmount(),
                        req.contractPeriodMonth(), req.joinChannel(),
                        req.contractInterestRate(), req.totalPreferentialRate(),
                        req.taxBenefitType(), req.isAutoRenewal(),
                        req.autoTransferEnabled(), req.autoTransferDay(),
                        req.sourceAccountId(),
                        req.branchId(), req.managerId(), req.savingType(), req.accountPassword()));
    }

    @GetMapping("/contracts")
    public List<Contract> list(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) ContractStatus contractStatus) {
        customerValidator.validate(authenticatedCustomerId, customerId);
        return contractService.findAll(customerId, contractStatus);
    }

    @GetMapping("/contracts/{contractId}")
    public Contract get(@PathVariable Long contractId) {
        return contractService.findById(contractId);
    }

    @PatchMapping("/contracts/{contractId}/status")
    public Contract changeStatus(@PathVariable Long contractId,
                                 @Valid @RequestBody ContractStatusUpdateRequest req) {
        return contractService.changeStatus(contractId, req.contractStatus());
    }

    @PatchMapping("/contracts/{contractId}/terminate")
    public Contract terminate(@PathVariable Long contractId, @RequestBody ContractTerminateRequest req) {
        return contractService.terminate(contractId, req.terminationReason(), req.targetAccountId());
    }

    @PatchMapping("/contracts/{contractId}/maturity")
    public Contract mature(@PathVariable Long contractId) {
        return contractService.mature(contractId);
    }

    @PatchMapping("/contracts/{contractId}/auto-transfer-day")
    public ResponseEntity<Void> updateAutoTransferDay(@PathVariable Long contractId,
                                                      @Valid @RequestBody AutoTransferDayUpdateRequest req) {
        contractService.updateAutoTransferDay(contractId, req.autoTransferDay());
        return ResponseEntity.noContent().build();
    }

    // ── 수신 계약 ──────────────────────────────────────────────────────────────

    @GetMapping("/contracts/{contractId}/deposit")
    public Contract getDepositContract(@PathVariable Long contractId) {
        return contractService.findDepositContract(contractId);
    }

    @PostMapping("/contracts/{contractId}/deposit")
    public ResponseEntity<Contract> setupDepositContract(@PathVariable Long contractId,
                                                         @RequestBody DepositContractRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contractService.updateDepositSettings(contractId, req.autoTransferEnabled(), req.autoTransferDay()));
    }

    @PutMapping("/contracts/{contractId}/deposit")
    public Contract updateDepositContract(@PathVariable Long contractId,
                                          @RequestBody DepositContractRequest req) {
        return contractService.updateDepositSettings(contractId, req.autoTransferEnabled(), req.autoTransferDay());
    }

    // ── 적용 금리 ──────────────────────────────────────────────────────────────

    @GetMapping("/contracts/{contractId}/applied-rates")
    public List<ContractAppliedRate> getAppliedRates(@PathVariable Long contractId) {
        return contractService.findAppliedRates(contractId);
    }

    @PostMapping("/contracts/{contractId}/applied-rates")
    public ResponseEntity<ContractAppliedRate> saveAppliedRate(@PathVariable Long contractId,
                                                               @Valid @RequestBody AppliedRateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contractService.saveAppliedRate(contractId, req.rateId(), req.appliedRate(), req.conditionVerifiedYn()));
    }

    // ── 특약 동의 ──────────────────────────────────────────────────────────────

    @GetMapping("/contracts/{contractId}/special-terms")
    public List<ContractSpecialTermAgreement> getAgreements(@PathVariable Long contractId) {
        return contractService.findAgreements(contractId);
    }

    @PostMapping("/contracts/{contractId}/special-terms")
    public ResponseEntity<ContractSpecialTermAgreement> agree(@PathVariable Long contractId,
                                                              @Valid @RequestBody SpecialTermAgreementRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contractService.agree(contractId, req.specialTermId(), req.isAgreed(),
                        req.agreedAt(), req.agreementIpAddress(), req.agreementDeviceInfo(), req.isElectronicSigned()));
    }

    // ── 우대금리 적용 ──────────────────────────────────────────────────────────

    @GetMapping("/contracts/{contractId}/preferential-rates")
    public List<ContractAppliedRate> getPreferentialRates(@PathVariable Long contractId) {
        return contractService.findAppliedRates(contractId);
    }

    @PostMapping("/contracts/{contractId}/preferential-rates")
    public ResponseEntity<ContractAppliedRate> addPreferentialRate(@PathVariable Long contractId,
                                                                   @RequestBody PreferentialRateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contractService.savePreferentialRate(contractId, req.conditionName(), req.appliedRate(), req.appliedYn()));
    }

    @DeleteMapping("/contracts/{contractId}/preferential-rates/{preferentialRateId}")
    public ResponseEntity<Void> deletePreferentialRate(@PathVariable Long contractId,
                                                       @PathVariable Long preferentialRateId) {
        contractService.deleteAppliedRate(preferentialRateId);
        return ResponseEntity.noContent().build();
    }

}
