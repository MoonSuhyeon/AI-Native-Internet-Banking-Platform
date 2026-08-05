package com.bank.payment.scheduler;

import com.bank.payment.common.IdGenerator;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.StatusHistory;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.mapper.StatusHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 타임아웃 감지 전용 트랜잭션 헬퍼 (OutboxTransactionHelper 패턴).
 *
 * TimeoutDetectionWorker는 @Transactional 금지(로그 I/O와 DB TX 분리).
 * status_history INSERT + next_timeout_at NULL 클리어는 짧은 독립 트랜잭션으로 처리.
 * ★ PI status는 건드리지 않음(CLEARING 유지). 역분개/Outbox/외부호출 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutTransactionHelper {

    private final PaymentInstructionMapper paymentInstructionMapper;
    private final StatusHistoryMapper statusHistoryMapper;
    private final IdGenerator idGenerator;

    @Transactional
    public void markTimeoutDetected(PaymentInstruction pi) {
        String piId = pi.getPaymentInstructionId();
        String routingNetworkType = pi.getRoutingNetworkType();
        LocalDateTime now = LocalDateTime.now();

        String eventType, reasonCode, reasonMsg;
        switch (routingNetworkType == null ? "" : routingNetworkType) {
            case "KFTC":
                eventType  = "KFTC_TIMEOUT_DETECTED";
                reasonCode = "KFTC_TIMEOUT";
                reasonMsg  = "KFTC 청산 ACK 미수신 타임아웃(시나리오 A)";
                break;
            case "BOK":
                eventType  = "BOK_TIMEOUT_DETECTED";
                reasonCode = "BOK_TIMEOUT";
                reasonMsg  = "BOK RTGS 정산 타임아웃";
                break;
            default:
                log.warn("[F6] 예상치 못한 routing_network_type, next_timeout_at 클리어 후 skip. piId={} type={}",
                        piId, routingNetworkType);
                paymentInstructionMapper.updateNextTimeoutAt(piId, null);
                return;
        }

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq == null ? 0 : maxSeq) + 1,
                "CLEARING", "CLEARING", eventType,
                "SCHEDULER", reasonCode, reasonMsg,
                now));

        // 재폴링 방지: NULL 클리어로 다음 폴링에서 재감지 안 됨. version 불간섭.
        paymentInstructionMapper.updateNextTimeoutAt(piId, null);
    }
}
