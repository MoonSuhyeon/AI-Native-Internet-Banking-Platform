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
import com.bank.deposit.security.FdsPreCheckGate;
import com.bank.deposit.security.TransferApprovalGate;
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

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 결제 API. POST /api/v1/payments.
 * 헤더(신원/멱등키) + 본문(이체 지시) → Command 조립 → Orchestrator → Result → Response 매핑.
 * 자행 동기 완결 → 200 OK.
 */
@RestController
// context-path 가 /api 이므로 여기서는 /api 를 붙이지 않는다.
// 외부 URL 은 병합 전과 동일한 /api/v1/payments 다.
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentOrchestrator paymentOrchestrator;
    private final PaymentInstructionMapper paymentInstructionMapper;
    /** 수신·결제가 같은 승인 체계를 쓴다. 계좌 번호로 묶이므로 양쪽에서 그대로 쓸 수 있다. */
    private final TransferApprovalGate transferApprovalGate;
    private final FdsPreCheckGate fdsPreCheckGate;

    public PaymentController(PaymentOrchestrator paymentOrchestrator,
                             PaymentInstructionMapper paymentInstructionMapper,
                             TransferApprovalGate transferApprovalGate,
                             FdsPreCheckGate fdsPreCheckGate) {
        this.paymentOrchestrator = paymentOrchestrator;
        this.paymentInstructionMapper = paymentInstructionMapper;
        this.transferApprovalGate = transferApprovalGate;
        this.fdsPreCheckGate = fdsPreCheckGate;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Auth-Token-Id") String authTokenId,
            @RequestBody PaymentRequest request) {

        // 자금이동 승인(step-up) 확인.
        //
        // X-Auth-Token-Id 는 원래 브라우저가 만들어 보내는 값이었고 아무도 검증하지 않았다
        // (newAuthToken() = 'T' + 타임스탬프 + 난수). 인증 토큰이라는 이름만 있고 인증이
        // 아니었다. 이제 인증보안계가 발급한 승인 토큰을 받아 여기서 대조한다 —
        // 내부이체(TransactionController)와 같은 체계다.
        transferApprovalGate.verify(
                authTokenId, request.senderAccountId(), request.receiverAccountNo(), request.transferAmount());

        // 승인 확인 다음에 이상거래 점검. 순서가 중요하다 — 인증되지 않은 요청으로
        // 탐지기를 두드리게 두면 탐지 통계가 오염되고 부하도 는다.
        fdsPreCheckGate.evaluate(
                userId, request.senderAccountId(), request.receiverBankCode(),
                request.receiverAccountNo(), request.transferAmount(),
                null, request.channel(), authTokenId != null && !authTokenId.isBlank());

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

        OffsetDateTime scheduledAt = request.scheduledExecutionAt();
        if (scheduledAt == null || !scheduledAt.isAfter(OffsetDateTime.now())) {
            return ResponseEntity.badRequest().build();
        }

        // 예약이체는 등록 시점에 승인을 확인한다. 실행은 스케줄러가 사람 없이 하므로
        // 그때는 물을 수 없다 — 등록이 유일한 인증 지점이다.
        transferApprovalGate.verify(
                authTokenId, request.senderAccountId(), request.receiverAccountNo(), request.transferAmount());

        // 예약이체도 등록 시점에 점검한다. 실행은 스케줄러가 사람 없이 하므로
        // 그때는 막아도 물어볼 상대가 없다.
        fdsPreCheckGate.evaluate(
                userId, request.senderAccountId(), request.receiverBankCode(),
                request.receiverAccountNo(), request.transferAmount(),
                null, request.channel(), authTokenId != null && !authTokenId.isBlank());

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
