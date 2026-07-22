package com.bank.docagent.infra.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ObjectStorageService {

    private final S3Client s3Client;

    @Value("${doc-agent.storage.raw-bucket}")    private String rawBucket;
    @Value("${doc-agent.storage.masked-bucket}") private String maskedBucket;

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM");

    public String uploadRaw(String applicationId, String submissionId,
                            byte[] data, String contentType) {
        String key = buildKey(applicationId, submissionId, "original");
        upload(rawBucket, key, data, contentType);
        return key;
    }

    public String uploadMasked(String applicationId, String submissionId,
                               byte[] data, String contentType) {
        String key = buildKey(applicationId, submissionId, "masked");
        upload(maskedBucket, key, data, contentType);
        return key;
    }

    private void upload(String bucket, String key, byte[] data, String contentType) {
        s3Client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(data)
        );
        log.debug("업로드 완료: bucket={} key={}", bucket, key);
    }

    private String buildKey(String applicationId, String submissionId, String suffix) {
        return String.format("%s/%s/%s/%s",
            LocalDate.now().format(DATE_PATH), applicationId, submissionId, suffix);
    }
}
