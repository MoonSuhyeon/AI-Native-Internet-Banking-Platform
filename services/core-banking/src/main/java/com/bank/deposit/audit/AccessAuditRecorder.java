package com.bank.deposit.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * 수신 데이터 열람을 기록한다.
 *
 * <p>실제 저장은 {@link AccessAuditWriter} 가 <b>별도 트랜잭션</b>으로 한다.
 * 거절을 기록한 뒤 호출부가 예외를 던지므로, 같은 트랜잭션에 실으면 그 예외가
 * 감사 기록까지 함께 롤백시킨다 — 막은 시도가 흔적 없이 사라진다.
 */
@Service
@RequiredArgsConstructor
public class AccessAuditRecorder {

    static final String ALLOWED = "ALLOWED";
    static final String DENIED  = "DENIED";

    private final AccessAuditWriter writer;
    private final Clock clock;

    /** 조회를 허용했다. */
    public void allowed(AccessActor actor, String action, String targetCustomerId, Long targetAccountId) {
        save(actor, action, targetCustomerId, targetAccountId, ALLOWED, null);
    }

    /** 조회를 거절했다. 무엇을 보려 했는지가 남아야 한다. */
    public void denied(AccessActor actor, String action, String targetCustomerId,
                       Long targetAccountId, String deniedReason) {
        save(actor, action, targetCustomerId, targetAccountId, DENIED, deniedReason);
    }

    private void save(AccessActor actor, String action, String targetCustomerId,
                      Long targetAccountId, String result, String deniedReason) {
        writer.write(AccessAudit.builder()
                .actorType(actor.type())
                .actorEmployeeId(actor.employeeId())
                .actorCustomerId(actor.customerId())
                .actorService(actor.service())
                .targetCustomerId(targetCustomerId)
                .targetAccountId(targetAccountId)
                .accessActionCode(action)
                .accessReason(actor.reason())
                .resultCode(result)
                .deniedReason(deniedReason)
                .traceId(actor.traceId())
                .accessedAt(OffsetDateTime.now(clock))
                .build());
    }
}
