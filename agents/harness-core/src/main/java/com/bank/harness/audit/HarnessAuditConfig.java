package com.bank.harness.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * 감사 배선.
 *
 * <p>{@code harness.audit.enabled=false} 로 끄면 아무 빈도 만들지 않는다.
 * 감사가 필요 없는 환경(로컬 실험)에서 테이블을 강제하지 않기 위한 것이지,
 * 운영에서 끄라는 뜻이 아니다.
 */
@AutoConfiguration
@EnableConfigurationProperties(HarnessAuditConfig.HarnessAuditProperties.class)
@ConditionalOnProperty(name = "harness.audit.enabled", matchIfMissing = true)
public class HarnessAuditConfig {

    @ConfigurationProperties(prefix = "harness.audit")
    public record HarnessAuditProperties(boolean enabled, boolean verifyImmutability) {
    }

    @Bean
    public AgentAuditLog agentAuditLog(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAgentAuditLog(jdbc);
    }

    /**
     * 불변성 검증기는 기본으로 켠다.
     *
     * <p>끌 수 있게 둔 것은 감사 테이블이 아직 없는 초기 구축 단계 때문이다.
     * 운영에서 끄면 감사 보장이 사라진다.
     */
    @Bean
    @ConditionalOnProperty(name = "harness.audit.verify-immutability", matchIfMissing = true)
    public AuditImmutabilityVerifier auditImmutabilityVerifier(DataSource dataSource,
                                                               NamedParameterJdbcTemplate jdbc) {
        return new AuditImmutabilityVerifier(dataSource, jdbc);
    }
}
