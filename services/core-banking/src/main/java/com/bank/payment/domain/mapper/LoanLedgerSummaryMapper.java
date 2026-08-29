package com.bank.payment.domain.mapper;

import com.bank.payment.api.dto.LoanLedgerSummary;
import org.apache.ibatis.annotations.Param;

/**
 * 여신 원장 집계 — 대사의 정본 쪽을 원장에서만 계산한다.
 *
 * <p>여신 보조부를 참조하지 않는다. 한쪽을 다른 쪽에서 뽑아 오면 대사가 항등식이
 * 되어 아무것도 검증하지 못한다.
 */
public interface LoanLedgerSummaryMapper {

    LoanLedgerSummary summarize(@Param("baseDate") String baseDate);
}
