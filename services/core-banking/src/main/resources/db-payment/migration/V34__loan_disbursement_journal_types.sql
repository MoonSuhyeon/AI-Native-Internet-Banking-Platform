-- =============================================================
-- V34__loan_disbursement_journal_types.sql
-- 대출 실행 분개 유형
--
-- 근거: docs/decisions/transaction-initiator-auth-model.md §5-1
--
--   대출 실행을 일반 자금이동으로 모델링하지 않고, 여신 도메인의 독립적인 거래
--   유형으로 취급한다. 대출채권과 고객예금의 생성은 단일 회계 거래로 원장에
--   기록하며, 집행계좌를 회계적 중간계정으로 사용하지 않는다.
--
-- 은행 회계에서 대출 실행은 돈을 옮기는 일이 아니다. 자산(대출채권)이 생기고
-- 부채(고객 예금)가 생기는 하나의 사건이다.
--
--   DEBIT   대출채권      자산 증가
--   CREDIT  고객 예금     부채 증가
--
-- 집행계좌는 이 분개에 등장하지 않는다. 지금까지는 집행계좌에서 고객계좌로의
-- 이체(TRANSFER_OUT/TRANSFER_IN)로 처리해 실행할 때마다 집행계좌 잔액이 줄었다.
-- 자금부가 계속 채워 넣어야 하는 구조인데, 그것은 대출 실행의 회계가 아니다.
--
-- 상환·자동이체·역분개 환급은 실제 자금이동이므로 결제 경로를 그대로 쓴다.
-- 갈리는 것은 회계적 의미이지 통제가 아니다 — 인가·FDS·멱등·감사·원장은 공통이다.
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
            'INTERBANK_SETTLEMENT',
            -- 대출 실행. 두 다리가 한 묶음(journal_no)이다.
            'LOAN_RECEIVABLE',          -- 대출채권 DEBIT  (내부계정 KB-LOAN-CR)
            'LOAN_DISBURSE_DEPOSIT'     -- 고객 예금 CREDIT (고객 계좌)
        ));


-- ── 내부계정 등록은 하지 않는다 ──────────────────────────────────────────────
--
-- KB-CLR-088·KB-FEE-001 과 마찬가지로 대출채권 계정(KB-LOAN-CR)은 별도 마스터
-- 테이블 없이 원장 account_id 에 문자열로 들어간다. 잔액을 갖지 않으므로
-- balance_before/after 는 0 으로 박제한다.
--
-- 계정과목 마스터를 세우는 것은 이 결정의 범위 밖이다. 지금 필요한 것은
-- 대출채권이 원장에 존재하게 만드는 것이고, 마스터는 계정과목 체계를 잡을 때
-- 함께 만든다.
