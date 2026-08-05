package com.bank.payment.outbound.kafka;

import com.bank.payment.domain.OutboxMessage;
import com.bank.payment.domain.mapper.OutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 상태 업데이트 전용 트랜잭션 헬퍼.
 *
 * OutboxPublisher는 @Transactional 금지(Kafka send가 DB TX 안에 들어가면 P-028 위반).
 * 모든 DB 상태 변경은 이 Bean의 짧은 독립 트랜잭션 안에서 처리한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxTransactionHelper {

    private final OutboxMessageMapper outboxMessageMapper;

    /**
     * PENDING 행을 PUBLISHING으로 원자 클레임.
     * CTE + FOR UPDATE SKIP LOCKED + UPDATE를 단일 @Transactional로 묶어
     * 다중 인스턴스 환경에서 동일 행의 중복 선점을 방지한다.
     */
    @Transactional
    public List<OutboxMessage> claimPending() {
        return outboxMessageMapper.claimPending();
    }

    @Transactional
    public void markSent(String messageId) {
        outboxMessageMapper.markSent(messageId);
    }

    @Transactional
    public void markFailed(String messageId, String lastError) {
        outboxMessageMapper.markFailed(messageId, lastError);
    }

    /**
     * Stuck PUBLISHING 복구. cutoff 이전에 last_modified_at이 갱신된 PUBLISHING 행을
     * PENDING으로 재설정해 재발행 대상으로 돌린다.
     */
    @Transactional
    public int resetStuckPublishing(LocalDateTime cutoff) {
        return outboxMessageMapper.resetStuckPublishing(cutoff);
    }
}
