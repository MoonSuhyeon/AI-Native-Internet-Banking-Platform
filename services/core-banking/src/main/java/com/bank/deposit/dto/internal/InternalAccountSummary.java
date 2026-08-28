package com.bank.deposit.dto.internal;

import com.bank.deposit.domain.entity.Account;

import java.time.LocalDate;

/**
 * 상담·조사에 필요한 만큼만 담은 계좌 요약.
 *
 * <p>엔티티를 그대로 내보내지 않는다. 계좌비밀번호 해시·내부 계약 식별자까지
 * 함께 나가면, 필요 없는 것을 준 만큼 지킬 것이 늘어난다.
 */
public record InternalAccountSummary(
        Long accountId,
        String accountNumber,
        String accountType,
        String accountAlias,
        Long balance,
        String currency,
        String accountStatus,
        LocalDate openedAt
) {
    public static InternalAccountSummary from(Account a) {
        return new InternalAccountSummary(
                a.getAccountId(),
                a.getAccountNumber(),
                a.getAccountType() != null ? a.getAccountType().name() : null,
                a.getAccountAlias(),
                a.getBalance(),
                a.getCurrency(),
                a.getAccountStatus() != null ? a.getAccountStatus().name() : null,
                a.getOpenedAt()
        );
    }
}
