package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.SubscriptionPaymentRecognitionHistory;
import com.bank.deposit.domain.enums.RecognitionStatus;
import com.bank.deposit.service.SubscriptionPaymentRecognitionHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/subscription-payment-histories")
@RequiredArgsConstructor
public class SubscriptionPaymentRecognitionHistoryController {

    private final SubscriptionPaymentRecognitionHistoryService service;

    /**
     * 계약 ID 로 청약 납입 인정 이력 전체 조회.
     * 선택적으로 status 파라미터로 필터링.
     * GET /api/subscription-payment-histories?contractId=1
     * GET /api/subscription-payment-histories?contractId=1&status=PENDING
     */
    @GetMapping
    public List<SubscriptionPaymentRecognitionHistory> list(
            @RequestParam Long contractId,
            @RequestParam(required = false) RecognitionStatus status) {
        if (status != null) {
            return service.findByContractIdAndStatus(contractId, status);
        }
        return service.findByContractId(contractId);
    }

    /**
     * 단건 조회.
     * GET /api/subscription-payment-histories/{id}
     */
    @GetMapping("/{id}")
    public SubscriptionPaymentRecognitionHistory get(@PathVariable Long id) {
        return service.findById(id);
    }
}
