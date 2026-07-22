package com.bank.loan.advisory.rag;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.AdvisoryCaseIndex;
import com.bank.loan.advisory.repository.AdvisoryCaseIndexRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 과거 심사 사례 인덱싱 서비스 (plan §11.6.2 + Task 6-4).
 *
 * 심사(LoanReview) 1건을 사례 인덱스로 변환:
 *   1. PII 마스킹된 summary_text 생성
 *   2. embed → ADVISORY_CASE_INDEX INSERT (CAST(? AS vector))
 *
 * overturn_yn 은 심사 ack 결과 기준 — 현 단계에서는 ack OVERTURN 여부로 설정.
 * PII 격리: summary_text 에 고객명·주민번호·계좌번호 없음 (심사 메타만 포함, plan §11.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseIndexingService {

    private final LoanReviewRepository         reviewRepo;
    private final AdvisoryCaseIndexRepository  caseIndexRepo;
    private final EmbeddingClient              embeddingClient;
    private final JdbcTemplate                 jdbcTemplate;

    /**
     * 단일 심사 사례 인덱싱. 이미 인덱스가 있어도 append (재인덱싱 정책 — append-only).
     *
     * @param revId      인덱싱 대상 심사 ID
     * @param overturnYn ack 결과 번복 여부 ('Y'/'N')
     * @param actorId    요청자 ID
     * @return 생성된 case_idx_id
     */
    @Transactional
    public Long index(Long revId, String overturnYn, Long actorId) {
        LoanReview review = reviewRepo.findById(revId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_042));

        String summaryText = buildSummaryText(review);
        float[] vec    = embeddingClient.embed(summaryText);
        String  vecStr = EmbeddingClient.toVectorString(vec);
        String  modelCd = embeddingClient.defaultModelCd();
        OffsetDateTime now = OffsetDateTime.now();

        jdbcTemplate.update("""
            INSERT INTO advisory_case_index
              (rev_id, decision_cd, overturn_yn, credit_score,
               dsr_ratio_bps, ltv_ratio_bps,
               cohort_employment_type_cd, cohort_loan_purpose_cd,
               summary_text, embedding_model_cd, embedding, indexed_at, created_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, now(), ?)
            """,
                revId,
                review.getRevDecisionCd(),
                overturnYn == null ? "N" : overturnYn,
                null,       // credit_score: customer-service 연동 후 채우기 (§10-1)
                null,       // dsr_ratio_bps
                null,       // ltv_ratio_bps
                null,       // cohort_employment_type_cd
                null,       // cohort_loan_purpose_cd
                summaryText,
                modelCd,
                vecStr,
                now,
                actorId);

        // 반환값: 마지막 INSERT 된 case_idx_id (DB 시퀀스)
        Long caseIdxId = jdbcTemplate.queryForObject(
                "SELECT case_idx_id FROM advisory_case_index ORDER BY case_idx_id DESC LIMIT 1",
                Long.class);
        log.info("사례 인덱싱 완료 — revId={} caseIdxId={}", revId, caseIdxId);
        return caseIdxId;
    }

    /**
     * 전체 COMPLETED 심사 일괄 인덱싱 (최초 구동 또는 모델 교체 시 사용).
     */
    @Transactional
    public int indexAll(Long actorId) {
        List<LoanReview> reviews = reviewRepo
                .findByRevStatusCdAndDeletedAtIsNullOrderByReviewedAtAsc(LoanReview.STATUS_COMPLETED);
        int count = 0;
        for (LoanReview review : reviews) {
            try {
                index(review.getRevId(), "N", actorId);
                count++;
            } catch (Exception e) {
                log.warn("사례 인덱싱 실패 — revId={}: {}", review.getRevId(), e.getMessage());
            }
        }
        log.info("사례 일괄 인덱싱 완료 — {}건", count);
        return count;
    }

    // ──────────────────────────────────────────────────
    // internal helpers
    // ──────────────────────────────────────────────────

    /**
     * PII 제거된 구조화 요약 텍스트.
     * customer-service 연동 전까지 심사 자체 메타(결정/금액/기간/심사관ID)만 사용.
     */
    private String buildSummaryText(LoanReview review) {
        String raw = String.format(
                "심사결정: %s 승인금액: %s 원 승인기간: %s 개월 승인금리: %sbps 심사유형: %s 심사관ID: %s",
                review.getRevDecisionCd(),
                review.getApprovedAmount()   != null ? review.getApprovedAmount()   : "N/A",
                review.getApprovedPeriodMo() != null ? review.getApprovedPeriodMo() : "N/A",
                review.getApprovedRateBps()  != null ? review.getApprovedRateBps()  : "N/A",
                review.getRevTypeCd(),
                review.getReviewerId()       != null ? review.getReviewerId()        : "N/A");
        return PiiMaskingUtil.mask(raw);
    }
}
