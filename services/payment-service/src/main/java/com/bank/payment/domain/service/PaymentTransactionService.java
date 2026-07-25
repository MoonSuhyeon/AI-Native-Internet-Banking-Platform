package com.bank.payment.domain.service;

import com.bank.payment.common.IdGenerator;
import com.bank.payment.common.LedgerFailureSimulator;
import com.bank.payment.common.exception.LedgerBalanceMismatchException;
import com.bank.payment.domain.ExternalCall;
import com.bank.payment.domain.IdempotencyKey;
import com.bank.payment.domain.Ledger;
import com.bank.payment.domain.OutboxMessage;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.StatusHistory;
import com.bank.payment.domain.BokSettlementTransaction;
import com.bank.payment.domain.KftcClearingTransaction;
import com.bank.payment.domain.mapper.BokSettlementTransactionMapper;
import com.bank.payment.domain.mapper.ExternalCallMapper;
import com.bank.payment.domain.mapper.IdempotencyKeyMapper;
import com.bank.payment.domain.mapper.KftcClearingTransactionMapper;
import com.bank.payment.domain.mapper.LedgerMapper;
import com.bank.payment.domain.mapper.OutboxMessageMapper;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.mapper.StatusHistoryMapper;
import com.bank.payment.outbound.feign.dto.BalanceTxData;
import com.bank.payment.outbound.feign.dto.WithdrawCancelData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * P-028 짧은 DB 트랜잭션 격리. Orchestrator(흐름)가 외부호출 사이사이 이 메서드들을 호출.
 * 외부호출(Feign)은 절대 이 클래스에 없음 — 트랜잭션 밖(Orchestrator) 책임.
 */
@Service
public class PaymentTransactionService {

    private static final DateTimeFormatter CLEARING_AT_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Value("${payment.timeout.kftc-clearing-minutes:5}")
    private int kftcClearingTimeoutMinutes;

    @Value("${payment.timeout.bok-clearing-seconds:30}")
    private int bokClearingTimeoutSeconds;

    private final PaymentInstructionMapper paymentInstructionMapper;
    private final IdempotencyKeyMapper idempotencyKeyMapper;
    private final StatusHistoryMapper statusHistoryMapper;
    private final ExternalCallMapper externalCallMapper;
    private final IdGenerator idGenerator;
    private final LedgerMapper ledgerMapper;
    private final OutboxMessageMapper outboxMessageMapper;
    private final KftcClearingTransactionMapper clearingTransactionMapper;
    private final BokSettlementTransactionMapper settlementTransactionMapper;
    private final ObjectMapper objectMapper;
    private final LedgerFailureSimulator ledgerFailureSimulator;

    public PaymentTransactionService(
            PaymentInstructionMapper paymentInstructionMapper,
            IdempotencyKeyMapper idempotencyKeyMapper,
            StatusHistoryMapper statusHistoryMapper,
            ExternalCallMapper externalCallMapper,
            IdGenerator idGenerator,
            LedgerMapper ledgerMapper,
            OutboxMessageMapper outboxMessageMapper,
            KftcClearingTransactionMapper clearingTransactionMapper,
            BokSettlementTransactionMapper settlementTransactionMapper,
            ObjectMapper objectMapper,
            LedgerFailureSimulator ledgerFailureSimulator) {
        this.paymentInstructionMapper = paymentInstructionMapper;
        this.idempotencyKeyMapper = idempotencyKeyMapper;
        this.statusHistoryMapper = statusHistoryMapper;
        this.externalCallMapper = externalCallMapper;
        this.idGenerator = idGenerator;
        this.ledgerMapper = ledgerMapper;
        this.outboxMessageMapper = outboxMessageMapper;
        this.clearingTransactionMapper = clearingTransactionMapper;
        this.settlementTransactionMapper = settlementTransactionMapper;
        this.objectMapper = objectMapper;
        this.ledgerFailureSimulator = ledgerFailureSimulator;
    }

    /** TX-1: 멱등키(PROCESSING) + 결제지시(DRAFT) + 상태이력(seq1 INSTRUCTION_CREATED) INSERT */
    @Transactional
    public PaymentInstruction txStep1(PaymentCommand command, boolean isIntraBank, String routingNetworkType) {
        LocalDateTime now = LocalDateTime.now();

        IdempotencyKey idempotencyKey = IdempotencyKey.of(
                command.idempotencyKey(),
                command.userId(),
                "",                     // requestHash: S1 미사용. 운영 시 요청 본문 해시
                now, now,
                now.plusMinutes(5));    // expiresAt: 합의서 일반 호출 5분 TTL
        idempotencyKeyMapper.insert(idempotencyKey);

        String piId = idGenerator.nextPaymentInstructionId();
        PaymentInstruction pi = PaymentInstruction.builder()
                .paymentInstructionId(piId)
                .idempotencyKey(command.idempotencyKey())
                .senderUserId(command.userId())
                .senderAccountId(command.senderAccountId())
                .authTokenId(command.authTokenId())
                .transactionNo("TXN-" + piId)               // 정식 채번 메서드 없음 — piId 파생 (TODO)
                .senderAccountNoSnap(command.senderAccountId()) // S1: 계좌ID=계좌번호 단순화
                .receiverBankCode(command.receiverBankCode())
                .receiverAccountNo(command.receiverAccountNo())
                .isIntraBank(isIntraBank)
                .routingNetworkType(routingNetworkType)
                .transferAmount(command.transferAmount())
                .feeAmount(isIntraBank ? BigDecimal.ZERO : new BigDecimal("500"))  // 자행 0, 타행 500
                .receiverPassbookSenderDisplay(command.receiverPassbookSenderDisplay())
                .receiverMemo(command.receiverMemo())
                .senderMemo(command.senderMemo())
                .status("DRAFT")
                .channel(command.channel())
                .requestedAt(now)
                .businessDate(now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                .version(0)
                .triggerSource("USER")
                .isScheduled(false)
                .firstRegistrantId(command.userId())
                .lastModifierId(command.userId())
                .build();
        paymentInstructionMapper.insert(pi);

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        StatusHistory history = StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                null, "DRAFT", "INSTRUCTION_CREATED", "USER", now);
        statusHistoryMapper.insert(history);

        return pi;
    }

    /**
     * AUTHORIZED 전이: step2 외부검증 통과 후 호출. DRAFT→AUTHORIZED + 상태이력(seq2 AUTH_PASSED).
     * AUTHORIZED = "검증 완료, 진행 확정" — 검증 전 호출 금지.
     */
    @Transactional
    public void authorize(String paymentInstructionId, Integer version) {
        LocalDateTime now = LocalDateTime.now();

        int updated = paymentInstructionMapper.updateStatus(
                paymentInstructionId, "AUTHORIZED", null, null, version);
        if (updated == 0) {
            throw new OptimisticLockingFailureException(
                    "결제지시 상태 갱신 충돌(AUTHORIZED): " + paymentInstructionId);
        }

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(paymentInstructionId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        StatusHistory history = StatusHistory.of(
                idGenerator.nextHistoryId(), paymentInstructionId, seq,
                "DRAFT", "AUTHORIZED", "AUTH_PASSED", "USER", now);
        statusHistoryMapper.insert(history);
    }

    /** 외부호출 박제: 호출당 독립 짧은 트랜잭션. txStep1/txStep4와 분리 (P-028 외부=트랜잭션밖 유지). */
    @Transactional
    public void recordExternalCall(ExternalCall externalCall) {
        externalCallMapper.insert(externalCall);
    }

    /**
     * PI 수신예금주명 박제: step2 A-2 수신조회 직후 단독 커밋.
     * version 컬럼 갱신 없음 — authorize 낙관락(WHERE version=0) 보호.
     */
    @Transactional
    public void updateReceiverHolderSnap(String paymentInstructionId,
                                         String receiverHolderNameSnap,
                                         LocalDateTime holderInquiryAt) {
        paymentInstructionMapper.updateReceiverHolderSnap(
                paymentInstructionId, receiverHolderNameSnap, holderInquiryAt);
    }

    /**
     * TX-2 (txStep4): 자행이체 확정 한 트랜잭션 (원자성).
     * PROCESSING 전이(seq3) → 분개 2건(차변=대변) → COMPLETED(seq4) → Outbox → 멱등키완료.
     * @param pi authorize까지 끝난 결제지시 (version은 AUTHORIZED 시점)
     * @param withdrawResult B-3 출금 응답 (balanceBefore/After 박제용)
     * @param depositResult B-4 입금 응답
     * @param command 원 명령 (금액/계좌 등)
     * @return PaymentResult (COMPLETED)
     */
    @Transactional
    public PaymentResult txStep4(PaymentInstruction pi, BalanceTxData withdrawResult,
                                 BalanceTxData depositResult, PaymentCommand command,
                                 String senderHolderName, String receiverHolderName) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();
        String businessDate = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        BigDecimal amount = command.transferAmount();

        // 1. AUTHORIZED→PROCESSING (낙관락, pi.getVersion()+1 = authorize 후 버전)
        markProcessing(piId, pi.getVersion() + 1, now);

        // 2. journal_no 1번 채번 (분개 그룹, 결제 1건당 1개)
        String journalNo = idGenerator.nextJournalNo();

        // 3. 출금 분개 (송신계좌 DEBIT TRANSFER_OUT)
        Ledger out = Ledger.intraTransferOut(
                idGenerator.nextLedgerId(), piId, command.senderAccountId(),
                journalNo, command.senderAccountId(), senderHolderName,
                amount,
                withdrawResult.balanceBefore(),
                withdrawResult.balanceAfter(),
                "KRW", businessDate, businessDate, businessDate,
                now, "자행이체 출금");

        // mock 프로파일: F5 시나리오 분개 INSERT 실패 시뮬레이션 (NoOp: 운영환경에서는 아무 일도 없음)
        ledgerFailureSimulator.checkAndThrow(command.receiverAccountNo());

        ledgerMapper.insert(out);

        // 4. 입금 분개 (수신계좌 CREDIT TRANSFER_IN, 같은 journalNo)
        Ledger in = Ledger.intraTransferIn(
                idGenerator.nextLedgerId(), piId, command.receiverAccountNo(),
                journalNo, command.receiverAccountNo(), receiverHolderName,
                amount,
                depositResult.balanceBefore(),
                depositResult.balanceAfter(),
                "KRW", businessDate, businessDate, businessDate,
                now, "자행이체 입금");
        ledgerMapper.insert(in);

        // 5. 차변=대변 검증 (P-014)
        BigDecimal debitSum = out.getAmount();
        BigDecimal creditSum = in.getAmount();
        if (debitSum.compareTo(creditSum) != 0) {
            throw new LedgerBalanceMismatchException(
                    "차변≠대변: DEBIT " + debitSum + " ≠ CREDIT " + creditSum + " (PI " + piId + ")");
        }

        // 6. PROCESSING→COMPLETED (낙관락, pi.getVersion()+2)
        int updated2 = paymentInstructionMapper.updateStatus(
                piId, "COMPLETED", now, null, pi.getVersion() + 2);
        if (updated2 == 0) {
            throw new OptimisticLockingFailureException(
                    "결제지시 상태 갱신 충돌(COMPLETED): " + piId);
        }
        Integer maxSeq4 = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq4 == null ? 0 : maxSeq4) + 1,
                "PROCESSING", "COMPLETED", "PAYMENT_COMPLETED", "SYSTEM", now));

        // 7. Outbox (PAYMENT_COMPLETED, PENDING)
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "status", "COMPLETED",
                    "amount", amount,
                    "completedAt", now.toString()));
        } catch (JsonProcessingException e) {
            // Map.of 원시값이라 실제 안 터짐. 터지면 시스템 버그 → 롤백
            throw new IllegalStateException("Outbox payload 직렬화 실패: " + piId, e);
        }
        OutboxMessage outbox = OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_COMPLETED",
                "v1", payload, now);
        outboxMessageMapper.insert(outbox);

        // 8. 멱등키 완료 (스냅샷 = Outbox payload 재활용)
        idempotencyKeyMapper.updateStatus(command.idempotencyKey(), "COMPLETED", payload);

        return new PaymentResult(piId, pi.getTransactionNo(), "COMPLETED", null, now);
    }

    /**
     * TX-2 (txStep4Scheduled): 예약이체 자행 확정 한 트랜잭션.
     * claimScheduled 가 이미 SCHEDULED→PROCESSING 커밋을 완료했으므로 markProcessing 을 호출하지 않는다.
     * 대신 PROCESSING_STARTED 이력만 INSERT (상태전이 UPDATE 없음, version 불변).
     * COMPLETED 전이는 WHERE version = pi.getVersion()+1 (claim 이 +1 을 먹었으므로 +2 아님).
     * 이력 시퀀스: SCHEDULED_TRIGGERED(claim) → PROCESSING_STARTED → PAYMENT_COMPLETED.
     * @param pi             selectDueScheduled 로 읽은 PI (version=V, claim 후 DB version=V+1)
     * @param withdrawResult B-3 출금 응답 (잔액 박제용)
     * @param depositResult  B-4 입금 응답 (잔액 박제용)
     * @param command        rebuildCommand 로 조립한 PaymentCommand
     * @param senderHolderName   step2a 재검증 조회된 송신 예금주명
     * @param receiverHolderName step2a 재검증 조회된 수신 예금주명
     * @return PaymentResult (COMPLETED)
     */
    @Transactional
    public PaymentResult txStep4Scheduled(PaymentInstruction pi, BalanceTxData withdrawResult,
                                          BalanceTxData depositResult, PaymentCommand command,
                                          String senderHolderName, String receiverHolderName) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();
        String businessDate = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        BigDecimal amount = command.transferAmount();

        // 1. PROCESSING_STARTED 이력만 INSERT (markProcessing 없음 — claim 이 이미 PROCESSING 커밋)
        Integer maxSeq1 = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq1 == null ? 0 : maxSeq1) + 1,
                "PROCESSING", "PROCESSING", "PROCESSING_STARTED", "SYSTEM", now));

        // 2. journal_no 채번 (분개 그룹, 결제 1건당 1개)
        String journalNo = idGenerator.nextJournalNo();

        // 3. 출금 분개 (송신계좌 DEBIT TRANSFER_OUT)
        Ledger out = Ledger.intraTransferOut(
                idGenerator.nextLedgerId(), piId, command.senderAccountId(),
                journalNo, command.senderAccountId(), senderHolderName,
                amount,
                withdrawResult.balanceBefore(),
                withdrawResult.balanceAfter(),
                "KRW", businessDate, businessDate, businessDate,
                now, "자행이체 출금");

        // mock 프로파일: F5 시나리오 분개 INSERT 실패 시뮬레이션 (3-C F5 재현용)
        ledgerFailureSimulator.checkAndThrow(command.receiverAccountNo());

        ledgerMapper.insert(out);

        // 4. 입금 분개 (수신계좌 CREDIT TRANSFER_IN)
        Ledger in = Ledger.intraTransferIn(
                idGenerator.nextLedgerId(), piId, command.receiverAccountNo(),
                journalNo, command.receiverAccountNo(), receiverHolderName,
                amount,
                depositResult.balanceBefore(),
                depositResult.balanceAfter(),
                "KRW", businessDate, businessDate, businessDate,
                now, "자행이체 입금");
        ledgerMapper.insert(in);

        // 5. 차변=대변 검증 (P-014)
        if (out.getAmount().compareTo(in.getAmount()) != 0) {
            throw new LedgerBalanceMismatchException(
                    "차변≠대변: DEBIT " + out.getAmount() + " ≠ CREDIT " + in.getAmount() + " (PI " + piId + ")");
        }

        // 6. PROCESSING→COMPLETED (낙관락: pi.getVersion()+1 — claim 이 +1, markProcessing 없으므로 +2 아님)
        int updated = paymentInstructionMapper.updateStatus(
                piId, "COMPLETED", now, null, pi.getVersion() + 1);
        if (updated == 0) {
            throw new OptimisticLockingFailureException(
                    "결제지시 상태 갱신 충돌(COMPLETED/Scheduled): " + piId);
        }
        Integer maxSeq4 = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq4 == null ? 0 : maxSeq4) + 1,
                "PROCESSING", "COMPLETED", "PAYMENT_COMPLETED", "SYSTEM", now));

        // 7. Outbox (PAYMENT_COMPLETED, PENDING)
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "status", "COMPLETED",
                    "amount", amount,
                    "completedAt", now.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패(Scheduled): " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_COMPLETED",
                "v1", payload, now));

        // 8. 멱등키 완료 (등록 요청의 멱등키 — 재시도 시 COMPLETED 반환)
        idempotencyKeyMapper.updateStatus(command.idempotencyKey(), "COMPLETED", payload);

        return new PaymentResult(piId, pi.getTransactionNo(), "COMPLETED", null, now);
    }

    /**
     * TX-2 (txStep4InterBank): 타행이체 확정 한 트랜잭션 (원자성).
     * AUTHORIZED→PROCESSING(seq3) → PROCESSING→CLEARING(seq4) + 분개4건(2묶음) + Outbox(KFTC_REQUEST_SENT)
     * + kftc_clearing_transaction REQUESTED INSERT + 멱등키완료.
     * ★역분개 아님(is_reversal=false). Kafka 발행은 Outbox 워커가 kftc.network.request로 비동기 처리.
     * @param pi authorize까지 끝난 결제지시 (version=0, DB version=1)
     * @param withdrawResult B-3 출금 응답 (TRANSFER_OUT 분개 잔액박제용)
     * @param command 원 명령 (금액/계좌/수신은행 등)
     * @param senderHolderName 송신 예금주명 (step2 A-2 조회값, "미조회")
     * @param receiverHolderName 수신 예금주명 (step2 A-2 조회값, "미조회") — DB 스냅샷용; Outbox payload는 command.receiverHolderName() 사용
     * @param senderBankCode 자행 3자리 은행코드 (004/088 — Orchestrator에서 계산해 전달)
     * @return PaymentResult (CLEARING, completedAt=null — KFTC 응답 대기)
     */
    @Transactional
    public PaymentResult txStep4InterBank(PaymentInstruction pi, BalanceTxData withdrawResult,
                                           PaymentCommand command, String senderHolderName,
                                           String receiverHolderName, String senderBankCode) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();
        String businessDate = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        BigDecimal transferAmount = command.transferAmount();
        BigDecimal feeAmount = pi.getFeeAmount();  // txStep1에서 isIntraBank=false → 500

        // 1. AUTHORIZED→PROCESSING (낙관락: pi.getVersion()+1=1 → DB v2)
        markProcessing(piId, pi.getVersion() + 1, now);

        // 2. journal_no 2묶음 채번 (JN-01=이체본금, JN-02=수수료)
        String jn1 = idGenerator.nextJournalNo();
        String jn2 = idGenerator.nextJournalNo();

        // 3. JN-01 차변: 송신계좌 DEBIT TRANSFER_OUT (B-3 잔액 박제)
        Ledger out = Ledger.interTransferOut(
                idGenerator.nextLedgerId(), piId, command.senderAccountId(),
                jn1, command.senderAccountId(), senderHolderName,
                transferAmount,
                withdrawResult.balanceBefore(),
                withdrawResult.balanceAfter(),
                "KRW", businessDate, businessDate, businessDate,
                now, "타행이체 출금");
        ledgerMapper.insert(out);

        // 4. JN-01 대변: KB-CLR-088 CREDIT CLEARING_PENDING (내부계정 잔액=0,0)
        Ledger clearing = Ledger.clearingPending(
                idGenerator.nextLedgerId(), piId,
                jn1, transferAmount,
                "KRW", businessDate, businessDate, businessDate,
                now, "타행이체 청산대기");
        ledgerMapper.insert(clearing);

        // 5. JN-01 차변=대변 검증 (P-014 묶음별)
        if (out.getAmount().compareTo(clearing.getAmount()) != 0) {
            throw new LedgerBalanceMismatchException(
                    "JN-01 차변≠대변: DEBIT " + out.getAmount()
                            + " ≠ CREDIT " + clearing.getAmount() + " (PI " + piId + ")");
        }

        // 6. JN-02 차변: 송신계좌 DEBIT FEE (별도 deposit 호출 없음 → balance=0,0)
        Ledger fee = Ledger.fee(
                idGenerator.nextLedgerId(), piId, command.senderAccountId(),
                jn2, command.senderAccountId(), senderHolderName,
                feeAmount,
                "KRW", businessDate, businessDate, businessDate,
                now, "타행이체 수수료");
        ledgerMapper.insert(fee);

        // 7. JN-02 대변: KB-FEE-001 CREDIT FEE_INCOME (내부계정 잔액=0,0)
        Ledger feeInc = Ledger.feeIncome(
                idGenerator.nextLedgerId(), piId,
                jn2, feeAmount,
                "KRW", businessDate, businessDate, businessDate,
                now, "타행이체 수수료수익");
        ledgerMapper.insert(feeInc);

        // 8. JN-02 차변=대변 검증 (P-014 묶음별)
        if (fee.getAmount().compareTo(feeInc.getAmount()) != 0) {
            throw new LedgerBalanceMismatchException(
                    "JN-02 차변≠대변: DEBIT " + fee.getAmount()
                            + " ≠ CREDIT " + feeInc.getAmount() + " (PI " + piId + ")");
        }

        // 9. PROCESSING→CLEARING (낙관락: pi.getVersion()+2=2 → DB v3)
        int updated2 = paymentInstructionMapper.updateStatus(
                piId, "CLEARING", null, null, pi.getVersion() + 2);
        if (updated2 == 0) {
            throw new OptimisticLockingFailureException(
                    "결제지시 상태 갱신 충돌(CLEARING): " + piId);
        }
        Integer maxSeq2 = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq2 == null ? 0 : maxSeq2) + 1,
                "PROCESSING", "CLEARING", "KFTC_REQUEST_SENT", "SYSTEM", now));

        // 9-1. F6 폴링워커용 타임아웃 세팅 (CLEARING 전이 직후, 같은 TX 내. version 불간섭)
        paymentInstructionMapper.updateNextTimeoutAt(piId, now.plusMinutes(kftcClearingTimeoutMinutes));

        // 10. Outbox (KFTC_REQUEST_SENT, PENDING) — 워커가 kftc.network.request로 비동기 발행
        // clearingNo를 먼저 생성해 payload에 포함 (Mock/KFTC 응답 측에서 clearingNo로 참조)
        String clearingTxId = idGenerator.nextClearingTransactionId();
        String clearingNo   = idGenerator.nextClearingNo();
        String clearingRequestedAt = now.format(CLEARING_AT_FMT);  // yyyyMMddHHmmss

        String payload;
        try {
            Map<String, Object> senderNode = new LinkedHashMap<>();
            senderNode.put("bankCode",    senderBankCode);
            senderNode.put("accountNo",   command.senderAccountId());
            senderNode.put("realName",    senderHolderName);
            senderNode.put("displayName", command.receiverPassbookSenderDisplay());

            Map<String, Object> receiverNode = new LinkedHashMap<>();
            receiverNode.put("bankCode",           command.receiverBankCode());
            receiverNode.put("accountNo",          command.receiverAccountNo());
            receiverNode.put("expectedHolderName", command.receiverHolderName());

            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("messageType",          "PAYMENT_REQUEST");
            payloadMap.put("messageVersion",       "v1");
            payloadMap.put("correlationId",        clearingNo);
            payloadMap.put("sequenceNo",           1);
            payloadMap.put("clearingNo",           clearingNo);
            payloadMap.put("sender",               senderNode);
            payloadMap.put("receiver",             receiverNode);
            payloadMap.put("amount",               transferAmount);
            payloadMap.put("currency",             "KRW");
            payloadMap.put("sentAt",               clearingRequestedAt);
            payloadMap.put("receiverPassbookMemo", command.receiverMemo());

            payload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패: " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "KFTC_REQUEST_SENT",
                "v1", payload, now));

        // 11. kftc_clearing_transaction REQUESTED INSERT (PI와 같은 TX, 1:1 박제)
        // (clearingTxId / clearingNo / clearingRequestedAt 는 위 step 10에서 생성)

        KftcClearingTransaction clearingTx = KftcClearingTransaction.requestedOut(
                clearingTxId,
                piId,
                clearingNo,
                senderBankCode,
                command.senderAccountId(),           // 계좌ID=계좌번호(S1 단순화)
                senderHolderName,
                command.receiverBankCode(),
                command.receiverAccountNo(),
                receiverHolderName,                  // 정식 연동 전까지 "미조회". 요청값은 미검증값이므로 DB 스냅샷에 박지 않음
                transferAmount,
                clearingRequestedAt);
        clearingTransactionMapper.insert(clearingTx);

        // 12. 멱등키 완료 (202 수락 완료 — KFTC 응답은 비동기, 멱등 재시도 시 CLEARING 반환)
        idempotencyKeyMapper.updateStatus(command.idempotencyKey(), "COMPLETED", payload);

        return new PaymentResult(piId, pi.getTransactionNo(), "CLEARING", null, null);
    }

    /**
     * TX-2 (txStep4InterBok): BOK 거액이체 확정 한 트랜잭션 (원자성).
     * AUTHORIZED→PROCESSING(seq3) → PROCESSING→CLEARING(seq4) + 분개4건(2묶음) + Outbox(BOK_REQUEST_SENT)
     * + bok_settlement_transaction REQUESTED INSERT + 멱등키완료.
     * txStep4InterBank의 BOK판 — 대칭. version흐름 동일(v0→1→2→3).
     * @param pi authorize까지 끝난 결제지시 (version=0, DB version=1)
     * @param withdrawResult B-3 출금 응답 (TRANSFER_OUT 분개 잔액박제용)
     * @param command 원 명령 (금액/계좌/수신은행 등)
     * @param senderHolderName 송신 예금주명 (step2 A-2 조회값)
     * @param receiverHolderName 수신 예금주명 (step2 A-2 조회값, "미조회") — DB 스냅샷용; command.receiverHolderName()은 null 가능
     * @param senderBankCode 자행 3자리 은행코드 (004/088)
     * @return PaymentResult (CLEARING, completedAt=null — BOK 응답 대기)
     */
    @Transactional
    public PaymentResult txStep4InterBok(PaymentInstruction pi, BalanceTxData withdrawResult,
                                          PaymentCommand command, String senderHolderName,
                                          String receiverHolderName, String senderBankCode) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();
        String businessDate = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        BigDecimal transferAmount = command.transferAmount();
        BigDecimal feeAmount = pi.getFeeAmount();

        // 1. AUTHORIZED→PROCESSING (낙관락: pi.getVersion()+1=1 → DB v2)
        markProcessing(piId, pi.getVersion() + 1, now);

        // 2. journal_no 2묶음 채번 (JN-01=이체본금, JN-02=수수료)
        String jn1 = idGenerator.nextJournalNo();
        String jn2 = idGenerator.nextJournalNo();

        // 3. JN-01 차변: 송신계좌 DEBIT TRANSFER_OUT (B-3 잔액 박제)
        Ledger out = Ledger.interTransferOut(
                idGenerator.nextLedgerId(), piId, command.senderAccountId(),
                jn1, command.senderAccountId(), senderHolderName,
                transferAmount,
                withdrawResult.balanceBefore(),
                withdrawResult.balanceAfter(),
                "KRW", businessDate, businessDate, businessDate,
                now, "BOK이체 출금");
        ledgerMapper.insert(out);

        // 4. JN-01 대변: KB-CLR-BOK CREDIT CLEARING_PENDING (내부계정 잔액=0,0)
        Ledger clearingBok = Ledger.clearingPendingBok(
                idGenerator.nextLedgerId(), piId,
                jn1, transferAmount,
                "KRW", businessDate, businessDate, businessDate,
                now, "BOK이체 청산대기");
        ledgerMapper.insert(clearingBok);

        // 5. JN-01 차대변 검증 (P-014)
        if (out.getAmount().compareTo(clearingBok.getAmount()) != 0) {
            throw new LedgerBalanceMismatchException(
                    "JN-01 차변≠대변: DEBIT " + out.getAmount()
                            + " ≠ CREDIT " + clearingBok.getAmount() + " (PI " + piId + ")");
        }

        // 6. JN-02 차변: 송신계좌 DEBIT FEE (balance=0,0)
        Ledger fee = Ledger.fee(
                idGenerator.nextLedgerId(), piId, command.senderAccountId(),
                jn2, command.senderAccountId(), senderHolderName,
                feeAmount,
                "KRW", businessDate, businessDate, businessDate,
                now, "BOK이체 수수료");
        ledgerMapper.insert(fee);

        // 7. JN-02 대변: KB-FEE-001 CREDIT FEE_INCOME (내부계정 잔액=0,0)
        Ledger feeInc = Ledger.feeIncome(
                idGenerator.nextLedgerId(), piId,
                jn2, feeAmount,
                "KRW", businessDate, businessDate, businessDate,
                now, "BOK이체 수수료수익");
        ledgerMapper.insert(feeInc);

        // 8. JN-02 차대변 검증 (P-014)
        if (fee.getAmount().compareTo(feeInc.getAmount()) != 0) {
            throw new LedgerBalanceMismatchException(
                    "JN-02 차변≠대변: DEBIT " + fee.getAmount()
                            + " ≠ CREDIT " + feeInc.getAmount() + " (PI " + piId + ")");
        }

        // 9. PROCESSING→CLEARING (낙관락: pi.getVersion()+2=2 → DB v3)
        int updated2 = paymentInstructionMapper.updateStatus(
                piId, "CLEARING", null, null, pi.getVersion() + 2);
        if (updated2 == 0) {
            throw new OptimisticLockingFailureException(
                    "결제지시 상태 갱신 충돌(CLEARING/BOK): " + piId);
        }
        Integer maxSeq2 = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq2 == null ? 0 : maxSeq2) + 1,
                "PROCESSING", "CLEARING", "BOK_REQUEST_SENT", "SYSTEM", now));

        // next_timeout_at: 송신+30초(BOK RTGS 정책). F6 폴링워커가 읽을 값. version 불간섭.
        paymentInstructionMapper.updateNextTimeoutAt(piId, now.plusSeconds(bokClearingTimeoutSeconds));

        // 10. BST 채번 — Outbox payload에 bokReferenceNo 포함하므로 INSERT 전 선채번
        String settlementTxId        = idGenerator.nextSettlementTransactionId();
        String bokReferenceNo        = idGenerator.nextBokReferenceNo();
        String settlementRequestedAt = now.format(CLEARING_AT_FMT);

        // 11. Outbox BOK_REQUEST_SENT — SETTLEMENT_REQUEST payload (⑤ 계약, Record Key=bokReferenceNo)
        // Map.ofEntries는 null value 불허 → receiverHolderName null 시 NPE. LinkedHashMap으로 교체.
        String payload;
        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("messageType",          "SETTLEMENT_REQUEST");
            payloadMap.put("messageVersion",       "v1");
            payloadMap.put("bokReferenceNo",       bokReferenceNo);
            payloadMap.put("paymentInstructionNo", piId);
            payloadMap.put("senderBankCode",       senderBankCode);
            payloadMap.put("senderAccountNo",      command.senderAccountId());
            payloadMap.put("receiverBankCode",     command.receiverBankCode());
            payloadMap.put("receiverAccountNo",    command.receiverAccountNo());
            payloadMap.put("receiverHolderName",   receiverHolderName);
            payloadMap.put("settlementAmount",     transferAmount);
            payloadMap.put("fee",                  feeAmount);
            payloadMap.put("requestedAt",          now.toString());
            payload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패(BOK_REQUEST_SENT): " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "BOK_REQUEST_SENT",
                "v1", payload, now));

        // 12. bok_settlement_transaction REQUESTED INSERT (PI와 같은 TX, 1:1 박제)
        BokSettlementTransaction bst = BokSettlementTransaction.requestedOut(
                settlementTxId,
                piId,
                bokReferenceNo,
                senderBankCode,
                command.senderAccountId(),
                senderHolderName,
                command.receiverBankCode(),
                command.receiverAccountNo(),
                receiverHolderName,
                transferAmount,
                settlementRequestedAt);
        settlementTransactionMapper.insert(bst);

        // 13. 멱등키 완료 (202 수락 완료 — BOK 응답은 비동기, 멱등 재시도 시 CLEARING 반환)
        idempotencyKeyMapper.updateStatus(command.idempotencyKey(), "COMPLETED", payload);

        return new PaymentResult(piId, pi.getTransactionNo(), "CLEARING", null, null);
    }

    /**
     * SCHEDULED→PROCESSING 선점(claim). 예약이체 워커 전용. 독립 @Transactional — 후속 실행 TX와 분리 필수.
     * 반환 true = 선점 성공, false = 다른 인스턴스가 이미 선점(정상 경합, 예외 아님).
     * ★ pi.getVersion() 은 selectDueScheduled 로 읽은 값(claim 전)을 그대로 넘길 것.
     *   이 메서드는 pi 객체의 version 을 변경하지 않는다. DB만 +1.
     *   단계 3 실행 TX는 pi.getVersion()+1 을 WHERE 조건으로 사용한다.
     */
    @Transactional
    public boolean claimScheduled(PaymentInstruction pi) {
        int updated = paymentInstructionMapper.claimScheduledForExecution(
                pi.getPaymentInstructionId(), pi.getVersion());
        if (updated == 0) {
            return false;
        }
        Integer maxSeq = statusHistoryMapper.selectMaxSequence(pi.getPaymentInstructionId());
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), pi.getPaymentInstructionId(),
                (maxSeq == null ? 0 : maxSeq) + 1,
                "SCHEDULED", "PROCESSING", "SCHEDULED_TRIGGERED", "SCHEDULER",
                LocalDateTime.now()));
        return true;
    }

    /**
     * AUTHORIZED→SCHEDULED 전이. is_scheduled=true + scheduled_execution_at 세팅.
     * 낙관락: WHERE status='AUTHORIZED' AND version=#{version}. affected rows≠1 이면 throw.
     * 상태이력: SCHEDULED_REGISTERED (AUTHORIZED→SCHEDULED, triggered_by='USER').
     * @param piId 결제지시번호
     * @param version authorize 직후 최신 버전 (pi.getVersion()+1)
     * @param scheduledExecutionAt 예약실행시각 (미래 시각, 컨트롤러에서 검증 완료)
     */
    @Transactional
    public void markScheduled(String piId, Integer version, LocalDateTime scheduledExecutionAt) {
        LocalDateTime now = LocalDateTime.now();

        int updated = paymentInstructionMapper.updateScheduled(piId, scheduledExecutionAt, version);
        if (updated == 0) {
            throw new OptimisticLockingFailureException(
                    "결제지시 SCHEDULED 전이 충돌(낙관락/상태 불일치): " + piId);
        }

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        StatusHistory history = StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "AUTHORIZED", "SCHEDULED", "SCHEDULED_REGISTERED", "USER", now);
        statusHistoryMapper.insert(history);
    }

    /**
     * 사용자 예약취소. SCHEDULED→CANCELED + SCHEDULED_CANCELED 이력.
     * claim 과 대칭: WHERE status='SCHEDULED' AND version=? 조건부 UPDATE.
     * affected=0 = 그새 claim 되거나 이미 취소됨 → PaymentCancelConflictException(409).
     */
    @Transactional
    public void cancelScheduled(String piId, Integer version, String reason) {
        LocalDateTime now = LocalDateTime.now();

        int updated = paymentInstructionMapper.cancelScheduledForUser(piId, version);
        if (updated == 0) {
            throw new com.bank.payment.common.exception.PaymentCancelConflictException(
                    "SCHEDULED 상태 전이 충돌(claim 경합 또는 이미 취소): " + piId);
        }

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        StatusHistory history = StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "SCHEDULED", "CANCELED", "SCHEDULED_CANCELED", "USER",
                null, reason,
                now);
        statusHistoryMapper.insert(history);
    }

    /** AUTHORIZED→PROCESSING 전이: updateStatus(낙관락) + PROCESSING_STARTED 이력. txStep4 3종 공용. */
    private void markProcessing(String piId, int version, LocalDateTime now) {
        int updated = paymentInstructionMapper.updateStatus(piId, "PROCESSING", null, null, version);
        if (updated == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(PROCESSING): " + piId);
        }
        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq == null ? 0 : maxSeq) + 1,
                "AUTHORIZED", "PROCESSING", "PROCESSING_STARTED", "SYSTEM", now));
    }

    /** PI 재조회 — 이중보상 가드용. 보상 진입 직전 현재 상태 확인. */
    public PaymentInstruction selectById(String paymentInstructionId) {
        return paymentInstructionMapper.selectById(paymentInstructionId);
    }

    /** CT 조회 — clearingNo 매칭용. consumer에서 2단계 매칭(clearingNo→CT→PI) 시 사용. */
    public KftcClearingTransaction selectByClearingNo(String clearingNo) {
        return clearingTransactionMapper.selectByClearingNo(clearingNo);
    }

    /** BST 조회 — bokReferenceNo 매칭용. consumer에서 2단계 매칭(bokReferenceNo→BST→PI) 시 사용. */
    public BokSettlementTransaction selectByBokReferenceNo(String bokReferenceNo) {
        return settlementTransactionMapper.selectByBokReferenceNo(bokReferenceNo);
    }

    /** F2용: 원분개 4건 조회 (is_reversal=FALSE 필터, 역분개 재조회 방지). */
    public List<Ledger> selectOriginalsByPaymentId(String piId) {
        return ledgerMapper.selectOriginalsByPaymentId(piId);
    }

    /** F2용: 원 BALANCE_WITHDRAW 외부호출 조회 — callId(보상대상참조) + responseBody(depositTxNo 파싱) 용. */
    public ExternalCall selectOriginalWithdrawCall(String piId) {
        return externalCallMapper.selectByPiIdAndCallType(piId, "BALANCE_WITHDRAW");
    }

    /** F2 재진입 가드용: BALANCE_WITHDRAW_CANCEL이 이미 기록됐는지 확인. */
    public ExternalCall selectExistingCancelCall(String piId) {
        return externalCallMapper.selectByPiIdAndCallType(piId, "BALANCE_WITHDRAW_CANCEL");
    }

    /**
     * TX-SETTLEMENT (S2-A 통지 수신): KFTC 차액결제 이연 — PI CLEARING 유지, CT SETTLED.
     * 1. CT clearing_status REQUESTED→SETTLED + settled_at/settlement_date
     *    (마감배치가 CT.clearing_status=SETTLED + PI.status=CLEARING 조합으로 처리 대상 식별)
     * 2. next_timeout_at = NULL — SETTLED 후 마감 대기 중 TimeoutDetectionWorker 오판 방지
     *    (selectTimedOut: next_timeout_at IS NOT NULL AND status='CLEARING' → null이면 미해당)
     * 3. 상태이력: seq5 KFTC_SETTLED (CLEARING→CLEARING, KFTC)
     * 4. Outbox KFTC_SETTLED (회계계 P-001 CLEARING_PENDING unwind + 마감배치 트리거용)
     * ★ PI 상태 CLEARING 그대로 유지 — COMPLETED 전이 + 분개 unwind는 마감배치(Step 6)가 수행.
     * ★ version 갱신 없음 — PI 상태 변경 없으므로 낙관락 불필요.
     * ★ BOK txSettlementBok(RTGS 즉시 COMPLETED)와 달리 CLEARING 이연. 건드리지 말 것.
     */
    @Transactional
    public void txSettlement(PaymentInstruction pi, String clearingNo,
                             String settledAt, String settlementDate) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();

        // 1. CT REQUESTED→SETTLED (settled_at/settlement_date 기록 — 마감배치 조회 기준)
        clearingTransactionMapper.updateSettled(piId, settledAt, settlementDate);

        // 2. next_timeout_at = NULL (CLEARING 유지 상태로 마감 대기 — 타임아웃 폴러 오판 방지)
        //    updateStatus를 거치지 않으므로 자동 NULL화 없음 → 명시적 클리어 필수
        paymentInstructionMapper.updateNextTimeoutAt(piId, null);

        // 3. 상태이력 seq5: KFTC_SETTLED (CLEARING→CLEARING, 상태 유지 — 청산완료 이벤트 기록)
        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "CLEARING", "CLEARING", "KFTC_SETTLED", "KFTC", now));

        // 4. Outbox KFTC_SETTLED (회계계 P-001 CLEARING_PENDING unwind 트리거 + 마감배치 SETTLEMENT_NOTIFY 이벤트)
        String kftcSettledPayload;
        try {
            kftcSettledPayload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "clearingNo", clearingNo,
                    "transferAmount", pi.getTransferAmount(),
                    "settledAt", settledAt,
                    "settlementDate", settlementDate != null ? settlementDate : "",
                    "receiverBankCode", pi.getReceiverBankCode()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패(KFTC_SETTLED): " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "KFTC_SETTLED", "v1", kftcSettledPayload, now));
    }

    /**
     * TX-SETTLEMENT-BOK (S3 완결): CLEARING→COMPLETED 한 트랜잭션.
     * 1. PI CLEARING→COMPLETED (version 3→4, completedAt=now)
     * 2. BST settlement_status REQUESTED→SETTLED + settled_at/settlement_date
     * 2.5. BOK RTGS 한은당좌 unwind 분개 쌍 INSERT (CLEARING_PENDING_UNWIND + INTERBANK_SETTLEMENT)
     *      — 원분개 CLEARING_PENDING(KB-CLR-BOK)에서 accountId/snap/holder/amount/currency/일자 계승
     *      — journal_no 균형: clearingPendingUnwind.amount == bokDda.amount == clearingOrig.amount
     * 3. 상태이력: BOK_CONFIRMED(CLEARING→CLEARING, BOK) + PAYMENT_COMPLETED(CLEARING→COMPLETED, SYSTEM)
     * 4. Outbox: BOK_CONFIRMED(먼저) + PAYMENT_COMPLETED(나중)
     */
    @Transactional
    public void txSettlementBok(PaymentInstruction pi, String bokReferenceNo,
                                String settledAt, String settlementDate) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();

        // 1. PI CLEARING→COMPLETED (낙관락: pi.getVersion()=3 → WHERE v=3, SET v=4)
        int updated = paymentInstructionMapper.updateStatus(piId, "COMPLETED", now, null, pi.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("SETTLEMENT_BOK 상태갱신 충돌: " + piId);
        }

        // 2. BST REQUESTED→SETTLED (counterparty_payment_id=NULL — P-015 OUT 미적용)
        settlementTransactionMapper.updateSettled(piId, settledAt, settlementDate);

        // 2.5. BOK RTGS 한은당좌 unwind 분개 쌍 — 원분개 CLEARING_PENDING(KB-CLR-BOK) 계승
        List<Ledger> originals = ledgerMapper.selectOriginalsByPaymentId(piId);
        Ledger clearingOrig = originals.stream()
                .filter(l -> "CLEARING_PENDING".equals(l.getJournalType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("BOK 청산대기 원분개 없음: " + piId));
        String jnSettlement = idGenerator.nextJournalNo();
        ledgerMapper.insert(Ledger.clearingPendingUnwind(
                idGenerator.nextLedgerId(), piId,
                clearingOrig.getAccountId(), clearingOrig.getAccountNoSnap(), clearingOrig.getHolderNameSnap(),
                jnSettlement, clearingOrig.getAmount(),
                clearingOrig.getCurrency(),
                clearingOrig.getTransactionDate(), clearingOrig.getPostingDate(), clearingOrig.getValueDate(),
                now, "BOK RTGS 한은당좌 정산 - 청산대기 해소"));
        ledgerMapper.insert(Ledger.bokDda(
                idGenerator.nextLedgerId(), piId,
                jnSettlement, clearingOrig.getAmount(),
                clearingOrig.getCurrency(),
                clearingOrig.getTransactionDate(), clearingOrig.getPostingDate(), clearingOrig.getValueDate(),
                now, "BOK RTGS 한은당좌 정산"));

        // 3. 상태이력: BOK_CONFIRMED (CLEARING→CLEARING, 상태 유지 — 한은 정산완료 이벤트 기록)
        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "CLEARING", "CLEARING", "BOK_CONFIRMED", "BOK", now));

        // 4. 상태이력: PAYMENT_COMPLETED (CLEARING→COMPLETED)
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq + 1,
                "CLEARING", "COMPLETED", "PAYMENT_COMPLETED", "SYSTEM", now));

        // 5. Outbox BOK_CONFIRMED — 먼저 INSERT (회계계 P-001 CLEARING_PENDING unwind 트리거, KFTC_SETTLED 대칭)
        String bokConfirmedPayload;
        try {
            bokConfirmedPayload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "bokReferenceNo", bokReferenceNo,
                    "transferAmount", pi.getTransferAmount(),
                    "settledAt", settledAt,
                    "settlementDate", settlementDate != null ? settlementDate : "",
                    "receiverBankCode", pi.getReceiverBankCode()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패(BOK_CONFIRMED): " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "BOK_CONFIRMED", "v1", bokConfirmedPayload, now));

        // 6. Outbox PAYMENT_COMPLETED — 나중 INSERT (message_id 단조증가 → 워커 ORDER BY message_id로 순서 보장)
        String paymentCompletedPayload;
        try {
            paymentCompletedPayload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "status", "COMPLETED",
                    "transferAmount", pi.getTransferAmount(),
                    "completedAt", now.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패(PAYMENT_COMPLETED/BOK): " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_COMPLETED", "v1", paymentCompletedPayload, now));
    }

    /**
     * TX-1 (F2/F3 보상): CLEARING→REVERSING 전이 + 이력 1건.
     * 이력: REVERSAL_STARTED (triggeredBy/reasonCode 파라미터).
     * ★F8의 txMarkReversing과 별개 메서드 — F8 호출처(processIntraBank) 무변경.
     * @param pi CLEARING 상태 PI (version=3). WHERE version=3, DB version→4
     * @param version WHERE 조건 버전 (freshPi.getVersion()=3)
     * @param rejectMessage 거절메시지 (reason_message 박제용)
     * @param triggeredBy 트리거주체 (F2='KFTC', F3='BOK', F4='SYSTEM')
     * @param reasonCode 사유코드 (F2='E2001', F3=rejectCode, F4='PUBLISH_FAILURE')
     */
    @Transactional
    public void txMarkReversingFromClearing(PaymentInstruction pi, Integer version,
                                            String rejectMessage, String triggeredBy, String reasonCode) {
        txMarkReversingFromClearing(pi, version, rejectMessage, triggeredBy, reasonCode, null, null);
    }

    /**
     * F4 전용 오버로드: 원인이벤트(causeEventType) 1건을 REVERSING 전이 이력 앞에 삽입.
     * F2/F3는 5-파라미터 버전 사용(causeEventType 불필요, 동작 불변).
     * @param causeEventType 원인이벤트 (F4='KFTC_REQUEST_FAILED'). null이면 미삽입.
     */
    @Transactional
    public void txMarkReversingFromClearing(PaymentInstruction pi, Integer version,
                                            String rejectMessage, String triggeredBy, String reasonCode,
                                            String causeEventType) {
        txMarkReversingFromClearing(pi, version, rejectMessage, triggeredBy, reasonCode, causeEventType, null);
    }

    /**
     * F6-Ⅱ: operatorId 포함 실제 구현체.
     * 5/6-파라미터 오버로드는 operatorId=null로 위임. F6-Ⅱ-2 운영자 취소는 operatorId 전달.
     * @param causeEventType 원인이벤트 (F4='KFTC_REQUEST_FAILED', null이면 미삽입)
     * @param operatorId 운영자ID (triggered_by='OPERATOR'일 때 NOT NULL 강제 — DB CHECK)
     */
    @Transactional
    public void txMarkReversingFromClearing(PaymentInstruction pi, Integer version,
                                            String rejectMessage, String triggeredBy, String reasonCode,
                                            String causeEventType, String operatorId) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();

        int updated = paymentInstructionMapper.updateStatus(piId, "REVERSING", null, null, version);
        if (updated == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(REVERSING/F2F3F4): " + piId);
        }

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;

        // 원인 이벤트 (F4: KFTC_REQUEST_FAILED, 운영자: OPERATOR_CANCEL_DECIDED). F2/F3는 null → 미삽입.
        // triggeredBy/operatorId를 그대로 관통 (F4=SYSTEM/null, 운영자=OPERATOR/operatorId).
        if (causeEventType != null) {
            statusHistoryMapper.insert(StatusHistory.of(
                    idGenerator.nextHistoryId(), piId, seq,
                    "CLEARING", "CLEARING", causeEventType, triggeredBy,
                    reasonCode, rejectMessage, operatorId, now));
            seq++;
        }

        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "CLEARING", "REVERSING", "REVERSAL_STARTED", triggeredBy,
                reasonCode, rejectMessage, operatorId, now));
    }

    /**
     * TX-2 (F2/F3/F4/F7/F6-Ⅱ 보상 공통): 역분개4건 + REVERSING→FAILED + CT/BST REJECTED + Outbox PAYMENT_REVERSED + 멱등키.
     * ctx.networkType() = "KFTC"|"BOK" 로 mapper/label 분기. 기능 변경 없는 순수 리팩터.
     */
    @Transactional
    public PaymentResult txCompleteNetworkRejectReversal(
            PaymentInstruction pi,
            Integer version,
            List<Ledger> originals,
            WithdrawCancelData cancelResult,
            ReversalContext ctx) {

        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();
        String businessDate = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);

        String networkLabel = "KFTC".equals(ctx.networkType()) ? "타행이체" : "BOK이체";

        // 원분개 4건 매칭 (journal_type 기준)
        Ledger origOut = originals.stream()
                .filter(l -> "TRANSFER_OUT".equals(l.getJournalType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("원분개 TRANSFER_OUT 없음: " + piId));
        Ledger origClr = originals.stream()
                .filter(l -> "CLEARING_PENDING".equals(l.getJournalType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("원분개 CLEARING_PENDING 없음: " + piId));
        Ledger origFee = originals.stream()
                .filter(l -> "FEE".equals(l.getJournalType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("원분개 FEE 없음: " + piId));
        Ledger origFeeInc = originals.stream()
                .filter(l -> "FEE_INCOME".equals(l.getJournalType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("원분개 FEE_INCOME 없음: " + piId));

        // R01 balance: B-5 응답잔액 박제 (null이면 0,0 fallback — chk_balance_before/after >= 0 만족)
        // WithdrawCancelData.balanceBefore/After는 BigDecimal 직접 반환 — valueOf 래핑 불필요.
        BigDecimal r01BalanceBefore = (cancelResult != null) ? cancelResult.balanceBefore() : BigDecimal.ZERO;
        BigDecimal r01BalanceAfter  = (cancelResult != null) ? cancelResult.balanceAfter()  : BigDecimal.ZERO;

        // R01: 송신계좌 CREDIT REVERSAL_TRANSFER_OUT (jn1)
        Ledger r01 = Ledger.reversalTransferOut(
                idGenerator.nextLedgerId(), piId, origOut.getAccountId(),
                origOut.getLedgerId(), origOut.getJournalNo(),
                origOut.getAccountNoSnap(), origOut.getHolderNameSnap(),
                origOut.getAmount(), r01BalanceBefore, r01BalanceAfter,
                origOut.getCurrency(), businessDate, businessDate, businessDate,
                now, networkLabel + " 출금취소 역분개", ctx.reversalReason());
        ledgerMapper.insert(r01);

        // R03: KB-CLR-xxx DEBIT REVERSAL_CLEARING_PENDING (jn1 — 원분개와 동일 journal_no)
        Ledger r03 = Ledger.reversalClearingPending(
                idGenerator.nextLedgerId(), piId,
                origClr.getLedgerId(), origClr.getJournalNo(),
                origClr.getAccountId(), origClr.getAccountNoSnap(), origClr.getHolderNameSnap(),
                origClr.getAmount(),
                origClr.getCurrency(), businessDate, businessDate, businessDate,
                now, networkLabel + " 청산대기 역분개", ctx.reversalReason());
        ledgerMapper.insert(r03);

        // ★ JN-1역 차대변 검증 (P-014): R03(DEBIT) == R01(CREDIT)
        if (r03.getAmount().compareTo(r01.getAmount()) != 0) {
            throw new LedgerBalanceMismatchException(
                    "JN-1역 차변≠대변: DEBIT " + r03.getAmount()
                            + " ≠ CREDIT " + r01.getAmount() + " (PI " + piId + ")");
        }

        // R02: 송신계좌 CREDIT REVERSAL_FEE (jn2)
        Ledger r02 = Ledger.reversalFee(
                idGenerator.nextLedgerId(), piId, origFee.getAccountId(),
                origFee.getLedgerId(), origFee.getJournalNo(),
                origFee.getAccountNoSnap(), origFee.getHolderNameSnap(),
                origFee.getAmount(),
                origFee.getCurrency(), businessDate, businessDate, businessDate,
                now, networkLabel + " 수수료 역분개", ctx.reversalReason());
        ledgerMapper.insert(r02);

        // R04: KB-FEE-001 DEBIT REVERSAL_FEE_INCOME (jn2 — 원분개와 동일 journal_no)
        Ledger r04 = Ledger.reversalFeeIncome(
                idGenerator.nextLedgerId(), piId,
                origFeeInc.getLedgerId(), origFeeInc.getJournalNo(),
                origFeeInc.getAmount(),
                origFeeInc.getCurrency(), businessDate, businessDate, businessDate,
                now, networkLabel + " 수수료수익 역분개", ctx.reversalReason());
        ledgerMapper.insert(r04);

        // ★ JN-2역 차대변 검증 (P-014): R04(DEBIT) == R02(CREDIT)
        if (r04.getAmount().compareTo(r02.getAmount()) != 0) {
            throw new LedgerBalanceMismatchException(
                    "JN-2역 차변≠대변: DEBIT " + r04.getAmount()
                            + " ≠ CREDIT " + r02.getAmount() + " (PI " + piId + ")");
        }

        // PI REVERSING → FAILED (낙관락, failure_category — V1 CHECK 기존값)
        int updated = paymentInstructionMapper.updateStatus(
                piId, "FAILED", now, ctx.failureCategory(), version);
        if (updated == 0) {
            throw new OptimisticLockingFailureException(
                    "결제지시 상태 갱신 충돌(FAILED/" + ctx.networkType() + "): " + piId);
        }

        // StatusHistory 1건: REVERSING→FAILED, PAYMENT_FAILED
        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "REVERSING", "FAILED", "PAYMENT_FAILED", ctx.triggeredBy(),
                ctx.rejectCode(), ctx.rejectMessage(), ctx.operatorId(), now));

        // CT/BST REQUESTED → REJECTED (networkType 기준 분기)
        if ("KFTC".equals(ctx.networkType())) {
            clearingTransactionMapper.updateRejected(piId, ctx.rejectCode(), ctx.rejectMessage());
        } else {
            settlementTransactionMapper.updateRejected(piId, ctx.rejectCode(), ctx.rejectMessage());
        }

        // Outbox PAYMENT_REVERSED
        // ★ payload.failureCategory (시나리오 시트8 Outbox 스펙)
        //   PI.failure_category (V1 CHECK 기존값)과 레이어별로 다름
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionNo", piId,
                    "originalAmount", pi.getTransferAmount(),
                    "fee", pi.getFeeAmount(),
                    "failureCategory", ctx.outboxFailureCategory(),
                    "failureCode", ctx.rejectCode(),
                    "failureMessage", ctx.rejectMessage(),
                    "reversedLedgers", List.of(
                            r01.getLedgerId(), r02.getLedgerId(),
                            r03.getLedgerId(), r04.getLedgerId()),
                    "reversedAt", now.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Outbox payload 직렬화 실패(PAYMENT_REVERSED/" + ctx.networkType() + "): " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_REVERSED", "v1", payload, now));

        // 멱등키 FAILED (원 요청 재시도 방지)
        idempotencyKeyMapper.updateStatus(pi.getIdempotencyKey(), "FAILED", payload);

        return new PaymentResult(piId, pi.getTransactionNo(), "FAILED", ctx.failureCategory(), now);
    }

    /**
     * TX-A (F8/F5 보상): fromStatus→REVERSING 전이 + 이력 2건.
     * 이력: SYSTEM_FAILURE_DETECTED(원인 감지) + COMPENSATION_STARTED(보상 시작).
     * @param pi      보상 대상 PI
     * @param version WHERE 조건 버전 (updateStatus 낙관락)
     * @param fromStatus 이력 from_status ("AUTHORIZED" = 즉시이체 F5/F8, "PROCESSING" = 예약이체 보상)
     */
    @Transactional
    public void txMarkReversing(PaymentInstruction pi, Integer version, String fromStatus) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();

        int updated = paymentInstructionMapper.updateStatus(
                piId, "REVERSING", null, null, version);
        if (updated == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(REVERSING): " + piId);
        }

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                fromStatus, "REVERSING", "SYSTEM_FAILURE_DETECTED", "SYSTEM", now));
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq + 1,
                fromStatus, "REVERSING", "COMPENSATION_STARTED", "SYSTEM", now));
    }

    /**
     * TX-B (F8 보상): REVERSING→FAILED 확정 + 이력 2건 + Outbox + 멱등키.
     * ★역분개 0건 (다2 확정 — B-4 입금 자체 안 됐으므로 ledger row 없음).
     * 이력: COMPENSATION_COMPLETED + PAYMENT_FAILED. REVERSAL_STARTED/COMPLETED 없음.
     * @param pi txStep1 직후 PI (version=0). WHERE version = pi.getVersion()+2 (txMarkReversing 후 2)
     * @param idempotencyKey 멱등키 (멱등키 테이블 FAILED 처리용)
     * @param version WHERE 조건 버전 (pi.getVersion()+2 = 2, DB version 2→3)
     */
    @Transactional
    public PaymentResult txCompleteReversal(PaymentInstruction pi, String idempotencyKey, Integer version) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();

        // ★역분개 INSERT 없음 (다2: B-4 입금 분개 자체가 없으므로 역분개도 없음, P-026)

        int updated = paymentInstructionMapper.updateStatus(
                piId, "FAILED", now, "SYSTEM_ERROR", version);
        if (updated == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(FAILED/REVERSAL): " + piId);
        }

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "REVERSING", "FAILED", "COMPENSATION_COMPLETED", "SYSTEM", now));
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq + 1,
                "REVERSING", "FAILED", "PAYMENT_FAILED", "SYSTEM", now));

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "status", "FAILED",
                    "failureCategory", "SYSTEM_ERROR",
                    "failedAt", now.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패: " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_FAILED", "v1", payload, now));

        idempotencyKeyMapper.updateStatus(idempotencyKey, "FAILED", payload);

        return new PaymentResult(piId, pi.getTransactionNo(), "FAILED", "SYSTEM_ERROR", now);
    }

    /**
     * TX-FAIL: 비즈니스 검증 실패(PaymentValidationException) 시 DRAFT→FAILED 확정 트랜잭션.
     * CHECK 3개 충족: failure_category SET / completed_at SET / next_retry_at·next_timeout_at=NULL(updateStatus XML).
     * @param pi txStep1 직후 PI (version=0, status=DRAFT)
     * @param failureCategory INSUFFICIENT_BALANCE / HOLDER_MISMATCH 등 실패 원인 enum
     * @param failedEventType 상태이력 검증실패 이벤트 (BALANCE_CHECK_FAILED 등)
     */
    @Transactional
    public PaymentResult txStepFail(PaymentInstruction pi, String failureCategory, String failedEventType, String fromStatus) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();

        // 1. fromStatus→FAILED (낙관락)
        int updated = paymentInstructionMapper.updateStatus(
                piId, "FAILED", now, failureCategory, pi.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(FAILED): " + piId);
        }

        // 2. 상태이력 seq2: 검증실패 이벤트 (fromStatus 유지, 원인 기록)
        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq2 = (maxSeq == null ? 0 : maxSeq) + 1;
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq2,
                fromStatus, fromStatus, failedEventType, "SYSTEM", now));

        // 3. 상태이력 seq3: PAYMENT_FAILED (fromStatus→FAILED 전이 확정)
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq2 + 1,
                fromStatus, "FAILED", "PAYMENT_FAILED", "SYSTEM", now));

        // 4. Outbox (PAYMENT_FAILED, PENDING) — Outbox 워커가 Kafka 발행
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "status", "FAILED",
                    "failureCategory", failureCategory,
                    "failedAt", now.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패: " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_FAILED",
                "v1", payload, now));

        // 5. 멱등키 FAILED (재시도 시 동일 응답 반환)
        idempotencyKeyMapper.updateStatus(pi.getIdempotencyKey(), "FAILED", payload);

        return new PaymentResult(piId, pi.getTransactionNo(), "FAILED", failureCategory, now);
    }

    /**
     * TX-IN: KFTC 수신 수령 — 멱등키 + PI(DRAFT) + 상태이력(INBOUND_RECEIVED) 동일 트랜잭션.
     * clearingNo를 멱등키로 사용. 동일 clearingNo 재수신 시 UNIQUE 충돌 → 예외 → DLQ(의도된 멱등 차단).
     * 검증·입금·CT INSERT·Outbox는 step③(processInbound)에서.
     * @return 채번된 piId
     */
    @Transactional
    public String txInboundReceive(InboundPaymentCommand command) {
        LocalDateTime now = LocalDateTime.now();

        IdempotencyKey idempotencyKey = IdempotencyKey.of(
                command.clearingNo(),
                "COUNTERPARTY_BANK",
                "",
                now, now,
                now.plusMinutes(5));
        idempotencyKeyMapper.insert(idempotencyKey);

        String piId = idGenerator.nextPaymentInstructionId();
        PaymentInstruction pi = PaymentInstruction.builder()
                .paymentInstructionId(piId)
                .idempotencyKey(command.clearingNo())
                .transactionNo("TXN-" + piId)
                .senderAccountNoSnap(command.senderAccountNo())
                .receiverBankCode(command.receiverBankCode())
                .receiverAccountNo(command.receiverAccountNo())
                .receiverHolderNameSnap(command.receiverExpectedHolderName())
                .isIntraBank(false)
                .routingNetworkType("KFTC")
                .transferAmount(command.transferAmount())
                .feeAmount(BigDecimal.ZERO)
                .receiverPassbookSenderDisplay(command.receiverPassbookMemo())
                .status("DRAFT")
                .channel("INBOUND")
                .requestedAt(now)
                .businessDate(now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                .version(0)
                .triggerSource("COUNTERPARTY_BANK")
                .isScheduled(false)
                .holderInquiryAt(now)
                .firstRegistrantId("COUNTERPARTY_BANK")
                .lastModifierId("COUNTERPARTY_BANK")
                .build();
        paymentInstructionMapper.insert(pi);

        StatusHistory history = StatusHistory.of(
                idGenerator.nextHistoryId(), piId, 1,
                null, "DRAFT", "INBOUND_RECEIVED", "COUNTERPARTY_BANK",
                null, "KFTC 수신", now);
        statusHistoryMapper.insert(history);

        return piId;
    }

    /**
     * TX-IN-AUTH: DRAFT→AUTHORIZED + 상태이력(INBOUND_VALIDATION_PASSED).
     * 수신계좌 검증 통과 후 호출. 낙관락: pi.version=0 → WHERE version=0 → DB version=1.
     */
    @Transactional
    public void txInboundAuthorize(PaymentInstruction pi) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();

        int updated = paymentInstructionMapper.updateStatus(piId, "AUTHORIZED", null, null, pi.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(AUTHORIZED): " + piId);
        }

        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq == null ? 0 : maxSeq) + 1,
                "DRAFT", "AUTHORIZED", "INBOUND_VALIDATION_PASSED", "COUNTERPARTY_BANK",
                null, "수신계좌 검증 통과", now));
    }

    /**
     * TX-IN-DEP: AUTHORIZED→PROCESSING(KFTC_ACK_SENT Outbox) → interTransferIn 분개1건 →
     * CT settledIn INSERT → PROCESSING→COMPLETED(KFTC_SETTLEMENT_SENT+PAYMENT_COMPLETED Outbox) → 멱등키완료.
     * @param pi      txInboundAuthorize 후 selectById로 재조회된 결제지시 (version=1)
     * @param depositTx B-4 입금 응답 (잔액 박제용)
     * @param command 원 수신 명령 (clearingNo·senderBankCode·senderRealName 등 transient 데이터)
     */
    @Transactional
    public void txInboundDeposit(PaymentInstruction pi, BalanceTxData depositTx,
                                 InboundPaymentCommand command) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();
        String clearingNo = command.clearingNo();
        String correlationId = command.correlationId();
        String senderBankCode = command.senderBankCode();
        String senderRealName = command.senderRealName();
        String senderAccountNo = command.senderAccountNo();
        String businessDate = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String settledAt = now.format(CLEARING_AT_FMT);

        // 1. AUTHORIZED→PROCESSING + 상태이력(KFTC_ACK_SENT) + Outbox KFTC_ACK_SENT
        int updated1 = paymentInstructionMapper.updateStatus(piId, "PROCESSING", null, null, pi.getVersion());
        if (updated1 == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(PROCESSING): " + piId);
        }
        Integer maxSeq1 = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq1 == null ? 0 : maxSeq1) + 1,
                "AUTHORIZED", "PROCESSING", "KFTC_ACK_SENT", "COUNTERPARTY_BANK", now));

        String ackPayload;
        try {
            ackPayload = objectMapper.writeValueAsString(Map.of(
                    "messageType", "PAYMENT_ACK",
                    "clearingNo", clearingNo,
                    "correlationId", correlationId,
                    "responseCode", "0000"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox ackPayload 직렬화 실패: " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "KFTC_ACK_SENT", "v1", ackPayload, now));

        // 2. 수신 분개 (interTransferIn, 1건 — IN 방향은 자금변동 1건)
        String journalNo = idGenerator.nextJournalNo();
        Ledger ledger = Ledger.interTransferIn(
                idGenerator.nextLedgerId(), piId, pi.getReceiverAccountNo(),
                journalNo, pi.getReceiverAccountNo(), pi.getReceiverHolderNameSnap(),
                pi.getTransferAmount(),
                depositTx.balanceBefore(),
                depositTx.balanceAfter(),
                "KRW", businessDate, businessDate, businessDate,
                now, "타행이체 수신 입금",
                senderAccountNo, senderBankCode, senderRealName);
        ledgerMapper.insert(ledger);

        // 3. CT settledIn INSERT
        String clearingTxId = idGenerator.nextClearingTransactionId();
        KftcClearingTransaction ct = KftcClearingTransaction.settledIn(
                clearingTxId, piId, null, clearingNo,
                senderBankCode, senderAccountNo, senderRealName,
                pi.getReceiverBankCode(),
                pi.getReceiverAccountNo(),
                pi.getReceiverHolderNameSnap(),
                pi.getTransferAmount(),
                settledAt, businessDate);
        clearingTransactionMapper.insert(ct);

        // 4. PROCESSING→COMPLETED + 상태이력(KFTC_SETTLEMENT_SENT) + Outbox 2건 + 멱등키완료
        int updated2 = paymentInstructionMapper.updateStatus(piId, "COMPLETED", now, null, pi.getVersion() + 1);
        if (updated2 == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(COMPLETED): " + piId);
        }
        Integer maxSeq2 = statusHistoryMapper.selectMaxSequence(piId);
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, (maxSeq2 == null ? 0 : maxSeq2) + 1,
                "PROCESSING", "COMPLETED", "KFTC_SETTLEMENT_SENT", "COUNTERPARTY_BANK", now));

        String settlementPayload;
        try {
            settlementPayload = objectMapper.writeValueAsString(Map.of(
                    "messageType", "SETTLEMENT_NOTIFY",
                    "clearingNo", clearingNo,
                    "correlationId", correlationId,
                    "responseCode", "0000",
                    "receivedAccountNo", pi.getReceiverAccountNo(),
                    "receivedHolderName", pi.getReceiverHolderNameSnap(),
                    "receivedAmount", pi.getTransferAmount(),
                    "settledAt", settledAt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox settlementPayload 직렬화 실패: " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "KFTC_SETTLEMENT_SENT", "v1", settlementPayload, now));

        String completedPayload;
        try {
            completedPayload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "status", "COMPLETED",
                    "direction", "IN",
                    "clearingNo", clearingNo,
                    "transferAmount", pi.getTransferAmount(),
                    "completedAt", now.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox completedPayload 직렬화 실패: " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_COMPLETED", "v1", completedPayload, now));

        idempotencyKeyMapper.updateStatus(clearingNo, "COMPLETED", settlementPayload);
    }

    /**
     * TX-IN-REJ: DRAFT→FAILED(ACCOUNT_RESTRICTED) + 상태이력2건(INBOUND_VALIDATION_FAILED·INBOUND_REJECTED) +
     * CT rejectedIn + Outbox 2건(KFTC_REJECT_SENT·PAYMENT_FAILED) + 멱등키FAILED. 한 TX.
     * 분개 0건 — P-002 "자금변동 없음=FAILED 직행".
     * @param pi      DRAFT 상태 결제지시 (version=0)
     * @param command 원 수신 명령 (clearingNo·senderBankCode 등)
     * @param rejectCode    거절코드 (E2001 등, mock 응답 또는 매핑)
     * @param rejectMessage 거절메시지
     */
    @Transactional
    public void txInboundReject(PaymentInstruction pi, InboundPaymentCommand command,
                                String rejectCode, String rejectMessage) {
        LocalDateTime now = LocalDateTime.now();
        String piId = pi.getPaymentInstructionId();
        String clearingNo = command.clearingNo();
        String correlationId = command.correlationId();
        String businessDate = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String rejectedAt = now.format(CLEARING_AT_FMT);

        // 1. DRAFT→FAILED (낙관락: version=0 → WHERE version=0 → DB version=1)
        int updated = paymentInstructionMapper.updateStatus(piId, "FAILED", now, "ACCOUNT_RESTRICTED", pi.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("결제지시 상태 갱신 충돌(FAILED/INBOUND_REJECT): " + piId);
        }

        // 2. 상태이력 2건 (enum v9 #87 INBOUND_VALIDATION_FAILED / #99 INBOUND_REJECTED)
        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "DRAFT", "DRAFT", "INBOUND_VALIDATION_FAILED", "COUNTERPARTY_BANK",
                rejectCode, rejectMessage, now));
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq + 1,
                "DRAFT", "FAILED", "INBOUND_REJECTED", "COUNTERPARTY_BANK",
                rejectCode, rejectMessage, now));

        // 3. CT rejectedIn INSERT
        String clearingTxId = idGenerator.nextClearingTransactionId();
        KftcClearingTransaction ct = KftcClearingTransaction.rejectedIn(
                clearingTxId, piId, null, clearingNo,
                command.senderBankCode(),
                command.senderAccountNo(),
                command.senderRealName(),
                pi.getReceiverBankCode(),
                pi.getReceiverAccountNo(),
                pi.getReceiverHolderNameSnap(),
                pi.getTransferAmount(),
                rejectCode, rejectMessage, rejectedAt);
        clearingTransactionMapper.insert(ct);

        // 4. Outbox 2건 (KFTC_REJECT_SENT → kftc.network.response, PAYMENT_FAILED → payment.failed)
        String rejectPayload;
        try {
            rejectPayload = objectMapper.writeValueAsString(Map.of(
                    "messageType", "PAYMENT_REJECT",
                    "clearingNo", clearingNo,
                    "correlationId", correlationId,
                    "responseCode", rejectCode,
                    "rejectMessage", rejectMessage,
                    "rejectedAt", rejectedAt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox rejectPayload 직렬화 실패: " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "KFTC_REJECT_SENT", "v1", rejectPayload, now));

        String failedPayload;
        try {
            failedPayload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "status", "FAILED",
                    "failureCategory", "ACCOUNT_RESTRICTED",
                    "direction", "IN",
                    "failedAt", now.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox failedPayload 직렬화 실패: " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_FAILED", "v1", failedPayload, now));

        // 5. 멱등키 FAILED
        idempotencyKeyMapper.updateStatus(clearingNo, "FAILED", failedPayload);
    }
}
