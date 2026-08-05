package com.bank.payment.outbound.ledger;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.service.AccountService;
import com.bank.deposit.service.DepositV1Service;
import com.bank.payment.outbound.ledger.dto.AccountInquiryData;
import com.bank.payment.outbound.ledger.dto.HolderInquiryData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 계좌 조회 포트의 인프로세스 구현.
 *
 * <p>병합 전에는 HTTP 로 deposit-service 를 불렀다. 이제 같은 프로세스의 수신계 서비스를
 * 직접 부른다 — 네트워크 왕복과 직렬화가 사라지고, 호출자가 트랜잭션 안에 있으면
 * 그 트랜잭션에서 읽는다(자기 트랜잭션이 방금 쓴 값을 본다).
 */
@Component
@RequiredArgsConstructor
public class LocalAccountQueryAdapter implements AccountQueryPort {

    private final AccountService accountService;
    private final DepositV1Service depositV1Service;

    @Override
    public AccountInquiryData getAccountByNo(String accountNo) {
        return toInquiry(accountService.findByAccountNumber(accountNo));
    }

    @Override
    public AccountInquiryData getAccount(String accountId) {
        return toInquiry(accountService.findById(Long.valueOf(accountId)));
    }

    @Override
    public HolderInquiryData getHolder(String accountNo) {
        var h = depositV1Service.getHolderInfo(accountNo);
        return new HolderInquiryData(
                h.accountNo(), h.holderName(), h.holderType(),
                h.customerId(), h.deceasedFlag(), (int) h.version());
    }

    /**
     * 엔티티 → 조회 DTO.
     *
     * <p>HTTP 시절에는 Account 엔티티가 그대로 직렬화돼 넘어왔고 payment 쪽이
     * {@code @JsonIgnoreProperties} 로 필요한 필드만 받았다. 이제 그 매핑을 여기서 명시한다.
     */
    private AccountInquiryData toInquiry(Account a) {
        return new AccountInquiryData(
                a.getAccountId(),
                a.getAccountNumber(),
                a.getAccountType() != null ? a.getAccountType().name() : null,
                a.getAccountStatus() != null ? a.getAccountStatus().name() : null,
                // productCode / branchCode 는 Account 에 없다. HTTP 시절에도 응답에 없어
                // payment 쪽에서 null 로 받던 필드다(@JsonIgnoreProperties).
                null,
                a.getOpenedAt() != null ? a.getOpenedAt().toString() : null,
                a.getClosedAt() != null ? a.getClosedAt().toString() : null,
                a.getBankCode(),
                a.getFraudFlag(),
                a.getVersion() != null ? a.getVersion().intValue() : null,
                a.getBalance(),
                a.getDailyWithdrawLimit(),
                a.getAtmWithdrawLimit());
    }
}
