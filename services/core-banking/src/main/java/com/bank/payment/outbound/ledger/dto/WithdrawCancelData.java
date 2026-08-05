package com.bank.payment.outbound.feign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * B-5 PATCH /api/transactions/{transactionId}/cancel 응답 data.
 * deposit이 bare Transaction 엔티티(40+ 필드)를 직렬화해 내려보내므로,
 * 실제 읽히는 필드만 선언하고 나머지 unknown field는 @JsonIgnoreProperties로 무시.
 * balanceBefore/balanceAfter: deposit BigDecimal(scale=2) → "50000.00" 형태 → BigDecimal 수신.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WithdrawCancelData(
        String cancelTransactionNo,             // ← deposit Transaction.transactionNumber
        String originalDepositTransactionNo,    // 미매핑: deposit은 originalTransactionId(Long FK)만 보유
        String accountNo,                       // 미매핑: deposit Transaction에 accountNo 필드 없음
        BigDecimal balanceBefore,               // 취소 직전 잔액 (R01 역분개 박제용)
        BigDecimal balanceAfter,                // 취소 후 잔액 복원 (R01 역분개 박제용)
        String canceledAt                       // 미매핑: REVERSAL row의 canceledAt은 null
) {}
