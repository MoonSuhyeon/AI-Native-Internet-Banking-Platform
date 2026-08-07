package com.bank.deposit.client.dto;

import java.math.BigDecimal;

/**
 * 인증보안계에 보내는 승인 토큰 검증 요청.
 *
 * <p>거래 내용을 함께 보내는 이유는 토큰이 그 거래에 묶여 있기 때문이다. 발급 때와
 * 다른 금액·계좌로 검증하면 인증보안계에서 조회 자체가 되지 않는다.
 */
public record TransactionApprovalVerifyRequest(
        String approvalToken,
        String fromAccountNo,
        String toAccountNo,
        Long amount
) {}
