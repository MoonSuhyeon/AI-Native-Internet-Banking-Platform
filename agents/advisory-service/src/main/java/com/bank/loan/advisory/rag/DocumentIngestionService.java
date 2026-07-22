package com.bank.loan.advisory.rag;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.AdvisoryDocument;
import com.bank.loan.advisory.dto.DocumentRegisterRequest;
import com.bank.loan.advisory.dto.DocumentRegisterResponse;
import com.bank.loan.advisory.repository.AdvisoryDocumentChunkRepository;
import com.bank.loan.advisory.repository.AdvisoryDocumentRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 정책문서 등록 + 청크 분할 + 임베딩 적재 (plan §11.4.1 — Task 6-3).
 *
 * 처리 순서:
 *   1. 중복 검증 (트랜잭션 없음)
 *   2. 청크 분할 후 embed 선계산 (트랜잭션 없음 — 외부 API 호출 격리)
 *   3. ADVISORY_DOCUMENT INSERT → ADVISORY_DOCUMENT_CHUNK INSERT → activate (단일 트랜잭션)
 *
 * AI_GUIDELINES: 트랜잭션 안에서 외부 API 호출 금지 → embed 는 트랜잭션 진입 전에 완료.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int CHUNK_SIZE    = 800;
    private static final int CHUNK_OVERLAP  = 100;

    private final AdvisoryDocumentRepository    docRepo;
    private final AdvisoryDocumentChunkRepository chunkRepo;
    private final EmbeddingClient               embeddingClient;
    private final JdbcTemplate                  jdbcTemplate;
    private final TransactionTemplate           transactionTemplate;

    public DocumentRegisterResponse register(DocumentRegisterRequest req, Long actorId) {
        if (req.content() == null || req.content().isBlank()) {
            throw new BusinessException(LoanErrorCode.LOAN_003,
                    "content 가 비어있습니다. 현 구현에서는 content 직접 제공이 필요합니다.");
        }

        // 1. 청크 분할 + 임베딩 선계산 (트랜잭션 밖 — 외부 API 호출)
        List<String[]> chunks  = splitChunks(req.content(), CHUNK_SIZE, CHUNK_OVERLAP);
        String         modelCd = embeddingClient.defaultModelCd();
        OffsetDateTime indexedAt = OffsetDateTime.now();

        List<String> vecStrings = new ArrayList<>(chunks.size());
        for (String[] chunk : chunks) {
            vecStrings.add(EmbeddingClient.toVectorString(embeddingClient.embed(chunk[0])));
        }

        // 2. 문서 마스터 + 청크 INSERT + 활성화 (단일 트랜잭션)
        DocumentRegisterResponse resp = Objects.requireNonNull(
            transactionTemplate.execute(status -> {
                docRepo.findByDocCdAndDocVersionAndDeletedAtIsNull(req.docCd(), req.docVersion())
                       .ifPresent(d -> { throw new BusinessException(LoanErrorCode.LOAN_001); });

                AdvisoryDocument doc = docRepo.save(AdvisoryDocument.builder()
                        .docCd(req.docCd())
                        .docTitle(req.docTitle())
                        .docCategoryCd(req.docCategoryCd())
                        .docVersion(req.docVersion())
                        .effectiveStartDate(req.effectiveStartDate())
                        .effectiveEndDate(req.effectiveEndDate())
                        .sourceUri(req.sourceUri())
                        .activeYn("N")
                        .docDesc(req.docDesc())
                        .build());

                for (int i = 0; i < chunks.size(); i++) {
                    String text        = chunks.get(i)[0];
                    String sectionPath = chunks.get(i)[1];
                    jdbcTemplate.update("""
                        INSERT INTO advisory_document_chunk
                          (doc_id, chunk_seq, chunk_text, section_path, chunk_token_count,
                           embedding_model_cd, embedding, indexed_at, created_at, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, now(), ?)
                        """,
                            doc.getDocId(), i + 1, text, sectionPath, estimateTokens(text),
                            modelCd, vecStrings.get(i), indexedAt, actorId);
                }

                doc.activate();
                log.info("정책문서 인입 완료 — docId={} docCd={} chunkCount={}",
                         doc.getDocId(), doc.getDocCd(), chunks.size());
                return DocumentRegisterResponse.of(doc, chunks.size());
            })
        );
        return resp;
    }

    /** 문서 비활성화 (재인입 준비 또는 만료 처리). */
    @Transactional
    public void deactivate(Long docId, Long actorId) {
        AdvisoryDocument doc = docRepo.findByDocIdAndDeletedAtIsNull(docId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_002));
        doc.deactivate();
        log.info("정책문서 비활성화 — docId={}", docId);
    }

    /** 문서 활성화 (비활성 → 활성). */
    @Transactional
    public void activate(Long docId, Long actorId) {
        AdvisoryDocument doc = docRepo.findByDocIdAndDeletedAtIsNull(docId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_002));
        if (chunkRepo.countByDocId(docId) == 0) {
            throw new BusinessException(LoanErrorCode.LOAN_003,
                    "청크가 없는 문서는 활성화할 수 없습니다. 먼저 content 를 인입하세요.");
        }
        doc.activate();
    }

    // ──────────────────────────────────────────────────
    // internal helpers
    // ──────────────────────────────────────────────────

    /**
     * 텍스트를 size 자 단위로 overlap 자 중복 포함해 분할.
     * 반환: [chunkText, "char:{startPos}"] 쌍 목록.
     */
    static List<String[]> splitChunks(String content, int size, int overlap) {
        List<String[]> result = new ArrayList<>();
        int pos = 0;
        while (pos < content.length()) {
            int end  = Math.min(pos + size, content.length());
            String text = content.substring(pos, end);
            result.add(new String[]{text, "char:" + pos});
            if (end >= content.length()) break;
            pos += size - overlap;
        }
        return result;
    }

    /** 토큰 수 근사치 (한국어 문자 기준 ÷ 1.5). */
    private static int estimateTokens(String text) {
        return Math.max(1, (int) (text.length() / 1.5));
    }
}
