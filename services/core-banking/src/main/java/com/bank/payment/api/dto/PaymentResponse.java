package com.bank.payment.api.dto;

import com.bank.deposit.security.RiskGuidance;

import java.util.List;
import java.time.OffsetDateTime;

/**
 * 결제 응답. 200 OK — COMPLETED(정상), FAILED(비즈니스 거절), SCHEDULED(지연 접수).
 *
 * <p>failureCategory: FAILED 시 원인 코드(INSUFFICIENT_BALANCE 등), 정상 시 null.
 *
 * <p><b>SCHEDULED 는 실패가 아니다.</b> 이상거래 점검이 지연을 지시하면 거래는
 * 접수되고 {@code delayedUntil} 에 실행된다. 그때까지 고객이 취소할 수 있다 —
 * 화면이 이것을 실패로 보여주면 지연 장치의 의미가 사라진다. 취소할 기회를 준
 * 것인데 고객은 안 된 줄 알고 다시 시도하게 된다.
 */
public record PaymentResponse(
        String paymentInstructionId,
        String transactionNo,
        String status,
        OffsetDateTime completedAt,
        String failureCategory,
        /** 지연 실행 예정 시각. 지연되지 않았으면 null. */
        OffsetDateTime delayedUntil,
        /**
         * 왜 지연됐는가 — <b>감사 기록용</b>.
         *
         * <p>주석은 오래 "고객 안내 문구의 근거" 라고 적혀 있었지만 실제로 담기던 값은
         * 탐지 신호 객체의 {@code toString()} 이었다. 화면에 그대로 쓰면
         * {@code {code=HIGH_AMOUNT, severity=MEDIUM, detail=...}} 이 뜬다.
         * 고객에게 보여 줄 것은 {@link #guidance} 다.
         */
        List<String> delayReasons,

        /** 고객에게 보여 줄 안내 — 왜 미뤘고, 지금 무엇을 할 수 있는지. */
        RiskGuidance guidance
) {

    /** 지연 없이 끝난 보통의 응답. */
    public static PaymentResponse of(String paymentInstructionId, String transactionNo,
                                     String status, OffsetDateTime completedAt,
                                     String failureCategory) {
        return new PaymentResponse(paymentInstructionId, transactionNo, status,
                completedAt, failureCategory, null, null, null);
    }

    /** 이상거래 점검이 지연을 지시한 응답. */
    public static PaymentResponse delayed(String paymentInstructionId, String transactionNo,
                                          OffsetDateTime delayedUntil, List<String> reasons,
                                          RiskGuidance guidance) {
        return new PaymentResponse(paymentInstructionId, transactionNo, "SCHEDULED",
                null, null, delayedUntil, reasons, guidance);
    }
}
