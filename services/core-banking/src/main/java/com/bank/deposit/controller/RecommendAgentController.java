package com.bank.deposit.controller;

import com.bank.deposit.dto.response.ProductRecommendResponse;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.CashflowBasedRecommendService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
public class RecommendAgentController {

    private final CashflowBasedRecommendService cashflowBasedRecommendService;
    private final AuthenticatedCustomerValidator customerValidator;

    /**
     * 현금흐름 기반 수신 상품 추천.
     *
     * <p>⚠ IDOR 주의: customerId 는 API Gateway / Security 레이어에서 인증된
     * 토큰 기반 principal 로 대체되어야 합니다. 현재는 내부망 전용으로만 사용하세요.
     */
    @GetMapping("/products/recommend-agent")
    public ProductRecommendResponse recommend(
            @RequestHeader(value = AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, required = false) String authenticatedCustomerId,
            @RequestParam String customerId,
            @RequestParam(defaultValue = "3") @Min(1) int periodMonth,
            @RequestParam(required = false) Integer birthYear) {
        customerValidator.validate(authenticatedCustomerId, customerId);
        return cashflowBasedRecommendService.recommend(customerId, periodMonth, birthYear);
    }
}
