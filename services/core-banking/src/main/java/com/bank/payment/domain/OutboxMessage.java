package com.bank.payment.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox메시지 (outbox_message)
 *
 * Transactional Outbox 패턴. 결제 이벤트 → Kafka 발행 보장.
 * - V5__create_outbox_message.sql 정합
 * - payment_instruction FK (NOT NULL)
 * - payload JSONB → String
 * - 비즈니스 메서드는 Stage 5에서 추가
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OutboxMessage {

    // 메시지번호
    private String messageId;

    // 결제지시번호
    private String paymentInstructionId;

    // 이벤트종류
    private String eventType;

    // 이벤트스키마버전
    private String eventSchemaVersion;

    // 페이로드 (JSONB → String. 직렬화/역직렬화는 Service 또는 typeHandler에서 처리 — Stage 5)
    private String payload;

    // 발행상태
    private String publishStatus;

    // 시도횟수
    private Integer attemptCount;

    // 처리가능시각
    private LocalDateTime availableAt;

    // 마지막오류
    private String lastError;

    // 발행시각
    private LocalDateTime publishedAt;

    // 최초등록일시
    private LocalDateTime firstRegisteredAt;

    // 최초등록자식별번호
    private String firstRegistrantId;

    // 최종수정일시
    private LocalDateTime lastModifiedAt;

    // 최종수정자식별번호
    private String lastModifierId;

    // ── Outbox 이벤트 생성 [공통] ────────────────────────
    /** Outbox 메시지 생성. publishStatus=PENDING 초기. eventType은 enum 시트 19개 중 하나 */
    public static OutboxMessage of(
            String messageId, String paymentInstructionId, String eventType,
            String eventSchemaVersion, String payload, LocalDateTime availableAt) {
        return OutboxMessage.builder()
                .messageId(messageId)
                .paymentInstructionId(paymentInstructionId)
                .eventType(eventType)
                .eventSchemaVersion(eventSchemaVersion)
                .payload(payload)
                .publishStatus("PENDING")
                .attemptCount(0)
                .availableAt(availableAt)
                .build();
    }
}
