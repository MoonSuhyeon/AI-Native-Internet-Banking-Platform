package com.bank.loan.document.storage;

import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 신청서류 원본 바이트 보관소.
 *
 * <p>기존에는 업로드가 메타데이터(이름·크기·해시)만 기록하고 파일 자체는 어디에도 남기지
 * 않았다. 그래서 다운로드가 성립하지 않았다. 여기서 원본을 보관해 그 구멍을 메운다.
 *
 * <p>경로는 {@code <root>/<docId>.bin} 으로 docId 에서 결정된다.
 * 경로를 DB 에 들고 있지 않으므로 스키마 변경이 필요 없고, 컬럼과 실제 파일이
 * 어긋날 여지도 없다.
 *
 * <p>운영에서는 {@code loan.document.storage-dir} 을 공유 볼륨이나 오브젝트 스토리지
 * 마운트로 지정한다. 지정하지 않으면 임시 디렉터리를 쓴다(로컬·테스트 전용).
 * 보존기한 경과분 정리 배치는 아직 없다 — {@code loan_document.retention_until} 이
 * 그 판단 근거가 된다.
 */
@Slf4j
@Component
public class LoanDocumentStorage {

    private final Path root;

    public LoanDocumentStorage(
            @Value("${loan.document.storage-dir:}") String configuredDir) {
        String dir = (configuredDir == null || configuredDir.isBlank())
                ? System.getProperty("java.io.tmpdir") + "/loan-documents"
                : configuredDir;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** 업로드 원본을 보관한다. 같은 docId 로 다시 쓰면 덮어쓴다. */
    public void store(Long docId, MultipartFile file) {
        try {
            Files.createDirectories(root);
            Files.write(pathOf(docId), file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(LoanErrorCode.LOAN_040);
        }
    }

    /** 보관된 원본을 읽는다. 파일이 없으면 서류 없음으로 취급한다. */
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

    private Path pathOf(Long docId) {
        return root.resolve(docId + ".bin");
    }
}
