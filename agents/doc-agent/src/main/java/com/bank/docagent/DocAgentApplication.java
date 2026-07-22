package com.bank.docagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bank.docagent", "com.bank.common"})
@EntityScan(basePackages = {"com.bank.docagent", "com.bank.common"})
@EnableJpaRepositories(basePackages = {"com.bank.docagent", "com.bank.common"})
public class DocAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocAgentApplication.class, args);
    }
}
