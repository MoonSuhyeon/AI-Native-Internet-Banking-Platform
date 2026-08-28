-- V28: 열람 감사는 넣기만 하고 고치거나 지울 수 없다.
--
-- customer-service 의 customer_access_log 에는 같은 트리거가 있다(V41). 그런데
-- deposit_access_log 는 다른 데이터베이스라 그 트리거가 닿지 않는다. 감사 테이블
-- 하나만 잠가 두면, 잠기지 않은 쪽이 곧 우회로가 된다.
--
-- 한계는 분명히 적어 둔다. 트리거는 애플리케이션 경로를 막을 뿐 DB 관리자를 막지
-- 못한다. 실제 통제는 계정 분리와 보존 스토리지가 함께 있어야 성립한다.

CREATE OR REPLACE FUNCTION fn_deposit_access_log_block_mutate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'deposit_access_log is INSERT-ONLY. UPDATE/DELETE is forbidden.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_deposit_access_log_no_update
    BEFORE UPDATE ON deposit_access_log
    FOR EACH ROW EXECUTE FUNCTION fn_deposit_access_log_block_mutate();

CREATE TRIGGER trg_deposit_access_log_no_delete
    BEFORE DELETE ON deposit_access_log
    FOR EACH ROW EXECUTE FUNCTION fn_deposit_access_log_block_mutate();
