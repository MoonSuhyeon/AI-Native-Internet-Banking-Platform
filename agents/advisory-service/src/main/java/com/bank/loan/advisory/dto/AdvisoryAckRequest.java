package com.bank.loan.advisory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * ack 요청 — `POST /advisory/reports/{advrId}/ack`.
 * decisionChangeYn 미지정 시 'N'. before/after decision 은 본심사 결정 변경 시 함께 캡처.
 */
public record AdvisoryAckRequest(
        @NotBlank String ackResponseCd,
        @Pattern(regexp = "[YN]?") String decisionChangeYn,
        String ackReasonCd,
        String ackRemark,
        String beforeDecisionCd,
        String afterDecisionCd
) {}
