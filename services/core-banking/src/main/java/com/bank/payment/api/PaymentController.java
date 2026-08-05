package com.bank.payment.api;

import com.bank.payment.api.dto.CancelScheduledRequest;
import com.bank.payment.api.dto.InboundPaymentResponse;
import com.bank.payment.api.dto.OperatorCancelRequest;
import com.bank.payment.api.dto.PaymentRequest;
import com.bank.payment.api.dto.PaymentResponse;
import com.bank.payment.api.dto.ScheduledPaymentRequest;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.service.PaymentCommand;
import com.bank.payment.domain.service.PaymentOrchestrator;
import com.bank.payment.domain.service.PaymentResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 결제 API. POST /api/v1/payments.
 * 헤더(신원/멱등키) + 본문(이체 지시) → Command 조립 → Orchestrator → Result → Response 매핑.
 * 자행 동기 완결 → 200 OK.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentOrchestrator paymentOrchestrator;
    private final PaymentInstructionMapper paymentInstructionMapper;

    public PaymentController(PaymentOrchestrator paymentOrchestrator,
                             PaymentInstructionMapper paymentInstructionMapper) {
        this.paymentOrchestrator = paymentOrchestrator;
        this.paymentInstructionMapper = paymentInstructionMapper;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Auth-Token-Id") String authTokenId,
            @RequestBody PaymentRequest request) {

        // api 입력 → 도메인 입력 번역 (Command 조립)
        PaymentCommand command = new PaymentCommand(
                request.senderAccountId(),
                request.receiverBankCode(),
                request.receiverAccountNo(),
                request.receiverHolderName(),
                request.transferAmount(),
                request.receiverMemo(),
                request.senderMemo(),
                request.channel(),
                request.receiverPassbookSenderDisplay(),
                userId,
                authTokenId,
                idempotencyKey
        );

        // 오케스트레이션
        PaymentResult result = paymentOrchestrator.processPayment(command);

        // 도메인 출력 → api 출력 매핑 (COMPLETED=null, FAILED=원인코드)
        PaymentResponse response = new PaymentResponse(
                result.paymentInstructionId(),
                result.transactionNo(),
                result.status(),
                result.completedAt(),
                result.failureCategory()
        );

        // 자행 동기 완결 → 200 OK (COMPLETED/FAILED 모두 비즈니스 정상결과)
        // 타행 청산 대기 → 202 Accepted (KFTC 응답 비동기, CLEARING 상태)
        if ("CLEARING".equals(result.status())) {
            return ResponseEntity.accepted().body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 예약이체 등록. POST /api/v1/payments/scheduled.
     * scheduledExecutionAt null 또는 현재 이하 → 400 (payment_instruction row 미생성).
     * 정상 등록 시 200 OK, status=SCHEDULED.
     */
    @PostMapping("/scheduled")
    public ResponseEntity<?> createScheduledPayment(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Auth-Token-Id") String authTokenId,
            @RequestBody ScheduledPaymentRequest request) {

        LocalDateTime scheduledAt = request.scheduledExecutionAt();
        if (scheduledAt == null || !scheduledAt.isAfter(LocalDateTime.now())) {
            return ResponseEntity.badRequest().build();
        }

        PaymentCommand command = new PaymentCommand(
                request.senderAccountId(),
                request.receiverBankCode(),
                request.receiverAccountNo(),
                request.receiverHolderName(),
                request.transferAmount(),
                request.receiverMemo(),
                request.senderMemo(),
                request.channel(),
                request.receiverPassbookSenderDisplay(),
                userId,
                authTokenId,
                idempotencyKey
        );

        PaymentResult result = paymentOrchestrator.registerScheduledPayment(command, scheduledAt);

        return ResponseEntity.ok(new PaymentResponse(
                result.paymentInstructionId(),
                result.transactionNo(),
                result.status(),
                result.completedAt(),
                result.failureCategory()
        ));
    }

    /**
     * 사용자 예약취소. SCHEDULED 상태 PI만 허용. 본인(X-User-Id) 검증.
     * 404: PI 없음. 403: 본인 아님. 409: SCHEDULED 아닌 상태 또는 claim 경합.
     */
    @PostMapping("/scheduled/{piId}/cancel")
    public ResponseEntity<?> cancelScheduledPayment(
            @PathVariable String piId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody(required = false) CancelScheduledRequest request) {

        String reason = Optional.ofNullable(request)
                .map(CancelScheduledRequest::reason)
                .orElse(null);

        PaymentResult result = paymentOrchestrator.cancelScheduledPayment(piId, userId, reason);

        return ResponseEntity.ok(new PaymentResponse(
                result.paymentInstructionId(),
                result.transactionNo(),
                result.status(),
                result.completedAt(),
                result.failureCategory()
        ));
    }

    /**
     * 운영자 강제취소. CLEARING 상태 PI만 허용.
     * 404: PI 없음. 409: CLEARING 아닌 상태. 400: operatorId/reason 빈값.
     */
    @PostMapping("/{piId}/operator-cancel")
    public ResponseEntity<?> operatorCancel(
            @PathVariable String piId,
            @RequestBody OperatorCancelRequest request) {

        if (request.operatorId() == null || request.operatorId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "operatorId는 필수값입니다"));
        }
        if (request.reason() == null || request.reason().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "reason은 필수값입니다"));
        }

        PaymentResult result = paymentOrchestrator.processOperatorCancel(
                piId, request.operatorId(), request.reason());

        return ResponseEntity.ok(new PaymentResponse(
                result.paymentInstructionId(),
                result.transactionNo(),
                result.status(),
                result.completedAt(),
                result.failureCategory()
        ));
    }

    /**
     * 수신계좌 기준 입금(COMPLETED) 내역 조회. GET /api/v1/payments/inbound?receiverAccountNo=
     * 다온 화면 전용. 인증: anyRequest().permitAll() → 헤더 불필요.
     */
    @GetMapping("/inbound")
    public ResponseEntity<List<InboundPaymentResponse>> getInboundPayments(
            @RequestParam String receiverAccountNo) {

        List<InboundPaymentResponse> result = paymentInstructionMapper
                .selectByReceiverAccountNo(receiverAccountNo)
                .stream()
                .map(pi -> new InboundPaymentResponse(
                        pi.getPaymentInstructionId(),
                        pi.getTransactionNo(),
                        pi.getTransferAmount(),
                        pi.getStatus(),
                        pi.getRequestedAt(),
                        pi.getCompletedAt(),
                        pi.getSenderAccountNoSnap(),
                        pi.getReceiverPassbookSenderDisplay(),
                        pi.getReceiverMemo()
                ))
                .toList();

        return ResponseEntity.ok(result);
    }
}
