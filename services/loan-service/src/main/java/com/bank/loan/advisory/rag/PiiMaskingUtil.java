package com.bank.loan.advisory.rag;

import java.util.regex.Pattern;

/**
 * 사례 인덱스 적재 전 PII 정규식 마스킹 유틸 (plan §11.6.2).
 *
 * 마스킹 대상:
 *   - 주민번호   \d{6}-\d{7}          → [RRN]
 *   - 계좌번호   \d{3,6}-\d{2,6}-\d{6,} → [ACCT]
 *   - 휴대전화   01[016789]-?\d{3,4}-?\d{4} → [PHONE]
 *   - 한글 이름(2~4자) 뒤에 '님' 또는 '씨'가 오는 경우 → [NAME]
 *
 * 마스킹 검증은 {@code AdvisoryRagFlowTest} 에서 정규식 불발 확인 포함.
 */
public final class PiiMaskingUtil {

    private PiiMaskingUtil() {}

    private static final Pattern RRN   = Pattern.compile("\\d{6}-\\d{7}");
    private static final Pattern ACCT  = Pattern.compile("\\d{3,6}-\\d{2,6}-\\d{6,}");
    private static final Pattern PHONE = Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}");
    private static final Pattern NAME  = Pattern.compile("[가-힣]{2,4}(?=님|씨)");

    public static String mask(String text) {
        if (text == null) return null;
        text = RRN.matcher(text).replaceAll("[RRN]");
        text = ACCT.matcher(text).replaceAll("[ACCT]");
        text = PHONE.matcher(text).replaceAll("[PHONE]");
        text = NAME.matcher(text).replaceAll("[NAME]");
        return text;
    }
}
