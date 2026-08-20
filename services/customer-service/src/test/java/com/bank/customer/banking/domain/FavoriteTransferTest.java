package com.bank.customer.banking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 즐겨찾기 등록 규칙.
 *
 * <p><b>무엇을 지키는가.</b> 자주쓰는계좌와 단축이체가 한 테이블을 쓰기 때문에,
 * 둘을 갈라 놓는 것은 {@code favorite_type} 과 <b>금액 규칙</b> 뿐이다. 그 규칙이
 * 무너지면 두 기능이 서로의 데이터를 섞어 읽는다 — 금액 없는 단축이체는 화면에
 * 보이지만 보낼 수 없고, 금액 붙은 자주쓰는계좌는 그 값이 아무 데도 안 쓰인다.
 *
 * <p>규칙을 서비스가 아니라 엔티티에 둔 이유가 여기서 확인된다. 등록 경로가 나중에
 * 하나 더 생겨도(가져오기·배치) 같은 검사를 지난다.
 */
class FavoriteTransferTest {

    private static FavoriteTransfer register(FavoriteType type, Long amount) {
        return FavoriteTransfer.register(
                1L, type, "월세", "088", "신한은행", "110-123-456789", "김임대", amount, 0);
    }

    @Test
    @DisplayName("단축이체는 금액이 있어야 한다")
    void quickTransferNeedsAmount() {
        assertThatThrownBy(() -> register(FavoriteType.QUICK_TRANSFER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("단축이체는 금액이 있어야 한다");
    }

    @Test
    @DisplayName("단축이체 금액은 0보다 커야 한다")
    void quickTransferAmountPositive() {
        // 0원 이체는 보낼 수 없다. 목록에 두면 눌러 봐야 실패한다.
        assertThatThrownBy(() -> register(FavoriteType.QUICK_TRANSFER, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("자주쓰는계좌에는 금액을 두지 않는다")
    void favoriteAccountRejectsAmount() {
        assertThatThrownBy(() -> register(FavoriteType.FAVORITE_ACCOUNT, 500_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("금액은 이체할 때 정한다");
    }

    @Test
    @DisplayName("종류에 맞으면 등록된다")
    void registersWhenAmountMatchesType() {
        assertThat(register(FavoriteType.QUICK_TRANSFER, 500_000L).getAmount()).isEqualTo(500_000L);
        assertThat(register(FavoriteType.FAVORITE_ACCOUNT, null).getAmount()).isNull();
    }

    @Test
    @DisplayName("별칭 없이는 등록할 수 없다")
    void aliasRequired() {
        // 별칭이 없으면 목록이 계좌번호만 늘어놓은 줄이 된다 — 고를 수 없다.
        assertThatThrownBy(() -> FavoriteTransfer.register(
                1L, FavoriteType.FAVORITE_ACCOUNT, "   ", "088", "신한은행",
                "110-123-456789", "김임대", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("별칭이 없다");
    }

    @Test
    @DisplayName("별칭 길이는 컬럼 길이를 넘지 않는다")
    void aliasLengthBounded() {
        // 여기서 막지 않으면 DB 가 거절한다 — 그때는 화면에 보일 만한 문구가 아니다.
        String tooLong = "가".repeat(FavoriteTransfer.ALIAS_MAX_LEN + 1);

        assertThatThrownBy(() -> FavoriteTransfer.register(
                1L, FavoriteType.FAVORITE_ACCOUNT, tooLong, "088", "신한은행",
                "110-123-456789", "김임대", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50자");
    }

    @Test
    @DisplayName("별칭 앞뒤 공백은 지운다")
    void aliasTrimmed() {
        assertThat(FavoriteTransfer.register(
                1L, FavoriteType.FAVORITE_ACCOUNT, "  월세  ", "088", "신한은행",
                "110-123-456789", "김임대", null, 0).getAlias())
                .isEqualTo("월세");
    }
}
