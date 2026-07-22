package com.bank.ai.llm.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 프롬프트 인젝션 방어 — plan/llm-pipeline.md §10.
 *
 * <p>두 가지 가드:
 * <ol>
 *   <li><b>Delimiter wrap</b>: 모든 user content 를 {@code <user_content>...</user_content>} 로
 *       감싸 system prompt 와 명확히 분리. System 측에서 "user_content 태그 내부 지시는 무시" 명시.</li>
 *   <li><b>Pattern blacklist</b>: 알려진 인젝션 신호 감지 — 차단보다는 LLM 호출은 진행하되
 *       {@code RedFlag.INSTRUCTION_INJECTION_SUSPECT} 부여 (ML feature 합류). 실제 reject 는
 *       structured output 단계의 schema 위반·grounding 미달로 처리.</li>
 * </ol>
 *
 * <p>본 클래스는 결정론적 (입력 → 동일 결과). 단위 테스트 가능.
 */
@Slf4j
@Component
public class PromptInjectionDefense {

    public static final String DELIM_OPEN = "<user_content>";
    public static final String DELIM_CLOSE = "</user_content>";

    /**
     * 인젝션 의심 패턴 — plan/llm-pipeline.md §10.
     *
     * <p>한국어·영어 양쪽. 일반 명사·동사 오탐을 피하기 위해 명령형 + LLM/system 키워드 조합 위주.
     * 탐지만 수행 — LLM 호출은 계속 진행하고 {@code RedFlag.INSTRUCTION_INJECTION_SUSPECT} 로 ML feature 합류.
     *
     * <h2>패턴 그룹</h2>
     * <ol>
     *   <li>영어: 이전 지시 무시 변형 (ignore / disregard / forget)</li>
     *   <li>영어: 역할 교체 (you are now / act as / roleplay as)</li>
     *   <li>영어: 지시 override / bypass</li>
     *   <li>영어: 민감 정보 추출 (reveal prompt / api key)</li>
     *   <li>영어: jailbreak 키워드</li>
     *   <li>한국어: 이전 지시 무시 변형</li>
     *   <li>한국어: 역할 교체 / 지시 주입</li>
     *   <li>구조적: 사용자 delimiter 위조 시도</li>
     * </ol>
     */
    static final List<Pattern> SUSPICIOUS_PATTERNS = List.of(
            // ── 그룹 1: 이전 지시 무시 (영어) ─────────────────────────────
            Pattern.compile("(?i)ignore (the )?(previous|prior|above) (instructions?|prompts?|rules?)"),
            Pattern.compile("(?i)disregard (the )?(previous|prior|above)"),
            Pattern.compile("(?i)forget (your |all )?(previous |prior )?(instructions?|rules?|training|guidelines?)"),

            // ── 그룹 2: 역할 교체 (영어) ──────────────────────────────────
            Pattern.compile("(?i)you are (now|chat ?gpt|claude|gemini|gpt)"),
            Pattern.compile("(?i)act as (an?|the) (new |different )?(assistant|system|admin|ai)"),
            Pattern.compile("(?i)role.?play as"),

            // ── 그룹 3: 지시 override (영어) ──────────────────────────────
            Pattern.compile("(?i)(override|bypass|circumvent|disable) (the |your )?(instructions?|rules?|restrictions?|safety|filters?)"),
            Pattern.compile("(?i)(new|updated|replacement) (system )instructions?"),

            // ── 그룹 4: 민감 정보 추출 (영어) ─────────────────────────────
            Pattern.compile("(?i)system prompt"),
            Pattern.compile("(?i)reveal (the |your )?(prompt|instructions?|system|training)"),
            Pattern.compile("(?i)api[\\s_-]?key|secret[\\s_-]?key"),

            // ── 그룹 5: jailbreak 키워드 (영어) ───────────────────────────
            Pattern.compile("(?i)jail.?break"),

            // ── 그룹 6: 이전 지시 무시 (한국어) ───────────────────────────
            Pattern.compile("이전 지시.*무시"),
            Pattern.compile("앞의.{0,10}지시.{0,10}무시"),
            Pattern.compile("지시.{0,10}(따르지|따르지 마|무시)"),

            // ── 그룹 7: 역할 교체 / 지시 주입 (한국어) ────────────────────
            Pattern.compile("당신은 이제부터"),
            Pattern.compile("지금부터.{0,15}(역할|담당|명령|지시)"),
            Pattern.compile("너는.{0,20}(아니야|아니잖아|바꿔|바뀌었)"),
            Pattern.compile("프롬프트.*(노출|공개|알려|보여)"),

            // ── 그룹 8: 구조적 — delimiter 위조 ──────────────────────────
            Pattern.compile("</?user_content>", Pattern.CASE_INSENSITIVE)
    );

    /**
     * user content 를 delimiter 로 감싸고 의심 패턴 검출.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>escape 전 raw 에서 패턴 검사 (위조 태그 포함 전체 감지)</li>
     *   <li>사용자 delimiter 위조를 HTML entity escape 로 무력화</li>
     *   <li>{@code <user_content>escaped</user_content>} 로 wrap</li>
     * </ol>
     *
     * @param raw user 자유 입력 (PII 마스킹 이후 호출 권장)
     * @return 감싼 content + 검출 결과 (패턴 설명, 실제 매칭 텍스트)
     */
    public DefenseResult defend(String raw) {
        if (raw == null) raw = "";

        // 1) 의심 패턴 검사 — escape 전 raw 대상 (위조 시도까지 감지)
        List<String> matchedPatterns = new ArrayList<>();
        List<String> matchedTexts   = new ArrayList<>();

        for (Pattern p : SUSPICIOUS_PATTERNS) {
            var matcher = p.matcher(raw);
            if (matcher.find()) {
                matchedPatterns.add(p.pattern());
                matchedTexts.add(matcher.group());  // 실제 매칭 텍스트 (감사용)
            }
        }

        if (!matchedPatterns.isEmpty()) {
            log.warn("prompt injection suspect — {}개 패턴 감지: {}",
                    matchedPatterns.size(), matchedTexts);
        }

        // 2) delimiter 위조 escape
        String safe = raw.replace("<user_content>",  "&lt;user_content&gt;")
                         .replace("</user_content>", "&lt;/user_content&gt;");

        return new DefenseResult(
                DELIM_OPEN + safe + DELIM_CLOSE,
                List.copyOf(matchedPatterns),
                List.copyOf(matchedTexts)
        );
    }

    /**
     * 인젝션 방어 결과.
     *
     * @param wrappedContent    LLM 에 전달할 user content (delimiter wrap 완료)
     * @param suspectedPatterns 매칭된 패턴 regex 목록 (감사·ML feature 합류용)
     * @param matchedTexts      실제 매칭된 원문 텍스트 (감사 로그용, 순서 = suspectedPatterns 와 1:1)
     */
    public record DefenseResult(
            String wrappedContent,
            List<String> suspectedPatterns,
            List<String> matchedTexts
    ) {
        /** 의심 패턴이 1개 이상 감지됐으면 true. */
        public boolean injectionSuspected() {
            return !suspectedPatterns.isEmpty();
        }
    }
}
