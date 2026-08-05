package com.bank.loan.document.service;

import com.bank.loan.document.domain.LoanDocument;
import com.bank.loan.document.dto.PurgeExpiredDocumentsResponse;
import com.bank.loan.document.repository.LoanDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 보존기한이 지난 신청서류의 원본 파기 배치.
 *
 * <p>지우는 것은 <b>원본 바이트뿐</b>이다. 서류 row(이름·해시·크기·제출시각·파기시각)는 남긴다.
 * 무엇을 언제 파기했는지가 곧 증빙이라, 보존기한이 지났다고 기록까지 없애면
 * "그런 서류를 받은 적이 없다"와 구분되지 않는다.
 *
 * <p>대상은 {@code retention_until < 오늘} 이면서 아직 파기되지 않은 서류다.
 * soft delete 된 서류도 포함한다 — 화면에서 지웠다고 원본이 사라지는 게 아니라
 * 보존기한까지 남겨두는 것이 규정이고, 기한이 지나면 똑같이 파기 대상이다.
 *
 * <p>한 건의 실패가 나머지를 막지 않도록 서류마다 독립 트랜잭션으로 처리한다.
 * 실패분은 purged_at 이 비어 있으므로 다음 실행에서 자연히 다시 잡힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanDocumentRetentionBatchService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final LoanDocumentRepository repository;
    private final LoanDocumentPurger purger;

    /**
     * @param batchSize 한 번에 처리할 최대 건수. 보존기한이 한꺼번에 도래해도
     *                  스토리지와 DB 에 몰아치지 않도록 자른다.
     */
    @Transactional(readOnly = true)
    public PurgeExpiredDocumentsResponse purgeExpired(int batchSize) {
        String today = LocalDate.now().format(YMD);
        List<LoanDocument> targets =
                repository.findPurgeTargets(today, PageRequest.of(0, batchSize));

        List<Long> purged = new ArrayList<>();
        int failed = 0;
        for (LoanDocument doc : targets) {
            try {
                purger.purgeOne(doc.getDocId());
                purged.add(doc.getDocId());
            } catch (Exception e) {
                // 개별 실패는 삼킨다. purged_at 이 비어 있으니 다음 실행에서 다시 잡힌다.
                failed++;
                log.error("[document-retention] 원본 파기 실패 docId={} retentionUntil={}",
                        doc.getDocId(), doc.getRetentionUntil(), e);
            }
        }

        log.info("[document-retention] today={} scanned={} purged={} failed={}",
                today, targets.size(), purged.size(), failed);
        return PurgeExpiredDocumentsResponse.of(targets.size(), purged, failed);
    }
}
