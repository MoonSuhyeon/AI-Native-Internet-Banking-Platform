package com.bank.ai.llm.prompt;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프롬프트 레지스트리 — plan/llm-pipeline.md §6.2.
 *
 * <p>애플리케이션 기동 시 {@code classpath:prompts/*.yml} 을 전부 로드해
 * {@code id:version} 키로 인덱싱. 이후 {@link #get(String, int)} 로 조회.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>런타임 변경 없음 — 프롬프트 변경 = YAML 수정 + 재기동.</li>
 *   <li>외부 의존성 없음 — SnakeYAML (Spring Boot 전이 의존) 만 사용.</li>
 *   <li>버전 미스매치는 기동 실패가 아닌 런타임 예외 — 서비스별 상수와 파일이 어긋나면
 *       첫 호출 시 {@link IllegalArgumentException} 발생.</li>
 * </ul>
 *
 * <h2>YAML 파일 명명 규칙</h2>
 * {@code {id}_v{version}.yml} — 예: {@code purpose_analysis_v1.yml}
 *
 * <h2>YAML 최상위 키 명세</h2>
 * <pre>
 * id:           &lt;string&gt;
 * version:      &lt;int&gt;
 * model:
 *   default:    &lt;string&gt;
 *   fallback:   &lt;string&gt;
 * system:       |
 *   &lt;multi-line&gt;
 * user_template: |
 *   &lt;template&gt;
 * output_schema: &lt;FQCN&gt;
 * max_tokens:   &lt;int&gt;
 * temperature:  &lt;float&gt;
 * changelog:
 *   - &lt;string&gt;
 * </pre>
 */
@Slf4j
@Component
public class PromptRegistry {

    private static final String PROMPTS_PATTERN = "classpath:prompts/*.yml";

    /** key = {@code id:version} */
    private Map<String, Prompt> registry = Collections.emptyMap();

    @PostConstruct
    public void load() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(PROMPTS_PATTERN);

        Map<String, Prompt> loaded = new HashMap<>();
        var yaml = new Yaml();

        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) yaml.load(is);
                if (raw == null) {
                    log.warn("PromptRegistry: 빈 YAML — {}", resource.getFilename());
                    continue;
                }
                Prompt prompt = parse(raw, resource.getFilename());
                String key = key(prompt.id(), prompt.version());
                if (loaded.containsKey(key)) {
                    log.warn("PromptRegistry: 중복 키 — {} ({})", key, resource.getFilename());
                }
                loaded.put(key, prompt);
                log.info("PromptRegistry loaded [{}] from {}", key, resource.getFilename());
            }
        }

        this.registry = Collections.unmodifiableMap(loaded);
        log.info("PromptRegistry 초기화 완료 — {} 개: {}", registry.size(), registry.keySet());
    }

    /**
     * 프롬프트 조회.
     *
     * @param id      프롬프트 id (예: {@code "purpose_analysis"})
     * @param version 정수 버전 (예: {@code 1})
     * @return 해당 {@link Prompt}
     * @throws IllegalArgumentException 존재하지 않는 id+version
     */
    public Prompt get(String id, int version) {
        var prompt = registry.get(key(id, version));
        if (prompt == null) {
            throw new IllegalArgumentException(
                    "PromptRegistry: 미등록 프롬프트 [%s:%d] — 총 %d 개 등록됨: %s"
                            .formatted(id, version, registry.size(), registry.keySet()));
        }
        return prompt;
    }

    /** 등록된 모든 프롬프트 (읽기 전용). 테스트·모니터링용. */
    public Map<String, Prompt> all() {
        return registry;
    }

    // ────────────────────────────────────────────────────────────

    private static String key(String id, int version) {
        return id + ":" + version;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Prompt parse(Map<String, Object> raw, String filename) {
        String id = requiredString(raw, "id", filename);
        int version = requiredInt(raw, "version", filename);

        Map modelMap = (Map) raw.getOrDefault("model", Map.of());
        String defaultModel = str(modelMap, "default", "stub-v1");
        String fallbackModel = str(modelMap, "fallback", "stub-v1");

        String system        = str(raw, "system", "");
        String userTemplate  = str(raw, "user_template", "");
        String outputSchema  = str(raw, "output_schema", "");

        int maxTokens   = num(raw, "max_tokens", 512);
        double temp     = numDouble(raw, "temperature", 0.0);

        List<String> changelog = raw.containsKey("changelog")
                ? (List<String>) raw.get("changelog")
                : List.of();

        return new Prompt(id, version, defaultModel, fallbackModel,
                system, userTemplate, outputSchema, maxTokens, temp, changelog);
    }

    private static String requiredString(Map<String, Object> m, String key, String file) {
        Object v = m.get(key);
        if (v == null) throw new IllegalStateException(
                "PromptRegistry: 필수 키 누락 [%s] — %s".formatted(key, file));
        return v.toString();
    }

    private static int requiredInt(Map<String, Object> m, String key, String file) {
        Object v = m.get(key);
        if (v == null) throw new IllegalStateException(
                "PromptRegistry: 필수 키 누락 [%s] — %s".formatted(key, file));
        return ((Number) v).intValue();
    }

    @SuppressWarnings("rawtypes")
    private static String str(Map m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    @SuppressWarnings("rawtypes")
    private static int num(Map m, String key, int def) {
        Object v = m.get(key);
        return v != null ? ((Number) v).intValue() : def;
    }

    @SuppressWarnings("rawtypes")
    private static double numDouble(Map m, String key, double def) {
        Object v = m.get(key);
        return v != null ? ((Number) v).doubleValue() : def;
    }
}
