-- V8: payment_instruction 박제 컬럼 2종 NOT NULL 해제 (P-028 정합)
-- receiver_holder_name_snap / holder_inquiry_at 는 외부조회(Step2 A-2 예금주조회)
--   결과를 박제하는 사후값. P-028상 DRAFT INSERT(Step1 TX-1)는 외부조회 전이라
--   이 시점엔 값이 없음 → nullable이어야 정합.
-- 컬럼명세서 v12.2가 NOT NULL로 정의했으나 P-028(정책 시트13)과 모순. 정책 시트10
--   CHECK제약_정의에도 해당 NOT NULL 근거 없음. 컬럼명세서 v12.3에서 N→Y 수정 메모.
-- sender_account_no_snap은 입력값(송신계좌번호)이라 DRAFT에 존재 → NOT NULL 유지(건드리지 않음).

ALTER TABLE payment_instruction ALTER COLUMN receiver_holder_name_snap DROP NOT NULL;
ALTER TABLE payment_instruction ALTER COLUMN holder_inquiry_at DROP NOT NULL;
