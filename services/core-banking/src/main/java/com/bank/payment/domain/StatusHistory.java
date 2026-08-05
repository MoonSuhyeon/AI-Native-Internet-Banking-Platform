package com.bank.payment.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 상태이력 (status_history)
 *
 * payment_instruction 상태 전이 감사 로그. Append-only.
 * - V6__create_status_history.sql 정합
 * - payment_instruction FK (NOT NULL)
 * - related_external_call_id FK (NULL 허용)
 * - UNIQUE 조합 (paymentInstructionId, sequenceInPayment)
 * - payloadSnapshot JSONB → String
 * - 비즈니스 메서드는 Stage 5에서 추가
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StatusHistory {

    // 상태이력번호
    private String historyId;

    // 결제지시번호
    private String paymentInstructionId;

    // 관련외부호출번호
    private String relatedExternalCallId;

    // 결제지시내순번
    private Integer sequenceInPayment;

    // 이전상태
    private String previousStatus;

    // 다음상태
    private String nextStatus;

    // 이벤트종류
    private String eventType;

    // 사유코드
    private String reasonCode;

    // 사유메시지
    private String reasonMessage;

    // 트리거주체
    private String triggeredBy;

    // 운영자ID
    private String operatorId;

    // 페이로드스냅샷 (JSONB → String. 직렬화/역직렬화는 Service 또는 typeHandler에서 처리 — Stage 5)
    private String payloadSnapshot;

    // 이벤트발생시각
    private LocalDateTime eventOccurredAt;

    // DB기록시각
    private LocalDateTime dbRecordedAt;

    // 최초등록일시
    private LocalDateTime firstRegisteredAt;

    // 최초등록자식별번호
    private String firstRegistrantId;

    // 최종수정일시
    private LocalDateTime lastModifiedAt;

    // 최종수정자식별번호
    private String lastModifierId;

    // ── 상태이력 생성 [공통] ─────────────────────────────
    /**
     * 상태이력 생성. eventType은 enum 시트 status_history.event_type 37개 중 하나.
     * 자행 S1 순번: INSTRUCTION_CREATED(null→DRAFT) / AUTH_PASSED(DRAFT→AUTHORIZED)
     *              / PROCESSING_STARTED(AUTHORIZED→PROCESSING) / PAYMENT_COMPLETED(PROCESSING→COMPLETED)
     */
    public static StatusHistory of(
            String historyId, String paymentInstructionId, Integer sequenceInPayment,
            String previousStatus, String nextStatus, String eventType,
            String triggeredBy, LocalDateTime eventOccurredAt) {
        return StatusHistory.builder()
                .historyId(historyId)
                .paymentInstructionId(paymentInstructionId)
                .sequenceInPayment(sequenceInPayment)
                .previousStatus(previousStatus)
                .nextStatus(nextStatus)
                .eventType(eventType)
                .triggeredBy(triggeredBy)
                .eventOccurredAt(eventOccurredAt)
                .build();
    }

    /**
     * 상태이력 생성 — reason_code/reason_message 포함 오버로드.
     * F2: reason_code='E2001', reason_message=rejectMessage 박제용.
     * V6 reason_code/reason_message 컬럼 모두 NULL 허용이므로 기존 8인자 of()는 그대로 유지.
     */
    public static StatusHistory of(
            String historyId, String paymentInstructionId, Integer sequenceInPayment,
            String previousStatus, String nextStatus, String eventType,
            String triggeredBy, String reasonCode, String reasonMessage,
            LocalDateTime eventOccurredAt) {
        return of(historyId, paymentInstructionId, sequenceInPayment,
                previousStatus, nextStatus, eventType,
                triggeredBy, reasonCode, reasonMessage, null, eventOccurredAt);
    }

    /**
     * 상태이력 생성 — operatorId 포함 11-파라미터 오버로드.
     * F6-Ⅱ: triggered_by='OPERATOR' 시 operator_id NOT NULL (chk_status_history_operator_consistency).
     * 기존 8/10-param은 operatorId=null로 위임.
     */
    public static StatusHistory of(
            String historyId, String paymentInstructionId, Integer sequenceInPayment,
            String previousStatus, String nextStatus, String eventType,
            String triggeredBy, String reasonCode, String reasonMessage,
            String operatorId, LocalDateTime eventOccurredAt) {
        return StatusHistory.builder()
                .historyId(historyId)
                .paymentInstructionId(paymentInstructionId)
                .sequenceInPayment(sequenceInPayment)
                .previousStatus(previousStatus)
                .nextStatus(nextStatus)
                .eventType(eventType)
                .triggeredBy(triggeredBy)
                .reasonCode(reasonCode)
                .reasonMessage(reasonMessage)
                .operatorId(operatorId)
                .eventOccurredAt(eventOccurredAt)
                .build();
    }
}
