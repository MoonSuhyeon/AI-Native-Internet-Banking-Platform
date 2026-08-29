package com.bank.payment.api;

import com.bank.common.security.service.AuthorizationDecision;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.exception.RiskGuidedException;
import com.bank.deposit.security.FdsPreCheckGate;
import com.bank.deposit.security.PreCheckDecision;
import com.bank.payment.api.dto.InternalPaymentRequest;
import com.bank.payment.api.dto.PaymentResponse;
import com.bank.payment.domain.service.PaymentCommand;
import com.bank.payment.domain.service.PaymentOrchestrator;
import com.bank.payment.domain.service.PaymentResult;
import com.bank.payment.security.ServiceAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시스템이 개시하는 결제. {@code POST /api/v1/internal/payments}.
 *
 * <p>같은 경로의 {@code GET /{paymentInstructionId}} 는 {@link InternalPaymentController} 가
 * 맡는다(이상거래 탐지용 조회). 자원은 같고 하는 일이 달라 클래스를 나눈다.
 *
 * <p><b>고객 경로와 무엇이 다른가.</b> 인증 주체만 다르다. 고객 경로는 사람을 확인하고
 * (step-up), 이 경로는 서비스를 확인한다(신원 + 작업 권한 + 거래 정책). 그 뒤로는
 * <b>FDS·원장·멱등·감사를 똑같이 지난다</b> — 같은 오케스트레이터를 부른다.
 *
 * <p>"internal 이라서 보안 검사를 생략한다" 가 아니다. 근거는
 * {@code docs/decisions/transaction-initiator-auth-model.md} 원칙 3.
 *
 * <p><b>왜 이 경로가 필요했나.</b> 여신은 고객 승인 토큰을 가질 수 없는데 고객 경로에
 * 밀어 넣으니, 없으면 {@code TRANSFER_APPROVAL_REQUIRED}, 만들어 보내면
 * {@code TRANSFER_APPROVAL_INVALID} 로 어느 쪽으로도 통과할 수 없었다. 배치가 개시한
 * 거래에는 "본인이 지시했습니까" 라고 물을 본인이 없다.
 */
@Slf4j
@RestController
@RequestMapping("/v1/internal/payments")
@RequiredArgsConstructor
public class SystemPaymentController {

    private final ServiceAuthorizationService serviceAuthorizationService;
    private final PaymentOrchestrator paymentOrchestrator;
    private final FdsPreCheckGate fdsPreCheckGate;

    @PostMapping
    public ResponseEntity<PaymentResponse> createInternalPayment(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Internal-Token", required = false) String credential,
            @RequestBody InternalPaymentRequest request) {

        // 1) 신원과 작업 인가. 허용·거절 모두 여기서 감사에 남는다.
        //
        //    자격증명을 required=false 로 받는 이유는, 헤더가 없다는 사실 자체가
        //    남아야 하기 때문이다. required=true 로 두면 스프링이 400 을 던져
        //    인가 감사에 아무것도 남지 않는다 — 그 시도가 가장 봐야 할 기록이다.
        AuthorizationDecision decision = serviceAuthorizationService.authorize(
                credential,
                request.operation(),
                new ServiceAuthorizationService.RequestContext(
                        request.transferAmount(),
                        request.senderAccountId(),
                        idempotencyKey,
                        MDC.get("traceId"),
                        "/api/v1/internal/payments"));

        if (!decision.allowed()) {
            // 어느 단계에서 막혔는지 호출자에게 알리지 않는다. 알려 주면 권한 경계를
            // 탐색하는 데 쓸 수 있다. 구분은 service_authorization_log 에만 남는다.
            log.warn("내부 결제 인가 거절 service={} operation={} reason={}",
                    decision.serviceId(), request.operation(), decision.denyReason());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 2) 이상거래 점검 — 고객 경로와 똑같이 지난다.
        //
        //    자동이체는 오히려 사람이 보고 있지 않으므로 더 필요하다.
        //
        //    hasApprovalToken 에 true 를 주는 것은 검사를 건너뛰려는 것이 아니라
        //    주체가 바뀌었기 때문이다. 고객 거래에서 step-up 이 증명하는 것은
        //    "본인이 지시했다" 이고, 시스템 거래에서 그에 대응하는 증명은 방금 끝난
        //    "권한 있는 서비스가 허용된 금액·계좌로 지시했다" 이다. 이것 없이
        //    false 를 주면 고액 실행이 STEP_UP 에서 막히는데, 그때 추가 인증을 해 줄
        //    사람이 없어 여신이 다시 통째로 멈춘다.
        String actor = decision.serviceId();
        PreCheckDecision fds = fdsPreCheckGate.evaluate(
                actor, request.senderAccountId(), request.receiverBankCode(),
                request.receiverAccountNo(), request.transferAmount(),
                null, request.channel(), true);

        if (fds.isDelayed()) {
            // 지연은 고객이 알아채고 취소할 시간을 주는 장치다. 이 경로에는 알아챌
            // 고객이 없어 지연을 이행할 수 없다. 못 미루면 통과시키지 않는다 —
            // 고객 경로가 타행 건에 대해 내린 것과 같은 결론이다.
            fdsPreCheckGate.reportDelayNotHonored("internal", fds.reasons());
            throw new RiskGuidedException(ErrorCode.TRANSFER_BLOCKED_BY_RISK, fds.guidance());
        }

        // 3) 여기서부터는 고객 거래와 완전히 같은 길이다 — 같은 명령, 같은 원장.
        //
        //    authTokenId 는 null 이다. 고객 인증이 없었으므로 그것이 정확한 표현이고,
        //    UNIQUE 제약이 NULL 다중을 허용하므로 호출부가 가짜 값을 만들 이유가 없다.
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
                actor,
                null,
                idempotencyKey);

        PaymentResult result = paymentOrchestrator.processPayment(command);

        PaymentResponse response = PaymentResponse.of(
                result.paymentInstructionId(),
                result.transactionNo(),
                result.status(),
                result.completedAt(),
                result.failureCategory(),
                result.journalNo());

        if ("CLEARING".equals(result.status())) {
            return ResponseEntity.accepted().body(response);
        }
        return ResponseEntity.ok(response);
    }
}
