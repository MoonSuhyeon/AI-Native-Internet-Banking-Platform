package com.bank.payment;

import com.bank.payment.domain.reconciliation.ReconciliationBreak;
import com.bank.payment.domain.reconciliation.ReconciliationBreakType;
import com.bank.payment.domain.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사가 실물 DB 에서 도는지 확인한다.
 *
 * <p><b>단위 테스트로는 부족한 이유.</b> {@code ReconciliationEngineTest} 는 판단 규칙이
 * 맞는지 본다. 그런데 대사에서 실제로 틀리기 쉬운 곳은 규칙이 아니라 <b>양쪽을 어떻게
 * 긁어 오는가</b> 다 — 어느 날짜 컬럼을 기준으로 잡는지, 역분개를 어떻게 상계하는지,
 * 청산 계정을 망별로 제대로 갈랐는지. 그게 틀리면 엔진은 멀쩡히 돌면서 "이상 없음" 을
 * 보고한다.
 *
 * <p>그래서 여기서는 원장과 청산 기록을 직접 심어 놓고, 대사가 그것을 찾아내는지 본다.
 */
class ReconciliationIntegrationTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    /** 오늘이 아닌 과거 영업일. dayClosed 판정이 켜져야 STALE_PENDING 을 볼 수 있다. */
    private static final String PAST_DATE = "20200102";

    private static final String KFTC_ACCOUNT = "KB-CLR-088";

    @BeforeEach
    void cleanReconciliationFixtures() {
        jdbc.update("DELETE FROM reconciliation_break WHERE business_date = ?", PAST_DATE);
        jdbc.update("DELETE FROM ledger WHERE posting_date = ?", PAST_DATE);
        jdbc.update("DELETE FROM kftc_clearing_transaction "
                + "WHERE our_payment_instruction_id LIKE 'PI-RECON%'");
        jdbc.update("DELETE FROM payment_instruction "
                + "WHERE payment_instruction_id LIKE 'PI-RECON%'");
        jdbc.update("DELETE FROM idempotency_key WHERE idempotency_key LIKE 'IDEM-PI-RECON%'");
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    /**
     * 결제지시 한 건.
     *
     * <p>원장과 청산 기록 둘 다 결제지시를 FK 로 참조하므로 먼저 심어야 한다.
     * 그래서 이 테스트의 "내부에 없다"는 <b>결제지시가 없다</b>가 아니라
     * <b>청산대기 분개가 없다</b>는 뜻이다 — 대사가 보는 것도 그쪽이다.
     */
    private void givenPaymentInstruction(String piId, long amount) {
        // 결제지시는 멱등키를 FK 로 참조한다. 실제 흐름과 같은 순서로 심는다.
        jdbc.update("""
                INSERT INTO idempotency_key (
                    idempotency_key, client_id, request_hash, idempotency_status,
                    first_received_at, last_received_at, expires_at
                ) VALUES (?, 'RECON-TEST', ?, 'COMPLETED', NOW(), NOW(), NOW() + INTERVAL '1 day')
                """,
                "IDEM-" + piId, "HASH-" + piId);
        jdbc.update("""
                INSERT INTO payment_instruction (
                    payment_instruction_id, idempotency_key, transaction_no,
                    sender_account_id, sender_account_no_snap,
                    receiver_bank_code, receiver_account_no, receiver_holder_name_snap,
                    holder_inquiry_at, is_intra_bank, routing_network_type,
                    transfer_amount, status, channel, requested_at, business_date
                ) VALUES (?, ?, ?, ?, '11112222333344', '088', '55556666777788', '받는이',
                          NOW(), FALSE, 'KFTC', ?, 'COMPLETED', 'WEB', NOW(), ?)
                """,
                piId, "IDEM-" + piId, "TXN-" + piId, "ACC-" + piId, amount, PAST_DATE);
    }

    /** 청산대기 분개 한 건. journalType 으로 원분개/역분개를 가른다. */
    private void givenClearingLedger(String piId, String drCr, long amount, String journalType) {
        ensurePaymentInstruction(piId, amount);
        boolean reversal = journalType.startsWith("REVERSAL");
        String originalId = "L-" + piId + "-CC";
        String ledgerId = reversal ? "L-" + piId + "-RD" : originalId;
        jdbc.update("""
                INSERT INTO ledger (
                    ledger_id, payment_instruction_id, account_id, journal_no,
                    account_no_snap, holder_name_snap, debit_credit, journal_type,
                    amount, currency, balance_before, balance_after,
                    transaction_date, posting_date, value_date,
                    posted_at, system_description, is_reversal,
                    original_ledger_id, reversal_reason, posting_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'KRW', 0, 0, ?, ?, ?, NOW(), ?, ?, ?, ?,
                          'POSTED')
                """,
                ledgerId, piId, KFTC_ACCOUNT, "JN-" + piId,
                KFTC_ACCOUNT, "청산계정", drCr, journalType,
                amount, PAST_DATE, PAST_DATE, PAST_DATE,
                "대사 테스트", reversal,
                // 역분개는 원분개 참조와 사유가 있어야 한다(스키마 CHECK).
                reversal ? originalId : null,
                reversal ? "KFTC_REJECTION" : null);
    }

    private void givenExternalClearing(String piId, long amount, String status) {
        ensurePaymentInstruction(piId, amount);
        jdbc.update("""
                INSERT INTO kftc_clearing_transaction (
                    clearing_transaction_id, our_payment_instruction_id, direction,
                    clearing_no, sender_bank_code, sender_account_no_snap, sender_holder_name_snap,
                    receiver_bank_code, receiver_account_no_snap, receiver_holder_name_snap,
                    clearing_amount, currency, clearing_status,
                    clearing_requested_at, settlement_date, network
                ) VALUES (?, ?, 'OUT', ?, '004', '1111', '보낸이', '088', '2222', '받는이',
                          ?, 'KRW', ?, ?, ?, 'KFTC_CLEARING')
                """,
                "CT-" + piId, piId, "CLR-" + piId,
                amount, status, PAST_DATE + "120000", PAST_DATE);
    }

    /** 같은 결제에 분개를 두 건 심는 경우가 있어 멱등하게 만든다. */
    private void ensurePaymentInstruction(String piId, long amount) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_instruction WHERE payment_instruction_id = ?",
                Integer.class, piId);
        if (exists == null || exists == 0) {
            givenPaymentInstruction(piId, amount);
        }
    }

    private List<ReconciliationBreak> reconcile() {
        return reconciliationService.run(PAST_DATE, ReconciliationService.NETWORK_KFTC);
    }

    // ── 일치 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정산된 건은 청산대기가 남아 있어도 불일치가 아니다 — unwind 는 회계계 몫")
    void settledWithRemainingPendingIsClean() {
        // KFTC 정산은 청산대기를 되돌리는 분개를 남기지 않는다. "청산계정이 0 이
        // 되어야 한다" 로 짰다면 여기 정상 거래가 불일치로 잡힌다.
        givenClearingLedger("PI-RECON-OK", "CREDIT", 1_000_000L, "CLEARING_PENDING");
        givenExternalClearing("PI-RECON-OK", 1_000_000L, "SETTLED");

        assertThat(reconcile()).isEmpty();
    }

    @Test
    @DisplayName("역분개가 상계되어 순액 0 이면 취소로 본다 — 총액만 세면 오탐한다")
    void reversedNetsToZero() {
        givenClearingLedger("PI-RECON-REV", "CREDIT", 1_000_000L, "CLEARING_PENDING");
        givenClearingLedger("PI-RECON-REV", "DEBIT", 1_000_000L, "REVERSAL_CLEARING_PENDING");
        givenExternalClearing("PI-RECON-REV", 1_000_000L, "REJECTED");

        assertThat(reconcile()).isEmpty();
    }

    // ── 불일치 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("망에 안 나간 건을 잡는다 — 출금됐는데 청산 기록이 없다")
    void missingExternalIsDetected() {
        givenClearingLedger("PI-RECON-ME", "CREDIT", 3_000_000L, "CLEARING_PENDING");

        assertThat(reconcile()).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo(ReconciliationBreakType.MISSING_EXTERNAL);
            assertThat(b.paymentInstructionId()).isEqualTo("PI-RECON-ME");
            assertThat(b.internalAmount()).isEqualTo(3_000_000L);
        });
    }

    @Test
    @DisplayName("장부에 안 남은 건을 잡는다 — 망은 처리했는데 원장에 없다")
    void missingInternalIsDetected() {
        givenExternalClearing("PI-RECON-MI", 2_000_000L, "SETTLED");

        assertThat(reconcile()).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo(ReconciliationBreakType.MISSING_INTERNAL);
            assertThat(b.externalAmount()).isEqualTo(2_000_000L);
        });
    }

    @Test
    @DisplayName("금액 차이를 잡는다")
    void amountMismatchIsDetected() {
        givenClearingLedger("PI-RECON-AM", "CREDIT", 1_000_000L, "CLEARING_PENDING");
        givenExternalClearing("PI-RECON-AM", 999_000L, "SETTLED");

        assertThat(reconcile()).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo(ReconciliationBreakType.AMOUNT_MISMATCH);
            assertThat(b.difference()).isEqualTo(1_000L);
        });
    }

    @Test
    @DisplayName("취소했는데 망에서는 나간 건을 잡는다 — 가장 위험한 유형")
    void reversedButSettledIsDetected() {
        givenClearingLedger("PI-RECON-RS", "CREDIT", 5_000_000L, "CLEARING_PENDING");
        givenClearingLedger("PI-RECON-RS", "DEBIT", 5_000_000L, "REVERSAL_CLEARING_PENDING");
        givenExternalClearing("PI-RECON-RS", 5_000_000L, "SETTLED");

        assertThat(reconcile()).singleElement()
                .satisfies(b -> assertThat(b.type())
                        .isEqualTo(ReconciliationBreakType.REVERSED_BUT_SETTLED));
    }

    @Test
    @DisplayName("영업일이 끝났는데 미확정인 건을 잡는다")
    void stalePendingIsDetected() {
        givenClearingLedger("PI-RECON-SP", "CREDIT", 700_000L, "CLEARING_PENDING");
        givenExternalClearing("PI-RECON-SP", 700_000L, "REQUESTED");

        assertThat(reconcile()).singleElement()
                .satisfies(b -> assertThat(b.type())
                        .isEqualTo(ReconciliationBreakType.STALE_PENDING));
    }

    // ── 적재 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("불일치가 저장된다 — 저장 안 되면 추이를 볼 수 없다")
    void breaksArePersisted() {
        givenClearingLedger("PI-RECON-P1", "CREDIT", 1_000_000L, "CLEARING_PENDING");
        reconcile();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM reconciliation_break WHERE business_date = ?", PAST_DATE);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("break_type")).isEqualTo("MISSING_EXTERNAL");
            assertThat(r.get("resolution_status")).isEqualTo("OPEN");
            assertThat(r.get("payment_instruction_id")).isEqualTo("PI-RECON-P1");
        });
    }

    @Test
    @DisplayName("재실행해도 중복 적재되지 않는다 — 쌓이면 건수 지표가 부풀어 신뢰를 잃는다")
    void rerunDoesNotDuplicate() {
        givenClearingLedger("PI-RECON-P2", "CREDIT", 1_000_000L, "CLEARING_PENDING");

        reconcile();
        reconcile();
        reconcile();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_break WHERE business_date = ?",
                Integer.class, PAST_DATE);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("재실행해도 최초 발견 시각은 유지된다 — 며칠째 미결인지 세는 기준이다")
    void firstDetectedAtSurvivesRerun() {
        givenClearingLedger("PI-RECON-P3", "CREDIT", 1_000_000L, "CLEARING_PENDING");
        reconcile();

        Object first = jdbc.queryForObject(
                "SELECT first_detected_at FROM reconciliation_break WHERE business_date = ?",
                Object.class, PAST_DATE);

        reconcile();

        Object afterRerun = jdbc.queryForObject(
                "SELECT first_detected_at FROM reconciliation_break WHERE business_date = ?",
                Object.class, PAST_DATE);
        assertThat(afterRerun).isEqualTo(first);
    }

    @Test
    @DisplayName("해소된 불일치는 RESOLVED 로 닫힌다 — 남겨 두면 목록을 아무도 안 본다")
    void resolvedBreaksAreClosed() {
        givenClearingLedger("PI-RECON-P4", "CREDIT", 1_000_000L, "CLEARING_PENDING");
        reconcile();

        // 빠져 있던 외부 기록이 뒤늦게 도착한 상황.
        givenExternalClearing("PI-RECON-P4", 1_000_000L, "SETTLED");
        assertThat(reconcile()).isEmpty();

        String status = jdbc.queryForObject(
                "SELECT resolution_status FROM reconciliation_break WHERE business_date = ?",
                String.class, PAST_DATE);
        assertThat(status).isEqualTo("RESOLVED");
    }

    // ── 망 분리 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BOK 대사는 KFTC 청산계정을 보지 않는다 — 섞이면 전건이 오탐된다")
    void networksAreIsolated() {
        givenClearingLedger("PI-RECON-NET", "CREDIT", 1_000_000L, "CLEARING_PENDING");

        // KFTC 계정(KB-CLR-088)의 분개다. BOK 대사는 KB-CLR-BOK 만 봐야 한다.
        assertThat(reconciliationService.run(PAST_DATE, ReconciliationService.NETWORK_BOK))
                .isEmpty();
    }

    @Test
    @DisplayName("알 수 없는 망은 거절한다 — 기본값으로 흘리면 엉뚱한 계정을 보고 '이상 없음' 을 낸다")
    void unknownNetworkIsRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> reconciliationService.run(PAST_DATE, "SWIFT"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
