-- =============================================================
-- V21__add_settlement_journal_types.sql
-- ledger journal_type CHECK에 정산 분개 2종 추가
--
-- 추가 값:
--   CLEARING_PENDING_UNWIND  — 청산대기 해소 차변 (KB-CLR-088/KB-CLR-BOK DEBIT)
--                              KFTC 마감 정산 / BOK RTGS 정산 시점 사용
--   INTERBANK_SETTLEMENT     — 한은당좌 정산 대변 (KB-DDA CREDIT)
--                              CLEARING_PENDING_UNWIND 의 차대변 상대편
--
-- is_reversal=FALSE (정산은 역분개가 아닌 신규 회계 사건)
-- reversal_reason CHECK / 기타 CHECK 변경 없음.
-- =============================================================

ALTER TABLE ledger
    DROP CONSTRAINT chk_ledger_journal_type;

ALTER TABLE ledger
    ADD CONSTRAINT chk_ledger_journal_type
        CHECK (journal_type IN (
            'TRANSFER_OUT',
            'TRANSFER_IN',
            'CLEARING_PENDING',
            'FEE',
            'FEE_INCOME',
            'REVERSAL_TRANSFER_OUT',
            'REVERSAL_CLEARING_PENDING',
            'REVERSAL_FEE',
            'REVERSAL_FEE_INCOME',
            'CLEARING_PENDING_UNWIND',
            'INTERBANK_SETTLEMENT'
        ));
