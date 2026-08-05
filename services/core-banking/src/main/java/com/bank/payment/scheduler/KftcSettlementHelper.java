package com.bank.payment.scheduler;

import com.bank.payment.common.IdGenerator;
import com.bank.payment.domain.KftcClearingTransaction;
import com.bank.payment.domain.Ledger;
import com.bank.payment.domain.OutboxMessage;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.StatusHistory;
import com.bank.payment.domain.mapper.LedgerMapper;
import com.bank.payment.domain.mapper.OutboxMessageMapper;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.mapper.StatusHistoryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * KFTC 마감 정산 트랜잭션 헬퍼. KftcSettlementBatchWorker 전용.
 *
 * 단건 TX: 한은당좌 unwind 분개 2건 + PI CLEARING→COMPLETED + 상태이력 + Outbox PAYMENT_COMPLETED.
 * txSettlementBok(step 2.5) 패턴과 동일. clearingPendingUnwind는 원분개 CLEARING_PENDING의
 * accountId("KB-CLR-088")/accountNoSnap/holderNameSnap을 계승 — BOK("KB-CLR-BOK")와 코드 공유.
 * (c)(d)는 txSettlement에서 이동한 PAYMENT_COMPLETED 이력·Outbox (payload 동일).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KftcSettlementHelper {

    private static final DateTimeFormatter BUSINESS_DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final PaymentInstructionMapper paymentInstructionMapper;
    private final StatusHistoryMapper statusHistoryMapper;
    private final LedgerMapper ledgerMapper;
    private final OutboxMessageMapper outboxMessageMapper;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    /**
     * KFTC 차액정산 단건 처리 (한 TX).
     * (a) 청산대기 unwind 분개(CLEARING_PENDING_UNWIND) + 한은당좌 분개(INTERBANK_SETTLEMENT)
     * (b) PI CLEARING→COMPLETED (낙관락: selectById로 신선 version 사용)
     * (c) 상태이력 PAYMENT_COMPLETED (CLEARING→COMPLETED)
     * (d) Outbox PAYMENT_COMPLETED
     */
    @Transactional
    public void settleKftc(KftcClearingTransaction ct) {
        String piId = ct.getOurPaymentInstructionId();
        LocalDateTime now = LocalDateTime.now();

        // PI 신선 조회 (낙관락 version + transferAmount 필요)
        PaymentInstruction pi = paymentInstructionMapper.selectById(piId);
        if (pi == null) {
            throw new IllegalStateException("[KFTC마감] PI 조회 실패: " + piId);
        }
        // 중복 처리 가드: 이미 COMPLETED이면 skip (다른 인스턴스 경합 또는 재시도)
        if (!"CLEARING".equals(pi.getStatus())) {
            log.warn("[KFTC마감] PI 상태 불일치, skip. piId={} status={}", piId, pi.getStatus());
            return;
        }

        // (a) 한은당좌 unwind 분개 — txSettlementBok step2.5 동일 패턴
        //     clearingOrig.getAccountId() = "KB-CLR-088" (원분개에서 자동 계승, BOK는 "KB-CLR-BOK")
        List<Ledger> originals = ledgerMapper.selectOriginalsByPaymentId(piId);
        Ledger clearingOrig = originals.stream()
                .filter(l -> "CLEARING_PENDING".equals(l.getJournalType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("KFTC 청산대기 원분개 없음: " + piId));

        String jnSettlement = idGenerator.nextJournalNo();
        ledgerMapper.insert(Ledger.clearingPendingUnwind(
                idGenerator.nextLedgerId(), piId,
                clearingOrig.getAccountId(), clearingOrig.getAccountNoSnap(), clearingOrig.getHolderNameSnap(),
                jnSettlement, clearingOrig.getAmount(),
                clearingOrig.getCurrency(),
                clearingOrig.getTransactionDate(), clearingOrig.getPostingDate(), clearingOrig.getValueDate(),
                now, "KFTC 차액정산 - 청산대기 해소"));
        ledgerMapper.insert(Ledger.bokDda(
                idGenerator.nextLedgerId(), piId,
                jnSettlement, clearingOrig.getAmount(),
                clearingOrig.getCurrency(),
                clearingOrig.getTransactionDate(), clearingOrig.getPostingDate(), clearingOrig.getValueDate(),
                now, "KFTC 차액정산 - 한은당좌"));

        // (b) PI CLEARING→COMPLETED (낙관락: selectById version = 현재 DB version)
        int updated = paymentInstructionMapper.updateStatus(piId, "COMPLETED", now, null, pi.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("[KFTC마감] PI COMPLETED 상태갱신 충돌(낙관락): " + piId);
        }

        // (c) 상태이력 PAYMENT_COMPLETED (CLEARING→COMPLETED) — txSettlement에서 이동
        Integer maxSeq = statusHistoryMapper.selectMaxSequence(piId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        statusHistoryMapper.insert(StatusHistory.of(
                idGenerator.nextHistoryId(), piId, seq,
                "CLEARING", "COMPLETED", "PAYMENT_COMPLETED", "SYSTEM", now));

        // (d) Outbox PAYMENT_COMPLETED — txSettlement에서 이동 (payload 동일)
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "paymentInstructionId", piId,
                    "status", "COMPLETED",
                    "transferAmount", pi.getTransferAmount(),
                    "completedAt", now.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화 실패(PAYMENT_COMPLETED/KFTC마감): " + piId, e);
        }
        outboxMessageMapper.insert(OutboxMessage.of(
                idGenerator.nextMessageId(), piId, "PAYMENT_COMPLETED", "v1", payload, now));
    }
}
