package com.bank.payment.domain.service;

import com.bank.payment.common.IdGenerator;
import com.bank.payment.domain.ExternalCall;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.outbound.feign.DepositAccountClient;
import com.bank.payment.outbound.feign.DepositBalanceClient;
import com.bank.payment.outbound.feign.dto.AccountInquiryData;
import com.bank.payment.common.exception.DepositInboundFailureException;
import com.bank.payment.outbound.feign.dto.BalanceTxData;
import com.bank.payment.outbound.feign.dto.DepositRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * IN 방향 수신 흐름 구현 (정책 시트4).
 * IN-01: 정상 수신 완결. IN-03: 수신계좌 검증 실패 → DRAFT→FAILED + CT REJECTED + KFTC_REJECT_SENT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundPaymentOrchestratorImpl implements InboundPaymentOrchestrator {

    private final PaymentTransactionService txService;
    private final DepositAccountClient depositAccountClient;
    private final DepositBalanceClient depositBalanceClient;
    private final IdGenerator idGenerator;

    @Override
    public void processInbound(String piId, InboundPaymentCommand command) {
        PaymentInstruction pi = txService.selectById(piId);
        String receiverAccountNo = pi.getReceiverAccountNo();

        // A-1: 수신계좌 검증 + accountId 획득 (by-number, D-REQ-1 해결)
        AccountInquiryData account;
        try {
            account = depositAccountClient.getAccountByNo(receiverAccountNo);
            recordCall(piId, "ACCOUNT_INQUIRY", "RECEIVER", "deposit", "GET",
                    "/api/accounts/by-number/" + receiverAccountNo, "SUCCESS");
        } catch (DepositInboundFailureException e) {
            recordCall(piId, "ACCOUNT_INQUIRY", "RECEIVER", "deposit", "GET",
                    "/api/accounts/by-number/" + receiverAccountNo, e.getDepositResponseCode(), "FAIL");
            txService.txInboundReject(pi, command, e.getDepositResponseCode(), e.getMessage());
            log.info("[IN] IN-03 거절 완결(계좌조회 실패): piId={} clearingNo={} rejectCode={}",
                    piId, command.clearingNo(), e.getDepositResponseCode());
            return;
        }

        if (!"ACTIVE".equals(account.accountStatus()) || Boolean.TRUE.equals(account.fraudFlag())) {
            txService.txInboundReject(pi, command, "E2001", "수신계좌 제한: " + account.accountStatus());
            log.info("[IN] IN-03 거절 완결: piId={} clearingNo={} rejectCode=E2001", piId, command.clearingNo());
            return;
        }

        // TX-IN-AUTH: DRAFT→AUTHORIZED (낙관락 version=0)
        txService.txInboundAuthorize(pi);
        pi = txService.selectById(piId);  // version 갱신 (1)

        // B-4: 입금 — A-1에서 획득한 accountId 재사용 (by-number 중복 호출 없음)
        String callIdemKey = piId + "-BALANCE_DEPOSIT-RECEIVER-1";
        String piMemo = pi.getReceiverPassbookSenderDisplay();
        String transactionMemo = (piMemo != null && !piMemo.isBlank())
                ? piId + "|" + piMemo
                : piId;
        DepositRequest depositReq = new DepositRequest(
                account.accountId(),
                pi.getTransferAmount(),
                "MOBILE",
                transactionMemo,
                command.senderRealName());  // depositorName: 입금인명 = 송신자 실명

        BalanceTxData depositTx;
        try {
            depositTx = depositBalanceClient.deposit(callIdemKey, depositReq);
            recordCall(piId, "BALANCE_DEPOSIT", "RECEIVER", "deposit", "POST",
                    "/api/transactions/deposit", "SUCCESS");
        } catch (DepositInboundFailureException e) {
            recordCall(piId, "BALANCE_DEPOSIT", "RECEIVER", "deposit", "POST",
                    "/api/transactions/deposit", e.getDepositResponseCode(), "FAIL");
            log.warn("[IN] TODO step③-c: 입금 실패. piId={} code={}", piId, e.getDepositResponseCode());
            return;
        }

        // TX-IN-DEP: 분개 + CT + COMPLETED + Outbox
        txService.txInboundDeposit(pi, depositTx, command);

        log.info("[IN] IN-01 완결: piId={} clearingNo={}", piId, command.clearingNo());
    }

    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode) {
        return recordCall(piId, callType, accountRole, targetSystem, httpMethod, endpointUrl,
                responseCode, "SUCCESS");
    }

    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode, String result) {
        LocalDateTime now = LocalDateTime.now();
        String callId = idGenerator.nextCallId();
        String callIdemKey = piId + "-" + callType + "-" + accountRole + "-1";
        ExternalCall ec = ExternalCall.of(
                callId, callIdemKey, piId,
                callType, targetSystem, endpointUrl, httpMethod,
                UUID.randomUUID().toString(), "{}", "{}", "",
                500, now);
        ec.recordResponse(200, "{}", "{}", responseCode, result, result, 50, now);
        txService.recordExternalCall(ec);
        return callId;
    }
}
