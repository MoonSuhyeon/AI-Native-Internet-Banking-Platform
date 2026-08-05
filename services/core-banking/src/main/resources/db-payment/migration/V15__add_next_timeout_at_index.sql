-- =============================================================
-- V15__add_next_timeout_at_index.sql
-- F6 폴링워커 성능용 부분 인덱스
--
-- next_timeout_at IS NOT NULL인 행(= CLEARING 대기 중인 PI)만 인덱스.
-- NULL(종료상태 등)은 대상 외 → 인덱스 크기 최소화.
-- =============================================================

CREATE INDEX idx_pi_next_timeout_at ON payment_instruction (next_timeout_at)
    WHERE next_timeout_at IS NOT NULL;
