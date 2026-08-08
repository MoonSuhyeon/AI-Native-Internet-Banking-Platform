package com.bank.fds.enrich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

/**
 * 결제계에서 보강해 온 거래 상세. 탐지 룰이 판정에 쓰는 값이다.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 를 둔 이유: 결제계가 응답에
 * 필드를 더해도 탐지기가 깨지지 않아야 한다. 반대로 <b>필드가 사라지면</b> 그 룰이
 * 조용히 못 잡게 되므로, 그쪽은 계약 테스트로 막는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentDetail(
        String paymentInstructionId,
        String transactionNo,
        String status,
        String senderUserId,
        String senderAccountNo,
        String receiverBankCode,
        String receiverAccountNo,
        String receiverHolderName,
        Long amount,
        Boolean intraBank,
        String routingNetworkType,
        String channel,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String businessDate
) {
}
