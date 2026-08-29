package com.bank.payment.api;

import com.bank.common.security.service.AuthorizationDecision;
import com.bank.common.security.service.ServiceOperation;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.payment.api.dto.LoanLedgerSummary;
import com.bank.payment.domain.mapper.LoanLedgerSummaryMapper;
import com.bank.payment.security.ServiceAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 여신 원장 집계 조회. {@code GET /api/v1/internal/ledger/loan-summary}.
 *
 * <p><b>무엇을 위한 것인가.</b> 여신 보조부와 원장을 대사하기 위한 <b>정본 쪽</b> 값이다.
 *
 * <p>복식부기는 우리 장부 안에서 차변과 대변이 맞는지만 본다. 여신이 자기 보조부에
 * 적은 숫자가 원장과 맞는지는 전혀 보장하지 않는다. 두 계통이 각자 산출한 값을
 * 맞춰 보는 것이 그것을 발견하는 유일한 수단이다.
 *
 * <p><b>여기서 여신 보조부를 읽지 않는다.</b> 한쪽을 다른 쪽에서 뽑아 오면 대사가
 * 항등식이 되어 아무것도 검증하지 못한다. 원장은 원장에서만 계산한다.
 */
@Slf4j
@RestController
@RequestMapping("/v1/internal/ledger")
@RequiredArgsConstructor
public class LoanLedgerSummaryController {

    private final ServiceAuthorizationService serviceAuthorizationService;
    private final LoanLedgerSummaryMapper mapper;

    @GetMapping("/loan-summary")
    public ResponseEntity<LoanLedgerSummary> loanSummary(
            @RequestHeader(value = "X-Internal-Token", required = false) String credential,
            @RequestParam("baseDate") String baseDate) {

        // 자금을 움직이지 않지만 같은 인가 관문을 지난다. 읽기라고 면제하면
        // 권한 대장이 반쪽이 된다.
        AuthorizationDecision decision = serviceAuthorizationService.authorize(
                credential,
                ServiceOperation.LOAN_LEDGER_READ.code(),
                new ServiceAuthorizationService.RequestContext(
                        null, null, null, MDC.get("traceId"),
                        "/api/v1/internal/ledger/loan-summary"));

        if (!decision.allowed()) {
            log.warn("원장 집계 조회 인가 거절 service={} reason={}",
                    decision.serviceId(), decision.denyReason());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return ResponseEntity.ok(mapper.summarize(baseDate));
    }
}
