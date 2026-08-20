package com.bank.customer.banking.dto;

import com.bank.customer.banking.domain.FavoriteType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 즐겨찾기 등록.
 *
 * <p>고객번호를 받지 않는다 — 게이트웨이가 주입한 {@code X-Customer-Id} 로 정한다.
 * 본문에 넣게 두면 남의 계좌에 즐겨찾기를 심을 수 있다.
 */
public record RegisterFavoriteTransferRequest(
        @NotNull(message = "종류를 지정해야 합니다.")
        FavoriteType favoriteType,

        @NotBlank(message = "별칭을 입력해주세요.")
        @Size(max = 50, message = "별칭은 50자를 넘을 수 없습니다.")
        String alias,

        @NotBlank(message = "은행을 선택해주세요.")
        String bankCode,

        @NotBlank(message = "은행명이 필요합니다.")
        String bankName,

        @NotBlank(message = "계좌번호를 입력해주세요.")
        String accountNumber,

        String accountHolderName,

        /** 단축이체에만 쓴다. 자주쓰는계좌면 비워 둔다. */
        Long amount
) {
}
