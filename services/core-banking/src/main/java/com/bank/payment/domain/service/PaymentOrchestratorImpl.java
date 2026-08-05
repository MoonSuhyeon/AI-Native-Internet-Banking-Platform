package com.bank.payment.domain.service;

import com.bank.payment.common.BankCodeMapper;
import com.bank.payment.common.IdGenerator;
import com.bank.payment.common.exception.DepositInboundFailureException;
import com.bank.payment.common.exception.LedgerInsertFailureException;
import com.bank.payment.common.exception.PaymentCancelConflictException;
import com.bank.payment.common.exception.PaymentNotFoundException;
import com.bank.payment.common.exception.PaymentUnauthorizedException;
import com.bank.payment.common.exception.PaymentValidationException;
import com.bank.payment.domain.BokSettlementTransaction;
import com.bank.payment.domain.ExternalCall;
import com.bank.payment.domain.KftcClearingTransaction;
import com.bank.payment.domain.Ledger;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.outbound.feign.DepositAccountClient;
import com.bank.payment.outbound.feign.DepositBalanceClient;
import com.bank.payment.outbound.feign.DepositErrorMapper;
import com.bank.payment.outbound.feign.dto.AccountInquiryData;
import com.bank.payment.outbound.feign.dto.BalanceTxData;
import com.bank.payment.outbound.feign.dto.DepositRequest;
import com.bank.payment.outbound.feign.dto.WithdrawCancelData;
import com.bank.payment.outbound.feign.dto.WithdrawRequest;
import com.bank.payment.config.PaymentMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * P-028 5단계 흐름 구현. 외부호출(Feign)은 여기서 트랜잭션 밖. DB 작업은 PaymentTransactionService 위임.
 *
 * Stage 5-6: 자행 S1 8건 (수신검증 추가).
 * F8 (다2): B-4 입금실패 → AUTHORIZED→REVERSING→FAILED + B-5 출금취소. 역분개 0건.
 * F2: KFTC 거절 → CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소.
 * call_idempotency_key 형식: {piId}-{callType}-{accountRole}-{attemptNo}
 */
@Slf4j
@Service
public class PaymentOrchestratorImpl implements PaymentOrchestrator {

    private static final long BOK_ROUTING_THRESHOLD = 1_000_000_000L;

    private final PaymentTransactionService txService;
    private final DepositAccountClient depositAccountClient;
    private final DepositBalanceClient depositBalanceClient;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final PaymentMetrics metrics;

    @Value("${payment.bank-code:A}")
    private String bankCode;

    public PaymentOrchestratorImpl(
            PaymentTransactionService txService,
            DepositAccountClient depositAccountClient,
            DepositBalanceClient depositBalanceClient,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            PaymentMetrics metrics) {
        this.txService = txService;
        this.depositAccountClient = depositAccountClient;
        this.depositBalanceClient = depositBalanceClient;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public PaymentResult processPayment(PaymentCommand command) {
        boolean isIntraBank = isIntraBank(command.receiverBankCode());
        String routingNetworkType = determineRoutingNetworkType(command);

        // TX-1: PI DRAFT INSERT — 중복 멱등키는 메트릭 기록 후 DuplicateKeyException 재발생
        PaymentInstruction pi;
        try {
            pi = txService.txStep1(command, isIntraBank, routingNetworkType);
        } catch (DuplicateKeyException e) {
            metrics.idempotencyDuplicate();
            log.warn("[OUT] 중복 멱등키 감지: idempotencyKey={}", command.idempotencyKey());
            throw e;
        }

        if (isIntraBank) {
            return processIntraBank(pi, command);
        } else if ("BOK".equals(routingNetworkType)) {
            return processInterBok(pi, command);
        } else {
            return processInterBank(pi, command);
        }
    }

    private PaymentResult processIntraBank(PaymentInstruction pi, PaymentCommand command) {
        // B-4 실패 보상 경로에서 B-5 target에 넣을 원 B-3 callId 보관
        WithdrawStepResult withdrawStep = null;

        try {
            ExternalValidationResult validation = step2a_registerValidation(pi, command);
            step2b_executeValidation(pi, command);

            txService.authorize(pi.getPaymentInstructionId(), pi.getVersion());

            // Step 3: 출금(B-3) + 입금(B-4) — 트랜잭션 밖
            withdrawStep = step3_withdraw(pi, command);
            BalanceTxData depositResult = step3b_deposit(pi, command);

            // TX-2: 분개 2건 + COMPLETED + Outbox + 멱등키완료
            PaymentResult result = txService.txStep4(pi, withdrawStep.txData(), depositResult, command,
                    validation.senderHolderName(), validation.receiverHolderName());
            metrics.paymentCompleted(pi.getRequestedAt());
            return result;

        } catch (PaymentValidationException e) {
            // 비즈니스 거절 → DRAFT→FAILED. 자금변동 없음(B-3 미도달). 200 OK + status=FAILED
            metrics.paymentFailed();
            return txService.txStepFail(pi, e.getFailureCategory(), failedEventTypeFor(e.getFailureCategory()), "DRAFT");

        } catch (DepositInboundFailureException e) {
            // step2a/2b(authorize 전, DRAFT) 실패 = 자금변동 없음 → txStepFail로 FAILED 직행.
            // step3b(authorize 후, AUTHORIZED) 실패 = B-3 출금 성공 후 B-4 실패 → 보상 필수 (P-002).
            String piId = pi.getPaymentInstructionId();
            PaymentInstruction freshPi = txService.selectById(piId);

            // 이중보상 가드: 이미 FAILED/CANCELED이면 skip (합의서 시트15 1차 방어)
            if ("FAILED".equals(freshPi.getStatus()) || "CANCELED".equals(freshPi.getStatus())) {
                return new PaymentResult(piId, pi.getTransactionNo(), "FAILED", "SYSTEM_ERROR", null);
            }

            // DRAFT 분기: authorize 전 deposit 호출 실패. 보상 진입 금지(withdrawStep=null, B-3 미도달).
            if ("DRAFT".equals(freshPi.getStatus())) {
                String fc = DepositErrorMapper.toFailureCategory(e.getDepositResponseCode());
                metrics.paymentFailed();
                return txService.txStepFail(freshPi, fc, failedEventTypeFor(fc), "DRAFT");
            }

            // withdrawStep == null: authorize 이후 step3 내부(출금 호출 이전, 예: getAccountByNo)에서 실패.
            // 출금이 일어나지 않았으므로 보상(step3c_withdrawCancel) 부적절. 출금 분개 시작 전이므로 AUTHORIZED→FAILED.
            if (withdrawStep == null) {
                String fc = DepositErrorMapper.toFailureCategory(e.getDepositResponseCode());
                metrics.paymentFailed();
                return txService.txStepFail(freshPi, fc, failedEventTypeFor(fc), "AUTHORIZED");
            }

            // TX-A: AUTHORIZED→REVERSING + 이력 2건
            // pi.getVersion()=0 → authorize 후 DB version=1 → txMarkReversing WHERE version=1 → version=2
            txService.txMarkReversing(pi, pi.getVersion() + 1, "AUTHORIZED");

            // B-5: 출금취소 (TX 밖)
            step3c_withdrawCancel(pi, command, withdrawStep.callId(), withdrawStep.txData());

            // TX-B: REVERSING→FAILED + 이력 2건 + Outbox + 멱등키
            // WHERE version=2 → version=3
            metrics.paymentFailed();
            return txService.txCompleteReversal(pi, command.idempotencyKey(), pi.getVersion() + 2);

        } catch (LedgerInsertFailureException e) {
            // F5: txStep4 분개 INSERT 실패 → txStep4 전체 롤백 → AUTHORIZED v1 복귀 → 보상 필수 (P-002)
            // B-3 출금·B-4 입금 모두 성공 후 분개만 실패이므로 B-5 출금취소 필수
            // withdrawStep은 B-3/B-4 모두 성공 후 txStep4 실패이므로 non-null 보장
            String piId = pi.getPaymentInstructionId();

            // ★version 신선화: txStep4 롤백으로 DB version=1(AUTHORIZED) 복귀.
            // Java pi.version=0(txStep1 기준)이므로 freshPi로 DB 실제값(1) 확인 후 낙관락 매칭
            PaymentInstruction freshPi = txService.selectById(piId);

            // 이중보상 가드: 이미 FAILED/CANCELED이면 skip (합의서 시트15 1차 방어)
            if ("FAILED".equals(freshPi.getStatus()) || "CANCELED".equals(freshPi.getStatus())) {
                return new PaymentResult(piId, pi.getTransactionNo(), "FAILED", "SYSTEM_ERROR", null);
            }

            // TX-A: AUTHORIZED→REVERSING + 이력 2건 (freshPi.version=1 → WHERE version=1, DB version→2)
            txService.txMarkReversing(freshPi, freshPi.getVersion(), "AUTHORIZED");

            // B-5: 출금취소 (TX 밖, compensation_target_call_id=원 B-3 callId)
            step3c_withdrawCancel(freshPi, command, withdrawStep.callId(), withdrawStep.txData());

            // TX-B: REVERSING→FAILED + 이력 2건 + Outbox + 멱등키 (freshPi.version+1=2 → WHERE version=2, DB version→3)
            metrics.paymentFailed();
            return txService.txCompleteReversal(freshPi, command.idempotencyKey(), freshPi.getVersion() + 1);
        }
    }

    @Override
    public PaymentResult registerScheduledPayment(PaymentCommand command, java.time.LocalDateTime scheduledExecutionAt) {
        boolean isIntraBank = isIntraBank(command.receiverBankCode());
        String routingNetworkType = determineRoutingNetworkType(command);

        PaymentInstruction pi;
        try {
            pi = txService.txStep1(command, isIntraBank, routingNetworkType);
        } catch (DuplicateKeyException e) {
            metrics.idempotencyDuplicate();
            log.warn("[SCHED] 중복 멱등키 감지: idempotencyKey={}", command.idempotencyKey());
            throw e;
        }

        try {
            // step2a: 계좌·예금주 A 검증 + receiver snapshot (잔액·한도 B 검증 제외)
            step2a_registerValidation(pi, command);

            // DRAFT→AUTHORIZED (pi.getVersion()=0)
            txService.authorize(pi.getPaymentInstructionId(), pi.getVersion());

            // AUTHORIZED→SCHEDULED (authorize 후 DB version=1 → version 인자=pi.getVersion()+1=1)
            txService.markScheduled(pi.getPaymentInstructionId(), pi.getVersion() + 1, scheduledExecutionAt);

            return new PaymentResult(pi.getPaymentInstructionId(), pi.getTransactionNo(), "SCHEDULED", null, null);

        } catch (PaymentValidationException e) {
            // A 검증 실패 → DRAFT→FAILED (B-3 미도달, 자금변동 없음)
            return txService.txStepFail(pi, e.getFailureCategory(), failedEventTypeFor(e.getFailureCategory()), "DRAFT");
        }
    }

    /**
     * 예약이체 실행. 워커가 claim 성공 후 호출.
     * ★txStep1/authorize/markScheduled 재호출 금지 — 이미 PROCESSING 상태.
     * 타행/BOK는 UnsupportedOperationException 가드.
     *
     * 실패 3갈래 처리:
     *   PaymentValidationException  → sender/잔액 실패 (B-3 미도달) → PROCESSING→FAILED
     *   DepositInboundFailureException → F8 입금실패 (B-3 성공) → PROCESSING→REVERSING→FAILED
     *   LedgerInsertFailureException   → F5 분개실패 (txStep4Scheduled 롤백) → PROCESSING→REVERSING→FAILED
     * ★freshPi(V+1) 재조회: claim 이 DB version=V+1 을 커밋했고 txStep4Scheduled 가 롤백될 수 있으므로
     *   selectById 로 신선한 version 을 확인 후 낙관락 사용 (즉시이체 F5/F8 과 동일 패턴).
     */
    @Override
    public PaymentResult executeScheduledIntraBank(PaymentInstruction pi) {
        if (!Boolean.TRUE.equals(pi.getIsIntraBank())) {
            throw new UnsupportedOperationException(
                    "예약이체 타행/BOK 미구현 — 후속 단계. piId=" + pi.getPaymentInstructionId());
        }

        String piId = pi.getPaymentInstructionId();
        PaymentCommand command = rebuildCommand(pi);
        // catch 블록에서도 접근 가능하도록 try 전에 선언 (F8/F5 보상에서 w.callId()/w.txData() 필요)
        WithdrawStepResult w = null;

        try {
            // step2a 실행용(attempt=2): sender A 재검증 + receiver snapshot 유지.
            // 등록 시(attempt=1) call_idempotency_key 와 분리 — DuplicateKeyException 방지.
            ExternalValidationResult validation = step2a_executeRevalidation(pi, command);
            // step2b: 실행 시점 잔액·한도 검증 (등록 시에는 생략했던 B 검증)
            step2b_executeValidation(pi, command);

            w = step3_withdraw(pi, command);
            BalanceTxData deposit = step3b_deposit(pi, command);

            PaymentResult result = txService.txStep4Scheduled(pi, w.txData(), deposit, command,
                    validation.senderHolderName(), validation.receiverHolderName());
            metrics.paymentCompleted(pi.getRequestedAt());
            return result;

        } catch (PaymentValidationException e) {
            // sender/잔액 실패 — B-3 미도달, 자금변동 없음
            PaymentInstruction freshPi = txService.selectById(piId);
            if ("FAILED".equals(freshPi.getStatus()) || "CANCELED".equals(freshPi.getStatus())) {
                return new PaymentResult(piId, pi.getTransactionNo(), "FAILED", "SYSTEM_ERROR", null);
            }
            metrics.paymentFailed();
            return txService.txStepFail(freshPi, e.getFailureCategory(),
                    failedEventTypeFor(e.getFailureCategory()), "PROCESSING");

        } catch (DepositInboundFailureException e) {
            // F8: B-4 입금 실패 — B-3 출금 성공, 자금변동 발생 → 보상 필수
            PaymentInstruction freshPi = txService.selectById(piId);
            if ("FAILED".equals(freshPi.getStatus()) || "CANCELED".equals(freshPi.getStatus())) {
                return new PaymentResult(piId, pi.getTransactionNo(), "FAILED", "SYSTEM_ERROR", null);
            }
            // freshPi.version = claim 후 V+1. txMarkReversing WHERE version=V+1 → REVERSING(V+2)
            txService.txMarkReversing(freshPi, freshPi.getVersion(), "PROCESSING");
            step3c_withdrawCancel(freshPi, command, w.callId(), w.txData());
            // txCompleteReversal WHERE version=V+2 → FAILED(V+3)
            metrics.paymentFailed();
            return txService.txCompleteReversal(freshPi, command.idempotencyKey(), freshPi.getVersion() + 1);

        } catch (LedgerInsertFailureException e) {
            // F5: txStep4Scheduled 롤백 → PROCESSING(V+1) 복귀. B-3/B-4 성공 → 보상 필수
            PaymentInstruction freshPi = txService.selectById(piId);
            if ("FAILED".equals(freshPi.getStatus()) || "CANCELED".equals(freshPi.getStatus())) {
                return new PaymentResult(piId, pi.getTransactionNo(), "FAILED", "SYSTEM_ERROR", null);
            }
            txService.txMarkReversing(freshPi, freshPi.getVersion(), "PROCESSING");
            step3c_withdrawCancel(freshPi, command, w.callId(), w.txData());
            metrics.paymentFailed();
            return txService.txCompleteReversal(freshPi, command.idempotencyKey(), freshPi.getVersion() + 1);
        }
    }

    /**
     * PI → PaymentCommand 재조립. PI 스냅샷 필드를 Command 12필드로 직접 매핑.
     * receiverHolderName ← receiverHolderNameSnap, userId ← senderUserId.
     */
    private PaymentCommand rebuildCommand(PaymentInstruction pi) {
        return new PaymentCommand(
                pi.getSenderAccountId(),
                pi.getReceiverBankCode(),
                pi.getReceiverAccountNo(),
                pi.getReceiverHolderNameSnap(),
                pi.getTransferAmount(),
                pi.getReceiverMemo(),
                pi.getSenderMemo(),
                pi.getChannel(),
                pi.getReceiverPassbookSenderDisplay(),
                pi.getSenderUserId(),
                pi.getAuthTokenId(),
                pi.getIdempotencyKey()
        );
    }

    /** BOK 거액이체 송신. step2/authorize/step3는 망 무관 공용 호출. */
    private PaymentResult processInterBok(PaymentInstruction pi, PaymentCommand command) {
        try {
            ExternalValidationResult validation = step2a_registerValidation(pi, command);
            step2b_executeValidation(pi, command);

            txService.authorize(pi.getPaymentInstructionId(), pi.getVersion());

            // Step 3: 출금(B-3) — BOK도 수신 입금 없음, 청산대기 분개(KB-CLR-BOK)로 박제
            WithdrawStepResult withdrawStep = step3_withdraw(pi, command);

            // TX-2: 분개4건(2묶음) + AUTHORIZED→PROCESSING→CLEARING + Outbox(BOK_REQUEST_SENT)
            //       + bok_settlement_transaction REQUESTED INSERT + 멱등키완료
            String numericBankCode = BankCodeMapper.toNumeric(bankCode);
            return txService.txStep4InterBok(pi, withdrawStep.txData(), command,
                    validation.senderHolderName(), validation.receiverHolderName(), numericBankCode);

        } catch (PaymentValidationException e) {
            // step2 검증 실패 — 자금변동 없음(B-3 미도달). 200 OK + status=FAILED
            metrics.paymentFailed();
            return txService.txStepFail(pi, e.getFailureCategory(), failedEventTypeFor(e.getFailureCategory()), "DRAFT");
        }
    }

    private PaymentResult processInterBank(PaymentInstruction pi, PaymentCommand command) {
        try {
            ExternalValidationResult validation = step2a_registerValidation(pi, command);
            step2b_executeValidation(pi, command);

            txService.authorize(pi.getPaymentInstructionId(), pi.getVersion());

            // Step 3: 출금(B-3) — 타행은 수신 입금 없음, 청산대기 분개로 박제
            WithdrawStepResult withdrawStep = step3_withdraw(pi, command);

            // TX-2: 분개4건(2묶음) + AUTHORIZED→PROCESSING→CLEARING + Outbox(KFTC_REQUEST_SENT)
            //       + kftc_clearing_transaction REQUESTED INSERT + 멱등키완료
            String numericBankCode = BankCodeMapper.toNumeric(bankCode);
            return txService.txStep4InterBank(pi, withdrawStep.txData(), command,
                    validation.senderHolderName(), validation.receiverHolderName(), numericBankCode);

        } catch (PaymentValidationException e) {
            // step2 검증 실패 — 자금변동 없음(B-3 미도달). 200 OK + status=FAILED
            metrics.paymentFailed();
            return txService.txStepFail(pi, e.getFailureCategory(), failedEventTypeFor(e.getFailureCategory()), "DRAFT");
        }
    }

    private static String failedEventTypeFor(String failureCategory) {
        return switch (failureCategory) {
            case "INSUFFICIENT_BALANCE" -> "BALANCE_CHECK_FAILED";
            case "LIMIT_EXCEEDED"       -> "LIMIT_CHECK_FAILED";
            case "OWNER_INQUIRY_FAILED" -> "OWNER_INQUIRY_FAILED";
            case "ACCOUNT_RESTRICTED"   -> "ACCOUNT_CHECK_FAILED";
            case "ACCOUNT_CLOSED"       -> "ACCOUNT_CHECK_FAILED";
            // 안전망: 위 5개 외 예상치 못한 code 진입 시 — 실제 경로에서는 미사용
            default                     -> "PAYMENT_FAILED";
        };
    }

    // P-007: 10억(1,000,000,000) 이상은 BOK(한은망 거액이체), 미만은 KFTC(금융결제원).
    // ★정책충돌: 테이블정의서 CHECK 예시 1억은 오류로 판단 — enum #16/#39 "10억" 채택.
    private String determineRoutingNetworkType(PaymentCommand command) {
        if (isIntraBank(command.receiverBankCode())) {
            return "INTERNAL";
        } else if (command.transferAmount().compareTo(BigDecimal.valueOf(BOK_ROUTING_THRESHOLD)) >= 0) {
            return "BOK";
        } else {
            return "KFTC";
        }
    }

    // receiverBankCode == 자행코드(A은행=004, B은행=088) → 자행
    private boolean isIntraBank(String receiverBankCode) {
        return BankCodeMapper.toNumeric(bankCode).equals(receiverBankCode);
    }

    /**
     * Step 2a: A 검증 (계좌·예금주). 등록 및 즉시이체 공용.
     * A-1송신 → A-1수신(자행) → A-2송신 → A-2수신(자행, HOLDER_DECEASED/HOLDER_MISMATCH)
     * receiver_holder_name_snap 영속화 포함. 트랜잭션 밖.
     */
    private ExternalValidationResult step2a_registerValidation(PaymentInstruction pi, PaymentCommand command) {
        String piId = pi.getPaymentInstructionId();
        String sender = command.senderAccountId();
        String receiver = command.receiverAccountNo();

        // A-1 계좌조회 (송신계좌) — by-number (D-REQ-1 해결)
        AccountInquiryData senderAccount;
        try {
            senderAccount = depositAccountClient.getAccountByNo(sender);
            recordCall(piId, "ACCOUNT_INQUIRY", "SENDER", "deposit", "GET",
                    "/api/accounts/by-number/" + sender, "SUCCESS");
        } catch (DepositInboundFailureException e) {
            recordCall(piId, "ACCOUNT_INQUIRY", "SENDER", "deposit", "GET",
                    "/api/accounts/by-number/" + sender, e.getDepositResponseCode(), "FAIL");
            throw new PaymentValidationException(
                    DepositErrorMapper.toFailureCategory(e.getDepositResponseCode()), e.getMessage());
        }
        if (!"ACTIVE".equals(senderAccount.accountStatus())) {
            // CLOSED → ACCOUNT_CLOSED, SUSPENDED/DORMANT 등 → ACCOUNT_RESTRICTED
            String fc = "CLOSED".equals(senderAccount.accountStatus()) ? "ACCOUNT_CLOSED" : "ACCOUNT_RESTRICTED";
            throw new PaymentValidationException(fc,
                    "송신계좌 비활성: " + senderAccount.accountStatus());
        }
        if (Boolean.TRUE.equals(senderAccount.fraudFlag())) {
            throw new PaymentValidationException("ACCOUNT_RESTRICTED", "송신계좌 사고신고");
        }

        // A-1 계좌조회 (수신계좌) — 자행만. 타행은 수신계좌가 타 은행 관할이므로 deposit 검증 생략
        if (Boolean.TRUE.equals(pi.getIsIntraBank())) {
            AccountInquiryData receiverAccount;
            try {
                receiverAccount = depositAccountClient.getAccountByNo(receiver);
                recordCall(piId, "ACCOUNT_INQUIRY", "RECEIVER", "deposit", "GET",
                        "/api/accounts/by-number/" + receiver, "SUCCESS");
            } catch (DepositInboundFailureException e) {
                recordCall(piId, "ACCOUNT_INQUIRY", "RECEIVER", "deposit", "GET",
                        "/api/accounts/by-number/" + receiver, e.getDepositResponseCode(), "FAIL");
                throw new PaymentValidationException(
                        DepositErrorMapper.toFailureCategory(e.getDepositResponseCode()), e.getMessage());
            }
            if (!"ACTIVE".equals(receiverAccount.accountStatus())) {
                // CLOSED → ACCOUNT_CLOSED, SUSPENDED/DORMANT 등 → ACCOUNT_RESTRICTED
                String fc = "CLOSED".equals(receiverAccount.accountStatus()) ? "ACCOUNT_CLOSED" : "ACCOUNT_RESTRICTED";
                throw new PaymentValidationException(fc,
                        "수신계좌 비활성: " + receiverAccount.accountStatus());
            }
            if (Boolean.TRUE.equals(receiverAccount.fraudFlag())) {
                throw new PaymentValidationException("ACCOUNT_RESTRICTED", "수신계좌 사고신고");
            }
        }

        // A-2 예금주조회 (송신계좌) — D-REQ-5: 예금주명은 deposit/customer-service 정식 조회 영역.
        // 정식 연동 전까지 미조회 표기. userId는 예금주명이 아님.
        String senderHolderName = "미조회";
        recordCall(piId, "ACCOUNT_OWNER_INQUIRY", "SENDER", "internal", "GET",
                "internal:command.userId", "SUCCESS");

        // A-2 예금주조회 (수신계좌) — D-REQ-5: 예금주명은 deposit/customer-service 정식 조회 영역.
        // 정식 연동 전까지 미조회 표기. 요청 receiverHolderName은 미검증값이므로 ledger에 박지 않음.
        String receiverHolderName = "미조회";
        recordCall(piId, "ACCOUNT_OWNER_INQUIRY", "RECEIVER", "internal", "GET",
                "internal:command.receiverHolderName", "SUCCESS");
        txService.updateReceiverHolderSnap(piId, receiverHolderName, null);

        return new ExternalValidationResult(senderHolderName, receiverHolderName);
    }

    /**
     * Step 2a 실행용(attempt=2): 예약이체 실행 시 sender 만 A 재검증 (Option B).
     * receiver 는 등록 시 박제된 snapshot(pi.getReceiverHolderNameSnap()) 신뢰 —
     * 실행 시 재조회/덮어쓰기 안 함. 등록~실행 사이 예금주 변경을 사용자가 확인 안 한 값으로
     * 덮어쓸 위험 방지 + 등록 snapshot 일관성 보존.
     * receiver 계좌 상태(폐쇄 등)는 step3b_deposit(B-4 입금) 시 거기서 처리.
     */
    private ExternalValidationResult step2a_executeRevalidation(PaymentInstruction pi, PaymentCommand command) {
        final int attempt = 2;
        String piId = pi.getPaymentInstructionId();
        String sender = command.senderAccountId();

        // A-1 계좌조회 (송신계좌) — ACTIVE 여부 + 사고신고 확인
        AccountInquiryData senderAccount;
        try {
            senderAccount = depositAccountClient.getAccountByNo(sender);
            recordCall(piId, "ACCOUNT_INQUIRY", "SENDER", "deposit", "GET",
                    "/api/accounts/by-number/" + sender, "SUCCESS", "SUCCESS", attempt);
        } catch (DepositInboundFailureException e) {
            recordCall(piId, "ACCOUNT_INQUIRY", "SENDER", "deposit", "GET",
                    "/api/accounts/by-number/" + sender, e.getDepositResponseCode(), "FAIL", attempt);
            throw new PaymentValidationException(
                    DepositErrorMapper.toFailureCategory(e.getDepositResponseCode()), e.getMessage());
        }
        if (!"ACTIVE".equals(senderAccount.accountStatus())) {
            String fc = "CLOSED".equals(senderAccount.accountStatus()) ? "ACCOUNT_CLOSED" : "ACCOUNT_RESTRICTED";
            throw new PaymentValidationException(fc, "송신계좌 비활성(실행시): " + senderAccount.accountStatus());
        }
        if (Boolean.TRUE.equals(senderAccount.fraudFlag())) {
            throw new PaymentValidationException("ACCOUNT_RESTRICTED", "송신계좌 사고신고(실행시)");
        }

        // A-2 예금주조회 (송신계좌) — D-REQ-5: 예금주명은 deposit/customer-service 정식 조회 영역.
        // 정식 연동 전까지 미조회 표기. userId는 예금주명이 아님.
        String senderHolderName = "미조회";
        recordCall(piId, "ACCOUNT_OWNER_INQUIRY", "SENDER", "internal", "GET",
                "internal:command.userId", "SUCCESS", "SUCCESS", attempt);

        // receiver: 등록 시 박제된 snapshot 을 그대로 사용 (재조회/덮어쓰기 없음)
        return new ExternalValidationResult(senderHolderName, pi.getReceiverHolderNameSnap());
    }

    /**
     * Step 2b: B 검증 (잔액·한도). 즉시이체 전용 — 예약등록 경로에서는 호출하지 않음.
     * B-1 잔액 → B-2 한도. 트랜잭션 밖.
     *
     * deposit 실서비스에 별도 /balances·/limits 엔드포인트 없음(D-REQ-3/4 미해소) →
     * 계좌조회 응답(AccountInquiryData)의 balance/dailyWithdrawLimit로 검증.
     */
    private void step2b_executeValidation(PaymentInstruction pi, PaymentCommand command) {
        String piId = pi.getPaymentInstructionId();
        String sender = command.senderAccountId();
        BigDecimal needed = command.transferAmount();
        String byNumberPath = "/api/accounts/by-number/" + sender;

        AccountInquiryData senderAcc;
        try {
            senderAcc = depositAccountClient.getAccountByNo(sender);
        } catch (DepositInboundFailureException e) {
            recordCall(piId, "BALANCE_INQUIRY", "SENDER", "deposit", "GET",
                    byNumberPath, e.getDepositResponseCode(), "FAIL");
            throw new PaymentValidationException(
                    DepositErrorMapper.toFailureCategory(e.getDepositResponseCode()), e.getMessage());
        }

        // B-1 잔액검증 — balance=availableBalance (D-REQ-3: deposit 가용잔액 별도 없음, balance로 대체).
        BigDecimal balance = senderAcc.balance();
        if (balance == null || balance.compareTo(needed) < 0) {
            recordCall(piId, "BALANCE_INQUIRY", "SENDER", "deposit", "GET",
                    byNumberPath, "DEP-0000", "FAIL");
            throw new PaymentValidationException("INSUFFICIENT_BALANCE",
                    "잔액 부족: 가용 " + balance + " < 필요 " + needed);
        }
        recordCall(piId, "BALANCE_INQUIRY", "SENDER", "deposit", "GET",
                byNumberPath, "DEP-0000");

        // B-2 한도검증 — Account.dailyWithdrawLimit (BigDecimal nullable). null=한도 미설정 → 스킵.
        // D-REQ-4 미해소: perTx/daily/monthly 분리 없음. dailyWithdrawLimit 단일 한도로 단순 비교.
        BigDecimal dailyLimit = senderAcc.dailyWithdrawLimit();
        if (dailyLimit != null && needed.compareTo(dailyLimit) > 0) {
            recordCall(piId, "LIMIT_CHECK", "SENDER", "deposit", "GET",
                    byNumberPath, "DEP-0000", "FAIL");
            throw new PaymentValidationException("LIMIT_EXCEEDED",
                    "이체 한도 초과: 요청 " + needed + " > 1일 한도 " + dailyLimit);
        }
        recordCall(piId, "LIMIT_CHECK", "SENDER", "deposit", "GET",
                byNumberPath, "DEP-0000");
    }

    // ── Step 3: 출금 (B-3, 트랜잭션 밖) ─────────────────────────────────────
    // WithdrawStepResult: BalanceTxData + callId (B-4 실패 시 B-5 compensation_target_call_id 참조용)
    private WithdrawStepResult step3_withdraw(PaymentInstruction pi, PaymentCommand command) {
        String piId = pi.getPaymentInstructionId();
        String callIdemKey = piId + "-BALANCE_WITHDRAW-SENDER-1";

        // by-number → accountId 획득 (step2a에서 검증된 계좌 재조회)
        AccountInquiryData senderAcc = depositAccountClient.getAccountByNo(command.senderAccountId());
        Long senderAccountId = senderAcc.accountId();

        String senderMemo = command.senderMemo();
        String transactionMemo = (senderMemo != null && !senderMemo.isBlank())
                ? piId + "|" + senderMemo
                : piId;

        WithdrawRequest request = new WithdrawRequest(
                senderAccountId,
                command.transferAmount(),
                "MOBILE",
                transactionMemo);

        BalanceTxData tx;
        try {
            tx = depositBalanceClient.withdraw(callIdemKey, request);
        } catch (DepositInboundFailureException e) {
            recordCall(piId, "BALANCE_WITHDRAW", "SENDER", "deposit", "POST",
                    "/api/transactions/withdraw", e.getDepositResponseCode(), "FAIL");
            throw e;
        }
        // ★deposit 응답 박제: BalanceTxData(transactionId Long PK 포함) → response_body JSONB
        // B-5 PATCH /transactions/{transactionId}/cancel 재시작 시 tx.transactionId 복원용
        String callId = recordCall(piId, "BALANCE_WITHDRAW", "SENDER", "deposit", "POST",
                "/api/transactions/withdraw", "SUCCESS", (Object) tx);
        return new WithdrawStepResult(tx, callId);
    }

    // ── Step 3b: 입금 (B-4, 트랜잭션 밖, 자행 수신) ──────────────────────────
    // DEP-0000 외 응답 코드 → DepositInboundFailureException (보상 필요 신호, P-002)
    private BalanceTxData step3b_deposit(PaymentInstruction pi, PaymentCommand command) {
        String piId = pi.getPaymentInstructionId();
        String callIdemKey = piId + "-BALANCE_DEPOSIT-RECEIVER-1";

        // by-number → accountId 획득
        AccountInquiryData receiverAcc = depositAccountClient.getAccountByNo(command.receiverAccountNo());
        Long receiverAccountId = receiverAcc.accountId();

        String receiverMemo = command.receiverMemo();
        String transactionMemo = (receiverMemo != null && !receiverMemo.isBlank())
                ? piId + "|" + receiverMemo
                : piId;

        DepositRequest request = new DepositRequest(
                receiverAccountId,
                command.transferAmount(),
                "MOBILE",
                transactionMemo,
                command.receiverPassbookSenderDisplay());  // depositorName: 통장 표시 송신자명

        BalanceTxData tx;
        try {
            tx = depositBalanceClient.deposit(callIdemKey, request);
        } catch (DepositInboundFailureException e) {
            recordCall(piId, "BALANCE_DEPOSIT", "RECEIVER", "deposit", "POST",
                    "/api/transactions/deposit", e.getDepositResponseCode(), "FAIL");
            throw e;
        }
        // ★deposit 응답 박제: BalanceTxData → response_body JSONB
        recordCall(piId, "BALANCE_DEPOSIT", "RECEIVER", "deposit", "POST",
                "/api/transactions/deposit", "SUCCESS", (Object) tx);
        return tx;
    }

    // ── Step 3c: 출금취소 (B-5, 트랜잭션 밖, F8 보상 전용) ──────────────────
    // compensation_type=COMPENSATION, compensation_target_call_id=원 B-3 callId
    // PATCH /api/transactions/{transactionId}/cancel — transactionId는 BalanceTxData.transactionId() 직접 사용.
    private void step3c_withdrawCancel(PaymentInstruction pi, PaymentCommand command,
                                        String originalWithdrawCallId, BalanceTxData withdrawTxData) {
        String piId = pi.getPaymentInstructionId();
        String callIdemKey = piId + "-BALANCE_WITHDRAW_CANCEL-SENDER-1";

        Long transactionId = withdrawTxData.transactionId();
        if (transactionId == null) {
            throw new IllegalStateException(
                "B-5 취소 불가: 원 출금 BalanceTxData에서 transactionId 추출 실패(null). piId=" + piId);
        }

        WithdrawCancelData cancelResult = depositBalanceClient.withdrawCancel(callIdemKey, transactionId);
        recordCall(piId, "BALANCE_WITHDRAW_CANCEL", "SENDER", "deposit", "PATCH",
                "/api/transactions/" + transactionId + "/cancel", "200", "SUCCESS",
                originalWithdrawCallId,  // ← compensation_target_call_id = 원 B-3 callId
                (Object) cancelResult);  // ★박제: WithdrawCancelData → response_body JSONB
    }

    // ── F2: KFTC 거절 보상 ────────────────────────────────────────────────────

    /**
     * F2 KFTC 거절 보상. CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + CT REJECTED.
     * 결정 (f) 재진입 가드: FAILED→skip / CLEARING|REVERSING→진행 / 그 외→warn+skip.
     */
    @Override
    public PaymentResult processKftcReject(
            PaymentInstruction freshPi, String clearingNo,
            String rejectCode, String rejectMessage, String rejectedAt) {

        String piId = freshPi.getPaymentInstructionId();
        String status = freshPi.getStatus();

        // 결정 (f) 멱등 가드
        if ("FAILED".equals(status)) {
            log.info("[F2] 이미 FAILED, skip. piId={}", piId);
            return new PaymentResult(piId, freshPi.getTransactionNo(), "FAILED", "KFTC_REJECTED", null);
        }
        if (!"CLEARING".equals(status) && !"REVERSING".equals(status)) {
            log.warn("[F2] 처리불가 상태, skip. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }

        boolean wasClearing = "CLEARING".equals(status);

        // TX-1: CLEARING → REVERSING (CLEARING 진입 시에만)
        if (wasClearing) {
            txService.txMarkReversingFromClearing(freshPi, freshPi.getVersion(), rejectMessage, "KFTC", "E2001");
        }

        // TX-2 낙관락 버전: TX-1 후 DB version+1 / REVERSING 재진입은 현재 version 그대로
        Integer tx2Version = wasClearing ? freshPi.getVersion() + 1 : freshPi.getVersion();

        // B-5: 출금취소 (TX 밖) — REVERSING 재진입 시 이미 수행된 경우 skip
        ExternalCall originalWithdrawCall = txService.selectOriginalWithdrawCall(piId);
        ExternalCall existingCancelCall = txService.selectExistingCancelCall(piId);

        WithdrawCancelData cancelResult = null;
        if (existingCancelCall != null) {
            log.info("[F2] B-5 이미 수행됨, skip 재호출. piId={} existingCallId={}",
                    piId, existingCancelCall.getCallId());
        } else {
            String originalCallId = (originalWithdrawCall != null) ? originalWithdrawCall.getCallId() : null;
            // ★ F-series real 검증 대기: extractTransactionId 기반. transactionId 추출 정합 미검증.
            Long transactionId = extractTransactionId(originalWithdrawCall);
            cancelResult = performWithdrawCancelForReject(piId, freshPi, originalCallId, transactionId);
        }

        // TX-2: 역분개4건 + FAILED + CT REJECTED + Outbox PAYMENT_REVERSED + 멱등키
        List<Ledger> originals = txService.selectOriginalsByPaymentId(piId);
        ReversalContext ctx = new ReversalContext(
                rejectCode, rejectMessage, clearingNo,
                "KFTC_REJECTION", "KFTC_REJECTED", "EXTERNAL_REJECTION",
                "SYSTEM", null, "KFTC");
        return txService.txCompleteNetworkRejectReversal(freshPi, tx2Version, originals, cancelResult, ctx);
    }

    // ── F3: BOK 거절 보상 ────────────────────────────────────────────────────

    /**
     * F3 BOK 거절 보상. CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + BST REJECTED.
     * processKftcReject의 BOK판. 재진입 가드 동일: FAILED→skip / CLEARING|REVERSING→진행 / 그 외→warn+skip.
     */
    @Override
    public PaymentResult processBokReject(
            PaymentInstruction freshPi, String bokReferenceNo,
            String rejectCode, String rejectMessage, String rejectedAt) {

        String piId = freshPi.getPaymentInstructionId();
        String status = freshPi.getStatus();

        // 멱등 가드 (⑥)
        if ("FAILED".equals(status)) {
            log.info("[F3] 이미 FAILED, skip. piId={}", piId);
            return new PaymentResult(piId, freshPi.getTransactionNo(), "FAILED", "BOK_REJECTED", null);
        }
        if (!"CLEARING".equals(status) && !"REVERSING".equals(status)) {
            log.warn("[F3] 처리불가 상태, skip. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }

        boolean wasClearing = "CLEARING".equals(status);

        // TX-1: CLEARING → REVERSING (CLEARING 진입 시에만)
        if (wasClearing) {
            txService.txMarkReversingFromClearing(freshPi, freshPi.getVersion(), rejectMessage, "BOK", rejectCode);
        }

        // TX-2 낙관락 버전: TX-1 후 DB version+1 / REVERSING 재진입은 현재 version 그대로
        Integer tx2Version = wasClearing ? freshPi.getVersion() + 1 : freshPi.getVersion();

        // B-5: 출금취소 (TX 밖) — REVERSING 재진입 시 이미 수행된 경우 skip
        ExternalCall originalWithdrawCall = txService.selectOriginalWithdrawCall(piId);
        ExternalCall existingCancelCall = txService.selectExistingCancelCall(piId);

        WithdrawCancelData cancelResult = null;
        if (existingCancelCall != null) {
            log.info("[F3] B-5 이미 수행됨, skip 재호출. piId={} existingCallId={}",
                    piId, existingCancelCall.getCallId());
        } else {
            String originalCallId = (originalWithdrawCall != null) ? originalWithdrawCall.getCallId() : null;
            // ★ F-series real 검증 대기: extractTransactionId 기반. transactionId 추출 정합 미검증.
            Long transactionId = extractTransactionId(originalWithdrawCall);
            cancelResult = performWithdrawCancelForReject(piId, freshPi, originalCallId, transactionId);
        }

        // TX-2: 역분개4건 + FAILED + BST REJECTED + Outbox PAYMENT_REVERSED + 멱등키
        List<Ledger> originals = txService.selectOriginalsByPaymentId(piId);
        ReversalContext ctx = new ReversalContext(
                rejectCode, rejectMessage, bokReferenceNo,
                "BOK_REJECTION", "BOK_REJECTED", "EXTERNAL_REJECTION",
                "SYSTEM", null, "BOK");
        return txService.txCompleteNetworkRejectReversal(freshPi, tx2Version, originals, cancelResult, ctx);
    }

    // ── F4: KFTC 송신실패 자동보상 ──────────────────────────────────────────────

    /**
     * F4 KFTC 송신실패 보상. OutboxPublisher가 KFTC_REQUEST_SENT send 실패 시 호출.
     * F2형 보상 재사용: CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + CT REJECTED.
     * reversal_reason=PUBLISH_FAILURE / PI.failure_category=SYSTEM_ERROR.
     */
    @Override
    public PaymentResult processPublishFailure(String piId, String lastError) {
        PaymentInstruction freshPi = txService.selectById(piId);
        if (freshPi == null) {
            log.error("[F4] PI 조회 실패, 보상 불가. piId={}", piId);
            return null;
        }

        String status = freshPi.getStatus();

        // 이중보상 가드
        if ("FAILED".equals(status) || "CANCELED".equals(status)) {
            log.info("[F4] 이중보상 가드: 이미 처리됨. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if ("REVERSING".equals(status)) {
            log.info("[F4] 이중보상 가드: 보상 진행중. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if (!"CLEARING".equals(status)) {
            log.warn("[F4] 예상치 못한 상태, skip. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }

        String rejectMsg = "KFTC 송신 실패: " + (lastError != null ? lastError : "");
        if (rejectMsg.length() > 200) {
            rejectMsg = rejectMsg.substring(0, 197) + "...";
        }

        // TX-1: CLEARING→CLEARING(KFTC_REQUEST_FAILED) + CLEARING→REVERSING(REVERSAL_STARTED)
        txService.txMarkReversingFromClearing(freshPi, freshPi.getVersion(), rejectMsg, "SYSTEM", "PUBLISH_FAILURE", "KFTC_REQUEST_FAILED");
        Integer tx2Version = freshPi.getVersion() + 1;

        // B-5: 출금취소 (TX 밖)
        ExternalCall originalWithdrawCall = txService.selectOriginalWithdrawCall(piId);
        ExternalCall existingCancelCall   = txService.selectExistingCancelCall(piId);

        WithdrawCancelData cancelResult = null;
        if (existingCancelCall != null) {
            log.info("[F4] B-5 이미 수행됨, skip 재호출. piId={}", piId);
        } else {
            String originalCallId = (originalWithdrawCall != null) ? originalWithdrawCall.getCallId() : null;
            // ★ F-series real 검증 대기: extractTransactionId 기반. transactionId 추출 정합 미검증.
            Long transactionId    = extractTransactionId(originalWithdrawCall);
            cancelResult = performWithdrawCancelForReject(piId, freshPi, originalCallId, transactionId);
        }

        // TX-2: 역분개4건(PUBLISH_FAILURE) + FAILED/SYSTEM_ERROR + CT REJECTED + Outbox PAYMENT_REVERSED + 멱등키
        List<Ledger> originals = txService.selectOriginalsByPaymentId(piId);
        ReversalContext ctx = new ReversalContext(
                "PUBLISH_FAILURE", rejectMsg, null,
                "PUBLISH_FAILURE", "SYSTEM_ERROR", "PUBLISH_FAILURE",
                "SYSTEM", null, "KFTC");
        return txService.txCompleteNetworkRejectReversal(freshPi, tx2Version, originals, cancelResult, ctx);
    }

    // ── F4 BOK: 송신실패 자동보상 ────────────────────────────────────────────────

    /**
     * F4 BOK 송신실패 보상. OutboxPublisher가 BOK_REQUEST_SENT send 실패 시 호출.
     * F3형 보상 재사용: CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + BST REJECTED.
     * reversal_reason=PUBLISH_FAILURE / PI.failure_category=SYSTEM_ERROR.
     */
    @Override
    public PaymentResult processBokPublishFailure(String piId, String lastError) {
        PaymentInstruction freshPi = txService.selectById(piId);
        if (freshPi == null) {
            log.error("[BOK F4] PI 조회 실패, 보상 불가. piId={}", piId);
            return null;
        }

        String status = freshPi.getStatus();

        // 이중보상 가드 (KFTC F4와 동일)
        if ("FAILED".equals(status) || "CANCELED".equals(status)) {
            log.info("[BOK F4] 이중보상 가드: 이미 처리됨. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if ("REVERSING".equals(status)) {
            log.info("[BOK F4] 이중보상 가드: 보상 진행중. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if (!"CLEARING".equals(status)) {
            log.warn("[BOK F4] 예상치 못한 상태, skip. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }

        String rejectMsg = "BOK 송신 실패: " + (lastError != null ? lastError : "");
        if (rejectMsg.length() > 200) {
            rejectMsg = rejectMsg.substring(0, 197) + "...";
        }

        // TX-1: CLEARING→CLEARING(BOK_REQUEST_FAILED) + CLEARING→REVERSING(REVERSAL_STARTED)
        txService.txMarkReversingFromClearing(freshPi, freshPi.getVersion(), rejectMsg, "SYSTEM", "PUBLISH_FAILURE", "BOK_REQUEST_FAILED");
        Integer tx2Version = freshPi.getVersion() + 1;

        // B-5: 출금취소 (TX 밖)
        ExternalCall originalWithdrawCall = txService.selectOriginalWithdrawCall(piId);
        ExternalCall existingCancelCall   = txService.selectExistingCancelCall(piId);

        WithdrawCancelData cancelResult = null;
        if (existingCancelCall != null) {
            log.info("[BOK F4] B-5 이미 수행됨, skip 재호출. piId={}", piId);
        } else {
            String originalCallId = (originalWithdrawCall != null) ? originalWithdrawCall.getCallId() : null;
            // ★ F-series real 검증 대기: extractTransactionId 기반. transactionId 추출 정합 미검증.
            Long transactionId    = extractTransactionId(originalWithdrawCall);
            cancelResult = performWithdrawCancelForReject(piId, freshPi, originalCallId, transactionId);
        }

        // TX-2: 역분개4건(PUBLISH_FAILURE) + FAILED/SYSTEM_ERROR + BST REJECTED + Outbox PAYMENT_REVERSED + 멱등키
        List<Ledger> originals = txService.selectOriginalsByPaymentId(piId);
        ReversalContext ctx = new ReversalContext(
                "PUBLISH_FAILURE", rejectMsg, null,
                "PUBLISH_FAILURE", "SYSTEM_ERROR", "PUBLISH_FAILURE",
                "SYSTEM", null, "BOK");
        return txService.txCompleteNetworkRejectReversal(freshPi, tx2Version, originals, cancelResult, ctx);
    }

    // ── F7: KFTC 정산실패 자동보상 ──────────────────────────────────────────────

    /**
     * F7 KFTC 정산실패 보상. SETTLEMENT_NOTIFY responseCode != "0000" 수신 시 호출.
     * F4형 보상 재사용: CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + CT REJECTED.
     * reversal_reason=SETTLEMENT_FAILURE / PI.failure_category=SYSTEM_ERROR.
     */
    @Override
    public PaymentResult processSettlementFailure(String clearingNo, String responseCode, String rejectMessage) {
        KftcClearingTransaction ct = txService.selectByClearingNo(clearingNo);
        if (ct == null) {
            log.warn("[F7] CT 조회 실패, 보상 불가. clearingNo={}", clearingNo);
            return null;
        }
        String piId = ct.getOurPaymentInstructionId();
        PaymentInstruction freshPi = txService.selectById(piId);
        if (freshPi == null) {
            log.error("[F7] PI 조회 실패, 보상 불가. piId={}", piId);
            return null;
        }

        String status = freshPi.getStatus();

        // 이중보상 가드
        if ("FAILED".equals(status) || "CANCELED".equals(status)) {
            log.info("[F7] 이중보상 가드: 이미 처리됨. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if ("REVERSING".equals(status)) {
            log.info("[F7] 이중보상 가드: 보상 진행중. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        // 정상완결 후 뒤늦은 실패통보 — 정책 시트6 케이스3 "범위 외/운영자" (P-014)
        if ("COMPLETED".equals(status)) {
            log.warn("[F7] PI 이미 COMPLETED(범위 외), 보상 skip. piId={}", piId);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if (!"CLEARING".equals(status)) {
            log.warn("[F7] 예상치 못한 상태, skip. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }

        String rejectMsg = "KFTC 정산실패: " + (rejectMessage != null && !rejectMessage.isEmpty() ? rejectMessage : responseCode);
        if (rejectMsg.length() > 200) {
            rejectMsg = rejectMsg.substring(0, 197) + "...";
        }

        // TX-1: CLEARING→CLEARING(KFTC_SETTLEMENT_FAILED) + CLEARING→REVERSING(REVERSAL_STARTED)
        txService.txMarkReversingFromClearing(freshPi, freshPi.getVersion(), rejectMsg, "SYSTEM", "SETTLEMENT_FAILURE", "KFTC_SETTLEMENT_FAILED");
        Integer tx2Version = freshPi.getVersion() + 1;

        // B-5: 출금취소 (TX 밖)
        ExternalCall originalWithdrawCall = txService.selectOriginalWithdrawCall(piId);
        ExternalCall existingCancelCall   = txService.selectExistingCancelCall(piId);

        WithdrawCancelData cancelResult = null;
        if (existingCancelCall != null) {
            log.info("[F7] B-5 이미 수행됨, skip 재호출. piId={}", piId);
        } else {
            String originalCallId = (originalWithdrawCall != null) ? originalWithdrawCall.getCallId() : null;
            // ★ F-series real 검증 대기: extractTransactionId 기반. transactionId 추출 정합 미검증.
            Long transactionId    = extractTransactionId(originalWithdrawCall);
            cancelResult = performWithdrawCancelForReject(piId, freshPi, originalCallId, transactionId);
        }

        // TX-2: 역분개4건(SETTLEMENT_FAILURE) + FAILED/SYSTEM_ERROR + CT REJECTED + Outbox PAYMENT_REVERSED + 멱등키
        List<Ledger> originals = txService.selectOriginalsByPaymentId(piId);
        ReversalContext ctx = new ReversalContext(
                "SETTLEMENT_FAILURE", rejectMsg, clearingNo,
                "SETTLEMENT_FAILURE", "SYSTEM_ERROR", "SETTLEMENT_FAILURE",
                "SYSTEM", null, "KFTC");
        return txService.txCompleteNetworkRejectReversal(freshPi, tx2Version, originals, cancelResult, ctx);
    }

    // ── F7 BOK: 정산실패 자동보상 ────────────────────────────────────────────────

    /**
     * F7 BOK 정산실패 보상. SETTLEMENT_COMPLETED responseCode != "0000" 수신 시 호출.
     * F3형 보상 재사용: CLEARING→REVERSING→FAILED + 역분개4건 + B-5 출금취소 + BST REJECTED.
     * reversal_reason=SETTLEMENT_FAILURE / PI.failure_category=SYSTEM_ERROR.
     */
    @Override
    public PaymentResult processBokSettlementFailure(String bokReferenceNo, String responseCode, String rejectMessage) {
        BokSettlementTransaction bst = txService.selectByBokReferenceNo(bokReferenceNo);
        if (bst == null) {
            log.warn("[BOK-F7] BST 조회 실패, 보상 불가. bokReferenceNo={}", bokReferenceNo);
            return null;
        }
        String piId = bst.getOurPaymentInstructionId();
        PaymentInstruction freshPi = txService.selectById(piId);
        if (freshPi == null) {
            log.error("[BOK-F7] PI 조회 실패, 보상 불가. piId={}", piId);
            return null;
        }

        String status = freshPi.getStatus();

        if ("FAILED".equals(status) || "CANCELED".equals(status)) {
            log.info("[BOK-F7] 이중보상 가드: 이미 처리됨. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if ("REVERSING".equals(status)) {
            log.info("[BOK-F7] 이중보상 가드: 보상 진행중. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if ("COMPLETED".equals(status)) {
            log.warn("[BOK-F7] PI 이미 COMPLETED(범위 외), 보상 skip. piId={}", piId);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }
        if (!"CLEARING".equals(status)) {
            log.warn("[BOK-F7] 예상치 못한 상태, skip. piId={} status={}", piId, status);
            return new PaymentResult(piId, freshPi.getTransactionNo(), status, null, null);
        }

        String rejectMsg = "BOK 정산실패: " + (rejectMessage != null && !rejectMessage.isEmpty() ? rejectMessage : responseCode);
        if (rejectMsg.length() > 200) {
            rejectMsg = rejectMsg.substring(0, 197) + "...";
        }

        // TX-1: CLEARING→CLEARING(BOK_SETTLEMENT_FAILED) + CLEARING→REVERSING(REVERSAL_STARTED)
        txService.txMarkReversingFromClearing(freshPi, freshPi.getVersion(), rejectMsg, "SYSTEM", "SETTLEMENT_FAILURE", "BOK_SETTLEMENT_FAILED");
        Integer tx2Version = freshPi.getVersion() + 1;

        // B-5: 출금취소 (TX 밖)
        ExternalCall originalWithdrawCall = txService.selectOriginalWithdrawCall(piId);
        ExternalCall existingCancelCall   = txService.selectExistingCancelCall(piId);

        WithdrawCancelData cancelResult = null;
        if (existingCancelCall != null) {
            log.info("[BOK-F7] B-5 이미 수행됨, skip 재호출. piId={}", piId);
        } else {
            String originalCallId = (originalWithdrawCall != null) ? originalWithdrawCall.getCallId() : null;
            // ★ F-series real 검증 대기: extractTransactionId 기반. transactionId 추출 정합 미검증.
            Long transactionId    = extractTransactionId(originalWithdrawCall);
            cancelResult = performWithdrawCancelForReject(piId, freshPi, originalCallId, transactionId);
        }

        // TX-2: 역분개4건(SETTLEMENT_FAILURE) + FAILED/SYSTEM_ERROR + BST REJECTED + Outbox PAYMENT_REVERSED + 멱등키
        List<Ledger> originals = txService.selectOriginalsByPaymentId(piId);
        ReversalContext ctx = new ReversalContext(
                "SETTLEMENT_FAILURE", rejectMsg, bokReferenceNo,
                "SETTLEMENT_FAILURE", "SYSTEM_ERROR", "SETTLEMENT_FAILURE",
                "SYSTEM", null, "BOK");
        return txService.txCompleteNetworkRejectReversal(freshPi, tx2Version, originals, cancelResult, ctx);
    }

    // ── F6-Ⅱ-2: 운영자 강제취소 ──────────────────────────────────────────────────

    /**
     * 사용자 예약취소. SCHEDULED→CANCELED + SCHEDULED_CANCELED 이력. 외부 API 없음.
     * 권한(403) 체크 → 상태(409) 체크 순서로 남의 PI 상태 노출 방지.
     */
    @Override
    public PaymentResult cancelScheduledPayment(String piId, String requesterUserId, String reason) {
        PaymentInstruction freshPi = txService.selectById(piId);
        if (freshPi == null) {
            throw new PaymentNotFoundException(piId);
        }

        if (!requesterUserId.equals(freshPi.getSenderUserId())) {
            throw new PaymentUnauthorizedException(piId);
        }

        if (!"SCHEDULED".equals(freshPi.getStatus())) {
            throw new PaymentCancelConflictException(freshPi.getStatus());
        }

        txService.cancelScheduled(piId, freshPi.getVersion(), reason);

        return new PaymentResult(piId, freshPi.getTransactionNo(), "CANCELED", null, null);
    }

    /**
     * 운영자 강제취소. CLEARING 상태만 허용. CLEARING→REVERSING→FAILED + 역분개4건 + B-5 + CT REJECTED.
     * reversal_reason=OPERATOR / failure_category=SYSTEM_ERROR / triggered_by=OPERATOR / operator_id 박제.
     */
    @Override
    public PaymentResult processOperatorCancel(String piId, String operatorId, String reason) {
        PaymentInstruction freshPi = txService.selectById(piId);
        if (freshPi == null) {
            throw new PaymentNotFoundException(piId);
        }

        String status = freshPi.getStatus();
        if (!"CLEARING".equals(status)) {
            throw new PaymentCancelConflictException(status);
        }

        // TX-1: CLEARING→CLEARING(OPERATOR_CANCEL_DECIDED, OPERATOR, operatorId)
        //       + CLEARING→REVERSING(REVERSAL_STARTED, OPERATOR, operatorId)
        txService.txMarkReversingFromClearing(
                freshPi, freshPi.getVersion(),
                reason, "OPERATOR", "OPERATOR", "OPERATOR_CANCEL_DECIDED", operatorId);
        Integer tx2Version = freshPi.getVersion() + 1;

        // B-5: 출금취소 (TX 밖, reason=OPERATOR_CANCEL)
        ExternalCall originalWithdrawCall = txService.selectOriginalWithdrawCall(piId);
        ExternalCall existingCancelCall   = txService.selectExistingCancelCall(piId);

        WithdrawCancelData cancelResult = null;
        if (existingCancelCall != null) {
            log.info("[F6] B-5 이미 수행됨, skip 재호출. piId={}", piId);
        } else {
            String originalCallId = (originalWithdrawCall != null) ? originalWithdrawCall.getCallId() : null;
            // ★ F-series real 검증 대기: extractTransactionId 기반. transactionId 추출 정합 미검증.
            Long transactionId    = extractTransactionId(originalWithdrawCall);
            cancelResult = performWithdrawCancelOperator(piId, freshPi, originalCallId, transactionId);
        }

        // TX-2: 역분개4건(OPERATOR) + FAILED/SYSTEM_ERROR + CT REJECTED + Outbox PAYMENT_REVERSED + 멱등키
        List<Ledger> originals = txService.selectOriginalsByPaymentId(piId);
        ReversalContext ctx = new ReversalContext(
                "OPERATOR", reason, null,
                "OPERATOR", "SYSTEM_ERROR", "OPERATOR",
                "OPERATOR", operatorId, "KFTC");
        return txService.txCompleteNetworkRejectReversal(freshPi, tx2Version, originals, cancelResult, ctx);
    }

    /**
     * B-5 출금취소 호출 (운영자 취소 전용, reason=OPERATOR_CANCEL).
     * performWithdrawCancelForReject와 동일 패턴. PATCH /api/transactions/{transactionId}/cancel.
     * ★ F-series real 검증 대기: transactionId는 extractTransactionId 기반 — 추출 정합 미검증.
     */
    private WithdrawCancelData performWithdrawCancelOperator(
            String piId, PaymentInstruction pi, String originalCallId, Long transactionId) {
        String callIdemKey = piId + "-BALANCE_WITHDRAW_CANCEL-SENDER-1";

        if (transactionId == null) {
            throw new IllegalStateException(
                "B-5 취소 불가: 원 출금 external_call에서 transactionId 추출 실패. piId=" + piId);
        }

        WithdrawCancelData cancelResult = depositBalanceClient.withdrawCancel(callIdemKey, transactionId);
        recordCall(piId, "BALANCE_WITHDRAW_CANCEL", "SENDER", "deposit", "PATCH",
                "/api/transactions/" + transactionId + "/cancel", "200", "SUCCESS",
                originalCallId, (Object) cancelResult);  // ★박제: WithdrawCancelData
        return cancelResult;
    }

    /**
     * B-3 응답 박제(JSONB)에서 deposit transactionNumber 추출.
     * BalanceTxData는 @JsonProperty("transactionNumber") 매핑 → 직렬화 키도 "transactionNumber".
     * 응답 박제 전(혹은 빈객체)이면 "" 반환.
     */
    private String extractDepositTxNo(ExternalCall call) {
        if (call == null) return "";
        try {
            JsonNode body = objectMapper.readTree(call.getResponseBody());
            return body.path("transactionNumber").asText("");
        } catch (Exception e) {
            log.warn("[F2] transactionNumber 파싱 실패, 빈값 사용. callId={}", call.getCallId());
            return "";
        }
    }

    /**
     * B-3 응답 박제(JSONB)에서 deposit transactionId(Long PK) 추출. B-5 PATCH 취소용.
     * 응답 박제 전(혹은 빈객체)이면 null 반환.
     */
    private Long extractTransactionId(ExternalCall call) {
        if (call == null) return null;
        try {
            JsonNode body = objectMapper.readTree(call.getResponseBody());
            JsonNode node = body.path("transactionId");
            return node.isNumber() ? node.asLong() : null;
        } catch (Exception e) {
            log.warn("[B-5] transactionId 파싱 실패, null 사용. callId={}", call.getCallId());
            return null;
        }
    }

    /**
     * B-5 출금취소 호출 + external_call 박제 (F2/F3/F4/F7 보상 전용).
     * PATCH /api/transactions/{transactionId}/cancel. compensation_target_call_id=원 B-3 callId.
     * ★ F-series real 검증 대기: transactionId는 extractTransactionId 기반 — 추출 정합 미검증.
     */
    private WithdrawCancelData performWithdrawCancelForReject(
            String piId, PaymentInstruction pi, String originalCallId, Long transactionId) {
        String callIdemKey = piId + "-BALANCE_WITHDRAW_CANCEL-SENDER-1";

        if (transactionId == null) {
            throw new IllegalStateException(
                "B-5 취소 불가: 원 출금 external_call에서 transactionId 추출 실패. piId=" + piId);
        }

        WithdrawCancelData cancelResult = depositBalanceClient.withdrawCancel(callIdemKey, transactionId);
        recordCall(piId, "BALANCE_WITHDRAW_CANCEL", "SENDER", "deposit", "PATCH",
                "/api/transactions/" + transactionId + "/cancel", "200", "SUCCESS",
                originalCallId, (Object) cancelResult);  // ★박제: WithdrawCancelData
        return cancelResult;
    }

    // ── recordCall 오버로드 ───────────────────────────────────────────────────

    /**
     * 외부호출 박제 (ORIGINAL). callId 반환 — B-3 callId를 B-5 target에 넣기 위해 필요.
     * call_idempotency_key 형식: {piId}-{callType}-{accountRole}-1
     * result: SUCCESS(기본) 또는 FAIL(B-1 잔액부족 등 비즈니스 거절).
     */
    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode) {
        return recordCall(piId, callType, accountRole, targetSystem, httpMethod, endpointUrl,
                responseCode, "SUCCESS", 1, null);
    }

    /** 응답 객체 박제 오버로드: SUCCESS 경로에서 deposit 응답을 response_body JSON으로 보존. */
    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode, Object responseBody) {
        return recordCall(piId, callType, accountRole, targetSystem, httpMethod, endpointUrl,
                responseCode, "SUCCESS", 1, responseBody);
    }

    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode, String result) {
        return recordCall(piId, callType, accountRole, targetSystem, httpMethod, endpointUrl,
                responseCode, result, 1, null);
    }

    /** attempt 오버로드: 등록=1, 실행=2. call_idempotency_key = {piId}-{callType}-{accountRole}-{attempt} */
    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode, String result, int attempt) {
        return recordCall(piId, callType, accountRole, targetSystem, httpMethod, endpointUrl,
                responseCode, result, attempt, null);
    }

    /**
     * 최하위 ORIGINAL 박제 — responseBody 객체를 ObjectMapper로 JSON 직렬화해 response_body 컬럼에 보존.
     * CLAUDE.md §5(외부 응답 박제) 준수. 직렬화 실패 시 "{}" 로 fallback (JSONB cast 보장).
     * @param responseBody deposit/KFTC 응답 DTO. null 이면 "{}".
     */
    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode, String result, int attempt,
                              Object responseBody) {
        LocalDateTime now = LocalDateTime.now();
        String callId = idGenerator.nextCallId();
        String callIdemKey = piId + "-" + callType + "-" + accountRole + "-" + attempt;
        String responseBodyJson = serializeResponseBody(responseBody, callType);
        ExternalCall ec = ExternalCall.of(
                callId, callIdemKey, piId,
                callType, targetSystem, endpointUrl, httpMethod,
                UUID.randomUUID().toString(), "{}", "{}", "",
                500, now);
        ec.recordResponse(200, "{}", responseBodyJson, responseCode, result, result, 50, now);
        txService.recordExternalCall(ec);
        return callId;
    }

    /** 보상 외부호출 박제. compensation_type=COMPENSATION + compensationTargetCallId 필수 (V4 CHECK). */
    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode, String result, String compensationTargetCallId) {
        return recordCall(piId, callType, accountRole, targetSystem, httpMethod, endpointUrl,
                responseCode, result, compensationTargetCallId, null);
    }

    /** 보상 박제 + responseBody 객체 직렬화 (B-5 SUCCESS 경로). */
    private String recordCall(String piId, String callType, String accountRole,
                              String targetSystem, String httpMethod, String endpointUrl,
                              String responseCode, String result, String compensationTargetCallId,
                              Object responseBody) {
        LocalDateTime now = LocalDateTime.now();
        String callId = idGenerator.nextCallId();
        String callIdemKey = piId + "-" + callType + "-" + accountRole + "-1";
        String responseBodyJson = serializeResponseBody(responseBody, callType);
        ExternalCall ec = ExternalCall.ofCompensation(
                callId, callIdemKey, piId, compensationTargetCallId,
                callType, targetSystem, endpointUrl, httpMethod,
                UUID.randomUUID().toString(), "{}", "{}", "",
                500, now);
        ec.recordResponse(200, "{}", responseBodyJson, responseCode, result, result, 50, now);
        txService.recordExternalCall(ec);
        return callId;
    }

    /**
     * 응답 DTO → JSON 직렬화. ★String.format 금지(사용자 memo 등 사용자 입력이 들어갈 수 있어
     * JSON injection 위험) → ObjectMapper 사용. 직렬화 실패 시 JSONB cast 안전을 위해 "{}".
     */
    private String serializeResponseBody(Object responseBody, String callType) {
        if (responseBody == null) return "{}";
        try {
            return objectMapper.writeValueAsString(responseBody);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[recordCall] responseBody 직렬화 실패, '{}' 사용. callType={} error={}",
                    "{}", callType, e.getMessage());
            return "{}";
        }
    }
}
