package com.bank.payment.domain.mapper;

import com.bank.payment.domain.OutboxMessage;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxMessageMapper {

    void insert(OutboxMessage outboxMessage);

    List<OutboxMessage> selectPending();

    /** PENDING → PUBLISHING 원자 클레임. @Transactional 범위 안에서만 호출. */
    List<OutboxMessage> claimPending();

    void markSent(String messageId);

    void markFailed(@Param("messageId") String messageId, @Param("lastError") String lastError);

    /** cutoff 이전에 last_modified_at이 갱신된 PUBLISHING 행을 PENDING으로 재설정. */
    int resetStuckPublishing(LocalDateTime cutoff);

    int countPending();
}
