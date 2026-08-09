package com.bank.payment.domain.mapper;

import java.time.OffsetDateTime;

/**
 * 조회용 대사 불일치 한 행.
 *
 * <p>도메인 레코드({@code ReconciliationBreak})와 나눈 이유는, 저장된 뒤에 붙는 것들
 * (조사 상태·최초 발견 시각)이 대사 판단에는 쓰이지 않기 때문이다. 한 타입에 섞으면
 * 대사 로직이 조사 상태를 보고 판단하는 것처럼 읽힌다.
 */
public record ReconciliationBreakRow(
        Long reconciliationBreakId,
        String businessDate,
        String network,
        String paymentInstructionId,
        String breakType,
        Long internalAmount,
        Long externalAmount,
        String externalStatus,
        String detail,
        String resolutionStatus,
        String resolutionNote,
        OffsetDateTime firstDetectedAt,
        OffsetDateTime lastDetectedAt) {
}
