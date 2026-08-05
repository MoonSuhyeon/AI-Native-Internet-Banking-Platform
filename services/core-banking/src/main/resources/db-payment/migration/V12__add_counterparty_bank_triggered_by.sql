-- V12: status_history.triggered_by CHECK에 COUNTERPARTY_BANK 추가
-- IN(수신) 트랜잭션의 triggered_by='COUNTERPARTY_BANK' 기록을 허용하기 위함
-- 기존 데이터(6개 값 내)는 새 제약(상위집합) 조건을 모두 충족하므로 안전
ALTER TABLE status_history DROP CONSTRAINT chk_status_history_triggered_by;
ALTER TABLE status_history ADD CONSTRAINT chk_status_history_triggered_by
    CHECK (triggered_by IN ('USER', 'SYSTEM', 'KFTC', 'BOK', 'OPERATOR', 'SCHEDULER', 'COUNTERPARTY_BANK'));
