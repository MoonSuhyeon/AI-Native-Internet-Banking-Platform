package com.bank.payment.common;

public final class BankCodeMapper {

    private BankCodeMapper() {}

    /** 알파벳 은행코드(A/B) 또는 이미 숫자인 값을 3자리 숫자코드로 변환. B→"088", 그 외→"004". */
    public static String toNumeric(String alphaOrNumeric) {
        return "B".equalsIgnoreCase(alphaOrNumeric) ? "088" : "004";
    }
}
