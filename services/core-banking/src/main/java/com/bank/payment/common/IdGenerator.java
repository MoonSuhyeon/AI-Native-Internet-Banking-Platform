package com.bank.payment.common;

import com.bank.payment.domain.mapper.IdSequenceMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ID 채번기. VARCHAR(20) PK 6개 + journal_no.
 * 형식: {접두}-{yyyyMMdd}-{6자리 zero-pad}
 *
 * 설계:
 * - 6자리 = 접두별 전역 시퀀스 (단조증가)
 * - yyyyMMdd = 발급시각 스탬프 (날짜별 리셋 안 함 - 의도된 설계)
 * - lazy 시드: 첫 채번 시 1회, 6개 접두 일괄 시드 (부팅 순서 의존 제거)
 *   → @PostConstruct 안 씀 (Flyway-MyBatis 부팅 순서 미보장 회피)
 *
 * 멱등키(idempotency_key)는 제외 - 클라이언트 발급 요청 입력.
 * 확장: 매일 000001 휴먼리더블 원하면 날짜별 필터+리셋. 멀티 인스턴스면 채번테이블.
 */
@Component
public class IdGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final IdSequenceMapper idSequenceMapper;

    // 접두별 카운터
    private final AtomicLong piSeq = new AtomicLong(0);
    private final AtomicLong ledgerSeq = new AtomicLong(0);
    private final AtomicLong journalSeq = new AtomicLong(0);
    private final AtomicLong historySeq = new AtomicLong(0);
    private final AtomicLong messageSeq = new AtomicLong(0);
    private final AtomicLong callSeq = new AtomicLong(0);
    private final AtomicLong clearingTxSeq    = new AtomicLong(0);
    private final AtomicLong clearingNoSeq    = new AtomicLong(0);
    private final AtomicLong settlementTxSeq  = new AtomicLong(0);
    private final AtomicLong bokRefSeq        = new AtomicLong(0);

    private volatile boolean seeded = false;

    public IdGenerator(IdSequenceMapper idSequenceMapper) {
        this.idSequenceMapper = idSequenceMapper;
    }

    // ── 채번 메서드 (접두 8종) ──────────────────────────
    public String nextPaymentInstructionId()  { ensureSeeded(); return build("P",    piSeq); }
    public String nextLedgerId()              { ensureSeeded(); return build("L",    ledgerSeq); }
    public String nextJournalNo()             { ensureSeeded(); return build("JN",   journalSeq); }
    public String nextHistoryId()             { ensureSeeded(); return build("SH",   historySeq); }
    public String nextMessageId()             { ensureSeeded(); return build("OB",   messageSeq); }
    public String nextCallId()                { ensureSeeded(); return build("EC",   callSeq); }
    public String nextClearingTransactionId()  { ensureSeeded(); return build("KCT",  clearingTxSeq); }
    public String nextClearingNo()             { ensureSeeded(); return build("KFTC", clearingNoSeq); }
    public String nextSettlementTransactionId(){ ensureSeeded(); return build("BST",  settlementTxSeq); }
    public String nextBokReferenceNo()         { ensureSeeded(); return build("BOK",  bokRefSeq); }

    // ── ID 조립 ─────────────────────────────────────────
    private String build(String prefix, AtomicLong seq) {
        String date = LocalDate.now().format(DATE_FMT);
        long n = seq.incrementAndGet();
        return String.format("%s-%s-%06d", prefix, date, n);
    }

    // ── lazy 시드 (첫 채번 시 1회, 6개 일괄) ────────────
    private void ensureSeeded() {
        if (seeded) return;
        synchronized (this) {
            if (seeded) return;
            piSeq.set(parseSeq(idSequenceMapper.selectMaxPaymentInstructionId()));
            ledgerSeq.set(parseSeq(idSequenceMapper.selectMaxLedgerId()));
            journalSeq.set(parseSeq(idSequenceMapper.selectMaxJournalNo()));
            historySeq.set(parseSeq(idSequenceMapper.selectMaxHistoryId()));
            messageSeq.set(parseSeq(idSequenceMapper.selectMaxMessageId()));
            callSeq.set(parseSeq(idSequenceMapper.selectMaxCallId()));
            clearingTxSeq.set(parseSeq(idSequenceMapper.selectMaxClearingTransactionId()));
            clearingNoSeq.set(parseSeq(idSequenceMapper.selectMaxClearingNo()));
            settlementTxSeq.set(parseSeq(idSequenceMapper.selectMaxSettlementTransactionId()));
            bokRefSeq.set(parseSeq(idSequenceMapper.selectMaxBokReferenceNo()));
            seeded = true;
        }
    }

    // ── MAX ID에서 시퀀스 파싱 (null 가드) ──────────────
    // 입력 예: "P-20260516-000123" → 123. null/형식 이상 → 0
    private long parseSeq(String maxId) {
        if (maxId == null || maxId.isBlank()) {
            return 0L;
        }
        int lastDash = maxId.lastIndexOf('-');
        if (lastDash < 0 || lastDash == maxId.length() - 1) {
            return 0L;
        }
        try {
            return Long.parseLong(maxId.substring(lastDash + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
