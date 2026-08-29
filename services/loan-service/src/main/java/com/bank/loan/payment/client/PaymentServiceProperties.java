package com.bank.loan.payment.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "payment")
public record PaymentServiceProperties(
        @DefaultValue("http://localhost:8080") String url,
        /**
         * 여신의 서비스 자격증명. core-banking 이 이 값의 SHA-256 으로 신원을 찾는다.
         *
         * <p>기본값의 {@code dev-secret} 은 의도된 표식이다 — {@code DevSecretGuard}
         * 가 운영 프로파일에서 이 표식을 보고 기동을 거부한다. 기존
         * {@code local-internal-token} 은 어떤 표식도 없어 운영에서 그대로 떠도
         * 아무도 모른다.
         */
        @DefaultValue("dev-secret-loan-service-credential") String credential,
        Collection collection,
        Disbursement disbursement
) {
    public record Collection(
            @DefaultValue("088") String bankCode,
            @DefaultValue("00000000000") String accountNo,
            @DefaultValue("한국은행") String holderName
    ) {}

    public record Disbursement(
            @DefaultValue("BANK_DISBURSE_001") String accountId
    ) {}
}
