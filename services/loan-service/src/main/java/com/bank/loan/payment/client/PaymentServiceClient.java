package com.bank.loan.payment.client;

import com.bank.loan.payment.client.dto.LoanDisbursementRequest;
import com.bank.loan.payment.client.dto.LoanLedgerSummary;
import com.bank.loan.payment.client.dto.LoanDisbursementResponse;
import com.bank.loan.payment.client.dto.PaymentRequest;
import com.bank.loan.payment.client.dto.PaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * core-banking 내부 결제 호출.
 *
 * <p><b>왜 고객 경로를 쓰지 않는가.</b> 예전에는 {@code /api/v1/payments} 를 불렀다.
 * 그 경로는 고객 이체 승인(step-up)을 요구하는데 여신은 그 토큰을 가질 수 없다.
 * 보내지 않으면 {@code TRANSFER_APPROVAL_REQUIRED}, 만들어 보내면
 * {@code TRANSFER_APPROVAL_INVALID} — 어느 쪽으로도 통과할 수 없었다.
 *
 * <p>배치가 개시한 거래에는 "본인이 이 거래를 지시했습니까" 라고 물을 본인이 없다.
 * 검증해야 할 것은 다른 질문이다 — "이 거래를 실행할 권한이 있는 시스템인가".
 * 근거는 {@code docs/decisions/transaction-initiator-auth-model.md}.
 *
 * <p><b>X-Auth-Token-Id 를 더 이상 만들지 않는다.</b> 예전에는 payment 의
 * {@code auth_token_id} 가 UNIQUE 라는 이유로 멱등키를 SHA-256 해시해 채웠는데,
 * core 는 같은 값을 고객 승인 토큰으로 읽어 검증하러 갔다. 한 헤더가 두 의미를
 * 겸하고 있었다. 내부 경로는 이 헤더를 요구하지 않고, core 가 {@code auth_token_id}
 * 에 null 을 넣는다 — 고객 인증이 없었으므로 그것이 정확한 표현이다.
 */
@Slf4j
@Component
public class PaymentServiceClient {

    private static final String INTERNAL_PAYMENTS_PATH = "/api/v1/internal/payments";
    private static final String LOAN_DISBURSEMENTS_PATH = "/api/v1/internal/loan-disbursements";
    private static final String LOAN_LEDGER_SUMMARY_PATH = "/api/v1/internal/ledger/loan-summary";

    private final RestClient restClient;
    private final String credential;

    public PaymentServiceClient(RestClient.Builder builder, PaymentServiceProperties props) {
        this.restClient = builder.baseUrl(props.url()).build();
        this.credential = props.credential();
    }

    /**
     * 결제를 요청한다.
     *
     * <p>신원은 자격증명에서 나온다. "나는 여신이다" 라고 주장하는 헤더를 보내지
     * 않는 이유는, 그런 헤더를 믿으면 토큰 하나를 가진 무엇이든 여신을 사칭할 수
     * 있기 때문이다.
     */
    public PaymentResponse pay(String idempotencyKey, PaymentRequest req) {
        log.debug("내부 결제 요청 operation={} idemKey={} amount={}",
                req.operation(), idempotencyKey, req.transferAmount());
        PaymentResponse resp = restClient.post()
                .uri(INTERNAL_PAYMENTS_PATH)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-Internal-Token", credential)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(PaymentResponse.class);
        log.info("내부 결제 응답 operation={} idemKey={} status={} piId={}",
                req.operation(), idempotencyKey,
                resp != null ? resp.status() : null,
                resp != null ? resp.paymentInstructionId() : null);
        return resp;
    }

    /**
     * 대출을 실행한다.
     *
     * <p>결제가 아니라 회계 거래다. 대출 실행은 집행계좌에서 돈을 빼오는 일이 아니라
     * 대출채권(자산)과 고객 예금(부채)이 동시에 생기는 하나의 사건이다. 결제 경로에
     * 밀어 넣으면 집행계좌가 회계적 중간계정이 되어 실행할 때마다 잔액이 줄어든다.
     *
     * <p>상환·자동이체·역분개 환급은 실제 자금이동이므로 {@link #pay} 를 그대로 쓴다.
     */
    public LoanDisbursementResponse disburse(String idempotencyKey, LoanDisbursementRequest req) {
        log.debug("대출 실행 요청 idemKey={} accountNo={} amount={}",
                idempotencyKey, req.accountNo(), req.amount());
        LoanDisbursementResponse resp = restClient.post()
                .uri(LOAN_DISBURSEMENTS_PATH)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-Internal-Token", credential)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(LoanDisbursementResponse.class);
        log.info("대출 실행 응답 idemKey={} journalNo={}",
                idempotencyKey, resp != null ? resp.journalNo() : null);
        return resp;
    }

    /**
     * 원장이 말한 하루치 여신 집계를 읽는다.
     *
     * <p>보조부와 맞춰 보기 위한 정본 쪽 값이다. 원장은 원장에서만 계산해 넘어온다 —
     * 여기서 보조부를 참조하면 대사가 항등식이 된다.
     */
    public LoanLedgerSummary loanLedgerSummary(String baseDate) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path(LOAN_LEDGER_SUMMARY_PATH)
                        .queryParam("baseDate", baseDate).build())
                .header("X-Internal-Token", credential)
                .retrieve()
                .body(LoanLedgerSummary.class);
    }
}
