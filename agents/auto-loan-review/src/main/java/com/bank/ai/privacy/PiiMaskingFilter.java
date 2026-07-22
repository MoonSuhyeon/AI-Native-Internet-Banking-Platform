package com.bank.ai.privacy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * 외부 LLM 호출 직전 호출되는 단방향-가역 PII 마스킹 게이트웨이.
 *
 * 같은 원문은 같은 토큰으로 치환되도록 요청 단위 매핑을 보존한다(LLM 응답에서 역치환 가능).
 * 매핑은 요청 lifetime 동안만 in-memory 로 유지. 운영시 Redis TTL 로 이동 예정.
 *
 * 적용 순서는 enum 선언 순서를 따른다 — 더 구체적인 패턴(주민번호/카드)을
 * 일반적인 패턴(이메일/이름)보다 먼저 두어 부분매칭 충돌을 줄인다.
 */
@Component
public class PiiMaskingFilter {

    /**
     * 마스킹 결과와 역치환용 매핑을 함께 보관한다.
     */
    public record MaskingResult(String maskedText, Map<String, String> mapping) {

        /** LLM 응답 텍스트에서 마스킹 토큰을 원문으로 복원한다. */
        public String unmask(String llmResponse) {
            String restored = llmResponse;
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                restored = restored.replace(e.getKey(), e.getValue());
            }
            return restored;
        }
    }

    /**
     * 입력 텍스트에서 모든 PII 패턴을 마스킹 토큰으로 치환한다.
     *
     * @param text 원문
     * @return 마스킹 결과 + 역치환 매핑
     */
    public MaskingResult mask(String text) {
        if (text == null || text.isEmpty()) {
            return new MaskingResult(text, Map.of());
        }
        // 매핑은 삽입 순서 보존(역치환 시 긴 토큰부터 매칭되도록).
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, String> reverseCache = new LinkedHashMap<>(); // 원문 -> 토큰 (중복 토큰 방지)

        String working = text;
        for (PiiPattern p : PiiPattern.values()) {
            Matcher m = p.pattern().matcher(working);
            StringBuilder buf = new StringBuilder();
            while (m.find()) {
                String match = m.group();
                String token = reverseCache.computeIfAbsent(match, k -> generateToken(p));
                mapping.putIfAbsent(token, match);
                m.appendReplacement(buf, Matcher.quoteReplacement(token));
            }
            m.appendTail(buf);
            working = buf.toString();
        }
        return new MaskingResult(working, mapping);
    }

    /**
     * 텍스트에 원시 PII 패턴이 1개 이상 포함되면 {@code true}.
     *
     * <p>LLM 출력 사후 검사에 사용. 마스킹 토큰({@code [[EMAIL_xxx]]})은 PII 패턴이 아니므로
     * false 를 반환한다.
     *
     * @param text 검사할 텍스트 (직렬화된 LLM 응답 JSON 등)
     * @return PII 패턴이 하나라도 매칭되면 true
     */
    public boolean containsPii(String text) {
        if (text == null || text.isEmpty()) return false;
        for (PiiPattern p : PiiPattern.values()) {
            if (p.pattern().matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * 텍스트에 PII 가 있으면 {@link PiiLeakageException} 던짐 — plan §8 출력 사후 검사.
     *
     * @param text    검사 대상 (LLM 응답 직렬화 문자열)
     * @param context 예외 메시지에 포함될 컨텍스트 (promptId 등)
     * @throws PiiLeakageException PII 패턴 감지 시
     */
    public void assertNoPii(String text, String context) {
        if (containsPii(text)) {
            throw new PiiLeakageException(context);
        }
    }

    /**
     * 텍스트에 고신뢰도 PII(주민번호·계좌·전화·카드·이메일)가 포함되면 {@code true}.
     *
     * <p>LLM 출력 사후 검사 전용. {@link PiiPattern#KOREAN_NAME}은 일반 한국어 문장에서
     * 오탐이 빈번하므로 제외한다(NER 보강 이전 임시 조치 — plan §8).
     *
     * @param text 검사할 텍스트
     * @return 고신뢰도 PII 패턴이 하나라도 매칭되면 true
     */
    public boolean containsSensitivePii(String text) {
        if (text == null || text.isEmpty()) return false;
        for (PiiPattern p : PiiPattern.values()) {
            if (p == PiiPattern.KOREAN_NAME) continue;
            if (p.pattern().matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * 출력 사후 검사용 고신뢰도 PII 어서션 — plan §8.
     *
     * <p>{@link #assertNoPii}와 달리 KOREAN_NAME 을 제외해 LLM 일반 한국어 응답의
     * 오탐을 방지한다.
     *
     * @param text    검사 대상 (LLM 응답 직렬화 문자열)
     * @param context 예외 메시지에 포함될 컨텍스트 (promptId 등)
     * @throws PiiLeakageException 고신뢰도 PII 패턴 감지 시
     */
    public void assertNoSensitivePii(String text, String context) {
        if (containsSensitivePii(text)) {
            throw new PiiLeakageException(context);
        }
    }

    private String generateToken(PiiPattern p) {
        // [[RRN_a1b2c3d4]] 형식 — LLM 이 원문으로 오해하지 않도록 대괄호 2중 + 짧은 UUID.
        return "[[" + p.token() + "_" + UUID.randomUUID().toString().substring(0, 8) + "]]";
    }
}
