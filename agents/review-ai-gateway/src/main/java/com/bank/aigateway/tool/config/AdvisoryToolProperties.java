package com.bank.aigateway.tool.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "advisory")
public class AdvisoryToolProperties {
    private String baseUrl = "http://localhost:8080";
}
