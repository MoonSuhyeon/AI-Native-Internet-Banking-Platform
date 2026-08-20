package com.bank.customer.banking.domain;

/**
 * 이체 즐겨찾기의 종류.
 *
 * <p>둘의 차이는 <b>금액을 미리 정하느냐</b> 하나다. 그래서 테이블을 나누지 않고
 * 종류로 구분한다 — 나누면 같은 CRUD 를 두 벌 쓰게 되고 한쪽만 고치는 일이 생긴다.
 */
public enum FavoriteType {

    /** 자주쓰는계좌. 이체 화면에서 고르고 금액은 그때 정한다. */
    FAVORITE_ACCOUNT,

    /** 단축이체. 금액까지 미리 정해 두고 한 번에 보낸다. */
    QUICK_TRANSFER;

    public boolean requiresAmount() {
        return this == QUICK_TRANSFER;
    }
}
