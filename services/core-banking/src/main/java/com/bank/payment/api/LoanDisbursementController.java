package com.bank.payment.api;

import com.bank.common.security.service.AuthorizationDecision;
import com.bank.common.security.service.ServiceOperation;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.exception.RiskGuidedException;
import com.bank.deposit.security.FdsPreCheckGate;
import com.bank.deposit.security.PreCheckDecision;
import com.bank.payment.api.dto.LoanDisbursementRequest;
import com.bank.payment.api.dto.LoanDisbursementResponse;
import com.bank.payment.domain.service.LoanDisbursementService;
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
 * 대출 실행. {@code POST /api/v1/internal/loan-disbursements}.
 *
 * <p><b>왜 결제가 아닌가.</b> 대출 실행은 돈을 옮기는 일이 아니라 자산(대출채권)이
 * 생기고 부채(고객 예금)가 생기는 하나의 회계 사건이다. 결제 경로에 밀어 넣으면
 * 집행계좌를 회계적 중간계정으로 쓰게 되고, 실행할 때마다 그 잔액이 줄어든다.
 * 근거는 {@code docs/decisions/transaction-initiator-auth-model.md} §5-1.
 *
 * <p><b>통제는 결제와 같다.</b> 서비스 신원·작업 인가·거래 정책·FDS·멱등·감사를
 * 똑같이 지난다. 갈리는 것은 회계적 의미이지 통제가 아니다.
 *
 * <p>실제 자금이 오가는 거래 — 상환·자동이체·역분개 환급 — 는
 * {@link SystemPaymentController} 를 그대로 쓴다.
 */
@Slf4j
@RestController
@RequestMapping("/v1/internal/loan-disbursements")
@RequiredArgsConstructor
public class LoanDisbursementController {

    private final ServiceAuthorizationService serviceAuthorizationService;
    private final LoanDisbursementService loanDisbursementService;
    private final FdsPreCheckGate fdsPreCheckGate;

    @PostMapping
    public ResponseEntity<LoanDisbursementResponse> disburse(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Internal-Token", required = false) String credential,
            @RequestBody LoanDisbursementRequest request) {

        // 1) 신원과 작업 인가. 허용·거절 모두 감사에 남는다.
        //
        //    송신계좌 자리에 수취 계좌를 넣지 않는다. 대출 실행에는 출금 계좌가
        //    없으므로 계좌 정책이 걸 대상도 없다. 여기서 지키는 것은 "여신이
        //    LOAN_DISBURSE 권한을 갖는가" 와 금액 한도다.
        AuthorizationDecision decision = serviceAuthorizationService.authorize(
                credential,
                ServiceOperation.LOAN_DISBURSE.code(),
                new ServiceAuthorizationService.RequestContext(
                        request.amount(), null, idempotencyKey,
                        MDC.get("traceId"), "/api/v1/internal/loan-disbursements"));

        if (!decision.allowed()) {
            log.warn("대출 실행 인가 거절 service={} reason={}",
                    decision.serviceId(), decision.denyReason());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 2) 이상거래 점검. 결제와 같은 게이트를 지난다 — 자동화된 경로일수록
        //    사람이 보고 있지 않으므로 더 필요하다.
        //
        //    수취 계좌를 송신 자리에 넣어 평가한다. 이 게이트는 계좌 단위 신호를
        //    보므로 대출 실행에서 볼 계좌는 돈이 들어가는 쪽 하나뿐이다.
        PreCheckDecision fds = fdsPreCheckGate.evaluate(
                decision.serviceId(), request.accountNo(), null,
                request.accountNo(), request.amount(),
                null, "SYSTEM", true);

        if (fds.isDelayed()) {
            // 지연은 고객이 알아채고 취소할 시간을 주는 장치인데 이 경로에는 알아챌
            // 고객이 없다. 못 미루면 통과시키지 않는다.
            fdsPreCheckGate.reportDelayNotHonored("loan-disbursement", fds.reasons());
            throw new RiskGuidedException(ErrorCode.TRANSFER_BLOCKED_BY_RISK, fds.guidance());
        }

        // 3) 기장. 예금 증가와 분개 두 다리가 한 트랜잭션이다.
        var result = loanDisbursementService.disburse(
                idempotencyKey, decision.serviceId(),
                request.accountNo(), request.amount(), request.memo());

        return ResponseEntity.ok(new LoanDisbursementResponse(
                result.journalNo(), result.balanceAfter()));
    }
}
