-- Customer access audit records are append-only.  This is a DB-level guardrail,
-- not a substitute for privileged PostgreSQL administration controls.
CREATE OR REPLACE FUNCTION fn_customer_access_log_block_mutate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'customer_access_log is INSERT-ONLY. UPDATE/DELETE is forbidden.';
END;
$$;

CREATE TRIGGER trg_customer_access_log_no_update
    BEFORE UPDATE ON customer_access_log
    FOR EACH ROW EXECUTE FUNCTION fn_customer_access_log_block_mutate();

CREATE TRIGGER trg_customer_access_log_no_delete
    BEFORE DELETE ON customer_access_log
    FOR EACH ROW EXECUTE FUNCTION fn_customer_access_log_block_mutate();
