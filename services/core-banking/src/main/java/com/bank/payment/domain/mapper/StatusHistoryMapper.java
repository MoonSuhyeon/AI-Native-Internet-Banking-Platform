package com.bank.payment.domain.mapper;

import com.bank.payment.domain.StatusHistory;
import org.apache.ibatis.annotations.Param;

public interface StatusHistoryMapper {

    void insert(StatusHistory statusHistory);

    Integer selectMaxSequence(@Param("paymentInstructionId") String paymentInstructionId);
}
