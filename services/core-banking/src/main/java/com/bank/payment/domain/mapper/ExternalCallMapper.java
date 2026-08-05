package com.bank.payment.domain.mapper;

import com.bank.payment.domain.ExternalCall;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface ExternalCallMapper {

    void insert(ExternalCall externalCall);

    int updateResponse(@Param("callId") String callId,
                       @Param("responseStatusCode") Integer responseStatusCode,
                       @Param("responseHeader") String responseHeader,
                       @Param("responseBody") String responseBody,
                       @Param("businessResponseCode") String businessResponseCode,
                       @Param("responseMessage") String responseMessage,
                       @Param("result") String result,
                       @Param("responseTimeMs") Integer responseTimeMs,
                       @Param("respondedAt") LocalDateTime respondedAt);

    /**
     * F2용: piId + callType으로 가장 최근 외부호출 1건 조회.
     * 원 BALANCE_WITHDRAW callId(compensation_target_call_id용) 및
     * responseBody JSON의 depositTransactionNo 추출 경로 확보에 사용.
     * B-5 멱등 가드(BALANCE_WITHDRAW_CANCEL 존재 여부 확인)에도 사용.
     */
    ExternalCall selectByPiIdAndCallType(@Param("piId") String piId,
                                         @Param("callType") String callType);
}
