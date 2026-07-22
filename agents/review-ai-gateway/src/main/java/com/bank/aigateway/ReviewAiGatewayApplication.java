package com.bank.aigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReviewAiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewAiGatewayApplication.class, args);
    }
}
