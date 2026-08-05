package com.bank.loan.document.storage;

import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 로컬 디렉터리 기반 서류 보관소 (기본 구현).
 *
 * <p>운영에서는 {@code loan.document.storage.dir} 을 영속 볼륨으로 지정해야 한다.
 * 지정하지 않으면 임시 디렉터리를 쓰는데, 컨테이너 재기동 시 사라져서 업로드된 서류의
 * 다운로드가 조용히 실패한다. 다중 인스턴스 배포라면 애초에 s3 구현을 써야 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "loan.document.storage.type",
        havingValue = "filesystem", matchIfMissing = true)
public class FilesystemLoanDocumentStorage implements LoanDocumentStorage {

    private final Path root;

    public FilesystemLoanDocumentStorage(
            @Value("${loan.document.storage.dir:}") String configuredDir) {
        String dir = (configuredDir == null || configuredDir.isBlank())
                ? System.getProperty("java.io.tmpdir") + "/loan-documents"
                : configuredDir;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        log.info("[document-storage] filesystem 사용 root={}", this.root);
    }

    @Override
    public void store(Long docId, MultipartFile file) {
        try {
            Files.createDirectories(root);
            Files.write(pathOf(docId), file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(LoanErrorCode.LOAN_040);
        }
    }

    @Override
    public byte[] load(Long docId) {
        Path path = pathOf(docId);
        if (!Files.exists(path)) {
            log.warn("서류 원본 파일 없음 docId={} path={}", docId, path);
            throw new BusinessException(LoanErrorCode.LOAN_041);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new BusinessException(LoanErrorCode.LOAN_041);
        }
    }

    @Override
    public void delete(Long docId) {
        try {
            Files.deleteIfExists(pathOf(docId));
        } catch (IOException e) {
            throw new IllegalStateException("서류 원본 파기 실패 docId=" + docId, e);
        }
    }

    private Path pathOf(Long docId) {
        return root.resolve(LoanDocumentStorage.objectKey(docId));
    }
}
