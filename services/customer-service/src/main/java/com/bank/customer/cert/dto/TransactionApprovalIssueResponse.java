package com.bank.customer.cert.dto;

import java.time.OffsetDateTime;

/** 발급된 승인 토큰. 원문은 이 응답에만 실리고 서버에는 해시로만 남는다. */
public record TransactionApprovalIssueResponse(
        String approvalToken,
        OffsetDateTime expiresAt
) {}
