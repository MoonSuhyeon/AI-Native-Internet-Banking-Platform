package com.bank.harness.pii;

import java.util.regex.Pattern;

/**
 * 프로세스 밖으로 나가는 문자열에서 개인식별정보를 가린다.
 *
 * <p>같은 규칙이 이미 {@code review-ai-gateway} 의 {@code PiiMaskingUtil} 에 있었지만
 * 거기 묶여 있어서 <b>도구 응답에만</b> 걸렸다. 외부 LLM 으로 나가는 프롬프트에는
 * 걸리는 곳이 없어, 프롬프트에 실린 고객 정보가 그대로 나갔다.
 *
 * <p>마스킹은 완전하지 않다. 정규식이 잡는 것만 가린다 — 주소·직장명처럼 형태가
 * 일정하지 않은 것은 통과한다. 그래서 이것은 <b>유출을 줄이는 장치</b>이지
 * "외부 LLM 을 써도 된다" 는 근거가 아니다. 정식 경로는 내부 추론이다.
 *
 * <p>가리는 것
 * <ul>
 *   <li>주민등록번호 {@code 000000-0000000} → {@code [RRN]}</li>
 *   <li>계좌번호 {@code 000-00-000000} → {@code [ACCT]}</li>
 *   <li>휴대전화 {@code 010-0000-0000} → {@code [PHONE]}</li>
 *   <li>이메일 → {@code [EMAIL]}</li>
 *   <li>한글 이름 2~4자 뒤에 '님' 또는 '씨' → {@code [NAME]}</li>
 * </ul>
 */
public final class PiiMasker {

    private PiiMasker() {}

    private static final Pattern RRN   = Pattern.compile("\\d{6}-\\d{7}");
    private static final Pattern ACCT  = Pattern.compile("\\d{3,6}-\\d{2,6}-\\d{6,}");
    private static final Pattern PHONE = Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern NAME  = Pattern.compile("[가-힣]{2,4}(?=님|씨)");

    /** 순서가 중요하다. 계좌번호를 먼저 가리면 주민번호 일부가 계좌로 잡힌다. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = RRN.matcher(text).replaceAll("[RRN]");
        masked = ACCT.matcher(masked).replaceAll("[ACCT]");
        masked = PHONE.matcher(masked).replaceAll("[PHONE]");
        masked = EMAIL.matcher(masked).replaceAll("[EMAIL]");
        masked = NAME.matcher(masked).replaceAll("[NAME]");
        return masked;
    }
}
