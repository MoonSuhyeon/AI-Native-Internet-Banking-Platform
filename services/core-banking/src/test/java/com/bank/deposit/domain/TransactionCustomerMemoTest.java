package com.bank.deposit.domain;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 개인 메모 수정 규칙.
 *
 * <p><b>핵심은 무엇을 안 바꾸는가다.</b> 화면의 "메모 수정" 이 손대던 자리가 사실은
 * 통장표시내용이었다 — 이체할 때 함께 보내 받는 쪽 통장에 이미 찍힌 문구다. 그것을
 * 사후에 고치면 실제로 보낸 내용과 기록이 달라진다. 메모 수정이 기록의 위조가 되어서는
 * 안 되므로 컬럼을 나눴고, 이 테스트가 그 분리를 지킨다.
 */
class TransactionCustomerMemoTest {

    private Transaction transfer() {
        return Transaction.builder()
                .transactionNumber("TXN-20260817-0001")
                .accountId(1L)
                .transactionType(TransactionType.TRANSFER)
                .directionType(DirectionType.OUT)
                .amount(500_000L)
                .balanceBefore(1_000_000L)
                .balanceAfter(500_000L)
                .channelType(TransactionChannel.INTERNET)
                .transactionAt(OffsetDateTime.now())
                .transactionMemo("월세")          // 통장표시내용 — 이미 보낸 문구
                .build();
    }

    @Test
    @DisplayName("메모를 고쳐도 통장표시내용은 그대로다")
    void doesNotTouchTransactionMemo() {
        Transaction tx = transfer();

        tx.updateCustomerMemo("8월분, 관리비 별도");

        assertThat(tx.getCustomerMemo()).isEqualTo("8월분, 관리비 별도");
        assertThat(tx.getTransactionMemo())
                .as("이미 보낸 문구는 사후에 바뀌면 안 된다")
                .isEqualTo("월세");
    }

    @Test
    @DisplayName("앞뒤 공백은 지운다")
    void trims() {
        Transaction tx = transfer();
        tx.updateCustomerMemo("  집주인 확인함  ");
        assertThat(tx.getCustomerMemo()).isEqualTo("집주인 확인함");
    }

    @Test
    @DisplayName("내용을 비우면 메모가 지워진다")
    void blankClears() {
        // 화면에서 다 지우고 저장하는 것이 "메모 삭제" 다. 빈 문자열이 남으면
        // "메모 있음" 으로 걸러져 지운 것이 지워지지 않는다.
        Transaction tx = transfer();
        tx.updateCustomerMemo("임시");

        tx.updateCustomerMemo("   ");

        assertThat(tx.getCustomerMemo()).isNull();
    }

    @Test
    @DisplayName("길이를 넘으면 거절한다")
    void tooLong() {
        // 잘라서 저장하면 사용자가 쓴 것과 남은 것이 달라진다. DB 가 거절하기 전에
        // 여기서 막아 무엇이 문제인지 알려 준다.
        Transaction tx = transfer();
        String tooLong = "가".repeat(Transaction.MEMO_MAX_LEN + 1);

        assertThatThrownBy(() -> tx.updateCustomerMemo(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255자");
    }
}
