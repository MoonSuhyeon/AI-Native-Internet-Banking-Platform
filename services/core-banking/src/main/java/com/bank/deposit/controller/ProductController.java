package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.*;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.dto.request.*;
import com.bank.deposit.dto.response.ProductResponse;
import com.bank.deposit.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // ── 공통 상품 ──────────────────────────────────────────────────────────────

    @GetMapping("/products")
    public List<ProductResponse> list(
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) ProductStatus productStatus) {
        return productService.findAllResponses(productType, productStatus);
    }

    @PostMapping("/products")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest req) {
        Product product = productService.create(
                req.productType(), req.productName(), req.description(),
                req.departmentId(), req.baseInterestRate(),
                req.minPeriodMonth(), req.maxPeriodMonth(),
                req.minJoinAmount(), req.maxJoinAmount(),
                req.isEarlyTerminationAllowed(), req.isTaxBenefitAvailable(),
                req.isAutoRenewalAvailable(), req.releasedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(product));
    }

    @GetMapping("/products/{productId:\\d+}")
    public ProductResponse get(@PathVariable Long productId) {
        return productService.findResponseById(productId);
    }

    @PutMapping("/products/{productId}")
    public ProductResponse update(@PathVariable Long productId, @Valid @RequestBody ProductUpdateRequest req) {
        return ProductResponse.from(productService.update(productId, req.productName(), req.description(), req.baseInterestRate()));
    }

    @PatchMapping("/products/{productId}")
    public ProductResponse changeStatus(@PathVariable Long productId, @Valid @RequestBody ProductStatusUpdateRequest req) {
        return ProductResponse.from(productService.changeStatus(productId, req.productStatus()));
    }

    // ── 수신 상품 ──────────────────────────────────────────────────────────────

    @GetMapping("/products/{productId}/deposit")
    public DepositProduct getDeposit(@PathVariable Long productId) {
        return productService.findDepositProduct(productId);
    }

    @PostMapping("/products/{productId}/deposit")
    public ResponseEntity<DepositProduct> createDeposit(@PathVariable Long productId,
                                                        @Valid @RequestBody DepositProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.createDepositProduct(productId, req.depositType(), req.isCompoundInterest()));
    }

    @PutMapping("/products/{productId}/deposit")
    public DepositProduct updateDeposit(@PathVariable Long productId, @Valid @RequestBody DepositProductRequest req) {
        return productService.updateDepositProduct(productId, req.depositType(), req.isCompoundInterest());
    }

    @DeleteMapping("/products/{productId}/deposit")
    public ResponseEntity<Void> deleteDeposit(@PathVariable Long productId) {
        productService.deleteDepositProduct(productId);
        return ResponseEntity.noContent().build();
    }

    // ── 적금 상품 ──────────────────────────────────────────────────────────────

    @GetMapping("/products/{productId}/savings")
    public SavingsProduct getSavings(@PathVariable Long productId) {
        return productService.findSavingsProduct(productId);
    }

    @PostMapping("/products/{productId}/savings")
    public ResponseEntity<SavingsProduct> createSavings(@PathVariable Long productId,
                                                        @Valid @RequestBody SavingsProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.createSavingsProduct(productId, req.savingType(),
                        req.monthlyPaymentMinAmount(), req.monthlyPaymentMaxAmount()));
    }

    @PutMapping("/products/{productId}/savings")
    public SavingsProduct updateSavings(@PathVariable Long productId, @Valid @RequestBody SavingsProductRequest req) {
        return productService.updateSavingsProduct(productId, req.savingType(),
                req.monthlyPaymentMinAmount(), req.monthlyPaymentMaxAmount());
    }

    // ── 청약 상품 ──────────────────────────────────────────────────────────────

    @GetMapping("/products/{productId}/subscription")
    public SubscriptionProduct getSubscription(@PathVariable Long productId) {
        return productService.findSubscriptionProduct(productId);
    }

    @PostMapping("/products/{productId}/subscription")
    public ResponseEntity<SubscriptionProduct> createSubscription(@PathVariable Long productId,
                                                                  @Valid @RequestBody SubscriptionProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.createSubscriptionProduct(productId, req.monthlyPaymentAmount(),
                        req.minMonthlyPayment(), req.maxMonthlyPayment(), req.maxRecognizedPaymentAmount()));
    }

    @PutMapping("/products/{productId}/subscription")
    public SubscriptionProduct updateSubscription(@PathVariable Long productId,
                                                  @Valid @RequestBody SubscriptionProductRequest req) {
        return productService.updateSubscriptionProduct(productId, req.monthlyPaymentAmount(),
                req.minMonthlyPayment(), req.maxMonthlyPayment());
    }

    // ── 가입 방식 ──────────────────────────────────────────────────────────────

    @GetMapping("/products/{productId}/join-channels")
    public List<ProductJoinChannel> getJoinChannels(@PathVariable Long productId) {
        return productService.findJoinChannels(productId);
    }

    @PostMapping("/products/{productId}/join-channels")
    public ResponseEntity<ProductJoinChannel> addJoinChannel(@PathVariable Long productId,
                                                             @Valid @RequestBody JoinChannelRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.addJoinChannel(productId, req.joinChannelCode()));
    }

    @DeleteMapping("/products/{productId}/join-channels/{channelId}")
    public ResponseEntity<Void> removeJoinChannel(@PathVariable Long productId, @PathVariable Long channelId) {
        productService.removeJoinChannel(productId, channelId);
        return ResponseEntity.noContent().build();
    }

    // ── 가입 대상 ──────────────────────────────────────────────────────────────

    @GetMapping("/products/{productId}/target-groups")
    public List<ProductTargetGroup> getProductTargetGroups(@PathVariable Long productId) {
        return productService.findProductTargetGroups(productId);
    }

    @PostMapping("/products/{productId}/target-groups")
    public ResponseEntity<ProductTargetGroup> linkTargetGroup(@PathVariable Long productId,
                                                              @Valid @RequestBody ProductTargetGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.linkTargetGroup(productId, req.targetGroupId()));
    }

    @DeleteMapping("/products/{productId}/target-groups/{targetGroupId}")
    public ResponseEntity<Void> unlinkTargetGroup(@PathVariable Long productId, @PathVariable Long targetGroupId) {
        productService.unlinkTargetGroup(productId, targetGroupId);
        return ResponseEntity.noContent().build();
    }

    // ── 금리 관리 ──────────────────────────────────────────────────────────────

    @GetMapping("/products/{productId}/interest-rates")
    public List<ProductInterestRate> getInterestRates(@PathVariable Long productId) {
        return productService.findInterestRates(productId);
    }

    @PostMapping("/products/{productId}/interest-rates")
    public ResponseEntity<ProductInterestRate> createInterestRate(@PathVariable Long productId,
                                                                  @Valid @RequestBody InterestRateCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.createInterestRate(productId, req.rateType(), req.rate(),
                        req.effectiveStartDate(), req.minimumContractPeriod(), req.maximumContractPeriod(),
                        req.minimumJoinAmount(), req.maximumJoinAmount(), req.conditionDescription()));
    }

    @GetMapping("/products/{productId}/interest-rates/{rateId}")
    public ProductInterestRate getInterestRate(@PathVariable Long productId, @PathVariable Long rateId) {
        return productService.findInterestRate(productId, rateId);
    }

    @PutMapping("/products/{productId}/interest-rates/{rateId}")
    public ProductInterestRate updateInterestRate(@PathVariable Long productId, @PathVariable Long rateId,
                                                  @Valid @RequestBody InterestRateUpdateRequest req) {
        return productService.updateInterestRate(productId, rateId, req.rate(), req.effectiveEndDate());
    }

    @PatchMapping("/products/{productId}/interest-rates/{rateId}/expire")
    public ProductInterestRate expireInterestRate(@PathVariable Long productId, @PathVariable Long rateId) {
        return productService.expireInterestRate(productId, rateId);
    }

    // ── 특약 연결 ──────────────────────────────────────────────────────────────

    @GetMapping("/products/{productId}/special-terms")
    public List<ProductSpecialTerm> getProductSpecialTerms(@PathVariable Long productId) {
        return productService.findProductSpecialTerms(productId);
    }

    @PostMapping("/products/{productId}/special-terms")
    public ResponseEntity<ProductSpecialTerm> linkSpecialTerm(@PathVariable Long productId,
                                                              @Valid @RequestBody ProductSpecialTermRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.linkSpecialTerm(productId, req.specialTermId(), req.isRequired()));
    }

    @DeleteMapping("/products/{productId}/special-terms/{specialTermId}")
    public ResponseEntity<Void> unlinkSpecialTerm(@PathVariable Long productId, @PathVariable Long specialTermId) {
        productService.unlinkSpecialTerm(productId, specialTermId);
        return ResponseEntity.noContent().build();
    }
}
