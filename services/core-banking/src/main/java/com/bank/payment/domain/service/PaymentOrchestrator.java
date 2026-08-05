package com.bank.payment.domain.service;

import com.bank.payment.domain.PaymentInstruction;

/**
 * 결제 오케스트레이션 진입점. P-028 5단계 흐름의 단일 진입 메서드.
 * 내부 단계(txStep1/step2/step3/txStep4)는 구현 디테일 (인터페이스 비노출).
 * 구현체는 Stage 5-3에서 (PaymentOrchestratorImpl + PaymentTransactionService 분리).
 */
public interface PaymentOrchestrator {

    /**
     * 결제(이체) 처리. 자행은 동기 완결 (COMPLETED 반환).
     * @param command 이체 지시 + 신원 + 멱등키
     * @return 처리 결과 (결제지시번호/거래번호/상태/완료시각)
     */
    PaymentResult processPayment(PaymentCommand command);

    /**
     * 예약이체 등록. DRAFT→AUTHORIZED→SCHEDULED 전이 후 종료.
     * step2b(잔액·한도)/step3(출금·입금)/txStep4 호출 없음. Outbox·ledger INSERT 없음.
     * A 검증 실패 시 DRAFT→FAILED (기존 txStepFail 패턴 동일).
     * @param command 이체 지시 + 신원 + 멱등키
     * @param scheduledExecutionAt 예약실행시각 (미래 시각, 컨트롤러에서 검증 완료)
     * @return 처리 결과 (status=SCHEDULED)
     */
    PaymentResult registerScheduledPayment(PaymentCommand command, java.time.LocalDateTime scheduledExecutionAt);

    /**
     * 예약이체 실행. 워커가 claim 성공 후 호출.
     * step2a(A 재검증) → step2b(잔액/한도) → B-3 출금 → B-4 입금 → txStep4Scheduled(분개+COMPLETED).
     * 자행만 구현. 타행/BOK는 UnsupportedOperationException (후속 단계).
     * txStep1/authorize/markScheduled 재호출 없음 — 이미 PROCESSING 상태.
     * @param pi selectDueScheduled 로 읽은 PI (claimScheduled 성공 직후, version=V, DB version=V+1)
     * @return PaymentResult (status=COMPLETED)
     */
    PaymentResult executeScheduledIntraBank(PaymentInstruction pi);

    /**
     * F2 KFTC 거절 보상. CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + CT REJECTED.
     * Kafka consumer(kftc.network.response REJECT/PAYMENT_REJECT)에서 호출.
     * @param freshPi DB 재조회한 PI (CLEARING 또는 REVERSING 상태)
     * @param clearingNo KFTC 청산식별번호
     * @param rejectCode KFTC responseCode (예: 'E2001')
     * @param rejectMessage KFTC 거절메시지
     * @param rejectedAt KFTC 거절시각 (yyyyMMddHHmmss)
     * @return FAILED 결제결과
     */
    PaymentResult processKftcReject(PaymentInstruction freshPi, String clearingNo,
                                     String rejectCode, String rejectMessage, String rejectedAt);

    /**
     * F3 BOK 거절 보상. CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + BST REJECTED.
     * processKftcReject의 BOK판. Kafka consumer(bok.network.response SETTLEMENT_REJECT)에서 호출.
     * @param freshPi DB 재조회한 PI (CLEARING 또는 REVERSING 상태)
     * @param bokReferenceNo BOK 참조번호 (BST 조회키)
     * @param rejectCode BOK responseCode (예: 'B1001')
     * @param rejectMessage BOK 거절메시지
     * @param rejectedAt BOK 거절시각
     * @return FAILED 결제결과
     */
    PaymentResult processBokReject(PaymentInstruction freshPi, String bokReferenceNo,
                                    String rejectCode, String rejectMessage, String rejectedAt);

    /**
     * F4 KFTC 송신실패 자동보상. Outbox 워커가 KFTC_REQUEST_SENT 발행 실패 시 호출.
     * F2형 보상 재사용: CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + CT REJECTED.
     * reversal_reason=PUBLISH_FAILURE / failure_category=SYSTEM_ERROR.
     * @param piId 결제지시번호 (Outbox 레코드에서 추출)
     * @param lastError 발행 실패 오류 메시지 (OutboxPublisher.truncate 적용된 값)
     * @return FAILED 결제결과 (보상 불가 시 null)
     */
    PaymentResult processPublishFailure(String piId, String lastError);

    /**
     * F4 BOK 송신실패 자동보상. Outbox 워커가 BOK_REQUEST_SENT 발행 실패 시 호출.
     * F3형 보상 재사용: CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + BST REJECTED.
     * reversal_reason=PUBLISH_FAILURE / failure_category=SYSTEM_ERROR.
     * @param piId 결제지시번호 (Outbox 레코드에서 추출)
     * @param lastError 발행 실패 오류 메시지 (OutboxPublisher.truncate 적용된 값)
     * @return FAILED 결제결과 (보상 불가 시 null)
     */
    PaymentResult processBokPublishFailure(String piId, String lastError);

    /**
     * F7 KFTC 정산실패 자동보상. SETTLEMENT_NOTIFY responseCode != "0000" 수신 시 호출.
     * F4형 보상 재사용: CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + CT REJECTED.
     * reversal_reason=SETTLEMENT_FAILURE / failure_category=SYSTEM_ERROR.
     * PI=COMPLETED(정상완결 후 뒤늦은 실패통보)는 보상 안 함 — 정책 시트6 케이스3 "범위 외/운영자".
     * @param clearingNo KFTC 청산식별번호 (CT 조회키)
     * @param responseCode KFTC responseCode (예: 'E9001')
     * @param rejectMessage KFTC 정산실패 메시지
     * @return FAILED 결제결과 (보상 불가 시 null)
     */
    PaymentResult processSettlementFailure(String clearingNo, String responseCode, String rejectMessage);

    /**
     * F7 BOK 정산실패 자동보상. SETTLEMENT_COMPLETED responseCode != "0000" 수신 시 호출.
     * F3형 보상 재사용: CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + BST REJECTED.
     * reversal_reason=SETTLEMENT_FAILURE / failure_category=SYSTEM_ERROR.
     * PI=COMPLETED(정상완결 후 뒤늦은 실패통보)는 보상 안 함.
     * @param bokReferenceNo BOK 참조번호 (BST 조회키)
     * @param responseCode BOK responseCode (예: 'B9001')
     * @param rejectMessage BOK 정산실패 메시지
     * @return FAILED 결제결과 (보상 불가 시 null)
     */
    PaymentResult processBokSettlementFailure(String bokReferenceNo, String responseCode, String rejectMessage);

    /**
     * 사용자 예약취소. SCHEDULED 상태만 허용. 본인(senderUserId) 검증 선행.
     * SCHEDULED→CANCELED + SCHEDULED_CANCELED 이력. 외부 API 호출 없음.
     * @param piId 결제지시번호
     * @param requesterUserId 요청자 사용자ID (X-User-Id 헤더)
     * @param reason 취소 사유 (nullable, 이력 reason_message 박제)
     * @throws com.bank.payment.common.exception.PaymentNotFoundException PI 미존재 시 (→ 404)
     * @throws com.bank.payment.common.exception.PaymentUnauthorizedException 본인 불일치 시 (→ 403)
     * @throws com.bank.payment.common.exception.PaymentCancelConflictException SCHEDULED 아닌 상태 또는 claim 경합 시 (→ 409)
     */
    PaymentResult cancelScheduledPayment(String piId, String requesterUserId, String reason);

    /**
     * F6-Ⅱ-2 운영자 강제취소. CLEARING 상태만 허용. CLEARING→REVERSING→FAILED + 역분개4건 + B-5 + CT REJECTED.
     * reversal_reason=OPERATOR / failure_category=SYSTEM_ERROR / triggered_by=OPERATOR / operator_id 박제.
     * @param piId 결제지시번호
     * @param operatorId 운영자ID (상태이력 operator_id, DB CHECK 필수)
     * @param reason 취소 사유 (reason_message 박제)
     * @throws com.bank.payment.common.exception.PaymentNotFoundException PI 미존재 시 (→ 404)
     * @throws com.bank.payment.common.exception.PaymentCancelConflictException CLEARING 아닌 상태 시 (→ 409)
     * @return FAILED 결제결과
     */
    PaymentResult processOperatorCancel(String piId, String operatorId, String reason);
}
