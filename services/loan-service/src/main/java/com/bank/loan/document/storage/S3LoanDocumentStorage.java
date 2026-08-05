package com.bank.loan.document.storage;

import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;

/**
 * MinIO/S3 호환 오브젝트 스토리지 기반 서류 보관소.
 *
 * <p>다중 인스턴스 배포에서 쓴다. 파일시스템 구현은 인스턴스마다 저장소가 갈라져서
 * 업로드를 받은 노드가 아닌 곳으로 다운로드 요청이 가면 실패한다.
 *
 * <p>버킷은 기동 시 만들지 않는다. 버킷 생성·수명주기·권한은 인프라가 쥐는 게 맞고,
 * 애플리케이션이 조용히 만들어버리면 정책 없는 버킷이 생겨난다. 없으면 그대로 실패시킨다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "loan.document.storage.type", havingValue = "s3")
public class S3LoanDocumentStorage implements LoanDocumentStorage {

    private final S3Client s3Client;
    private final String bucket;

    public S3LoanDocumentStorage(S3Client s3Client,
                                 @Value("${loan.document.storage.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        log.info("[document-storage] s3 사용 bucket={}", bucket);
    }

    @Override
    public void store(Long docId, MultipartFile file) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(LoanDocumentStorage.objectKey(docId))
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromBytes(file.getBytes()));
        } catch (IOException | S3Exception e) {
            log.error("서류 원본 업로드 실패 docId={} bucket={}", docId, bucket, e);
            throw new BusinessException(LoanErrorCode.LOAN_040);
        }
    }

    @Override
    public byte[] load(Long docId) {
        try {
            return s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(LoanDocumentStorage.objectKey(docId))
                            .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            log.warn("서류 원본 객체 없음 docId={} bucket={}", docId, bucket);
            throw new BusinessException(LoanErrorCode.LOAN_041);
        } catch (S3Exception e) {
            log.error("서류 원본 조회 실패 docId={} bucket={}", docId, bucket, e);
            throw new BusinessException(LoanErrorCode.LOAN_041);
        }
    }

    @Override
    public void delete(Long docId) {
        // S3 의 DeleteObject 는 없는 키에 대해서도 성공을 돌려주므로 그대로 멱등이다.
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(LoanDocumentStorage.objectKey(docId))
                .build());
    }
}
