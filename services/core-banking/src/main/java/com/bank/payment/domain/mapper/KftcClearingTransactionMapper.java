package com.bank.payment.domain.mapper;

import com.bank.payment.domain.KftcClearingTransaction;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface KftcClearingTransactionMapper {

    void insert(KftcClearingTransaction clearingTransaction);

    KftcClearingTransaction selectByClearingNo(@Param("clearingNo") String clearingNo);

    /**
     * 마감배치 전용: 당일 차액정산 대상 CT 목록 조회.
     * 조건: clearing_status=SETTLED + settlement_date=#{settlementDate} + direction=OUT + 연결 PI status=CLEARING.
     * direction=IN은 txInboundDeposit에서 PI COMPLETED로 완결되므로 PI.status='CLEARING' 조건에 걸리지 않음.
     * @param settlementDate 정산일자 (yyyyMMdd)
     */
    List<KftcClearingTransaction> selectDueForSettlement(@Param("settlementDate") String settlementDate);

    void updateSettled(@Param("piId") String piId,
                       @Param("settledAt") String settledAt,
                       @Param("settlementDate") String settlementDate);

    /** F2 거절: clearing_status=REJECTED + reject_code/message 박제. settled_at 등 건드리지 않음(P-015). */
    void updateRejected(@Param("piId") String piId,
                        @Param("rejectCode") String rejectCode,
                        @Param("rejectMessage") String rejectMessage);
}
