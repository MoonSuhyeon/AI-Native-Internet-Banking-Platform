package com.bank.loan.advisory.listener;

import com.bank.loan.advisory.event.QuarantineTriggeredEvent;
import com.bank.loan.notification.channel.StubOperatorAlertAdapter;
import com.bank.loan.notification.outbox.NotificationOutboxAppender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 격리(Quarantine) 신호 → 운영자 알림 outbox 적재.
 *
 * AuditFairnessAgent 가 BIAS_SUSPECTED / VIOLATION_SUSPECTED 결론을 낼 때 발행한
 * QuarantineTriggeredEvent 를 수신해 OP_ALERT 채널로 outbox 에 적재한다.
 *
 * - 동기 @EventListener: 이미 advisoryExecutor 비동기 스레드에서 호출되므로 추가 비동기 불필요.
 * - NotificationOutboxAppender.enqueue() 는 REQUIRES_NEW 트랜잭션을 열어 독립적으로 저장한다.
 * - idempotencyKey = QUARANTINE_ALERT:{revId}:OP_ALERT → 동일 심사 중복 알림 차단.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuarantineNotificationListener {

    public static final String EVENT_TYPE = "QUARANTINE_ALERT";

    private final NotificationOutboxAppender outboxAppender;

    @EventListener
    public void onQuarantineTriggered(QuarantineTriggeredEvent event) {
        String payload = buildPayload(event);
        try {
            outboxAppender.enqueue(
                    EVENT_TYPE,
                    event.revId(),
                    StubOperatorAlertAdapter.CHANNEL_CD,
                    payload);
            log.warn("[quarantine-alert] enqueued — revId={} reviewer={} conclusion={} advrIds={}",
                    event.revId(), event.reviewerId(), event.conclusionCd(), event.advrIds());
        } catch (Exception e) {
            log.error("[quarantine-alert] outbox 적재 실패 (무시) — revId={}: {}",
                    event.revId(), e.getMessage());
        }
    }

    private static String buildPayload(QuarantineTriggeredEvent e) {
        return String.format(
                "{\"revId\":%d,\"reviewerId\":%s,\"conclusionCd\":\"%s\",\"analysisType\":\"%s\",\"advrIds\":%s}",
                e.revId(),
                e.reviewerId() != null ? e.reviewerId().toString() : "null",
                e.conclusionCd(),
                e.analysisType(),
                e.advrIds().toString().replace(" ", ""));
    }
}
