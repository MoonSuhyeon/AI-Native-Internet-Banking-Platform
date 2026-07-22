package com.bank.ai.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 에이전트 감사 로그 서비스 — phase-b-operational.md §B1.
 *
 * <p>INSERT 는 항상 {@link Propagation#REQUIRES_NEW} 독립 트랜잭션으로 실행한다.
 * 파이프라인 메인 트랜잭션 롤백 여부와 무관하게 감사 로그는 커밋된다.
 *
 * <p>{@code ai.audit.enabled=false} 이면 모든 메서드는 no-op 로 동작한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AuditLogProperties.class)
public class AuditLogService {

    private final AuditLogRepository repository;
    private final AuditLogProperties properties;

    /**
     * 에이전트 의견 생성 결과를 감사 로그에 기록한다.
     *
     * <p>실패 시 예외를 전파하지 않는다 — 감사 로그 저장 실패가 파이프라인 전체를 중단해서는 안 된다.
     * 대신 ERROR 로그를 남겨 모니터링 시스템이 감지할 수 있게 한다.
     *
     * @param record 기록할 감사 레코드
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AgentAuditRecord record) {
        if (!properties.enabled()) {
            return;
        }
        repository.insert(record);
        log.debug("[Audit] revId={} track={} fallback={} 감사 로그 저장 완료",
                record.revId(), record.track(), record.fallbackReason());
    }

    /**
     * revId 기준 최신 감사 로그 1건 조회 (Admin 조회·재현 검증용).
     *
     * @param revId 조회 대상 심사 신청 ID
     * @return 감사 레코드 (없으면 empty)
     */
    @Transactional(readOnly = true)
    public Optional<AgentAuditRecord> findLatestByRevId(Long revId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        return repository.findLatestByRevId(revId);
    }
}
