package com.bank.payment.outbound.feign;

/**
 * deposit-service ErrorCode.name() → payment failure_category 변환.
 * 유효값은 V1__create_payment_instruction.sql chk_pi_failure_category CHECK 제약 기준.
 */
public class DepositErrorMapper {

    private DepositErrorMapper() {}

    /**
     * deposit ErrorCode.name() → payment failure_category.
     * 반환값은 반드시 DB CHECK 허용값 중 하나.
     * ALREADY_CANCELED(B-5 멱등)는 isAlreadyCanceled()로 별도 판정 후 호출자가 처리.
     */
    public static String toFailureCategory(String depositCode) {
        if (depositCode == null) return "SYSTEM_ERROR";
        return switch (depositCode) {
            case "INSUFFICIENT_BALANCE"                        -> "INSUFFICIENT_BALANCE";
            case "ACCOUNT_NOT_ACTIVE", "INVALID_STATUS"        -> "ACCOUNT_RESTRICTED";
            case "ACCOUNT_NOT_FOUND", "NOT_FOUND",
                 "TRANSACTION_NOT_FOUND", "CONTRACT_NOT_FOUND",
                 "PRODUCT_NOT_FOUND"                           -> "ACCOUNT_NOT_FOUND";
            case "FORBIDDEN"                                   -> "AUTH_FAILED";
            case "ALREADY_CANCELED"                            -> "ACCOUNT_RESTRICTED";
            case "INTERNAL_SERVER_ERROR", "DUPLICATE"          -> "SYSTEM_ERROR";
            default -> {
                // DEPOSIT_HTTP_404 → ACCOUNT_NOT_FOUND, DEPOSIT_HTTP_403 → AUTH_FAILED, 나머지 → SYSTEM_ERROR
                if (depositCode.equals("DEPOSIT_HTTP_404")) yield "ACCOUNT_NOT_FOUND";
                if (depositCode.equals("DEPOSIT_HTTP_403")) yield "AUTH_FAILED";
                yield "SYSTEM_ERROR";
            }
        };
    }

    /** B-5 출금취소 응답이 멱등 성공(이미 취소됨)인지 판정. */
    public static boolean isAlreadyCanceled(String depositCode) {
        return "ALREADY_CANCELED".equals(depositCode);
    }
}
