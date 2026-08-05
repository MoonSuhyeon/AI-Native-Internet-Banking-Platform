-- withdrawCancel(@PatchMapping)이 PATCH를 기록할 때 CHECK 위반으로 INSERT 실패하던 문제 수정
ALTER TABLE external_call
    DROP CONSTRAINT chk_external_call_http_method;

ALTER TABLE external_call
    ADD CONSTRAINT chk_external_call_http_method
        CHECK (http_method IN ('GET', 'POST', 'PUT', 'DELETE', 'PATCH'));
