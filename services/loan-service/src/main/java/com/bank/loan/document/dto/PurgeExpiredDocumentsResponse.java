package com.bank.loan.document.dto;

import java.util.List;

/**
 * 보존기한 경과 서류 파기 배치 결과.
 *
 * @param scanned    이번 실행에서 훑은 대상 수 (batchSize 로 잘린다)
 * @param purged     원본 파기에 성공한 수
 * @param failed     파기에 실패해 다음 실행으로 넘긴 수
 * @param purgedDocIds 파기된 서류 id — 감사 추적용
 */
public record PurgeExpiredDocumentsResponse(
        int scanned,
        int purged,
        int failed,
        List<Long> purgedDocIds
) {
    public static PurgeExpiredDocumentsResponse of(int scanned, List<Long> purgedDocIds, int failed) {
        return new PurgeExpiredDocumentsResponse(scanned, purgedDocIds.size(), failed, purgedDocIds);
    }
}
