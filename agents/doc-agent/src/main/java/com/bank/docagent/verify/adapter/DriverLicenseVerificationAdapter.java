package com.bank.docagent.verify.adapter;

import com.bank.docagent.verify.port.IdentityVerificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * 공공데이터포털 — 도로교통공단 운전면허 진위확인 실연동 어댑터.
 * DRIVER_LICENSE_API_ENABLED=true 인 경우에만 활성화.
 * 그 외 유형(RESIDENT_CARD, FOREIGNER_CARD)은 SKIPPED 반환 (협약 미보유).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "doc-agent.identity-verify.driver-license.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DriverLicenseVerificationAdapter implements IdentityVerificationPort {

    private static final String CACHE_TTL_HOURS = "24";

    private final RestClient restClient;
    private final JdbcTemplate jdbcTemplate;

    @Value("${doc-agent.identity-verify.driver-license.url}")    private String apiUrl;
    @Value("${doc-agent.identity-verify.driver-license.api-key}") private String apiKey;

    @Override
    public VerifyResult verify(VerifyType type, String name, String idNumber, String birthDate) {
        if (type != VerifyType.DRIVER_LICENSE) return VerifyResult.SKIPPED;

        String cacheKey = buildCacheKey(type, idNumber, birthDate);

        // 캐시 조회
        VerifyResult cached = queryCache(cacheKey);
        if (cached != null) {
            log.debug("진위확인 캐시 히트: cacheKey={} result={}", cacheKey, cached);
            return cached;
        }

        VerifyResult result = callApi(name, idNumber, birthDate);
        saveCache(cacheKey, result);
        return result;
    }

    private VerifyResult callApi(String name, String dlNo, String birthDate) {
        try {
            String uri = UriComponentsBuilder.fromHttpUrl(apiUrl)
                .queryParam("serviceKey", apiKey)
                .queryParam("dlNo", dlNo)
                .queryParam("userName", name)
                .queryParam("birthDate", birthDate)
                .queryParam("_type", "json")
                .build(false).toUriString();

            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get().uri(uri)
                .retrieve().body(Map.class);

            // 공공데이터포털 응답 구조: response.body.items.item.dlStatusInfo
            String status = extractStatus(body);
            VerifyResult result = "정상".equals(status) ? VerifyResult.VALID : VerifyResult.INVALID;
            log.info("운전면허 진위확인: dlNo={}*** status={} result={}", dlNo.substring(0, 4), status, result);
            return result;
        } catch (Exception e) {
            log.error("운전면허 진위확인 API 오류: {}", e.getMessage());
            return VerifyResult.ERROR;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractStatus(Map<String, Object> body) {
        try {
            var response = (Map<String, Object>) body.get("response");
            var respBody = (Map<String, Object>) response.get("body");
            var items    = (Map<String, Object>) respBody.get("items");
            var item     = (Map<String, Object>) items.get("item");
            return (String) item.get("dlStatusInfo");
        } catch (Exception e) {
            log.warn("응답 파싱 실패: {}", e.getMessage());
            return "오류";
        }
    }

    private VerifyResult queryCache(String cacheKey) {
        try {
            return jdbcTemplate.query(
                "SELECT result FROM identity_verify_cache WHERE cache_key = ? AND expires_at > now()",
                rs -> rs.next() ? VerifyResult.valueOf(rs.getString("result")) : null,
                cacheKey
            );
        } catch (Exception e) {
            return null;
        }
    }

    private void saveCache(String cacheKey, VerifyResult result) {
        try {
            jdbcTemplate.update(
                "INSERT INTO identity_verify_cache(cache_key, result, expires_at) " +
                "VALUES(?, ?, now() + INTERVAL '" + CACHE_TTL_HOURS + " hours') " +
                "ON CONFLICT(cache_key) DO UPDATE SET result=EXCLUDED.result, expires_at=EXCLUDED.expires_at",
                cacheKey, result.name()
            );
        } catch (Exception e) {
            log.warn("캐시 저장 실패: {}", e.getMessage());
        }
    }

    private String buildCacheKey(VerifyType type, String idNumber, String birthDate) {
        try {
            String raw = type.name() + ":" + idNumber + ":" + birthDate;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return type.name() + ":" + idNumber.hashCode();
        }
    }
}
