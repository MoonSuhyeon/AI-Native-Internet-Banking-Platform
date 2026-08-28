package com.bank.deposit.controller;

import com.bank.deposit.audit.AccessActor;
import com.bank.deposit.audit.AccessActorResolver;
import com.bank.deposit.audit.ResourceAccessGuard;
import com.bank.deposit.dto.internal.InternalAccountSummary;
import com.bank.deposit.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.bank.deposit.audit.AccessActorResolver.*;

/**
 * 수신 데이터 열람 — 감사가 붙은 내부 읽기 API.
 *
 * <p><b>왜 기존 {@code /accounts} 를 그대로 쓰지 않는가.</b> 기존 조회는 고객 본인
 * 소유권만 본다({@code X-Customer-Id} 일치). AI 상담처럼 <b>직원을 대신해</b> 남의 계좌를
 * 읽는 경로에는 맞지 않는다. 여기서는 행위자와 사유를 요구하고 전부 기록한다.
 *
 * <p><b>왜 감사가 API 와 같이 와야 하는가.</b> DB 직접 접근을 API 로 바꾸면서 감사를
 * 빼면 <b>"DB 는 안 보지만 감사도 안 남는 API"</b> 가 된다. 경계만 옮기고 통제는
 * 그대로 비는 것이라, 둘은 한 작업이다.
 *
 * <p>context-path 가 이미 {@code /api} 다. 여기에 또 {@code /api} 를 쓰면 외부 URL 이
 * {@code /api/api/...} 가 된다.
 */
@RestController
@RequestMapping("/v1/internal/banking")
@RequiredArgsConstructor
public class InternalBankingReadController {

    private static final String RESOURCE_ACCOUNT = "DEPOSIT_ACCOUNT";
    private static final String ACTION_READ = "READ";

    private final AccountService accountService;
    private final AccessActorResolver actorResolver;
    private final ResourceAccessGuard accessGuard;

    /**
     * 고객의 계좌 요약 목록.
     *
     * <p>잔액이 포함되므로 열람 자체가 감사 대상이다. 비밀번호 해시·내부 식별자는
     * 내보내지 않는다 — 상담이 필요한 것은 잔액과 상태까지다.
     */
    @GetMapping("/customers/{customerId}/accounts")
    public List<InternalAccountSummary> accounts(
            @PathVariable String customerId,
            @RequestHeader(value = EMPLOYEE_ID_HEADER, required = false) String employeeId,
            @RequestHeader(value = CUSTOMER_ID_HEADER, required = false) String callerCustomerId,
            @RequestHeader(value = SERVICE_HEADER,     required = false) String service,
            @RequestHeader(value = REASON_HEADER,      required = false) String reason,
            @RequestHeader(value = TRACE_HEADER,       required = false) String traceId) {

        AccessActor actor = actorResolver.resolve(
                employeeId, callerCustomerId, service, reason, traceId,
                RESOURCE_ACCOUNT + "_" + ACTION_READ, customerId);

        // 두 경계를 모두 통과해야 한다 — 상류 인가와 자원 쪽 최종 판단.
        // 통과 여부와 무관하게 요청·판단·결과가 한 건으로 기록된다.
        accessGuard.authorizeRead(actor, RESOURCE_ACCOUNT, ACTION_READ, customerId);

        return accountService.findByCustomer(customerId).stream()
                .map(InternalAccountSummary::from)
                .toList();
    }
}
