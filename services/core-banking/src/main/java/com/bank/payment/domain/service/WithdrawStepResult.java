package com.bank.payment.domain.service;

import com.bank.payment.outbound.feign.dto.BalanceTxData;

/**
 * B-3 출금 단계 결과 래퍼.
 * B-4 실패 보상 시 원 B-3 callId를 B-5 compensation_target_call_id에 넣기 위해 함께 보관.
 */
record WithdrawStepResult(BalanceTxData txData, String callId) {}
