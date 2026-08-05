package com.bank.loan.document.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.loan.document.domain.LoanDocument;
import com.bank.loan.document.repository.LoanDocumentRepository;
import com.bank.loan.document.storage.LoanDocumentStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 서류 한 건의 원본 파기 — 건별 독립 트랜잭션.
 *
 * <p>배치 서비스와 별도 빈인 이유는 순전히 트랜잭션 때문이다. 같은 빈 안에서 호출하면
 * Spring 프록시를 거치지 않아 {@code REQUIRES_NEW} 가 무시되고, 한 건이 실패하면
 * 배치 전체가 함께 롤백된다.
 */
@Service
@RequiredArgsConstructor
public class LoanDocumentPurger {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "LOAN_DOCUMENT";
    private static final String REASON_PURGED = "DOCUMENT_RETENTION_EXPIRED";

    private final LoanDocumentRepository repository;
    private final LoanDocumentStorage storage;
    private final CurrentActorProvider currentActor;
    private final StatusHistoryPublisher statusHistoryPublisher;

    /**
     * 원본을 파기하고 파기 사실을 기록한다.
     *
     * <p>스토리지 삭제를 먼저 하고 purged_at 을 찍는다. 순서를 뒤집으면 스토리지 삭제가
     * 실패했는데 파기된 것으로 기록돼 원본이 영원히 남는다. 이 순서라면 삭제 후 커밋 전에
     * 죽어도 다음 실행에서 다시 지울 뿐이고, 삭제는 멱등이라 무해하다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeOne(Long docId) {
        LoanDocument doc = repository.findById(docId).orElseThrow();
        if (doc.isPurged()) {
            return;
        }

        storage.delete(docId);
        doc.markPurged(OffsetDateTime.now());

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, docId,
                doc.getDocStatusCd(), doc.getDocStatusCd(),
                REASON_PURGED, "retentionUntil=" + doc.getRetentionUntil(),
                currentActor.currentActorId()
        ));
    }
}
