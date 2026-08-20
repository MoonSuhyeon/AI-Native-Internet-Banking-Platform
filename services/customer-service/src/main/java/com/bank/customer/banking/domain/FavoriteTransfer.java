package com.bank.customer.banking.domain;

import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이체 즐겨찾기 — 자주쓰는계좌·단축이체.
 *
 * <p>금액 규칙을 엔티티에 둔다. 등록 경로가 나중에 하나 더 생겨도(가져오기·배치)
 * 같은 검사를 지나야 한다 — 서비스에만 두면 새 경로가 건너뛸 수 있고, 그러면 금액
 * 없는 단축이체가 생겨 화면에서 보낼 수 없는 항목이 목록에 남는다.
 */
@Getter
@Entity
@Table(name = "favorite_transfer")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class FavoriteTransfer extends BaseEntity {

    /** 별칭 최대 길이. 컬럼과 같은 값이라 여기서 막으면 DB 가 거절하지 않는다. */
    public static final int ALIAS_MAX_LEN = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "favorite_id")
    private Long favoriteId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "favorite_type", nullable = false, length = 30)
    private FavoriteType favoriteType;

    @Column(name = "alias", nullable = false, length = 50)
    private String alias;

    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    @Column(name = "bank_name", nullable = false, length = 50)
    private String bankName;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Column(name = "account_holder_name", length = 100)
    private String accountHolderName;

    /** 단축이체에만 있다. 자주쓰는계좌는 금액을 그때 정한다. */
    @Column(name = "amount")
    private Long amount;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public static FavoriteTransfer register(
            Long customerId, FavoriteType type, String alias,
            String bankCode, String bankName, String accountNumber,
            String accountHolderName, Long amount, int displayOrder) {

        assertAmountMatchesType(type, amount);
        return FavoriteTransfer.builder()
                .customerId(customerId)
                .favoriteType(type)
                .alias(requireAlias(alias))
                .bankCode(bankCode)
                .bankName(bankName)
                .accountNumber(accountNumber)
                .accountHolderName(accountHolderName)
                .amount(amount)
                .displayOrder(displayOrder)
                .build();
    }

    /**
     * 종류와 금액이 맞는지 본다.
     *
     * <p>금액 없는 단축이체는 목록에 보이지만 보낼 수 없다 — 화면에서 "얼마를" 이
     * 비어 있기 때문이다. 반대로 자주쓰는계좌에 금액이 붙어 있으면 그 값이 어디에도
     * 쓰이지 않아, 나중에 읽는 사람이 "왜 있지" 를 묻게 된다.
     */
    public static void assertAmountMatchesType(FavoriteType type, Long amount) {
        if (type.requiresAmount()) {
            if (amount == null || amount <= 0) {
                throw new IllegalArgumentException(
                        "단축이체는 금액이 있어야 한다 — 금액 없이는 보낼 수 없다");
            }
        } else if (amount != null) {
            throw new IllegalArgumentException(
                    "자주쓰는계좌에는 금액을 두지 않는다 — 금액은 이체할 때 정한다");
        }
    }

    private static String requireAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("별칭이 없다 — 목록에서 무엇인지 알 수 없다");
        }
        String trimmed = alias.strip();
        if (trimmed.length() > ALIAS_MAX_LEN) {
            throw new IllegalArgumentException(
                    "별칭은 " + ALIAS_MAX_LEN + "자를 넘을 수 없다: " + trimmed.length() + "자");
        }
        return trimmed;
    }

    /** 목록 순서 변경. 자주 쓰는 것을 위로 올릴 수 있어야 편의 기능이 된다. */
    public void changeOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
