package com.bank.payment.domain.mapper;

import com.bank.payment.domain.BokSettlementTransaction;
import org.apache.ibatis.annotations.Param;

public interface BokSettlementTransactionMapper {

    void insert(BokSettlementTransaction settlementTransaction);

    BokSettlementTransaction selectByBokReferenceNo(@Param("bokReferenceNo") String bokReferenceNo);

    void updateSettled(@Param("piId") String piId,
                       @Param("settledAt") String settledAt,
                       @Param("settlementDate") String settlementDate);

    /** F3 거절: settlement_status=REJECTED + reject_code/message 박제. settled_at 등 건드리지 않음. */
    void updateRejected(@Param("piId") String piId,
                        @Param("rejectCode") String rejectCode,
                        @Param("rejectMessage") String rejectMessage);
}
