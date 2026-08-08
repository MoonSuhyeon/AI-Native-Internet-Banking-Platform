package com.bank.payment.api.dto;

import com.bank.payment.domain.PaymentInstruction;

import java.time.OffsetDateTime;

/**
 * 결제지시 상세 — 이상거래 탐지·조사용 읽기 전용 응답.
 *
 * <p><b>왜 필요한가.</b> {@code payment.completed} 이벤트는 paymentInstructionId·status·
 * amount·completedAt 네 개만 싣는다. 금액 룰 하나를 빼면 탐지 룰을 만들 수 없다 —
 * 분할·속도·신규수취인·타행집중은 모두 송신자와 수취인을 알아야 한다.
 *
 * <p>이벤트 payload 를 늘리지 않고 여기서 조회하는 이유: payload 확장은 결제 트랜잭션
 * 경로를 건드린다. 탐지는 결제 성공 여부에 영향을 주면 안 되므로, 읽기 전용 조회로 분리한다.
 *
 * <p><b>담는 값의 범위.</b> 탐지·조사에 실제로 쓰이는 필드만 담는다. 멱등키·낙관적락버전·
 * 재시도 시각 같은 내부 처리 값은 뺀다 — 나가지 않아도 되는 값은 나가지 않는 편이 낫다.
 *
 * <p>계좌번호와 예금주명이 포함된다. 직원 범위 내부 API 이며 게이트웨이 뒤에 둔다.
 */
public record PaymentDetailResponse(
        String paymentInstructionId,
        String transactionNo,
        String status,

        /** 송신 고객번호 — 분할·속도 룰의 집계 키. */
        String senderUserId,
        String senderAccountNo,

        /** 수취인 — 신규 수취인·fan-out 룰의 집계 키. */
        String receiverBankCode,
        String receiverAccountNo,
        String receiverHolderName,

        Long amount,

        /** 자행/타행. 타행 집중 룰에 쓴다. */
        Boolean intraBank,
        String routingNetworkType,

        /** 채널(인터넷뱅킹·모바일 등). 평소와 다른 채널은 계정탈취 신호가 된다. */
        String channel,

        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String businessDate
) {

    public static PaymentDetailResponse from(PaymentInstruction pi) {
        return new PaymentDetailResponse(
                pi.getPaymentInstructionId(),
                pi.getTransactionNo(),
                pi.getStatus(),
                pi.getSenderUserId(),
                // 스냅샷을 쓴다. 거래 시점의 값이어야 한다 — 계좌 별명이나 정보가
                // 나중에 바뀌어도 그때 무엇으로 보냈는지가 탐지·조사의 사실이다.
                pi.getSenderAccountNoSnap(),
                pi.getReceiverBankCode(),
                pi.getReceiverAccountNo(),
                pi.getReceiverHolderNameSnap(),
                pi.getTransferAmount(),
                pi.getIsIntraBank(),
                pi.getRoutingNetworkType(),
                pi.getChannel(),
                pi.getRequestedAt(),
                pi.getCompletedAt(),
                pi.getBusinessDate()
        );
    }
}
