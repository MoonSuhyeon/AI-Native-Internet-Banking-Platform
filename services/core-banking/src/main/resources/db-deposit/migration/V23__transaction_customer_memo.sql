-- 거래 메모 — 고객이 나중에 붙이는 개인 메모.
--
-- 화면에 "메모 수정" 버튼이 있었지만 부를 곳이 없었다. 그런데 고칠 대상을 찾다
-- 보니 더 큰 문제가 있었다.
--
-- **같은 컬럼이 두 화면에서 다른 뜻으로 쓰이고 있었다.**
--
--   이체 결과조회   transaction_memo → "출금통장표시내용"
--   거래내역 조회   transaction_memo → "메모"  + [메모 수정] 버튼
--
-- transaction_memo 는 이체할 때 함께 보내는 통장표시내용이다. 받는 쪽 통장에 찍힌
-- 그 문구이고, 돈이 이미 그 문구와 함께 나갔으므로 **사후에 바꿀 수 없는 사실**이다.
-- "메모 수정" 이 이 컬럼을 덮어쓰면 실제로 보낸 내용이 바뀐다 — 기록의 위조다.
--
-- 그래서 컬럼을 나눈다. 실물 은행도 통장표시내용(고정)과 메모(수정 가능)를 따로 둔다.

ALTER TABLE deposit_transactions
    ADD COLUMN IF NOT EXISTS customer_memo VARCHAR(255);

COMMENT ON COLUMN deposit_transactions.transaction_memo IS
    '통장표시내용. 거래 시점에 함께 보낸 문구이며 사후에 바꾸지 않는다.';
COMMENT ON COLUMN deposit_transactions.customer_memo IS
    '고객이 나중에 붙이는 개인 메모. 거래 사실이 아니라 본인 기록이라 수정할 수 있다.';

-- "메모 있음/없음" 으로 거래를 거르는 화면이 있다.
CREATE INDEX IF NOT EXISTS ix_deposit_tx_customer_memo
    ON deposit_transactions (account_id)
    WHERE customer_memo IS NOT NULL;
