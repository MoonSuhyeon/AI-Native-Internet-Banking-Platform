package com.bank.payment.domain.mapper;

import com.bank.payment.domain.Ledger;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface LedgerMapper {

    void insert(Ledger ledger);

    List<Ledger> selectByPaymentId(@Param("paymentInstructionId") String paymentInstructionId);

    /** F2 역분개용: is_reversal=FALSE 필터로 원분개만 반환 (역분개 재조회 방지). */
    List<Ledger> selectOriginalsByPaymentId(@Param("piId") String piId);
}
