package com.bank.docagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class StorageConfig {

    @Value("${doc-agent.storage.endpoint}")   private String endpoint;
    @Value("${doc-agent.storage.access-key}") private String accessKey;
    @Value("${doc-agent.storage.secret-key}") private String secretKey;
    @Value("${doc-agent.storage.region}")     private String region;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .region(Region.of(region))
            .forcePathStyle(true)   // MinIO / R2 요구사항
            .build();
    }
}
