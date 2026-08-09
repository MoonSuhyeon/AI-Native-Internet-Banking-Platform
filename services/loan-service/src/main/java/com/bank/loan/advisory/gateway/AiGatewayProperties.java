package com.bank.loan.advisory.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "advisory.ai-gateway")
public class AiGatewayProperties {

    private String baseUrl = "http://localhost:8088";
    private long timeoutMs = 35000;
    private long connectTimeoutMs = 3000;
    private Rag rag = new Rag();

    @Getter
    @Setter
    public static class Rag {
        private int maxChunks = 5;
        private int maxContentChars = 1000;
    }
}
