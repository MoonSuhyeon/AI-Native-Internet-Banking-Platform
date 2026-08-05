package com.bank.payment.domain.mapper;

import com.bank.payment.domain.IdempotencyKey;
import org.apache.ibatis.annotations.Param;

public interface IdempotencyKeyMapper {

    void insert(IdempotencyKey idempotencyKey);

    IdempotencyKey selectByKey(@Param("idempotencyKey") String idempotencyKey);

    int updateStatus(@Param("idempotencyKey") String idempotencyKey,
                     @Param("idempotencyStatus") String idempotencyStatus,
                     @Param("firstResponseSnap") String firstResponseSnap);
}
