package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.AdvisoryDocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AdvisoryDocumentChunkRepository extends JpaRepository<AdvisoryDocumentChunk, Long> {

    List<AdvisoryDocumentChunk> findByDocIdOrderByChunkSeqAsc(Long docId);

    int countByDocId(Long docId);

    /** embedding_model_cd 별 청크 건수를 집계해 반환. */
    @Query("SELECT c.embeddingModelCd AS embeddingModelCd, COUNT(c) AS count " +
           "FROM AdvisoryDocumentChunk c GROUP BY c.embeddingModelCd")
    List<ChunkModelSummary> countByEmbeddingModelCd();

    interface ChunkModelSummary {
        String getEmbeddingModelCd();
        long getCount();
    }
}
