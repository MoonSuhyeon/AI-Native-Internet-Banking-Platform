package com.bank.payment.domain.mapper;

import com.bank.payment.domain.Ledger;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface LedgerMapper {

    void insert(Ledger ledger);

    List<Ledger> selectByPaymentId(@Param("paymentInstructionId") String paymentInstructionId);

    /**
     * 분개 묶음으로 읽는다.
     *
     * <p>대출 실행처럼 결제를 거치지 않는 회계 거래는 {@code payment_instruction_id}
     * 가 없다. 복식부기 검증은 저장된 행을 다시 읽어야 하므로 그때 이 경로를 쓴다.
     */
    List<Ledger> selectByJournalNo(@Param("journalNo") String journalNo);

    /** F2 역분개용: is_reversal=FALSE 필터로 원분개만 반환 (역분개 재조회 방지). */
    List<Ledger> selectOriginalsByPaymentId(@Param("piId") String piId);
}
