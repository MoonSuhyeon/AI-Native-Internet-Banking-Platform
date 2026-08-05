package com.bank.loan.document.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * 서류 보관용 S3 클라이언트.
 *
 * <p>{@code loan.document.storage.type=s3} 일 때만 만든다. 기본(filesystem) 배포에서는
 * 이 빈이 아예 생성되지 않으므로 MinIO 가 떠 있지 않아도 기동에 지장이 없다.
 *
 * <p>doc-agent 의 {@code StorageConfig} 와 같은 구성이다 — 엔드포인트를 덮어쓰고
 * path-style 접근을 강제한다(MinIO 요구사항).
 */
@Configuration
@ConditionalOnProperty(name = "loan.document.storage.type", havingValue = "s3")
public class LoanDocumentStorageConfig {

    @Value("${loan.document.storage.endpoint}")   private String endpoint;
    @Value("${loan.document.storage.access-key}") private String accessKey;
    @Value("${loan.document.storage.secret-key}") private String secretKey;
    @Value("${loan.document.storage.region}")     private String region;

    @Bean
    public S3Client loanDocumentS3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .forcePathStyle(true)   // MinIO 요구사항
                .build();
    }
}
