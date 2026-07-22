package com.bank.loan.advisory.rag;

import com.bank.loan.advisory.observability.AdvisoryMetrics;
import com.bank.loan.advisory.repository.AdvisoryCaseIndexRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 과거 완료 심사 케이스를 ADVISORY_CASE_INDEX 에 일괄 적재하는 백필 서비스.
 *
 * 설계 원칙:
 *   - 임베딩 API 호출은 트랜잭션 밖에서 수행 (AI_GUIDELINES: 트랜잭션 내 외부 API 호출 금지).
 *     벡터 계산 완료 후 {@link #persistIndex} 에서만 트랜잭션 열어 INSERT.
 *   - revId 당 1건만 적재 (existsByRevId 체크) — 동일 기간 재실행 시 신규 적재 0건.
 *   - 페이지 단위 처리 (PAGE_SIZE=100) — findAll 무페이지 금지 준수.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseIndexBackfillService {

    private static final int    PAGE_SIZE  = 100;
    private static final String SORT_FIELD = "revId";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LoanReviewRepository        reviewRepo;
    private final AdvisoryCaseIndexRepository caseIndexRepo;
    private final EmbeddingClient             embeddingClient;
    private final JdbcTemplate                jdbcTemplate;
    private final AdvisoryMetrics             advisoryMetrics;

    // ── 공개 API ─────────────────────────────────────────────────────────────

    /**
     * 백필 실행.
     *
     * @param fromDate  시작일 YYYYMMDD (null = 전체)
     * @param toDate    종료일 YYYYMMDD exclusive (null = 전체)
     * @param dryRun    true 면 임베딩·INSERT 없이 대상 건수만 카운트
     * @param actorId   요청자 ID
     * @return 처리 결과 요약
     */
    public BackfillResult backfill(String fromDate, String toDate,
                                   boolean dryRun, Long actorId) {
        OffsetDateTime from = fromDate != null ? toStartOfDay(fromDate) : null;
        OffsetDateTime to   = toDate   != null ? toStartOfDay(toDate)   : null;
        String modelCd = embeddingClient.defaultModelCd();

        int processed = 0, skipped = 0, failed = 0;
        int page = 0;

        while (true) {
            Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(SORT_FIELD).ascending());
            Page<LoanReview> slice = fetchPage(from, to, pageable);

            if (slice.isEmpty()) break;

            for (LoanReview review : slice.getContent()) {
                Long revId = review.getRevId();

                // 멱등: 이미 동일 revId 가 인덱스에 있으면 건너뜀
                if (caseIndexRepo.existsByRevId(revId)) {
                    skipped++;
                    // dryRun 은 진단 실행 — 운영 카운터를 오염시키지 않도록 메트릭 미기록
                    if (!dryRun) {
                        advisoryMetrics.incrementBackfillSkipped();
                    }
                    continue;
                }

                // dryRun 은 대상 건수만 카운트 (임베딩·INSERT·메트릭 모두 생략)
                if (dryRun) {
                    processed++;
                    continue;
                }

                try {
                    // 임베딩 — 트랜잭션 밖
                    String summaryText = buildSummary(review);
                    float[] vec        = embeddingClient.embed(summaryText);
                    // INSERT — 별도 트랜잭션
                    persistIndex(revId, review.getRevDecisionCd(), summaryText,
                                 vec, modelCd, actorId);
                    processed++;
                    advisoryMetrics.incrementBackfillProcessed();
                } catch (Exception e) {
                    failed++;
                    advisoryMetrics.incrementBackfillFailed();
                    log.warn("[backfill] revId={} 실패: {}", revId, e.getMessage());
                }
            }

            if (!slice.hasNext()) break;
            page++;
        }

        log.info("[backfill] 완료 — 처리={} 건너뜀={} 실패={} dryRun={}", processed, skipped, failed, dryRun);
        return new BackfillResult(processed, skipped, failed, dryRun);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private Page<LoanReview> fetchPage(OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        if (from != null && to != null) {
            return reviewRepo
                    .findByRevStatusCdAndReviewedAtGreaterThanEqualAndReviewedAtLessThanAndDeletedAtIsNull(
                            LoanReview.STATUS_COMPLETED, from, to, pageable);
        }
        return reviewRepo.findByRevStatusCdAndDeletedAtIsNull(LoanReview.STATUS_COMPLETED, pageable);
    }

    /** PII 제거된 구조화 요약 텍스트. */
    private String buildSummary(LoanReview review) {
        String raw = String.format(
                "심사결정: %s 승인금액: %s원 승인기간: %s개월 승인금리: %sbps 심사유형: %s 심사관ID: %s",
                review.getRevDecisionCd(),
                review.getApprovedAmount()   != null ? review.getApprovedAmount()   : "N/A",
                review.getApprovedPeriodMo() != null ? review.getApprovedPeriodMo() : "N/A",
                review.getApprovedRateBps()  != null ? review.getApprovedRateBps()  : "N/A",
                review.getRevTypeCd(),
                review.getReviewerId()       != null ? review.getReviewerId()        : "N/A");
        return PiiMaskingUtil.mask(raw);
    }

    /** INSERT only — 별도 트랜잭션으로 분리해 외부 API 호출과 격리. */
    @Transactional
    public void persistIndex(Long revId, String decisionCd, String summaryText,
                              float[] vec, String modelCd, Long actorId) {
        jdbcTemplate.update("""
                INSERT INTO advisory_case_index
                  (rev_id, decision_cd, overturn_yn, credit_score,
                   dsr_ratio_bps, ltv_ratio_bps,
                   cohort_employment_type_cd, cohort_loan_purpose_cd,
                   summary_text, embedding_model_cd, embedding, indexed_at, created_at, created_by)
                VALUES (?, ?, 'N', null, null, null, null, null,
                        ?, ?, CAST(? AS vector), now(), now(), ?)
                """,
                revId, decisionCd,
                summaryText, modelCd, EmbeddingClient.toVectorString(vec), actorId);
    }

    private static OffsetDateTime toStartOfDay(String yyyymmdd) {
        LocalDate d = LocalDate.parse(yyyymmdd, DATE_FMT);
        return d.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    // ── 결과 DTO ─────────────────────────────────────────────────────────────

    public record BackfillResult(int processed, int skipped, int failed, boolean dryRun) {}
}
