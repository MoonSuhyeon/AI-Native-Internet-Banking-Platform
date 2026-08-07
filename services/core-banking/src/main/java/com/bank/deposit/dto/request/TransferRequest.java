package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.domain.enums.TransferType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull Long fromAccountId,
        Long toAccountId,
        String toAccountNo,
        @NotNull @Positive Long amount,
        TransferType transferType,
        String counterpartyBankCode,
        String counterpartyBankName,
        String counterpartyName,
        TransactionChannel channelType,
        String transactionMemo,
        String idempotencyKey,

        /**
         * 자금이동 승인 토큰(step-up). 인증보안계에서 인증서 PIN 확인 후 발급받는다.
         *
         * <p>지금은 <b>선택</b>이다 — 있으면 검증하고 없으면 통과시킨다. 프런트·챗봇이
         * 발급 단계를 붙일 때까지의 과도기이며, deposit.transfer.approval.required=true 로
         * 바꾸면 필수가 된다. 처음부터 필수로 두면 토큰을 안 보내는 경로가 전부 죽는다.
         * 설계: docs/plan/transfer-step-up-auth.md
         */
        String approvalToken
) {}
