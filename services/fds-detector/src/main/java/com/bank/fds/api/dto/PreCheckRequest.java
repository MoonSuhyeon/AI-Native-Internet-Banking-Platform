package com.bank.fds.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 사전 점검 요청 — 결제 승인 <b>전</b>에 결제계가 보낸다.
 *
 * <p>거래가 아직 만들어지지 않았으므로 결제지시번호가 없다. 그래서 사후 경로처럼
 * 결제계에 되물어 보강할 수 없고, 판정에 필요한 값을 요청에 다 실어야 한다.
 *
 * <p>담는 값은 <b>룰이 실제로 보는 것만</b>이다. 인라인 경로는 예산이 수백 ms 라
 * 없어도 되는 값을 나르면 그만큼 손해다.
 */
public record PreCheckRequest(

        @NotNull(message = "senderUserId 는 필수입니다 — 고객 프로파일 조회 키입니다")
        String senderUserId,

        String senderAccountNo,

        /** 수취인. 신규 수취인·분산 판정에 쓴다. */
        String receiverBankCode,
        String receiverAccountNo,

        @NotNull(message = "amount 는 필수입니다 — 장애 시 구간별 정책의 기준입니다")
        Long amount,

        /** 자행/타행. */
        Boolean intraBank,

        /** 채널. 평소와 다른 채널은 계정탈취 신호가 된다. */
        String channel
) {
}
