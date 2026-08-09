package com.bank.loan.advisory.rag;

import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 정책 인용 검색 결과 Redis 캐시.
 *
 * 키: rag:policy:{ruleCd}:{topK}:{md5(queryText)}
 * TTL: advisory.rag.cache.ttl-hours (기본 6시간)
 *
 * 조회/저장 실패는 모두 warn 로그 후 pass-through — 캐시 장애가 검색 장애로 전파되지 않는다.
 * 빈 결과(ai-service 장애 포함)는 저장하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyCitationCache {

    static final String KEY_PREFIX = "rag:policy:";

    private static final TypeReference<List<PolicyCitationResponse.CitationItem>> CITATION_LIST_TYPE =
            new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${advisory.rag.cache.ttl-hours:6}")
    private long ttlHours;

    public Optional<List<PolicyCitationResponse.CitationItem>> get(String ruleCd, String queryText, int topK) {
        String key = buildKey(ruleCd, queryText, topK);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, CITATION_LIST_TYPE));
        } catch (Exception e) {
            log.warn("[PolicyCitationCache] 캐시 조회 실패 (무시) key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String ruleCd, String queryText, int topK,
                    List<PolicyCitationResponse.CitationItem> items) {
        if (items.isEmpty()) return;
        String key = buildKey(ruleCd, queryText, topK);
        try {
            String json = objectMapper.writeValueAsString(items);
            redis.opsForValue().set(key, json, Duration.ofHours(ttlHours));
        } catch (Exception e) {
            log.warn("[PolicyCitationCache] 캐시 저장 실패 (무시) key={}: {}", key, e.getMessage());
        }
    }

    /** 정책문서 갱신 시 해당 룰의 캐시 일괄 무효화. */
    public void evictByRuleCd(String ruleCd) {
        String pattern = KEY_PREFIX + ruleCd + ":*";
        try {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("[PolicyCitationCache] 룰 캐시 무효화 — ruleCd={} {}건", ruleCd, keys.size());
            }
        } catch (Exception e) {
            log.warn("[PolicyCitationCache] 캐시 무효화 실패 (무시) pattern={}: {}", pattern, e.getMessage());
        }
    }

    private String buildKey(String ruleCd, String queryText, int topK) {
        return KEY_PREFIX + ruleCd + ":" + topK + ":" + md5(queryText);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
