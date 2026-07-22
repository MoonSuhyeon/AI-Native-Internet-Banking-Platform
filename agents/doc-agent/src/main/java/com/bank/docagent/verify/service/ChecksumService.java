package com.bank.docagent.verify.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 주민번호·사업자번호 체크섬 검증.
 * 원본 SSN은 이 서비스 내부에서만 사용, 외부 노출·로그 출력 금지.
 */
@Slf4j
@Service
public class ChecksumService {

    private static final int[] SSN_WEIGHTS = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};

    /**
     * @param maskedSsn "YYMMDD-G******" 형식 (마스킹된 값)
     * @param rawSsnHint OCR이 추출한 원본 (null이면 체크섬 SKIP)
     * @return true = 유효, false = 체크섬 실패
     */
    public boolean validateSsn(String maskedSsn, String rawSsnHint) {
        if (rawSsnHint == null || rawSsnHint.isBlank()) {
            log.debug("SSN 원본 없음 — 체크섬 SKIP");
            return true; // SKIP이지 실패가 아님
        }
        String digits = rawSsnHint.replaceAll("[^0-9]", "");
        if (digits.length() != 13) {
            log.debug("SSN 자릿수 불일치: len={}", digits.length());
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (digits.charAt(i) - '0') * SSN_WEIGHTS[i];
        }
        int check = (11 - (sum % 11)) % 10;
        boolean valid = check == (digits.charAt(12) - '0');
        if (!valid) log.warn("SSN 체크섬 실패 (앞 6자리: {})", digits.substring(0, 6));
        return valid;
    }

    /**
     * 사업자번호 체크섬 (10자리).
     */
    public boolean validateBusinessNumber(String raw) {
        if (raw == null) return true;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() != 10) return false;
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (digits.charAt(i) - '0') * w[i];
        sum += (int) Math.floor((digits.charAt(8) - '0') * 5 / 10.0);
        int check = (10 - (sum % 10)) % 10;
        return check == (digits.charAt(9) - '0');
    }
}
