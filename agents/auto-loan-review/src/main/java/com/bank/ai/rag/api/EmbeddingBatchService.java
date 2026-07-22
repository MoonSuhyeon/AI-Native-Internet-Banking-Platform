package com.bank.ai.rag.api;

import com.bank.ai.privacy.PiiMaskingFilter;
import com.bank.ai.rag.api.dto.ChunkBatchItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 청크 배치 임베딩·적재 서비스 — D3-1.
 *
 * <p>흐름:
 * <ol>
 *   <li>PII 고신뢰도 검사 — 감지 시 {@link com.bank.ai.privacy.PiiLeakageException} throw</li>
 *   <li>{@link CorpusIndexer} 위임 — {@code ai.rag.backend} 에 따라 pgvector 또는 ES 적재</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingBatchService {

    private final CorpusIndexer corpusIndexer;
    private final PiiMaskingFilter piiMaskingFilter;

    /**
     * 청크 목록을 PII 검사 후 활성 백엔드에 일괄 upsert.
     *
     * @return 처리 건수 (PII 오류 시 예외 throw)
     */
    public int upsertAll(List<ChunkBatchItem> items) {
        int count = 0;
        for (var item : items) {
            piiMaskingFilter.assertNoSensitivePii(
                    item.chunkText(), "batch corpus=%s sourceId=%s".formatted(item.corpus(), item.sourceId()));
            corpusIndexer.upsert(item);
            count++;
        }
        log.info("EmbeddingBatchService: {} 건 upsert 완료", count);
        return count;
    }
}
