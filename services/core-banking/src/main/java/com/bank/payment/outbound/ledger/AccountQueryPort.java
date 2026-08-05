package com.bank.payment.outbound.ledger;

import com.bank.payment.outbound.ledger.dto.AccountInquiryData;
import com.bank.payment.outbound.ledger.dto.HolderInquiryData;

/**
 * 계좌 조회 포트.
 *
 * <p>병합 전에는 deposit-service 를 향한 {@code @FeignClient} 였다. 이제 같은 프로세스
 * 안의 수신계 서비스를 부르는 어댑터가 구현한다({@link LocalAccountQueryAdapter}).
 * 인터페이스로 남긴 것은 B은행 시뮬레이션이 다른 구현을 끼울 수 있어야 하기 때문이다.
 */
public interface AccountQueryPort {

    /** 계좌번호로 계좌 조회. accountId(PK) 를 포함한다. */
    AccountInquiryData getAccountByNo(String accountNo);

    /** 계좌 PK 로 조회. 계좌번호를 이미 아는 경우엔 getAccountByNo 를 쓴다. */
    AccountInquiryData getAccount(String accountId);

    /** 예금주 조회. */
    HolderInquiryData getHolder(String accountNo);
}
