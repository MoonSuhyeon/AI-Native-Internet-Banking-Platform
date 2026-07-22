package com.bank.ai.privacy;

import java.util.regex.Pattern;

/**
 * 외부 LLM 호출 전 마스킹 대상 PII 종류.
 *
 * 정규식은 한국 금융 도메인 빈출 패턴 기준.
 * 추후 NER 모델 보강 시 본 enum에 항목 추가하고 PiiMaskingFilter 의 적용 순서만 조정.
 */
public enum PiiPattern {

    /** 주민등록번호: 6자리-7자리 (하이픈 유무 모두 매칭). */
    RRN("RRN", Pattern.compile("\\b\\d{6}[- ]?[1-4]\\d{6}\\b")),

    /** 계좌번호: 9~16자리 숫자 (하이픈 허용). */
    ACCOUNT("ACCT", Pattern.compile("\\b\\d{2,6}[- ]?\\d{2,6}[- ]?\\d{2,6}\\b")),

    /** 휴대전화: 010-XXXX-XXXX 류. */
    PHONE("PHONE", Pattern.compile("\\b01[016789][- ]?\\d{3,4}[- ]?\\d{4}\\b")),

    /** 카드번호: 4-4-4-4 16자리. */
    CARD("CARD", Pattern.compile("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b")),

    /** 이메일. */
    EMAIL("EMAIL", Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b")),

    /** 한국 성명: 한글 2~4자. 정밀도 낮으니 NER 보강 권장. */
    KOREAN_NAME("NAME", Pattern.compile("(?<![가-힣])[가-힣]{2,4}(?![가-힣])"));

    private final String token;
    private final Pattern pattern;

    PiiPattern(String token, Pattern pattern) {
        this.token = token;
        this.pattern = pattern;
    }

    public String token() {
        return token;
    }

    public Pattern pattern() {
        return pattern;
    }
}
